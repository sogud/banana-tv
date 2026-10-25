#!/usr/bin/env python3
"""Exercise the native media flow on an explicitly selected, disposable emulator build."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--server", required=True, type=int)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--navigation-only", action="store_true", help="Run only the browser navigation regression")
    parser.add_argument("--source-update-only", action="store_true", help="Run only fixed-URL source update checks")
    parser.add_argument("--panel-only", action="store_true", help="Run only custom media panels and remote-control checks")
    parser.add_argument("--formats-only", action="store_true", help="Run XML format, query replacement and invalid-response checks")
    parser.add_argument("--genres-only", action="store_true", help="Run unified category and genre navigation checks")
    parser.add_argument("--cache-only", action="store_true", help="Run cached catalog display, refresh and network-failure checks")
    parser.add_argument("--posters-only", action="store_true", help="Run old-page poster cancellation checks")
    parser.add_argument("--usage", choices=["player", "wallpaper", "startup", "reliability", "stall"], help="Run isolated usability regressions")
    parser.add_argument("--hls-fixture", type=Path, help="Directory containing licensed index.m3u8 and TS segments for reliability playback")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an emulator may receive fixture data")
    adb = [args.adb, "-P", str(args.server), "-s", args.serial]

    def command(*parts):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, timeout=60).stdout

    identity = command("shell", "getprop", "ro.kernel.qemu").decode().strip()
    if identity != "1":
        raise SystemExit("The selected device is not an emulator")
    command("shell", "pm", "path", "com.sogud.bananatv.mediatest")
    clip = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/mountain_loop.mp4"
    media = clip.read_bytes()
    failed_requests = {"/unavailable": 0, "/malformed": 0, "/slow": 0}
    cache_state = {"mode": "old", "revision": "1"}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            parsed = urlparse(self.path)
            query = parse_qs(parsed.query)
            base = "http://127.0.0.1:" + str(self.server.server_port)
            if parsed.path in ["/web-player", "/empty-web-player"]:
                body = b'<html><body></body></html>' if parsed.path == "/empty-web-player" else b'<html><body><video muted preload="metadata" src="/clip1.mp4"></video></body></html>'
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path == "/parser-failed":
                failed_requests["/parser-failed"] = failed_requests.get("/parser-failed", 0) + 1
                self.send_error(503)
                return
            if parsed.path == "/parser-json":
                if self.headers.get("Authorization") or self.headers.get("X-Parser-Test") != "approved":
                    self.send_error(403)
                    return
                body = json.dumps({"data": {"url": ["原画", base + "/clip1.mp4"], "format": "mp4",
                                             "header": {"Referer": base + "/web-player"}}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path in ["/unavailable", "/malformed"]:
                failed_requests[parsed.path] += 1
                self.send_response(503 if parsed.path == "/unavailable" else 200)
                self.end_headers()
                self.wfile.write(b"<html>Unavailable upstream</html>")
                return
            if parsed.path == "/stats":
                body = json.dumps(failed_requests).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if args.hls_fixture and (parsed.path.startswith("/reliable-hls/") or parsed.path in ["/slow-hls.m3u8", "/slow-hls2.m3u8", "/fast-hls.m3u8", "/fast-hls2.m3u8"]):
                if parsed.path.startswith("/slow-hls"):
                    time.sleep(0.45)
                name = parsed.path.rsplit("/", 1)[-1] if parsed.path.startswith("/reliable-hls/") else "index.m3u8"
                if not re.fullmatch(r"[A-Za-z0-9_.-]+\.(?:m3u8|ts)", name):
                    self.send_error(404); return
                target = args.hls_fixture / name
                if not target.is_file():
                    self.send_error(404); return
                body = target.read_bytes()
                if name == "index.m3u8":
                    body = body.replace(b"piece", b"/reliable-hls/piece")
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
                start, end = 0, len(body) - 1
                if match:
                    start = int(match.group(1)); end = min(int(match.group(2)), end) if match.group(2) else end
                if start > end:
                    self.send_error(416); return
                length = len(body); body = body[start:end + 1]
                self.send_response(206 if match else 200)
                if match:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{length}")
                self.send_header("Content-Type", "application/vnd.apple.mpegurl" if name.endswith(".m3u8") else "video/mp2t")
                self.send_header("Content-Length", str(len(body))); self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if parsed.path in ["/quality-master.m3u8", "/quality-list.m3u8", "/quality-broken.m3u8"]:
                failed_requests[parsed.path] = failed_requests.get(parsed.path, 0) + 1
                text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nquality-list.m3u8\n" if parsed.path == "/quality-master.m3u8" else "#EXTM3U\n#EXTINF:10,\nquality-segment.bin\n#EXT-X-ENDLIST\n"
                if parsed.path == "/quality-broken.m3u8":
                    text = "#EXTM3U\n#EXT-X-ENDLIST\n"
                body = text.encode(); self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
            if parsed.path == "/quality-segment.bin":
                failed_requests[parsed.path] = failed_requests.get(parsed.path, 0) + 1
                if self.headers.get("Range") != "bytes=0-65535":
                    self.send_error(400); return
                parsed = parsed._replace(path="/clip1.mp4")
            if parsed.path == "/stall-config.json":
                body = json.dumps({"sites": [{"key": "stall", "name": "缓冲测试", "type": 1, "api": base + "/reliability-api?source=stall"}]}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
            if parsed.path == "/stalled-video.mp4":
                self.send_response(200); self.send_header("Content-Length", "1000000"); self.end_headers()
                time.sleep(25); return
            if parsed.path == "/reliability-config.json":
                value = {"sites": [{"key": key, "name": name, "type": 1, "api": base + "/reliability-api?source=" + key}
                                   for key, name in [("slow", "慢片库"), ("fast", "快片库"), ("wrong", "年份不同的片库")]]}
                body = json.dumps(value, ensure_ascii=False).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
                return
            if parsed.path == "/reliability-api":
                key = query.get("source", ["slow"])[0]
                clip_url = base + ("/slow-media.mp4" if key == "slow" else "/clip1.mp4")
                if key == "stall":
                    clip_url = base + "/stalled-video.mp4"
                if args.hls_fixture and key != "stall":
                    clip_url = base + ("/slow-hls.m3u8" if key == "slow" else "/fast-hls.m3u8")
                backup_url = clip_url.replace("/slow-media.mp4", "/slow-media2.mp4").replace("/clip1.mp4", "/clip2.mp4").replace("-hls.m3u8", "-hls2.m3u8")
                movie = {"vod_id": key + "-film", "vod_name": "Spring · 可靠性测试", "vod_year": "2020" if key == "wrong" else "2019",
                         "type_name": "电影", "vod_play_from": "原画$$$备用",
                         "vod_play_url": "1$" + clip_url + "#2$" + clip_url + "$$$第01集$" + backup_url + "#第02集$" + backup_url}
                body = json.dumps({"class": [{"type_id": "film", "type_name": "电影"}], "list": [movie]}, ensure_ascii=False).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
                return
            if parsed.path == "/unavailable-video.mp4":
                self.send_error(404); return
            if parsed.path in ["/slow-media.mp4", "/slow-media2.mp4"]:
                time.sleep(0.45)
                parsed = parsed._replace(path="/clip1.mp4")
            if parsed.path == "/stage-entry-config.json":
                count = failed_requests.get(parsed.path, 0) + 1
                failed_requests[parsed.path] = count
                body = json.dumps({"sites": [{"key": "stage", "name": "初始来源" if count == 1 else "更新来源", "type": 1, "api": base + "/api"}]}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path == "/slow-entry-config.json":
                count = failed_requests.get(parsed.path, 0) + 1
                failed_requests[parsed.path] = count
                if count > 1:
                    time.sleep(5)
                body = json.dumps({"sites": [{"key": "fixture", "name": "测试片库", "type": 1, "api": base + "/api"}]}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if parsed.path == "/auto-config.json":
                revision = failed_requests.get(parsed.path, 0) + 1
                failed_requests[parsed.path] = revision
                if revision == 5:
                    self.send_error(503)
                    return
                if revision == 6:
                    time.sleep(5)
                value = {"sites": [
                    {"key": "other", "name": "另一来源", "type": 1, "api": "./second"},
                    {"key": "keep", "name": "初始来源" if revision == 1 else "更新来源", "type": 1, "api": "./api"}]}
                if revision == 3:
                    value = {"sites": []}
                body = b"<html>not JSON</html>" if revision == 4 else json.dumps(value, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if parsed.path == "/refresh-config.json":
                failed_requests["/refresh-config"] = failed_requests.get("/refresh-config", 0) + 1
                revision = failed_requests["/refresh-config"]
                value = {"sites": [{"key": "refresh", "name": "初始配置" if revision == 1 else "更新后的配置", "type": 1, "api": "./api"}]} if revision <= 2 else {"sites": []}
                body = json.dumps(value, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path in ["/poster.jpg", "/slow-poster.jpg"]:
                failed_requests["posters"] = failed_requests.get("posters", 0) + 1
                if parsed.path == "/slow-poster.jpg":
                    failed_requests["slow-posters"] = failed_requests.get("slow-posters", 0) + 1
                    time.sleep(5)
                body = (clip.parents[1] / "drawable-nodpi/mountain_poster.jpg").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if parsed.path in ["/clip1.mp4", "/clip2.mp4"]:
                start, end = 0, len(media) - 1
                header = self.headers.get("Range", "")
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", header)
                if match:
                    start = int(match.group(1))
                    end = min(int(match.group(2)), end) if match.group(2) else end
                if start > end or start >= len(media):
                    self.send_error(416)
                    return
                self.send_response(206 if match else 200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Accept-Ranges", "bytes")
                if match:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(media)}")
                self.send_header("Content-Length", str(end - start + 1))
                self.end_headers()
                self.wfile.write(media[start:end + 1])
                return
            if parsed.path == "/xml":
                if query.get("ac") not in (["list"], ["videolist"]) or query.get("token") != ["fixture"]:
                    self.send_error(400)
                    return
                page = int(query.get("pg", ["1"])[0])
                body = (f'<rss><class><ty id="1">电影</ty></class><list page="{page}" pagecount="3">'
                        f'<video><id>xml-sample</id><name>A &amp; B</name><type>电影</type><year>2019</year>'
                        f'<pic>{base}/poster.jpg</pic><des><![CDATA[<p>Spring</p>]]></des>'
                        f'<dl><dd flag="原画"><![CDATA[1${base}/clip1.mp4#2${base}/clip2.mp4]]></dd>'
                        f'<dd flag="备用">1${base}/clip2.mp4</dd></dl></video></list></rss>').encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path == "/formats-config.json":
                value = {"jarHost": "catvod-v1", "sites": [{"key": "xml", "name": "XML 测试", "type": 0, "api": "./xml?ac=list&token=fixture"},
                                   {"key": "web", "name": "网页播放测试", "type": 4, "api": "./api", "jarHost": "native"},
                                   {"key": "jx", "name": "JSON 解析测试", "type": 4, "api": "./jx-api"}],
                         "parses": [{"type": 1, "url": "./parser-failed?url="},
                                    {"type": 1, "url": "./parser-json?url=", "ext": {"header": {"X-Parser-Test": "approved"}}}]}
            elif parsed.path == "/unknown-host-config.json":
                value = {"jarHost": "unknown", "sites": [{"key": "unknown", "type": 1, "api": "./api"}]}
            elif parsed.path == "/folder-config.json":
                value = {"sites": [{"key": "folders", "name": "目录测试", "type": 1, "api": "./folders"}]}
            elif parsed.path == "/folders":
                folder = query.get("t", [""])[0]
                movie = {"vod_id": "root", "vod_name": "收藏合集", "vod_tag": "folder"}
                if folder == "root":
                    movie = {"vod_id": "nested", "vod_name": "二级目录", "vod_tag": "folder"}
                if folder == "nested" or "ids" in query:
                    movie = {"vod_id": "leaf", "vod_name": "Spring · 文件夹测试", "vod_play_from": "原画",
                             "vod_play_url": "片段$" + base + "/clip1.mp4"}
                value = {"page": 1, "pagecount": 1, "class": [{"type_id": "film", "type_name": "电影"}], "list": [movie]}
            elif parsed.path == "/poster-grid-config.json":
                value = {"sites": [{"key": "poster-grid", "name": "海报测试", "type": 1, "api": "./poster-grid"}]}
            elif parsed.path == "/poster-grid":
                value = {"page": 1, "pagecount": 1, "class": [{"type_id": "film", "type_name": "电影"}],
                         "list": [{"vod_id": str(i), "vod_name": "Poster " + str(i), "vod_year": "2020", "type_name": "动作片",
                                   "vod_pic": base + "/poster.jpg?grid=" + str(i)} for i in range(100)]}
            elif parsed.path == "/cache-mode":
                cache_state.update({key: values[0] for key, values in query.items() if key in cache_state})
                value = cache_state
            elif parsed.path == "/cache-config.json":
                value = {"sites": [{"key": "cached", "name": "缓存测试", "type": 1, "api": "./cache-api",
                                    "header": {"X-Test-Revision": cache_state["revision"]}}]}
            elif parsed.path == "/cache-api":
                failed_requests["cache-detail"] = failed_requests.get("cache-detail", 0) + int("ids" in query)
                if cache_state["mode"] == "fail":
                    self.send_error(503)
                    return
                if cache_state["mode"] == "fresh":
                    time.sleep(1)
                label = "旧内容" if cache_state["mode"] == "old" else "新内容"
                movie = {"vod_id": label, "vod_name": "Cache · " + label, "vod_year": "2020", "type_name": "动作片",
                         "vod_play_from": "原画", "vod_play_url": "片段$" + base + "/clip1.mp4"}
                value = {"page": 1, "pagecount": 1, "class": [{"type_id": "film", "type_name": "电影"}], "list": [movie]}
            elif parsed.path == "/taxonomy-config.json":
                value = {"sites": [{"key": "taxonomy", "name": "分类层级测试", "type": 1, "api": "./taxonomy"}]}
            elif parsed.path == "/taxonomy":
                category = query.get("t", [""])[0]
                page = int(query.get("pg", ["1"])[0])
                movie = {"vod_id": category + str(page), "vod_name": category + " 第" + str(page) + "页", "vod_year": "2020", "type_name": "动作片"}
                value = {"page": page, "pagecount": 0 if category == "parent" else 2,
                         "class": [{"type_id": "parent", "type_name": "电影片"}, {"type_id": "action", "type_name": "动作片"}, {"type_id": "comedy", "type_name": "喜剧片"}],
                         "list": [] if category == "parent" else [movie]}
            elif parsed.path == "/discovery-config.json":
                value = {"sites": [
                    {"key": "fixture", "name": "本机测试片库", "type": 1, "api": "./api"},
                    {"key": "hidden", "name": "第二片库", "type": 1, "api": "./hidden"}]}
            elif parsed.path == "/search-config.json":
                value = {"sites": [
                    {"key": "fixture", "name": "本机测试片库", "type": 1, "api": "./api"},
                    {"key": "second", "name": "第二片库", "type": 1, "api": "./second"},
                    {"key": "failed", "name": "失效片库", "type": 1, "api": "./unavailable"},
                    {"key": "ignored", "name": "不可搜索片库", "type": 1, "searchable": 0, "api": "./unavailable"}]}
            elif parsed.path == "/config.json":
                value = {"sites": [
                    {"key": "fixture", "name": "本机测试片库", "type": 1, "api": "./api"},
                    {"key": "mirror", "name": "备用接口测试", "type": 1, "api": "./unavailable", "mirrors": ["./malformed", "./api"]},
                    {"key": "dead-mirrors", "name": "接口全部失效测试", "type": 1, "api": "./unavailable", "mirrors": ["./malformed"]},
                    {"key": "parser", "name": "网页解析测试", "type": 4, "api": "./api"},
                    {"key": "javascript", "name": "JavaScript 测试", "type": 3, "api": "./source.js", "ext": {"base": base}},
                    {"key": "python", "name": "缺失组件测试", "type": 3, "api": "./missing.py"}]}
            elif parsed.path == "/source.js":
                code = """let base = '';
