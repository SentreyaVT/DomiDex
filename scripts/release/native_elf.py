"""Strict, dependency-free ELF inspection and same-link symbol verification.

Only full, unstripped ELF candidates can prove runtime bytes by themselves.
Debug-only ELF files are deliberately rejected: a matching name or Build ID is
not sufficient provenance. This module never invokes tools or writes files.
"""

from __future__ import annotations

import hashlib
import struct


class ElfError(ValueError):
    """Malformed or unsupported ELF; callers must not infer symbol coverage."""


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _slice(data: bytes, offset: int, size: int) -> bytes:
    if offset < 0 or size < 0 or offset > len(data) or size > len(data) - offset:
        raise ElfError("ELF range is outside the file")
    return data[offset : offset + size]


def _string(data: bytes, offset: int) -> str:
    if not 0 <= offset < len(data):
        raise ElfError("String-table index is outside the table")
    end = data.find(b"\0", offset)
    if end < 0:
        raise ElfError("Unterminated ELF string")
    # Surrogate escaping preserves arbitrary symbol bytes without conflation.
    return data[offset:end].decode("utf-8", errors="surrogateescape")


class _Elf:
    def __init__(self, data: bytes):
        if not isinstance(data, bytes) or len(data) < 16 or data[:4] != b"\x7fELF":
            raise ElfError("Not an ELF file")
        if data[4] not in (1, 2) or data[5] not in (1, 2) or data[6] != 1:
            raise ElfError("Unsupported ELF class, byte order or version")
        self.data = data
        self.bits = 64 if data[4] == 2 else 32
        self.endian = "little" if data[5] == 1 else "big"
        self.order = "<" if data[5] == 1 else ">"
        self.osabi, self.abi_version = data[7:9]
        fmt = "HHIQQQIHHHHHH" if self.bits == 64 else "HHIIIIIHHHHHH"
        header = self.unpack(fmt, 16)
        (self.kind, self.machine, version, self.entry, phoff, shoff,
         self.flags, ehsize, phentsize, phnum, shentsize, shnum, shstrndx) = header
        if version != 1 or ehsize != 16 + struct.calcsize(self.order + fmt):
            raise ElfError("Invalid ELF header")
        # Extended numbering needs additional rules; fail closed, not guess.
        if not shnum or shstrndx == 0xFFFF or phnum == 0xFFFF:
            raise ElfError("Missing sections or unsupported extended numbering")
        section_fmt = "IIQQQQIIQQ" if self.bits == 64 else "IIIIIIIIII"
        if shentsize != struct.calcsize(self.order + section_fmt):
            raise ElfError("Invalid section-header entry size")
        _slice(data, shoff, shnum * shentsize)
        raw = [self.unpack(section_fmt, shoff + i * shentsize) for i in range(shnum)]
        if raw[0][1] != 0 or not 0 < shstrndx < shnum or raw[shstrndx][1] != 3:
            raise ElfError("Invalid section-name table")
        names = _slice(data, raw[shstrndx][4], raw[shstrndx][5])
        self.sections = []
        self.named = {}
        for index, values in enumerate(raw):
            nameoff, kind, flags, address, offset, size, link, info, align, entsize = values
            name = _string(names, nameoff)
            if index and (not name or name in self.named):
                raise ElfError("Missing or duplicate section name")
            if kind != 8:  # SHT_NOBITS has no file content.
                _slice(data, offset, size)
            if align and align & (align - 1):
                raise ElfError("Non-power-of-two section alignment")
            if address + size > 1 << self.bits:
                raise ElfError("Section address overflows ELF address space")
            section = dict(name=name, type=kind, flags=flags, address=address,
                           offset=offset, size=size, link=link, info=info,
                           alignment=align, entry_size=entsize, index=index)
            self.sections.append(section)
            self.named[name] = section

        self.programs = []
        program_fmt = "IIQQQQQQ" if self.bits == 64 else "IIIIIIII"
        if phnum:
            if phentsize != struct.calcsize(self.order + program_fmt):
                raise ElfError("Invalid program-header entry size")
            _slice(data, phoff, phnum * phentsize)
        for i in range(phnum):
            record = self.unpack(program_fmt, phoff + i * phentsize)
            if self.bits == 64:
                kind, flags, offset, vaddr, paddr, filesz, memsz, align = record
            else:
                kind, offset, vaddr, paddr, filesz, memsz, flags, align = record
            _slice(data, offset, filesz)
            if kind == 1 and (filesz > memsz or vaddr + memsz > 1 << self.bits):
                raise ElfError("Invalid load segment")
            self.programs.append(dict(type=kind, flags=flags, offset=offset,
                                      address=vaddr, physical_address=paddr,
                                      file_size=filesz, memory_size=memsz, alignment=align))

        build_ids = set()
        for section in self.sections:
            if section["type"] != 7:
                continue
            notes = self.content(section)
            cursor = 0
            while cursor < len(notes):
                if len(notes) - cursor < 12:
                    if any(notes[cursor:]):
                        raise ElfError("Truncated ELF note header")
                    break  # Some producers pad a note section with zeroes.
                namesz, descsz, note_type = struct.unpack_from(self.order + "III", notes, cursor)
                cursor += 12
                owner = _slice(notes, cursor, namesz).rstrip(b"\0")
                cursor += (namesz + 3) & ~3
                desc = _slice(notes, cursor, descsz)
                cursor += (descsz + 3) & ~3
                if cursor > len(notes):
                    raise ElfError("Truncated ELF note padding")
                if owner == b"GNU" and note_type == 3:
                    if not desc:
                        raise ElfError("Empty GNU Build ID")
                    build_ids.add(desc.hex())
        if len(build_ids) > 1:
            raise ElfError("Conflicting GNU Build IDs")
        self.build_id = next(iter(build_ids), None)
        # Parse both now so inspect_elf also rejects malformed symbol tables.
        self.symtab = self.symbols(".symtab", 2)
        self.dynsym = self.symbols(".dynsym", 11)

    def unpack(self, fmt: str, offset: int) -> tuple:
        fmt = self.order + fmt
        return struct.unpack(fmt, _slice(self.data, offset, struct.calcsize(fmt)))

    def content(self, section: dict) -> bytes:
        if section["type"] == 8:
            raise ElfError("No file bytes for NOBITS section")
        return _slice(self.data, section["offset"], section["size"])

    def symbols(self, name: str, expected_type: int) -> list[dict]:
        table = self.named.get(name)
        if table is None:
            return []
        fmt = "IBBHQQ" if self.bits == 64 else "IIIBBH"
        size = struct.calcsize(self.order + fmt)
        if (table["type"] != expected_type or table["entry_size"] != size
                or table["size"] % size or not 0 < table["link"] < len(self.sections)):
            raise ElfError("Invalid symbol-table layout")
        strings = self.sections[table["link"]]
        if strings["type"] != 3:
            raise ElfError("Invalid symbol string table")
        string_data = self.content(strings)
        result = []
        for offset in range(table["offset"], table["offset"] + table["size"], size):
            record = self.unpack(fmt, offset)
            if self.bits == 64:
                nameoff, info, other, shndx, value, symbol_size = record
            else:
                nameoff, value, symbol_size, info, other, shndx = record
            if shndx == 0xFFFF or (0 < shndx < 0xFF00 and shndx >= len(self.sections)):
                raise ElfError("Invalid or unsupported symbol section index")
            result.append(dict(name=_string(string_data, nameoff), type=info & 15,
                               binding=info >> 4, visibility=other, section_index=shndx,
                               section=(self.sections[shndx]["name"] if 0 < shndx < 0xFF00
                                        else "special:" + str(shndx)), value=value, size=symbol_size))
        return result

    def report(self) -> dict:
        allocated = {
            section["name"]: dict(type=section["type"], flags=section["flags"],
                                  address=section["address"], size=section["size"],
                                  alignment=section["alignment"],
                                  sha256=(_sha(self.content(section)) if section["type"] != 8 else None))
            for section in self.sections if section["flags"] & 2
        }
        return dict(elf_class=self.bits, bits=self.bits, **{"class": self.bits},
                    machine=self.machine, endian=self.endian, type=self.kind,
                    flags=self.flags, osabi=self.osabi, abi_version=self.abi_version,
                    build_id=self.build_id, has_symtab=bool(self.symtab),
                    debug_sections=[s["name"] for s in self.sections
                                    if s["size"] and s["name"].startswith((".debug", ".zdebug"))],
                    symbol_counts={".symtab": len(self.symtab), ".dynsym": len(self.dynsym)},
                    allocated_sections=allocated, program_headers=self.programs,
                    sha256=_sha(self.data), size=len(self.data))


