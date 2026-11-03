"""Original minimal descriptor fixtures, without executable third-party content."""

import struct
import unittest
from source_abi_inventory import Dex, DexError


def fixture():
    strings = ["Lcom/github/catvod/crawler/Spider;", "V", "init", "Landroid/content/Context;",
               "Lfixture/Child;", "secret-literal-not-a-descriptor"]
    data = bytearray(112)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 36, 112, 0x12345678)
    string_offset = len(data)
    data.extend(bytes(4 * len(strings)))
    type_offset = len(data)
    data.extend(struct.pack("<IIII", 0, 1, 3, 4))
    proto_offset = len(data)
    data.extend(bytes(12))
    method_offset = len(data)
    data.extend(struct.pack("<HHI", 0, 0, 2))
    class_offset = len(data)
    data.extend(struct.pack("<IIIIIIII", 3, 1, 0, 0, 0, 0, 0, 0))
    params_offset = len(data)
    data.extend(struct.pack("<IH", 1, 2))
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, string_offset + index * 4, len(data))
        data.extend(bytes([len(value)]))
        data.extend(value.encode() + b"\0")
    struct.pack_into("<II", data, 56, len(strings), string_offset)
    struct.pack_into("<II", data, 64, 4, type_offset)
    struct.pack_into("<II", data, 72, 1, proto_offset)
    struct.pack_into("<III", data, proto_offset, 1, 1, params_offset)
    struct.pack_into("<II", data, 88, 1, method_offset)
    struct.pack_into("<II", data, 96, 1, class_offset)
    struct.pack_into("<I", data, 32, len(data))
    return data


class InventoryTest(unittest.TestCase):
    def test_external_descriptor_without_private_literals(self):
        result = Dex(fixture()).inventory()
        self.assertEqual(result["methods"], [{"descriptor":
            "Lcom/github/catvod/crawler/Spider;->init(Landroid/content/Context;)V", "defined": False}])
        self.assertNotIn("secret-literal", str(result))

    def test_definition_distinguished_from_possible_linkage(self):
        data = fixture()
        class_offset = struct.unpack_from("<I", data, 100)[0]
        struct.pack_into("<I", data, class_offset, 0)
        struct.pack_into("<I", data, class_offset + 24, len(data))
        data.extend(bytes([0, 0, 1, 0, 0, 1, 0]))
        struct.pack_into("<I", data, 32, len(data))
        result = Dex(data).inventory()
        self.assertTrue(result["methods"][0]["defined"])
        self.assertEqual(result["classes"], ["Lcom/github/catvod/crawler/Spider;"])

    def test_malformed_and_truncated_input(self):
        cases = [b"not dex", fixture()[:112]]
        invalid_table = fixture()
        struct.pack_into("<II", invalid_table, 88, 0xffffffff, 112)
        cases.append(invalid_table)
        invalid_index = fixture()
        type_offset = struct.unpack_from("<I", invalid_index, 68)[0]
        struct.pack_into("<I", invalid_index, type_offset, 0xffffffff)
        cases.append(invalid_index)
        for data in cases:
            with self.subTest(size=len(data)), self.assertRaises(DexError):
                Dex(data).inventory()


if __name__ == "__main__":
    unittest.main()
