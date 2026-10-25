#!/usr/bin/env python3
"""Generate update.json from a signed release APK; does not upload or publish anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--url", action="append", required=True, help="HTTPS APK URL; primary first, up to four")
    parser.add_argument("--notes", type=Path, help="UTF-8 release notes, up to 4000 characters")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, default=Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/34.0.0")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new path")
    if not args.apk.is_file() or not 0 < args.apk.stat().st_size <= 512 * 1024 * 1024:
        parser.error("APK must exist and be no larger than 512 MiB")
    if len(args.url) > 4:
        parser.error("At most four APK URLs are supported")
    for value in args.url:
        url = urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment or len(value) > 4096:
            parser.error("Use HTTPS URLs without credentials or fragments")
    tools = args.build_tools
    subprocess.run([str(tools / "apksigner"), "verify", str(args.apk)], check=True, capture_output=True)
    badging = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(args.apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not sdk or package[1] != "com.sogud.bananatv":
        parser.error("Expected the production Banana TV package")
    if int(package[2]) <= 0 or not re.fullmatch(r"[0-9]+(\.[0-9]+){1,3}([-.][A-Za-z0-9]+)*", package[3]) or int(sdk[1]) < 28:
        parser.error("Unsupported version name or minSdk")
    notes = args.notes.read_text(encoding="utf-8") if args.notes else ""
    if len(notes) > 4000:
        parser.error("Release notes exceed 4000 characters")
    digest = hashlib.sha256()
    with args.apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    manifest = {
        "schemaVersion": 1,
        "packageName": package[1],
        "variant": "guard" if package[3].endswith("-guard") else "standard",
        "versionCode": int(package[2]),
        "versionName": package[3],
        "minSdk": int(sdk[1]),
        "size": args.apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "urls": args.url,
        "notes": notes,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Created " + str(args.output) + "; no files were published.")


if __name__ == "__main__":
    main()
