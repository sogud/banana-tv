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


def endpoint(value, allow_http=False):
    if not isinstance(value, str):
        raise ValueError("API and mirror addresses must be HTTPS strings")
    url = urlsplit(value)
    if url.scheme not in (("http", "https") if allow_http else ("https",)) or not url.hostname or url.username or url.password or url.fragment:
        raise ValueError("Use absolute HTTPS addresses without credentials or fragments")


def read_catalog(path):
    if path.stat().st_size > MAXIMUM_BYTES:
        raise ValueError("Configuration exceeds 2 MiB")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object with a sites array")
    origin = value.get("origin") if "config" in value else None
    config = value.get("config", value)
    if not isinstance(config, dict) or not isinstance(config.get("sites"), list) or not 1 <= len(config["sites"]) <= 500:
        raise ValueError("Configuration must have 1–500 sites")
    if origin:
        endpoint(origin)
        def absolute(item):
            if isinstance(item, dict):
                return {key: absolute(child) for key, child in item.items()}
            if isinstance(item, list):
                return [absolute(child) for child in item]
            if isinstance(item, str) and item.startswith(("./", "../")):
                return urljoin(origin, item)
            return item
        config = absolute(config)
    return config


def merge_catalogs(paths, include_executable=False):
    if not paths:
        raise ValueError("Provide private source configurations; public builds contain none")
    configs = [read_catalog(path) for path in paths]
    # Keep the largest configuration's shared defaults; scope other defaults to their own sites.
    primary = max(configs, key=lambda config: len(config["sites"]))
    catalog = {key: copy.deepcopy(primary[key]) for key in ("spider", "jarHost", "flags", "parses") if key in primary}
    sites, skipped = [], []
    keys, endpoints = set(), {}
    for config in configs:
        for entry in config["sites"]:
            if not isinstance(entry, dict) or not isinstance(entry.get("key"), str) or not entry["key"].strip():
                raise ValueError("Each site needs a stable key")
            key, kind = entry["key"], entry.get("type", 1)
            if type(kind) is not int or kind not in ((0, 1, 3, 4) if include_executable else (1, 4)):
                skipped.append({"key": key, "reason": "requires explicitly enabled executable/XML aggregation"})
                continue
            api = entry.get("api")
            if not isinstance(api, str) or not api:
                raise ValueError("Each site needs an API")
            jar = entry.get("jar", config.get("spider", ""))
            if kind == 3 and api.startswith("csp_"):
                if not jar:
                    raise ValueError("JAR adapter has no declared archive")
                endpoint(jar.split(";md5;", 1)[0], allow_http=True)
            else:
                endpoint(api, allow_http=include_executable)
            mirrors = entry.get("mirrors", [])
            if not isinstance(mirrors, list) or len(mirrors) > 2 or (mirrors and kind not in (0, 1, 4)):
                raise ValueError("Only HTTP API sites support up to two mirrors")
            for mirror in mirrors:
                endpoint(mirror, allow_http=include_executable)
            site = copy.deepcopy(entry)
            for field, root_field, empty in (("jar", "spider", ""), ("jarHost", "jarHost", ""),
                                              ("flags", "flags", []), ("parses", "parses", [])):
                effective = entry.get(field, config.get(root_field, empty))
                if effective != catalog.get(root_field, empty):
                    site[field] = copy.deepcopy(effective)
            behavior = {field: value for field, value in site.items() if field not in ("key", "name", "mirrors")}
            identity = json.dumps(behavior, ensure_ascii=False, sort_keys=True)
            if identity in endpoints:
                previous = endpoints[identity]
                merged = list(previous.get("mirrors", []))
                for mirror in mirrors:
                    if mirror != api and mirror not in merged and len(merged) < 2:
                        merged.append(mirror)
                if merged:
                    previous["mirrors"] = merged
                skipped.append({"key": key, "reason": "duplicate API/adapter"})
                continue
            if key in keys:
                raise ValueError("Conflicting source keys; assign distinct keys before merging")
            sites.append(site)
            keys.add(key)
            endpoints[identity] = site
    if not sites or len(sites) > 500:
        raise ValueError("The merged catalog must have 1–500 compatible sites")
    catalog["sites"] = sites
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


def prepare(paths, output, name, snapshot=None, origin=None, selected=None, dependencies=None, include_executable=False):
    if output.exists():
        raise ValueError("Output already exists; use a new revision directory")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", name):
        raise ValueError("Use a lowercase Worker name, up to 63 characters")
    if snapshot is None:
        catalog, skipped = merge_catalogs(paths, include_executable)
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
    parser.add_argument("inputs", nargs="*", type=Path, help="Private local source configurations; no default sources")
    parser.add_argument("--output", type=Path, required=True, help="New revision directory outside the public source tree")
    parser.add_argument("--name", default="banana-tv-sources", help="Stable Cloudflare Worker name")
    parser.add_argument("--reviewed-snapshot", type=Path, help="Preserve a single tested snapshot, including its script dependencies")
    parser.add_argument("--origin", help="Pinned HTTPS origin for relative references in the reviewed snapshot")
    parser.add_argument("--site", action="append", help="Previously tested provider key; repeat to select a reviewed subset")
    parser.add_argument("--include-executable", action="store_true", help="Preserve declared XML, JAR, JavaScript and Python sources in a private aggregate")
    parser.add_argument("--dependencies", type=Path, help="Reviewed dependency tree to copy under the protected assets path")
    args = parser.parse_args()
    if args.reviewed_snapshot and (args.inputs or not args.origin):
        parser.error("Use --origin and no positional inputs with --reviewed-snapshot")
    if not args.reviewed_snapshot and (args.origin or args.site or args.dependencies):
        parser.error("--origin, --site and --dependencies require --reviewed-snapshot")
    if not args.reviewed_snapshot and not args.inputs:
        parser.error("Provide private source configurations; no catalog is bundled")
    try:
        report = prepare(args.inputs, args.output, args.name, args.reviewed_snapshot, args.origin, args.site, args.dependencies, args.include_executable)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Cannot prepare catalog: {error}\n")
    print(json.dumps(report, ensure_ascii=False))
    print("Prepared locally. No files were published.")


if __name__ == "__main__":
    main()
