#!/usr/bin/env python3
"""Check wallpaper index compatibility, packaged paths, licenses and media hashes offline."""
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def webp_size(path):
    data = path.read_bytes()
    require(data[:4] == b"RIFF" and data[8:12] == b"WEBP", f"Invalid WebP: {path.name}")
    require(data[12:16] == b"VP8 " and data[23:26] == b"\x9d\x01\x2a",
            f"Expected still lossy WebP: {path.name}")
    width, height = struct.unpack("<HH", data[26:30])
    return width & 0x3FFF, height & 0x3FFF


def validate(project):
    resource_root = project / "app/src/main/res"
    document = ET.parse(resource_root / "values/wallpapers.xml").getroot()
    arrays = {element.attrib["name"]: [item.text for item in element] for element in document}
    images = arrays["wallpaper_images"]
    names = arrays["wallpaper_names"]
    modes = [int(value) for value in arrays["wallpaper_modes"]]
    previews = arrays["wallpaper_thumbnails"]
    require(len(images) == len(names) == len(modes) == len(previews), "Wallpaper arrays differ in length")
    require(modes[:10] == [0, 0, 0, 0, 1, 2, 3, 4, 5, 6], "Saved wallpaper modes changed")
    require(images[:4] == [f"@drawable/spring_valley_{i}" for i in range(1, 5)]
            and images[4:10] == ["@drawable/mountain_poster"] * 6, "Saved wallpaper images changed")
    require(all(value == 0 for value in modes[10:]), "New photographs must remain still")
    for reference in images + previews:
        name = reference.removeprefix("@drawable/")
        matches = list((resource_root / "drawable-nodpi").glob(name + ".*"))
        require(len(matches) == 1, f"Missing or ambiguous wallpaper resource: {reference}")

    manifest = project / "wallpaper-sources.json"
    require(manifest.read_bytes() == (project / "app/src/main/assets/wallpaper-sources.json").read_bytes(),
            "Source and APK provenance differ")
    records = json.loads(manifest.read_text())
    landscape_bytes = 0
    hashes = set()
    for record in records:
        require(record["license"] in {"CC BY 4.0", "CC0", "Public domain"}, "Undocumented license")
        require(record["credit"] and record["rights_source"].startswith("https://"), "Missing media credit")
        for field, hash_field in [("file", "sha256"), ("thumbnail", "thumbnail_sha256")]:
            path = project / record[field]
            require(hashlib.sha256(path.read_bytes()).hexdigest() == record[hash_field],
                    f"Media hash mismatch: {record[field]}")
        require(webp_size(project / record["thumbnail"]) == (320, 180), "Oversized gallery preview")
        if record["file"].endswith(".webp"):
            require(webp_size(project / record["file"]) == (1920, 1080), "Wallpaper must be 1080p")
            require(record["sha256"] not in hashes, "Duplicate photograph")
            hashes.add(record["sha256"])
            landscape_bytes += (project / record["file"]).stat().st_size
    require(len(hashes) == 8 and len(images) == 18, "Expected eight new photographs and eighteen options")
    require(landscape_bytes < 3 * 1024 * 1024, "Landscape pack exceeds 3 MiB")
    require(webp_size(resource_root / "drawable-nodpi/preview_mountain.webp") == (320, 180),
            "Oversized video preview")
    video = json.loads((project / "app/src/main/assets/video-wallpaper-source.json").read_text())
    for file_field, hash_field in [("video_file", "video_sha256"), ("poster_file", "poster_sha256"),
                                   ("thumbnail_file", "thumbnail_sha256")]:
        path = project / "app/src/main" / video[file_field]
        require(hashlib.sha256(path.read_bytes()).hexdigest() == video[hash_field],
                f"Video/poster hash mismatch: {video[file_field]}")
    print(f"PASS: 18 compatible slots, licensed media hashes, 320x180 previews; pack {landscape_bytes:,} bytes")


if __name__ == "__main__":
    validate(Path(__file__).resolve().parents[1])
