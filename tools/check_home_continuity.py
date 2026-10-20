"""Check that Home on the visible launcher does not restart its video player."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", default="5037")
    parser.add_argument("--package", default="com.sogud.bbtv")
    parser.add_argument("--input-device", help="Previously verified kernel Home input device")
    parser.add_argument("--linux-key-code", type=int)
    parser.add_argument("--native-home", action="store_true",
                        help="Require native Home resolution; allow Android's temporary Activity pause")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    adb = [args.adb, "-P", args.server_port, "-s", args.serial]

    def shell(*command):
        return subprocess.check_output(adb + ["shell"] + list(command), text=True, timeout=15)

    def focused_window():
        for line in shell("dumpsys", "window", "windows").splitlines():
            if "mCurrentFocus=" in line:
                return line.strip()
        return "unknown"

    def players(pid):
        result = []
        for player_pid, connection in re.findall(
                r"pid\((\d+)\), connId\((\d+)\)", shell("dumpsys", "media.player")):
            if player_pid == pid:
                result.append(int(connection))
        return sorted(result)

    if args.input_device:
        if not re.fullmatch(r"/dev/input/event[0-9]+", args.input_device):
            parser.error("Expected a verified /dev/input/eventN device")
        if args.linux_key_code is None:
            parser.error("A verified Linux Home key code is required")
    elif args.linux_key_code is not None:
        parser.error("--linux-key-code requires --input-device")

    native_resolver = None
    if args.native_home:
        native_resolver = shell("cmd", "package", "resolve-activity", "--brief",
                                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").strip()
        if args.package + "/.MainActivity" not in native_resolver:
            raise RuntimeError("The selected launcher is not the system Home resolver")

    if args.package not in focused_window():
        raise RuntimeError("Open the launcher with a playing video wallpaper before running this check")
    main_window = focused_window()
    shell("input", "keyevent", "82")
    time.sleep(0.2)
    panel_window = focused_window()
    if panel_window == main_window or args.package not in panel_window:
        raise RuntimeError("Could not open launcher settings before the Home check")
    pid = shell("pidof", args.package).strip()
    before = players(pid)
    if len(before) != 1:
        raise RuntimeError("Expected exactly one active video wallpaper player")

    # Use the last stored record, not /proc/uptime: logcat's monotonic clock
    # and uptime can disagree after this TV has been in standby.
    baseline = shell("logcat", "-b", "events", "-d", "-v", "epoch", "-t", "1").splitlines()[-1]
    if not re.match(r"\s*\d+\.\d+", baseline):
        raise RuntimeError("No lifecycle event baseline is available")
    if args.input_device:
        # Keep press timing on the TV: separate ADB round trips can create a long press.
        release = (f"sendevent {args.input_device} 1 {args.linux_key_code} 0; "
                   f"sendevent {args.input_device} 0 0 0")
        press = (f"set -e\ntrap '{release}' EXIT HUP INT TERM\n"
                 f"sendevent {args.input_device} 1 {args.linux_key_code} 1\n"
                 f"sendevent {args.input_device} 0 0 0\nsleep 0.08\n")
        shell(press)
    else:
        shell("input", "keyevent", "3")
    time.sleep(0.8)
    after = players(pid)
    final_window = focused_window()
    records = shell("logcat", "-b", "events", "-d", "-v", "epoch", "-t", "256").splitlines()
    if baseline not in records:
        raise RuntimeError("The event baseline is no longer in the capture; rerun with less device activity")
    records = records[records.index(baseline) + 1:]

    pauses = []
    stock_home = []
    for line in records:
        if "am_on_paused_called:" in line and args.package + ".MainActivity" in line:
            pauses.append(line.strip())
        if "am_resume_activity:" in line and "com.mitv.tvhome/" in line:
            stock_home.append(line.strip())
    panel_closed = final_window == main_window
    lifecycle_ok = args.native_home or not pauses
    passed = lifecycle_ok and not stock_home and before == after and panel_closed
    result = {
        "home_video_continuity_passed": passed,
        "players_before": before,
        "players_after": after,
        "launcher_pause_events": pauses,
        "stock_home_resume_events": stock_home,
        "home_action_observed": panel_closed,
        "native_home_mode": args.native_home,
        "native_home_resolver": native_resolver,
        "final_window": final_window,
        "input": "verified kernel Home" if args.input_device else "ADB KEYCODE_HOME",
        "visual_flash_free_verified": False,
        "limit": "Checks lifecycle and player continuity; physical first-frame appearance needs separate verification.",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
