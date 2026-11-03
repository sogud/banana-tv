#!/usr/bin/env python3
"""Read DEX host descriptors without loading or executing third-party code.

Method/field pools show possible linkage, not executed behavior. Definitions are
reported separately because a JAR may bundle a class also supplied by its host.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


HOSTS = ("Lcom/github/catvod/", "Lcom/fongmi/quickjs/")
MAXIMUM_DEX_BYTES = 64 * 1024 * 1024


class DexError(ValueError):
    pass


class Dex:
    def __init__(self, data):
        self.data = data
        if len(data) < 112 or data[:4] != b"dex\n" or data[7] != 0:
            raise DexError("Expected a standard DEX header")
        if self.u32(32) != len(data) or self.u32(36) != 112 or self.u32(40) != 0x12345678:
            raise DexError("Invalid DEX size, header or endianness")
        strings, offset = self.table(56, 4)
        self.strings = []
        for index in range(strings):
            start = self.u32(offset + index * 4)
            _, start = self.uleb(start)
            end = data.find(b"\0", start)
            if end < 0:
                raise DexError("Unterminated DEX string")
            # Descriptors are ASCII. Literal strings are not included in output.
            self.strings.append(data[start:end].decode("utf-8", errors="replace"))
        types, offset = self.table(64, 4)
        self.types = [self.item(self.strings, self.u32(offset + i * 4)) for i in range(types)]

    @staticmethod
    def item(items, index):
        if index >= len(items):
            raise DexError("DEX index outside table")
        return items[index]

    def unpack(self, fmt, offset):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > len(self.data):
            raise DexError("Truncated DEX structure")
        return struct.unpack_from(fmt, self.data, offset)

    def u32(self, offset):
        return self.unpack("<I", offset)[0]

    def uleb(self, offset):
        result = 0
        for shift in range(0, 35, 7):
            byte = self.unpack("<B", offset)[0]
            offset += 1
            if shift == 28 and byte > 15:
                raise DexError("Invalid DEX variable integer")
            result |= (byte & 127) << shift
            if byte < 128:
                return result, offset
        raise DexError("Invalid DEX variable integer")

    def table(self, header, width):
        count, offset = self.unpack("<II", header)
        if count and (offset < 112 or count > (len(self.data) - offset) // width):
            raise DexError("DEX table outside file")
        return count, offset

    def definitions(self):
        classes = []
        methods, fields = set(), set()
        count, offset = self.table(96, 32)
        for index in range(count):
            record = offset + index * 32
            classes.append(self.item(self.types, self.u32(record)))
            cursor = self.u32(record + 24)
            if not cursor:
                continue
            sizes = []
            for _ in range(4):
                size, cursor = self.uleb(cursor)
                sizes.append(size)
            for group, size in enumerate(sizes):
                previous = 0
                for _ in range(size):
                    delta, cursor = self.uleb(cursor)
                    previous += delta
                    _, cursor = self.uleb(cursor)  # access flags
                    if group >= 2:
                        _, cursor = self.uleb(cursor)  # code offset
                        methods.add(previous)
                    else:
                        fields.add(previous)
        return classes, methods, fields

    def inventory(self):
        classes, defined_methods, defined_fields = self.definitions()
        proto_count, proto_offset = self.table(72, 12)
        prototypes = []
        for index in range(proto_count):
            _, result, params = self.unpack("<III", proto_offset + index * 12)
            arguments = []
            if params:
                size = self.u32(params)
                if size > (len(self.data) - params - 4) // 2:
                    raise DexError("DEX prototype outside file")
                for arg in range(size):
                    type_index = self.unpack("<H", params + 4 + arg * 2)[0]
                    arguments.append(self.item(self.types, type_index))
            prototypes.append("(" + "".join(arguments) + ")" + self.item(self.types, result))
        methods = []
        count, offset = self.table(88, 8)
        for index in range(count):
            owner, proto, name = self.unpack("<HHI", offset + index * 8)
            owner = self.item(self.types, owner)
            if owner.startswith(HOSTS):
                methods.append({"descriptor": owner + "->" + self.item(self.strings, name)
                                + self.item(prototypes, proto), "defined": index in defined_methods})
        fields = []
        count, offset = self.table(80, 8)
        for index in range(count):
            owner, field_type, name = self.unpack("<HHI", offset + index * 8)
            owner = self.item(self.types, owner)
            if owner.startswith(HOSTS):
                fields.append({"descriptor": owner + "->" + self.item(self.strings, name)
                               + ":" + self.item(self.types, field_type), "defined": index in defined_fields})
        return {"classes": sorted(c for c in classes if c.startswith(HOSTS)),
                "methods": sorted(methods, key=lambda value: value["descriptor"]),
                "fields": sorted(fields, key=lambda value: value["descriptor"])}


def inspect(path):
    with zipfile.ZipFile(path) as archive:
        dex_files = []
        for entry in archive.infolist():
            name = entry.filename
            if "/" in name or not name.startswith("classes") or not name.endswith(".dex"):
                continue
            if entry.file_size > MAXIMUM_DEX_BYTES:
                raise DexError("DEX exceeds inspection size limit")
            data = archive.read(entry)
            dex_files.append({"name": name, "sha256": hashlib.sha256(data).hexdigest(),
                              **Dex(data).inventory()})
    if not dex_files:
        raise DexError("Archive contains no Android DEX")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return {"artifact_sha256": digest.hexdigest(), "dex": dex_files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        # No absolute paths or DEX literal strings are emitted.
        print(json.dumps({"version": 1, "evidence": "descriptor pools, not execution traces",
                          "artifacts": [inspect(path) for path in args.artifacts]}, ensure_ascii=False, indent=2))
    except (DexError, OSError, zipfile.BadZipFile, RuntimeError):
        parser.exit(1, "Unable to inspect DEX archive; check its format and size.\n")


if __name__ == "__main__":
    main()
