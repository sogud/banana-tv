"""Verify the actual Home-key destination on an authorized TV."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", default="5037")
    parser.add_argument("--package", default="com.sogud.bbtv")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--from-component", help="Optional app to leave with Home")
    parser.add_argument("--already-open", action="store_true", help="Use the already visible origin window")
    parser.add_argument("--origin-window", help="Vendor window title when it does not contain the package name")
    parser.add_argument("--input-device", help="Verified kernel input device")
    parser.add_argument("--linux-key-code", type=int, help="Key code mapped to HOME in that device's key layout")
    args = parser.parse_args()
    adb = [args.adb, "-P", args.server_port, "-s", args.serial, "shell"]

    def shell(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=20)

    def foreground():
        output = shell("dumpsys", "activity", "activities")
        for line in output.splitlines():
            if "mResumedActivity" in line:
                return line.strip()
        return "unknown"

    def focused_window():
        for line in shell("dumpsys", "window", "windows").splitlines():
            if "mCurrentFocus=" in line:
                return line.strip()
        return "unknown"

    origin = args.from_component or args.package + "/.MainActivity"
    if not args.already_open:
        shell("am", "start", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.LAUNCHER", "-n", origin)
    for _ in range(15):
        if (args.origin_window or origin.split("/")[0]) in focused_window():
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("Could not open the requested origin before the Home test")
    if args.input_device:
        if not re.fullmatch(r"/dev/input/event[0-9]+", args.input_device):
            raise ValueError("Expected a kernel event device")
        if args.linux_key_code is None:
            raise ValueError("A verified Linux Home key code is required")
        try:
            shell("sendevent", args.input_device, "1", str(args.linux_key_code), "1")
            shell("sendevent", args.input_device, "0", "0", "0")
            time.sleep(0.08)
        finally:
            shell("sendevent", args.input_device, "1", str(args.linux_key_code), "0")
            shell("sendevent", args.input_device, "0", "0", "0")
    else:
        shell("input", "keyevent", "3")
    # Do not accept the launcher's old frame before Android has processed Home.
    time.sleep(1.5)
    observations = []
    window_observations = []
    for _ in range(4):
        observations.append(foreground())
        window_observations.append(focused_window())
        time.sleep(0.2)
    passed = all(args.package + "/.MainActivity" in item for item in observations[-3:])
    passed = passed and all(args.package in item for item in window_observations[-3:])
    result = {"home_key_passed": passed, "observations": observations,
              "focused_windows": window_observations,
              "input": "kernel Home key" if args.input_device else "ADB KEYCODE_HOME",
              "linux_key_code": args.linux_key_code,
              "origin": origin, "physical_remote_verified": False}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