def inspect_elf(data: bytes) -> dict:
    """Return JSON-serializable identity; raise ElfError if parsing is unsafe."""
    return _Elf(data).report()


def verify_symbol_candidate(binary: bytes, candidate: bytes) -> dict:
    """Fail-closed verification of an unstripped original against shipped ELF.

    A matching Build ID is necessary but insufficient. All allocated section
    addresses, sizes and contents must match, as must dynamic symbols. Defined
    function/data symbols must reference valid runtime addresses. Debug-only
    files require separate same-link provenance and are not accepted here.
    """
    def reject(reason: str) -> dict:
        return dict(accepted=False, reason=reason, level=None)

    try:
        shipped, original = _Elf(binary), _Elf(candidate)
        if shipped.kind != 3 or original.kind != 3:
            return reject("Both files must be linked ET_DYN shared libraries")
        identity = lambda e: (e.bits, e.endian, e.machine, e.flags, e.osabi, e.abi_version, e.entry)
        if identity(shipped) != identity(original):
            return reject("ELF architecture, ABI or header identity differs")
        if not shipped.build_id or shipped.build_id != original.build_id:
            return reject("Missing or unequal GNU Build ID")
        if not original.symtab:
            return reject("Candidate has no full static symbol table")
        left, right = shipped.report(), original.report()
        if not any(s["flags"] & 4 and s["size"] and s["type"] != 8
                   for s in shipped.sections if s["flags"] & 2):
            return reject("Shipped ELF has no verifiable executable runtime section")
        if left["allocated_sections"] != right["allocated_sections"]:
            return reject("Runtime sections differ or code bytes are unavailable (debug-only ELF)")
        # Header table offsets can change on stripping; load layout may not.
        if shipped.programs != original.programs:
            return reject("Program segment layout differs")
        # Sections do not cover every loaded byte (alignment gaps can execute,
        # too). Compare complete PT_LOAD contents, except the ELF header's
        # section-table locator/count fields, which legitimately change when
        # stripping .symtab and DWARF. All other runtime bytes remain exact.
        bookkeeping = ((40, 48), (60, 64)) if shipped.bits == 64 else ((32, 36), (48, 52))
        load_segments = [p for p in shipped.programs if p["type"] == 1]
        if not load_segments:
            return reject("No load segments available for complete runtime-byte verification")
        for segment in load_segments:
            start, size = segment["offset"], segment["file_size"]
            shipped_bytes = bytearray(_slice(binary, start, size))
            original_bytes = bytearray(_slice(candidate, start, size))
            for lo, hi in bookkeeping:
                lo, hi = max(lo, start), min(hi, start + size)
                if lo < hi:
                    shipped_bytes[lo - start:hi - start] = bytes(hi - lo)
                    original_bytes[lo - start:hi - start] = bytes(hi - lo)
            if shipped_bytes != original_bytes:
                return reject("Loaded runtime bytes differ outside ELF section-table bookkeeping")
        normalize = lambda rows: [{k: v for k, v in row.items() if k != "section_index"} for row in rows]
        if normalize(shipped.dynsym) != normalize(original.dynsym):
            return reject("Dynamic symbols, addresses or sizes differ")

        functions = objects = address_symbols = 0
        for symbol in original.symtab:
            index = symbol["section_index"]
            if not 0 < index < 0xFF00:
                continue  # Undefined, absolute and common are not runtime addresses.
            section = original.sections[index]
            if not section["flags"] & 2 or symbol["type"] in (3, 4):
                continue  # Non-runtime, STT_SECTION and STT_FILE metadata.
            if symbol["type"] == 6:  # TLS st_value is relative to the TLS block.
                tls = [p for p in original.programs if p["type"] == 7]
                if len(tls) != 1 or not section["flags"] & 0x400:
                    return reject("TLS symbol lacks a unique TLS segment")
                value = tls[0]["address"] + symbol["value"]
            else:
                value = symbol["value"]
                if original.machine == 40 and symbol["type"] in (2, 10):
                    value &= ~1  # ARM Thumb function pointer state bit.
            if value < section["address"] or value + symbol["size"] > section["address"] + section["size"]:
                return reject("Static symbol address or size is outside its runtime section")
            address_symbols += 1
            if symbol["type"] in (2, 10) and symbol["name"] and symbol["size"]:
                if not section["flags"] & 4 or section["type"] == 8:
                    return reject("Function symbol is not backed by executable bytes")
                functions += 1
            if symbol["type"] in (1, 6) and symbol["name"]:
                objects += 1
        if not functions:
            return reject("No named, non-empty defined functions in candidate symbol table")
        has_debug_info = any(n in original.named and original.named[n]["size"]
                             for n in (".debug_info", ".zdebug_info"))
        has_debug_line = any(n in original.named and original.named[n]["size"]
                             for n in (".debug_line", ".zdebug_line"))
        return dict(accepted=True, reason="Same Build ID, runtime bytes and symbol addresses verified",
                    level="FULL" if has_debug_info and has_debug_line else "SYMBOL_TABLE",
                    build_id=shipped.build_id, binary_sha256=left["sha256"],
                    candidate_sha256=right["sha256"], allocated_sections_checked=len(left["allocated_sections"]),
                    defined_functions_checked=functions, defined_objects_checked=objects,
                    address_symbols_checked=address_symbols, dynamic_symbols_match=True,
                    load_segments_checked=len(load_segments),
                    runtime_bytes_match=True, debug_sections=right["debug_sections"])
    except (ElfError, struct.error, OverflowError) as error:
        return reject("Malformed or unsupported ELF: " + str(error))
