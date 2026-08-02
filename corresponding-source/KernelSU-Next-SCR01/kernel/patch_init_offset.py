#!/usr/bin/env python3

import hashlib
import struct
import sys
from pathlib import Path

from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection


EXPECTED_INPUT = "0a37c39e94f816b7c769efa583e0134d4e3331a38d6c93a926b9373229e70ebd"
EXPECTED_OUTPUT = "b1f6b9afbbfc2f6c388dada781f0761899f494bc6f05e8657fa4325b5a0cbfd9"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    allow_unpinned = len(sys.argv) == 4 and sys.argv[3] == "--allow-unpinned"
    if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and not allow_unpinned):
        raise SystemExit(
            "usage: patch_init_offset.py input-ksu_glue.ko output-ksu_glue.ko "
            "[--allow-unpinned]"
        )
    source_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    if not source_path.is_file():
        raise SystemExit("input ksu_glue.ko does not exist")
    if output_path.exists():
        raise SystemExit("output ksu_glue.ko already exists")
    with source_path.open("rb") as source:
        data = bytearray(source.read())
        if digest(data) != EXPECTED_INPUT and not allow_unpinned:
            raise SystemExit("unexpected unpatched ksu_glue.ko hash")
        source.seek(0)
        elf = ELFFile(source)
        module_index = None
        for index, section in enumerate(elf.iter_sections()):
            if section.name == ".gnu.linkonce.this_module":
                module_index = index
                break
        symbol_table = elf.get_section_by_name(".symtab")
        if module_index is None or symbol_table is None:
            raise SystemExit("required module metadata is missing")
        symbols = list(symbol_table.iter_symbols())
        matches = 0
        for section in elf.iter_sections():
            if not isinstance(section, RelocationSection):
                continue
            if section.header["sh_info"] != module_index:
                continue
            base = section.header["sh_offset"]
            count = section.header["sh_size"] // 24
            for entry in range(count):
                entry_offset = base + entry * 24
                relocation_offset, relocation_info, _ = struct.unpack_from(
                    "<QQq", data, entry_offset
                )
                symbol = symbols[relocation_info >> 32].name
                if symbol == "init_module" and relocation_offset == 0x150:
                    struct.pack_into("<Q", data, entry_offset, 0x158)
                    matches += 1
        if matches != 1:
            raise SystemExit(f"expected one init_module relocation, found {matches}")
    output_digest = digest(data)
    if output_digest != EXPECTED_OUTPUT and not allow_unpinned:
        raise SystemExit("patched ksu_glue.ko hash mismatch")
    with output_path.open("xb") as destination:
        destination.write(data)
    print(output_digest)


if __name__ == "__main__":
    main()
