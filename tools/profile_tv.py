"""Profile an already-open TV launcher using Android's rendering statistics.

This sends D-pad events. Close dialogs and open the launcher's first row first.
It does not change the default home, display mode, or system packages.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path


def parse_summary(output):
    # The first block is process-wide. Later blocks repeat per-window counters.
    match = re.search(r"Total frames rendered: (\d+)\s+Janky frames: (\d+) \(([\d.]+)%\)", output)
    if not match:
        raise ValueError("Android returned no rendering counters")
    frames, janky = int(match[1]), int(match[2])
    if frames == 0:
        raise ValueError("No frames recorded; check the foreground application")
    percentiles = {}
    first_block = output[:output.find("HISTOGRAM:")]
    for percentile, ms in re.findall(r"(\d+)th percentile: (\d+)ms", first_block):
        percentiles["p" + percentile + "_ms"] = int(ms)
    return {
        "frames": frames,
        "janky_frames": janky,
        "janky_percent": round(100 * janky / frames, 2),
        **percentiles,
        "sample_at_least_300_frames": frames >= 300,
        "note": "Android gfxinfo histogram; not average FPS or physical-remote latency.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-port", default="5037")
    parser.add_argument("--package", default="com.sogud.bbtv")
    parser.add_argument("--cycles", type=int, default=8)
    parser.add_argument("--scenario", choices=["dock", "browse"], default="dock")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-P", args.server_port, "-s", args.serial, "shell"]

    def shell(*command):
        return subprocess.check_output(adb + list(command), timeout=120, text=True)

    shell("dumpsys", "gfxinfo", args.package, "reset")
    commands = []
    keys = [22, 22, 22, 21, 21, 21]
    if args.scenario == "browse":
        keys += [20, 22, 21, 19]
    for _ in range(args.cycles):
        for key in keys:
            commands.append("input keyevent " + str(key))
            commands.append("sleep 0.2")
    shell("; ".join(commands))
    output = shell("dumpsys", "gfxinfo", args.package, "framestats")
    summary = parse_summary(output)
    summary["package"] = args.package
    summary["input_events"] = args.cycles * len(keys)
    summary["scenario"] = args.scenario
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix(".txt").write_text(output)
    args.output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
