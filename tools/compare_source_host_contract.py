#!/usr/bin/env python3
"""Compare reports from the original, disposable source-host fixture."""

import argparse
import json
import re
from pathlib import Path


def normalize(value):
    if isinstance(value, str):
        # Wire envelopes are JSON strings; scalar body strings must retain their types.
        if value.lstrip().startswith(("{", "[")):
            try:
                return normalize(json.loads(value))
            except json.JSONDecodeError:
                pass
        return normalize_address(value)
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, dict):
        return {normalize_address(key): normalize(item) for key, item in value.items()}
    return value


def normalize_address(value):
    return re.sub(r"http://(?:localhost|127\.0\.0\.1):\d+/", "http://fixture.invalid/", value)


def differences(expected, actual, path=""):
    if type(expected) is not type(actual):
        return [path or "/"]
    if isinstance(expected, dict):
        result = []
        for key in sorted(set(expected) | set(actual)):
            child = path + "/" + key
            if key not in expected or key not in actual:
                result.append(child)
            else:
                result.extend(differences(expected[key], actual[key], child))
        return result
    if isinstance(expected, list):
        result = []
        if len(expected) != len(actual):
            result.append(path + "/length")
        for index, (left, right) in enumerate(zip(expected, actual)):
            result.extend(differences(left, right, path + "/" + str(index)))
        return result
    return [] if expected == actual else [path or "/"]


def compare(expected, actual):
    required = {"defaults", "explicit", "requests", "cookies", "storage", "failureResult", "afterClose",
                "webSocket", "webSocketFirst", "async", "stream"}
    missing = required - expected.keys()
    if missing:
        raise ValueError("Reference report is incomplete: " + ", ".join(sorted(missing)))
    request_cases = {"get", "getWithNumber", "getWithHeaders", "post", "req", "json", "jsonText",
                     "nonSuccessGet", "nonSuccessReq", "result", "base64Parts", "binaryParts", "binaryJson",
                     "limitedBytes", "params", "form"}
    missing = request_cases - expected["requests"].keys()
    if missing:
        raise ValueError("Reference request cases are incomplete: " + ", ".join(sorted(missing)))
    socket_cases = {"textAccepted", "binaryAccepted", "receivedText", "receivedBytes", "header", "events"}
    first_cases = {"text", "binaryParts", "async", "rpc", "failure"}
    for section, cases in [("webSocket", socket_cases), ("webSocketFirst", first_cases)]:
        missing = cases - expected[section].keys()
        if missing:
            raise ValueError("Reference " + section + " cases are incomplete: " + ", ".join(sorted(missing)))
    async_cases = {"request", "callback", "map", "zeroThreadsMap", "emptyZeroThreadsMap", "emptyMap",
                   "mapFailure", "batch", "batchParts", "batchFailure"}
    missing = async_cases - expected["async"].keys()
    if missing:
        raise ValueError("Reference async cases are incomplete: " + ", ".join(sorted(missing)))
    stream_cases = {"stream", "readAsync", "status", "maxBytes", "chunkedMaxBytes", "afterClose", "download",
                    "downloadAsync", "downloadStatus", "afterDownloadFailure", "downloadLimit", "afterDownloadLimit", "sizeHint"}
    missing = stream_cases - expected["stream"].keys()
    if missing:
        raise ValueError("Reference stream cases are incomplete: " + ", ".join(sorted(missing)))
    changed = differences(normalize(expected), normalize(actual))
    return {"result": "PASS" if not changed else "FAIL", "differences": changed,
            "request_cases": len(expected["requests"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("legacy", type=Path)
    parser.add_argument("replacement", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = compare(json.loads(args.legacy.read_text()), json.loads(args.replacement.read_text()))
    text = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
