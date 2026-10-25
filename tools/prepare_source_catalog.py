#!/usr/bin/env python3
"""Prepare a reviewed source catalog and private-link Worker; never publish."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
from urllib.parse import urljoin, urlsplit


MAXIMUM_BYTES = 2 * 1024 * 1024
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = PROJECT_ROOT / "app/src/main/assets/media-sources.json"


def endpoint(value):
    if not isinstance(value, str):
        raise ValueError("API and mirror addresses must be HTTPS strings")
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment:
        raise ValueError("Use absolute HTTPS addresses without credentials or fragments")


def merge_catalogs(paths):
    sites, flags, skipped = [], [], []
    keys, endpoints = set(), set()
    for path in paths:
        if path.stat().st_size > MAXIMUM_BYTES:
            raise ValueError("Configuration exceeds 2 MiB")
        config = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(config, dict):
            raise ValueError("Expected a JSON object with a sites array; depot formats need separate conversion")
        entries = config.get("sites")
        if not isinstance(entries, list) or not entries or len(entries) > 500:
            raise ValueError("Configuration must have 1–500 sites")
        for entry in entries:
            if not isinstance(entry, dict):
                raise ValueError("Each site must be an object")
            key = entry.get("key")
            if not isinstance(key, str) or not key.strip():
                raise ValueError("Each site needs a stable key")
            kind = entry.get("type", 1)
            if type(kind) is not int or kind not in (1, 4):
                skipped.append({"key": key, "reason": "requires a separately tested adapter"})
                continue
            endpoint(entry.get("api"))
            mirrors = entry.get("mirrors", [])
            if not isinstance(mirrors, list) or len(mirrors) > 2:
                raise ValueError("Each JSON API site supports at most two mirrors")
            for mirror in mirrors:
                endpoint(mirror)
            identity = (kind, entry["api"])
            if identity in endpoints:
                skipped.append({"key": key, "reason": "duplicate API"})
                continue
            if key in keys:
                raise ValueError("Conflicting site keys; assign stable distinct keys before merging")
            site = copy.deepcopy(entry)
            # Executable adapters are distributed separately, never copied into a native API feed.
            site.pop("jar", None)
            sites.append(site)
            keys.add(key)
            endpoints.add(identity)
        values = config.get("flags", [])
        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
            raise ValueError("flags must be an array of strings")
        for value in values:
            if value not in flags:
                flags.append(value)
    if not sites or len(sites) > 500:
        raise ValueError("The merged catalog must have 1–500 compatible sites")
    catalog = {"sites": sites}
    if flags:
        catalog["flags"] = flags
    return catalog, skipped


def reviewed_catalog(path, origin, selected):
    endpoint(origin)
    if not selected:
        raise ValueError("A reviewed snapshot requires explicit --site keys")
    if path.stat().st_size > MAXIMUM_BYTES:
        raise ValueError("Configuration exceeds 2 MiB")
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict) or not isinstance(config.get("sites"), list):
        raise ValueError("Expected a snapshot with a sites array")
    sites, found, skipped = [], set(), []
    for site in config["sites"]:
        if not isinstance(site, dict) or not isinstance(site.get("key"), str):
            raise ValueError("Each snapshot site needs a stable key")
        key = site["key"]
        if key not in selected:
            skipped.append({"key": key, "reason": "not selected from reviewed snapshot"})
            continue
        if key in found:
            raise ValueError("Duplicate selected site key")
        if type(site.get("type", 1)) is not int or site.get("type", 1) not in (0, 1, 3, 4) or not site.get("api"):
            raise ValueError("Selected site has an unsupported type or missing API")
        sites.append(site)
        found.add(key)
    if found != set(selected):
        raise ValueError("Some selected site keys are missing from the snapshot")
    if not sites or len(sites) > 500:
        raise ValueError("The selected catalog must have 1–500 sites")
    config["sites"] = sites
    # Keep one snapshot's shared JAR, parsers and flags together; do not combine unrelated hosts.
    def absolute(value):
        if isinstance(value, dict):
            return {key: absolute(item) for key, item in value.items()}
        if isinstance(value, list):
            return [absolute(item) for item in value]
        if isinstance(value, str) and value.startswith(("./", "../")):
            return urljoin(origin, value)
        return value
    return absolute(config), skipped


def prepare(paths, output, name, snapshot=None, origin=None, selected=None, dependencies=None):
    if output.exists():
        raise ValueError("Output already exists; use a new revision directory")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", name):
        raise ValueError("Use a lowercase Worker name, up to 63 characters")
    if snapshot is None:
        catalog, skipped = merge_catalogs(paths)
    else:
        catalog, skipped = reviewed_catalog(snapshot, origin, selected)
    body = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode()
    if len(body) > MAXIMUM_BYTES:
        raise ValueError("Merged catalog exceeds the app's 2 MiB limit")
    files = []
    if dependencies is not None:
        if snapshot is None or not dependencies.is_dir() or dependencies.is_symlink():
            raise ValueError("Dependencies require a reviewed snapshot and a regular directory")
        for source in dependencies.rglob("*"):
            relative = source.relative_to(dependencies)
            if source.is_symlink() or any(part.startswith(".") for part in relative.parts):
                raise ValueError("Dependency paths must not contain symlinks or hidden files")
            if source.is_file():
                if source.suffix.lower() not in (".jar", ".js", ".py", ".json"):
                    raise ValueError("Only reviewed JAR, JavaScript, Python and JSON dependencies may be copied")
                files.append((source, relative))
    assets = output / "public"
    assets.mkdir(parents=True)
    (assets / "sources.json").write_bytes(body)
    for source, relative in files:
        destination = assets / "assets" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    (assets / "_headers").write_text(
        "/sources.json\n  Content-Type: application/json; charset=utf-8\n"
        "  Cache-Control: public, max-age=300\n  X-Content-Type-Options: nosniff\n"
        "/assets/*\n  Cache-Control: public, max-age=86400\n  X-Content-Type-Options: nosniff\n",
        encoding="utf-8")
    worker = PROJECT_ROOT / "deploy/source-catalog/worker.js"
    (output / "worker.js").write_bytes(worker.read_bytes())
    config = {"name": name, "main": "worker.js", "compatibility_date": "2026-10-24", "workers_dev": True,
              "preview_urls": False,
              "assets": {"directory": "./public", "binding": "ASSETS", "run_worker_first": True,
                         "not_found_handling": "none"}}
    (output / "wrangler.json").write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    report = {"sites": len(catalog["sites"]), "sha256": hashlib.sha256(body).hexdigest(),
              "dependencies": len(files), "skipped": skipped, "published": False}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="*", type=Path, help="Reviewed local JSON files; defaults to the bundled catalog")
    parser.add_argument("--output", type=Path, required=True, help="New revision directory outside the public source tree")
    parser.add_argument("--name", default="banana-tv-sources", help="Stable Cloudflare Worker name")
    parser.add_argument("--reviewed-snapshot", type=Path, help="Preserve a single tested snapshot, including its script dependencies")
    parser.add_argument("--origin", help="Pinned HTTPS origin for relative references in the reviewed snapshot")
    parser.add_argument("--site", action="append", help="Previously tested provider key; repeat to select a reviewed subset")
    parser.add_argument("--dependencies", type=Path, help="Reviewed dependency tree to copy under the protected assets path")
    args = parser.parse_args()
    if args.reviewed_snapshot and (args.inputs or not args.origin):
        parser.error("Use --origin and no positional inputs with --reviewed-snapshot")
    if not args.reviewed_snapshot and (args.origin or args.site or args.dependencies):
        parser.error("--origin, --site and --dependencies require --reviewed-snapshot")
    try:
        report = prepare(args.inputs or [DEFAULT_CATALOG], args.output, args.name, args.reviewed_snapshot, args.origin, args.site, args.dependencies)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Cannot prepare catalog: {error}\n")
    print(json.dumps(report, ensure_ascii=False))
    print("Prepared locally. No files were published.")


if __name__ == "__main__":
    main()
