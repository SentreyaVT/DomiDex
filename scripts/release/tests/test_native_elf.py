"""Synthetic ELF tests: independent of SDK installs, caches and user paths."""

import importlib.util
from pathlib import Path
import struct
import unittest


MODULE = Path(__file__).resolve().parents[1] / "native_elf.py"
SPEC = importlib.util.spec_from_file_location("native_elf", MODULE)
native_elf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(native_elf)


def elf(*, symbols=False, debug=False, build_id=b"same-build-id", code=b"\x11\x22\x33\x44",
        machine=183, symbol_value=0x1000, symbol_size=4, dynvalue=0x1000,
        debug_only=False, bits=64, big=False, program_delta=0):
    """Produce a tiny linked ELF with load sections stable across stripping."""
    order = ">" if big else "<"
    pack = lambda fmt, *args: struct.pack(order + fmt, *args)
    header_size = 64 if bits == 64 else 52
    program_size = 56 if bits == 64 else 32
    section_size = 64 if bits == 64 else 40
    symfmt = "IBBHQQ" if bits == 64 else "IIIBBH"
    symsize = 24 if bits == 64 else 16
    symbol = lambda name, val, sz: (pack(symfmt, name, 0x12, 0, 1, val, sz) if bits == 64
                                    else pack(symfmt, name, val, sz, 0x12, 0, 1))
    note = b""
    if build_id is not None:
        note = pack("III", 4, len(build_id), 3) + b"GNU\0" + build_id
        note += b"\0" * (-len(note) % 4)
    # name, type, flags, address, content, link, entry_size
    sections = [
        ("", 0, 0, 0, b"", 0, 0),
        (".text", 8 if debug_only else 1, 6, 0x1000, code, 0, 0),
        (".note.gnu.build-id", 7, 2, 0x2000, note, 0, 0),
        (".dynstr", 3, 2, 0x2100, b"\0hello\0", 0, 0),
        (".dynsym", 11, 2, 0x2200, bytes(symsize) + symbol(1, dynvalue, 4), 3, symsize),
    ]
    if symbols:
        sections += [(".strtab", 3, 0, 0, b"\0hello\0", 0, 0),
                     (".symtab", 2, 0, 0, bytes(symsize) + symbol(1, symbol_value, symbol_size), 5, symsize)]
    if debug:
        sections += [(".debug_info", 1, 0, 0, b"debug info", 0, 0),
                     (".debug_line", 1, 0, 0, b"line info", 0, 0)]
    sections.append((".shstrtab", 3, 0, 0, b"", 0, 0))
    names = b"".join(section[0].encode() + b"\0" for section in sections)
    sections[-1] = (".shstrtab", 3, 0, 0, names, 0, 0)
    result = bytearray(header_size + program_size)
    headers = []
    name_offset = 0
    for name, kind, flags, address, data, link, entsize in sections:
        offset = len(result)
        if kind != 8:
            result.extend(data)
        headers.append((name_offset, kind, flags, address, offset, len(data), link, 0, 1, entsize))
        name_offset += len(name) + 1
    shoff = len(result)
    for header in headers:
        result.extend(pack("IIQQQQIIQQ" if bits == 64 else "IIIIIIIIII", *header))
    ident = b"\x7fELF" + bytes([2 if bits == 64 else 1, 2 if big else 1, 1]) + bytes(9)
    header = pack("HHIQQQIHHHHHH" if bits == 64 else "HHIIIIIHHHHHH",
                  3, machine, 1, 0, header_size, shoff, 0, header_size,
                  program_size, 1, section_size, len(sections), len(sections) - 1)
    result[:header_size] = ident + header
    # PT_LOAD references only .text, and remains the same after adding symbols.
    textoff = headers[1][4]
    program = (pack("IIQQQQQQ", 1, 5, textoff, 0x1000 + program_delta, 0, 4, 4, 1) if bits == 64
               else pack("IIIIIIII", 1, textoff, 0x1000 + program_delta, 0, 4, 4, 5, 1))
    result[header_size:header_size + program_size] = program
    return bytes(result)


