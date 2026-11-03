import unittest

from compare_source_host_contract import compare, differences, normalize


class SourceHostContractComparisonTest(unittest.TestCase):
    def test_wire_json_ignores_object_order_and_fixture_port(self):
        first = {"wire": '{"code":200,"url":"http://localhost:1234/clip"}'}
        second = {"wire": '{"url":"http://localhost:5678/clip","code":200}'}
        self.assertEqual(differences(normalize(first), normalize(second)), [])

    def test_scalar_string_is_not_coerced_to_number_or_boolean(self):
        self.assertEqual(differences(normalize("200"), normalize(200)), ["/"])
        self.assertEqual(differences(normalize("true"), normalize(True)), ["/"])

    def test_missing_and_null_differ_and_array_order_matters(self):
        self.assertEqual(differences({"body": None}, {}), ["/body"])
        self.assertEqual(differences([1, 2], [2, 1]), ["/0", "/1"])

    def test_malformed_json_and_non_fixture_urls_are_not_rewritten(self):
        self.assertEqual(normalize('{"unfinished":'), '{"unfinished":')
        self.assertEqual(normalize("https://example.invalid:1234/path"), "https://example.invalid:1234/path")

    def test_matching_incomplete_reports_are_not_accepted(self):
        with self.assertRaisesRegex(ValueError, "Reference report is incomplete"):
            compare({}, {})
        empty = {key: {} for key in ["defaults", "explicit", "requests", "cookies", "storage", "failureResult", "afterClose", "webSocket", "webSocketFirst", "async", "stream"]}
        with self.assertRaisesRegex(ValueError, "Reference request cases are incomplete"):
            compare(empty, empty)

    def test_matching_incomplete_socket_reports_are_not_accepted(self):
        requests = ["get", "getWithNumber", "getWithHeaders", "post", "req", "json", "jsonText",
                    "nonSuccessGet", "nonSuccessReq", "result", "base64Parts", "binaryParts", "binaryJson",
                    "limitedBytes", "params", "form"]
        report = {key: {} for key in ["defaults", "explicit", "cookies", "storage", "failureResult", "afterClose",
                                      "webSocket", "webSocketFirst", "async", "stream"]}
        report["requests"] = dict.fromkeys(requests)
        with self.assertRaisesRegex(ValueError, "Reference webSocket cases are incomplete"):
            compare(report, report)
        report["webSocket"] = dict.fromkeys(["textAccepted", "binaryAccepted", "receivedText", "receivedBytes", "header", "events"])
        with self.assertRaisesRegex(ValueError, "Reference webSocketFirst cases are incomplete"):
            compare(report, report)
        report["webSocketFirst"] = dict.fromkeys(["text", "binaryParts", "async", "rpc", "failure"])
        with self.assertRaisesRegex(ValueError, "Reference async cases are incomplete"):
            compare(report, report)
        report["async"] = dict.fromkeys(["request", "callback", "map", "zeroThreadsMap", "emptyZeroThreadsMap",
                                          "emptyMap", "mapFailure", "batch", "batchParts", "batchFailure"])
        with self.assertRaisesRegex(ValueError, "Reference stream cases are incomplete"):
            compare(report, report)
        report["stream"] = dict.fromkeys(["stream", "readAsync", "status", "maxBytes", "chunkedMaxBytes", "afterClose",
                                           "download", "downloadAsync", "downloadStatus", "afterDownloadFailure",
                                           "downloadLimit", "afterDownloadLimit", "sizeHint"])
        self.assertEqual(compare(report, report)["result"], "PASS")


if __name__ == "__main__":
    unittest.main()
