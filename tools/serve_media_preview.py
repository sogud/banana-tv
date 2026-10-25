#!/usr/bin/env python3
"""Serve a prepared catalog and its dependencies to a local Android emulator."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", required=True, type=Path)
    parser.add_argument("--origin-file", required=True, type=Path, help="Private file holding the deployment's source URL")
    parser.add_argument("--port", type=int, default=18468)
    args = parser.parse_args()
    root = (args.revision / "public").resolve()
    origin = args.origin_file.read_text().strip().rsplit("/", 1)[0]
    device = "http://10.0.2.2:" + str(args.port)

    def local(value):
        if isinstance(value, dict):
            return {key: local(item) for key, item in value.items()}
        if isinstance(value, list):
            return [local(item) for item in value]
        if isinstance(value, str) and value.startswith(origin + "/assets/"):
            return device + value[len(origin):]
        return value

    body = (json.dumps(local(json.loads((root / "sources.json").read_text())), ensure_ascii=False) + "\n").encode()
    if origin.encode() in body:
        parser.error("An origin reference was not converted for local preview")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *parts):
            pass

        def do_GET(self):
            path = unquote(urlsplit(self.path).path)
            data = body
            if path != "/sources.json":
                if not path.startswith("/assets/") or ".." in path.split("/") or "\\" in path:
                    self.send_error(404)
                    return
                file = (root / path.lstrip("/")).resolve()
                if not file.is_relative_to(root) or not file.is_file():
                    self.send_error(404)
                    return
                data = file.read_bytes()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Type", "application/json" if path.endswith(".json") else "application/octet-stream")
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print("Emulator preview: " + device + "/sources.json", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