class NativeElfTest(unittest.TestCase):
    def test_inspect_and_match_unstripped(self):
        binary, original = elf(), elf(symbols=True)
        info = native_elf.inspect_elf(binary)
        self.assertEqual(info["build_id"], b"same-build-id".hex())
        self.assertEqual((info["bits"], info["machine"], info["endian"]), (64, 183, "little"))
        self.assertFalse(info["has_symtab"])
        result = native_elf.verify_symbol_candidate(binary, original)
        self.assertTrue(result["accepted"], result)
        self.assertEqual(result["level"], "SYMBOL_TABLE")
        self.assertEqual(result["defined_functions_checked"], 1)

    def test_32_bit_and_big_endian(self):
        for bits in (32, 64):
            for big in (False, True):
                with self.subTest(bits=bits, big=big):
                    result = native_elf.verify_symbol_candidate(elf(bits=bits, big=big), elf(symbols=True, bits=bits, big=big))
                    self.assertTrue(result["accepted"], result)

    def test_full_symbol_level(self):
        result = native_elf.verify_symbol_candidate(elf(), elf(symbols=True, debug=True))
        self.assertTrue(result["accepted"])
        self.assertEqual(result["level"], "FULL")

    def test_reject_invalid_or_mismatched_symbols(self):
        cases = {
            "stripped": elf(),
            "wrong_build_id": elf(symbols=True, build_id=b"other"),
            "no_build_id": elf(symbols=True, build_id=None),
            "machine": elf(symbols=True, machine=62),
            "bits": elf(symbols=True, bits=32),
            "endian": elf(symbols=True, big=True),
            "code": elf(symbols=True, code=b"\x44\x33\x22\x11"),
            "dynamic_symbol": elf(symbols=True, dynvalue=0x1001),
            "outside_address": elf(symbols=True, symbol_value=0x1004),
            "outside_size": elf(symbols=True, symbol_size=8),
            "empty_function": elf(symbols=True, symbol_size=0),
            "debug_only": elf(symbols=True, debug=True, debug_only=True),
            "load_layout": elf(symbols=True, program_delta=0x1000),
            "garbage": b"not an elf",
            "truncated": elf(symbols=True)[:100],
        }
        for name, candidate in cases.items():
            with self.subTest(name=name):
                result = native_elf.verify_symbol_candidate(elf(), candidate)
                self.assertFalse(result["accepted"], result)
                self.assertIsNone(result["level"])

    def test_missing_build_id_on_both_is_not_a_match(self):
        result = native_elf.verify_symbol_candidate(elf(build_id=None), elf(symbols=True, build_id=None))
        self.assertFalse(result["accepted"])

    def test_malformed_section_table(self):
        binary = bytearray(elf())
        struct.pack_into("<Q", binary, 40, len(binary) + 1)
        with self.assertRaises(native_elf.ElfError):
            native_elf.inspect_elf(bytes(binary))

    def test_duplicate_section_name(self):
        binary = bytearray(elf(symbols=True))
        shoff = struct.unpack_from("<Q", binary, 40)[0]
        first_name = struct.unpack_from("<I", binary, shoff + 64)[0]
        struct.pack_into("<I", binary, shoff + 128, first_name)
        with self.assertRaisesRegex(native_elf.ElfError, "duplicate"):
            native_elf.inspect_elf(bytes(binary))

    def test_bad_symbol_table_link(self):
        binary = bytearray(elf(symbols=True))
        shoff = struct.unpack_from("<Q", binary, 40)[0]
        struct.pack_into("<I", binary, shoff + 6 * 64 + 40, 1)
        with self.assertRaisesRegex(native_elf.ElfError, "string table"):
            native_elf.inspect_elf(bytes(binary))

    def test_extended_numbering_rejected(self):
        binary = bytearray(elf())
        struct.pack_into("<H", binary, 60, 0)
        with self.assertRaisesRegex(native_elf.ElfError, "extended numbering"):
            native_elf.inspect_elf(bytes(binary))

    def test_truncated_note_rejected(self):
        binary = bytearray(elf())
        shoff = struct.unpack_from("<Q", binary, 40)[0]
        note_offset = struct.unpack_from("<Q", binary, shoff + 128 + 24)[0]
        struct.pack_into("<I", binary, note_offset + 4, 0xFFFFFFFF)
        with self.assertRaises(native_elf.ElfError):
            native_elf.inspect_elf(bytes(binary))

    def test_loaded_gap_bytes_are_verified(self):
        binary, original = bytearray(elf()), bytearray(elf(symbols=True))
        # Expand PT_LOAD over its own header; byte 15 is ELF padding and is
        # outside all sections, but still part of the loaded runtime image.
        for data in (binary, original):
            struct.pack_into("<Q", data, 64 + 8, 0)
            struct.pack_into("<Q", data, 64 + 32, 124)
            struct.pack_into("<Q", data, 64 + 40, 124)
        self.assertTrue(native_elf.verify_symbol_candidate(bytes(binary), bytes(original))["accepted"])
        original[15] = 1
        result = native_elf.verify_symbol_candidate(bytes(binary), bytes(original))
        self.assertFalse(result["accepted"])
        self.assertIn("Loaded runtime bytes", result["reason"])


if __name__ == "__main__":
    unittest.main()
