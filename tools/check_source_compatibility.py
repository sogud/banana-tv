#!/usr/bin/env python3
"""Probe every provider in a private CatVod snapshot on a disposable Android TV emulator."""
import argparse
import collections
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
from urllib.parse import quote, unquote, urlparse
from urllib.request import Request, urlopen


PACKAGE = "com.sogud.bananatv.mediatest"
RUNNER = PACKAGE + ".test/com.sogud.bananatv.media.MediaIntegrationProbe"


def kind(site):
    if site.get("type", 1) == 0:
        return "xml"
    if site.get("type", 1) != 3:
        return "json"
    api = str(site.get("api", ""))
    if api.startswith("csp_"):
        return "java"
    return "python" if ".py" in api else "javascript" if ".js" in api else "unknown"


def save(path, value):
    pending = path.with_suffix(".pending")
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    pending.chmod(0o600)
    pending.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--server", type=int, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--origin", required=True, help="Pinned upstream configuration URL")
    parser.add_argument("--output", type=Path, required=True, help="Private result directory; contains third-party addresses")
    parser.add_argument("--kind", action="append", choices=["java", "javascript", "python", "json", "xml"])
    parser.add_argument("--site", action="append", help="Exact provider key; repeat to select several")
    parser.add_argument("--jar", type=Path, help="Test an unmodified alternative upstream JAR")
    parser.add_argument("--timeout", type=int, default=100, help="Whole-provider timeout in seconds")
    parser.add_argument("--resume", action="store_true", help="Skip providers already recorded in this output directory")
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--shard-count", type=int, default=1)
    parser.add_argument("--occupied-proxy", action="store_true", help="Emulate another app owning the legacy discovery port")
    parser.add_argument("--skip-category", action="store_true", help="Isolate search/playback after a separately recorded category timeout")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an emulator may receive source fixtures")
    if urlparse(args.origin).scheme != "https":
        parser.error("Use a pinned HTTPS upstream URL")
    if args.shard_count < 1 or not 0 <= args.shard_index < args.shard_count:
        parser.error("Invalid shard index/count")
    adb = [args.adb, "-P", str(args.server), "-s", args.serial]

    def command(*parts, timeout=30):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, timeout=timeout).stdout

    if command("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Selected device is not an emulator")
    apk_path = command("shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
    apk_hash = command("shell", "sha256sum", apk_path).split()[0]
    config = json.loads(args.config.read_text())
    sites = config["sites"]
    selected = [site for site in sites if (not args.kind or kind(site) in args.kind)
                and (not args.site or site["key"] in args.site)]
    selected = [site for index, site in enumerate(selected) if index % args.shard_count == args.shard_index]
    if not selected:
        parser.error("No matching providers")
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    report_path = args.output / "results.json"
    if report_path.exists() and not args.resume:
        parser.error("Results already exist; choose another directory or use --resume")
    report = json.loads(report_path.read_text()) if report_path.exists() else {
        "origin": args.origin, "config_sha256": hashlib.sha256(args.config.read_bytes()).hexdigest(),
        "serial": args.serial, "apk_sha256": apk_hash, "started_utc": datetime.now(timezone.utc).isoformat(), "results": []}
    if report["config_sha256"] != hashlib.sha256(args.config.read_bytes()).hexdigest() or report.get("apk_sha256") != apk_hash:
        parser.error("Resume requires the same configuration and installed APK")
    completed = {item["site"] for item in report["results"]}
    origin = args.origin.rsplit("/", 1)[0] + "/"
    cache = args.output / "dependencies"
    cache.mkdir(exist_ok=True, mode=0o700)
    jar = args.jar.read_bytes() if args.jar else None
    lock = threading.Lock()
    dependencies = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *parts):
            pass

        def do_GET(self):
            path = unquote(urlparse(self.path).path)
            try:
                if path == "/source.json":
                    data = json.dumps(self.server.config, ensure_ascii=False).encode()
                elif path == "/override.jar" and jar is not None:
                    data = jar
                elif path.startswith("/assets/"):
                    relative = path[len("/assets/"):]
                    if ".." in relative.split("/"):
                        self.send_error(400)
                        return
                    address = origin + quote(relative, safe="/@:")
                    destination = cache / hashlib.sha256(address.encode()).hexdigest()
                    with lock:
                        if not destination.exists():
                            with urlopen(Request(address, headers={"User-Agent": "BananaTV-compatibility"}), timeout=20) as response:
                                data = response.read(32 * 1024 * 1024 + 1)
                            if len(data) > 32 * 1024 * 1024:
                                raise ValueError("Dependency exceeds size limit")
                            destination.write_bytes(data)
                            destination.chmod(0o600)
                        data = destination.read_bytes()
                        dependencies[relative] = {"cache": destination.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                        save(args.output / "dependencies.json", dependencies)
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                self.send_error(502)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    port = str(server.server_port)
    base = "http://127.0.0.1:" + port

    def localize(value):
        if isinstance(value, dict):
            return {key: localize(item) for key, item in value.items()}
        if isinstance(value, list):
            return [localize(item) for item in value]
        if isinstance(value, str):
            if value.startswith("./"):
                return base + "/assets/" + value[2:]
            match = re.match(r"https://raw\.githubusercontent\.com/qist/tvbox/(?:master|refs/heads/master)/(.+)", value)
            if match:
                return base + "/assets/" + match.group(1)
        return value

    server.config = localize(config)
    if jar is not None:
        server.config["spider"] = base + "/override.jar;md5;" + hashlib.md5(jar).hexdigest()
        report["jar_sha256"] = hashlib.sha256(jar).hexdigest()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    command("reverse", "tcp:" + port, "tcp:" + port)
    try:
        for index, site in enumerate(selected):
            if (args.output / "STOP").exists():
                break
            key = site["key"]
            if key in completed:
                continue
            command("shell", "am", "force-stop", PACKAGE)
            started = time.monotonic()
            remote = ["am", "instrument", "-w", "-e", "fixture", base, "-e", "source", base + "/source.json",
                      "-e", "audit", "true", "-e", "site", key, RUNNER]
            if args.occupied_proxy:
                remote[-1:-1] = ["-e", "occupied_proxy", "true"]
            if args.skip_category:
                remote[-1:-1] = ["-e", "skip_category", "true"]
            parts = ["shell", shlex.join(remote)]
            try:
                call = subprocess.run(adb + parts, capture_output=True, text=True, timeout=args.timeout)
                output = call.stdout + call.stderr
                timed_out = False
            except subprocess.TimeoutExpired as error:
                output = (error.stdout or b"").decode(errors="replace") + (error.stderr or b"").decode(errors="replace")
                timed_out = True
                command("shell", "am", "force-stop", PACKAGE)
            log = args.output / (hashlib.sha256(key.encode()).hexdigest()[:16] + ".txt")
            log.write_text(output)
            log.chmod(0o600)
            fields = dict(re.findall(r"^INSTRUMENTATION_RESULT: ([^=]+)=(.*)$", output, re.MULTILINE))
            stages = re.findall(r"^INSTRUMENTATION_STATUS: stage=(.*)$", output, re.MULTILINE)
            item = {"site": key, "name": site.get("name", key), "kind": kind(site), **fields,
                    "seconds": round(time.monotonic() - started, 1), "log": log.name, "category_skipped": args.skip_category}
            item["stage"] = fields.get("stage", stages[-1] if stages else "instrumentation")
            if timed_out:
                item["result"] = "TIMEOUT"
            elif fields.get("result") not in ("PASS", "FAIL"):
                item["result"] = "PROCESS_CRASH" if "Process crashed" in output else "RUNNER_FAILED"
            report["results"].append(item)
            if item.get("result") == "PASS":
                capture = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "cache/source-audit-player.jpg"], capture_output=True, timeout=10)
                if capture.returncode == 0 and capture.stdout.startswith(b"\xff\xd8"):
                    picture = log.with_suffix(".jpg")
                    picture.write_bytes(capture.stdout)
                    picture.chmod(0o600)
                    item["capture"] = picture.name
            report["counts"] = dict(collections.Counter(item["result"] for item in report["results"]))
            save(report_path, report)
            print(f"{index + 1}/{len(selected)} {key}: {item['result']} ({item['stage']}, {item['seconds']}s)", flush=True)
    finally:
        command("shell", "am", "force-stop", PACKAGE)
        command("reverse", "--remove", "tcp:" + port)
        server.shutdown()
        server.server_close()
    print(json.dumps(report["counts"], ensure_ascii=False))


if __name__ == "__main__":
    main()
