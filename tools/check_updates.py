#!/usr/bin/env python3
"""Check HTTPS update failover and a user-confirmed upgrade on a disposable emulator package."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import threading
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--server", required=True, type=int)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--current-apk", required=True, type=Path)
    parser.add_argument("--upgrade-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only a disposable emulator is supported")
    adb = [args.adb, "-P", str(args.server), "-s", args.serial]

    def command(*parts, timeout=60):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, timeout=timeout).stdout

    if command("shell", "getprop", "ro.kernel.qemu").decode().strip() != "1":
        parser.error("Selected device is not an emulator")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/34.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    package = "com.sogud.bananatv.updatetest"
    for apk, code in [(args.current_apk, 13), (args.upgrade_apk, 14)]:
        badging = subprocess.run([str(sdk / "aapt2"), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
        if f"name='{package}' versionCode='{code}'" not in badging:
            parser.error("Use the isolated update fixture APKs at versionCodes 13 and 14")
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="banana-update-check-") as folder:
        work = Path(folder)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", "-keyout", str(work / "tls.key"), "-out", str(work / "tls.crt")], check=True, capture_output=True)
        subprocess.run([str(java / "keytool"), "-genkeypair", "-keystore", str(work / "wrong.jks"), "-storepass", "fixture-only", "-keypass", "fixture-only", "-alias", "fixture", "-dname", "CN=Wrong fixture signer", "-keyalg", "RSA", "-validity", "1"], check=True, capture_output=True)
        subprocess.run([str(sdk / "apksigner"), "sign", "--ks", str(work / "wrong.jks"), "--ks-pass", "pass:fixture-only", "--key-pass", "pass:fixture-only", "--out", str(work / "wrong.apk"), str(args.upgrade_apk)], check=True, capture_output=True)
        apks = {"/upgrade.apk": args.upgrade_apk.read_bytes(), "/wrong.apk": (work / "wrong.apk").read_bytes(), "/current.apk": args.current_apk.read_bytes()}
        counts = {}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *unused):
                pass

            def do_GET(self):
                counts[self.path] = counts.get(self.path, 0) + 1
                base = "https://localhost:" + str(self.server.server_port)
                if self.path in ["/failed", "/broken"]:
                    self.send_error(503)
                    return
                if self.path in ["/redirect", "/cleartext"]:
                    self.send_response(302)
                    self.send_header("Location", "/upgrade.apk" if self.path == "/redirect" else "http://localhost/file.apk")
                    self.end_headers()
                    return
                if self.path.endswith(".json"):
                    apk = apks["/upgrade.apk"]
                    urls = [base + "/broken", base + "/redirect"]
                    code = 14
                    if self.path == "/wrong-signer.json":
                        apk = apks["/wrong.apk"]
                        urls = [base + "/wrong.apk"]
                    elif self.path == "/downgrade.json":
                        apk = apks["/current.apk"]
                        urls = [base + "/current.apk"]
                        code = 13
                    elif self.path == "/wrong-version.json":
                        code = 15
                        urls = [base + "/upgrade.apk"]
                    elif self.path == "/https-downgrade.json":
                        urls = [base + "/cleartext"]
                    elif self.path == "/truncated.json":
                        urls = [base + "/truncated"]
                    value = {"schemaVersion": 1, "packageName": package, "variant": "standard", "versionCode": code, "versionName": "0.1.12-dev", "minSdk": 28, "size": len(apk), "sha256": "0" * 64 if self.path == "/bad-hash.json" else hashlib.sha256(apk).hexdigest(), "urls": urls, "notes": "更新下载与校验测试"}
                    body = json.dumps(value, ensure_ascii=False).encode()
                elif self.path in apks:
                    body = apks[self.path]
                else:
                    body = b"incomplete data"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                    pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(work / "tls.crt", work / "tls.key")
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        port = str(server.server_port)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            command("reverse", "tcp:" + port, "tcp:" + port)
            command("install", "--no-incremental", "-r", "-d", str(args.current_apk))
            command("install", "--no-incremental", "-r", str(args.test_apk))
            command("shell", "appops", "set", package, "REQUEST_INSTALL_PACKAGES", "deny")
            certificate = base64.b64encode((work / "tls.crt").read_bytes()).decode()
            output = command("shell", "am", "instrument", "-w", "-e", "base", "https://localhost:" + port, "-e", "certificate", certificate, package + ".test/com.sogud.bananatv.UpdateIntegrationProbe", timeout=120).decode()
            (args.output / "probe.txt").write_text(output)
            if "result=PASS" not in output:
                raise SystemExit(output)
            panel = command("exec-out", "run-as", package, "cat", "files/update-panel.png")
            if not panel.startswith(b"\x89PNG\r\n\x1a\n"):
                raise SystemExit("Update panel capture is not a PNG")
            (args.output / "update-panel.png").write_bytes(panel)
            # Android may kill an app when this permission changes. Restart the probe
            # and require the cached manifest/APK to resume without another download.
            command("shell", "appops", "set", package, "REQUEST_INSTALL_PACKAGES", "allow")
            resumed = command("shell", "am", "instrument", "-w", "-e", "install_only", "true", package + ".test/com.sogud.bananatv.UpdateIntegrationProbe", timeout=60).decode()
            (args.output / "resumed-probe.txt").write_text(resumed)
            if "result=PASS" not in resumed:
                raise SystemExit(resumed)
            command("shell", "uiautomator", "dump", "/sdcard/banana-update-ui.xml")
            layout = command("shell", "cat", "/sdcard/banana-update-ui.xml").decode()
            (args.output / "installer.xml").write_text(layout)
            (args.output / "installer.png").write_bytes(command("exec-out", "screencap", "-p"))
            nodes = ET.fromstring(layout).iter("node")
            buttons = [node for node in nodes if "packageinstaller" in node.get("package", "") and node.get("text", "").lower() in ["update", "install", "更新", "安装"] and node.get("enabled") == "true"]
            if len(buttons) != 1:
                raise SystemExit("Expected one system installation confirmation button; inspect installer.xml")
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", buttons[0].get("bounds")))
            command("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            for unused in range(30):
                installed = command("shell", "dumpsys", "package", package).decode()
                if "versionCode=14 " in installed:
                    break
                time.sleep(0.5)
            else:
                raise SystemExit("System-confirmed upgrade did not reach versionCode 14")
            saved = command("shell", "run-as", package, "cat", "shared_prefs/update-probe.xml").decode()
            if "preserved" not in saved:
                raise SystemExit("Upgrade did not preserve fixture preferences")
            for endpoint in ["/failed", "/broken", "/upgrade.apk", "/wrong.apk", "/current.apk", "/cleartext", "/truncated"]:
                if counts.get(endpoint, 0) < 1:
                    raise SystemExit("Fixture endpoint not exercised: " + endpoint)
            result = {"result": "PASS", "upgrade": "13 → 14 through Android installer", "preferences": "preserved", "requests": counts, "scope": "Disposable emulator only; no TV or production app changes"}
            (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            print(output.strip())
            print(resumed.strip())
            print("PASS: system-confirmed upgrade 13 → 14; preferences preserved")
            command("shell", "input", "keyevent", "3")
        finally:
            command("reverse", "--remove", "tcp:" + port)
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