const movie = () => ({vod_id:'sample', vod_name:'Spring · 播放测试', vod_play_from:'原画', vod_play_url:'1$'+base+'/clip1.mp4#2$'+base+'/clip2.mp4'});
export default {
 init(ext) { base = ext.base; },
 home() { return {class:[{type_id:'film',type_name:'电影'}]}; },
 homeVod() { return {list:[movie()]}; },
 search(word) { return {list:word==='missing'?[]:[movie()]}; },
 category() { return {list:[movie()]}; },
 detail() { return {list:[movie()]}; },
 play(flag,id) { return {url:id,parse:0}; }
};"""
                body = code.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/javascript")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            elif parsed.path == "/invalid.json":
                value = {"sites": []}
            elif parsed.path == "/bad-jar.json":
                value = {"spider": base + "/bad.jar;md5;" + "0" * 32,
                         "sites": [{"key": "bad-jar", "api": "csp_Test", "type": 3}]}
            elif parsed.path == "/bad.jar":
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"invalid executable fixture; never loaded")
                return
            elif parsed.path in ["/api", "/second", "/hidden", "/jx-api"]:
                category = query.get("t", [""])[0]
                if category in ["slow", "slow2"] or query.get("wd") == ["slow"]:
                    failed_requests["/slow"] += 1
                    time.sleep(3)
                movie = {"vod_id": "sample", "vod_name": "Spring · 播放测试", "vod_year": "2019",
                         "type_name": "动画片",
                         "vod_content": "本机测试素材，来自 Blender 的 Spring。", "vod_pic": base + "/poster.jpg", "vod_remarks": "2 集",
                         "vod_play_from": "原画$$$备用", "vod_play_url":
                         "1$" + base + "/clip1.mp4#2$" + base + "/clip2.mp4$$$1$" + base + "/clip2.mp4"}
                value = {"page": int(query.get("pg", ["1"])[0]), "pagecount": 2,
                         "class": [{"type_id": "film", "type_name": "电影"}, {"type_id": "series", "type_name": "剧集"},
                                   {"type_id": "slow", "type_name": "综艺"}, {"type_id": "empty", "type_name": "纪录片"}],
                         "list": [] if query.get("wd") == ["missing"] else [movie]}
                if category in ["series", "shows"]:
                    movie["vod_name"] = "Spring · 剧集测试"
                if category in ["slow", "slow2"]:
                    movie["vod_name"] = "Spring · 过期结果"
                if category in ["empty", "empty2"]:
                    value["list"] = []
                if parsed.path != "/api":
                    value["class"] = [{"type_id": "cinema", "type_name": "电影片"}, {"type_id": "shows", "type_name": "电视剧"},
                                      {"type_id": "slow2", "type_name": "综艺节目"}, {"type_id": "empty2", "type_name": "纪录片"}]
                    movie["vod_id"] = "other-id"
                    movie["vod_play_from"] = "第二线路"
                    movie["vod_play_url"] = "第二集$" + base + "/clip2.mp4"
                if parsed.path == "/hidden" and not any(key in query for key in ["wd", "t", "ids"]):
                    value["list"] = []
                if category:
                    failed_requests["category:" + parsed.path + ":" + category] = failed_requests.get("category:" + parsed.path + ":" + category, 0) + 1
                    allowed = ["film", "series", "slow", "empty"] if parsed.path == "/api" else ["cinema", "shows", "slow2", "empty2"]
                    if category not in allowed:
                        self.send_error(400)
                        return
                if query.get("pg") == ["2"]:
                    value["list"] = [dict(movie, vod_id="page-two", vod_name="Spring · 第二页测试")]
                if "ac" not in query:
                    movie.pop("vod_pic")
                else:
                    value.pop("class")
                if "play" in query:
                    value = {"parse": 1, "url": base + "/web-player"}
                    if parsed.path == "/jx-api":
                        value = {"parse": 0, "jx": 1, "url": "https://video.example.invalid/watch",
                                 "header": {"Authorization": "fixture-source-only"}}
            else:
                self.send_error(404)
                return
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Cancelled navigation deliberately closes the response socket.

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_port
    try:
        command("reverse", "tcp:" + str(port), "tcp:" + str(port))
        options = []
        if args.navigation_only:
            options = ["-e", "navigation_only", "true"]
        if args.source_update_only:
            options = ["-e", "source_update_only", "true"]
        if args.usage:
            options = ["-e", "usage", args.usage]
        if args.panel_only:
            options = ["-e", "panel_only", "true"]
        if args.formats_only:
            options = ["-e", "formats_only", "true"]
        if args.genres_only:
            options = ["-e", "genres_only", "true"]
        if args.cache_only:
            options = ["-e", "cache_only", "true"]
        if args.posters_only:
            options = ["-e", "posters_only", "true"]
        output = command("shell", "am", "instrument", "-w", "-e", "fixture", "http://127.0.0.1:" + str(port),
                         *options, "com.sogud.bananatv.mediatest.test/com.sogud.bananatv.media.MediaIntegrationProbe").decode()
        print(output)
        if "result=PASS" not in output:
            raise SystemExit("Native media fixture failed")
    finally:
        command("reverse", "--remove", "tcp:" + str(port))
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
