"""Hash-pinned official symbol sources; never reconstruct symbols or install an NDK."""
from pathlib import Path
import hashlib
import struct
import urllib.request
import zlib

SOURCE = 'https://dl.google.com/android/repository/android-ndk-r27c-windows.zip'
MEMBER = ('android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/'
          'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so')
BUILD_ID = 'b04675a35ad96f8a9dcaa073e3bd31d4536f00ad'
SHA256 = 'f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970'
TOTAL, OFFSET, COMPRESSED, SIZE = 781511249, 737368580, 499300, 1794776


def _range(start, count):
    request = urllib.request.Request(SOURCE, headers={
        'Range': f'bytes={start}-{start + count - 1}', 'Accept-Encoding': 'identity'})
    with urllib.request.urlopen(request, timeout=30) as response:
        expected = f'bytes {start}-{start + count - 1}/{TOTAL}'
        if response.status != 206 or response.headers.get('Content-Range') != expected:
            raise ValueError('Official symbol server did not honor the pinned byte range')
        data = response.read(count + 1)
    if len(data) != count:
        raise ValueError('Truncated official symbol download')
    return data


def original_libcxx(cache: Path, allow_download=False):
    """Return verified original bytes. ELF identity against the AAB is checked separately."""
    target = cache / 'ndk-r27c' / 'arm64-v8a' / 'libc++_shared.so'
    if target.exists():
        data = target.read_bytes()
    else:
        if not allow_download:
            raise FileNotFoundError('Pinned NDK r27c symbol cache missing (offline mode)')
        header = struct.unpack('<IHHHHHIIIHH', _range(OFFSET, 30))
        signature, _, flags, method, _, _, crc, compressed, size, name_len, extra_len = header
        if (signature != 0x04034B50 or method != 8 or flags & 1 or
                compressed != COMPRESSED or size != SIZE or name_len + extra_len > 65536):
            raise ValueError('Unexpected official NDK ZIP header')
        name = _range(OFFSET + 30, name_len + extra_len)
        if name[:name_len].decode('utf-8') != MEMBER:
            raise ValueError('Unexpected official NDK ZIP member')
        data = zlib.decompress(_range(OFFSET + 30 + len(name), compressed), -zlib.MAX_WBITS)
        if zlib.crc32(data) != crc:
            raise ValueError('Official NDK member CRC mismatch')
    if len(data) != SIZE or hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('Official NDK symbol SHA-256 mismatch; cache not replaced')
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(data)
    return data, {'source': SOURCE, 'member': MEMBER, 'sha256': SHA256}
