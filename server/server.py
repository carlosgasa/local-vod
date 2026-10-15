#!/usr/bin/env python3
"""Local VOD: sirve media/ a la app del Fire TV y un dashboard en /.

Sin dependencias: solo stdlib. Arranca con `python3 server.py`.
Config por variables de entorno: VOD_MEDIA (carpeta), VOD_PORT (puerto).
"""

import json
import mimetypes
import os
import re
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote

ROOT = Path(__file__).resolve().parent.parent
MEDIA_ROOT = Path(os.environ.get("VOD_MEDIA") or ROOT / "media")
APK = ROOT / "dist" / "app.apk"
PORT = int(os.environ.get("VOD_PORT") or 8000)
DASHBOARD = Path(__file__).resolve().parent / "dashboard.html"

VIDEO_EXT = {".mkv", ".mp4", ".m4v", ".avi", ".webm", ".mov", ".ts", ".mpg", ".mpeg", ".wmv", ".flv"}
SUB_EXT = {".srt", ".ass", ".ssa", ".vtt"}
POSTER_STEMS = ("poster", "cover", "folder", "caratula", "carátula")
POSTER_EXT = (".jpg", ".jpeg", ".png", ".webp")
CHUNK = 64 * 1024

SEASON_RE = re.compile(r"(?:season|temporada|temp|^s)[\s._-]*0*(\d+)", re.I)
# S01E02 / 1x02 / S1.E2
EPISODE_RE = re.compile(r"s[\s._-]*(\d{1,2})[\s._-]*[ex][\s._-]*(\d{1,3})|(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)", re.I)

mimetypes.add_type("video/x-matroska", ".mkv")
mimetypes.add_type("video/mp2t", ".ts")
mimetypes.add_type("text/vtt", ".vtt")
mimetypes.add_type("application/x-subrip", ".srt")


# ---------------------------------------------------------------- escaneo

def natkey(name):
    """Orden natural: ep2 antes de ep10."""
    return [int(p) if p.isdigit() else p.lower() for p in re.split(r"(\d+)", str(name))]


def media_url(path):
    return "/media/" + quote(str(path.relative_to(MEDIA_ROOT)))


def find_poster(folder):
    for stem in POSTER_STEMS:
        for ext in POSTER_EXT:
            p = folder / (stem + ext)
            if p.is_file():
                return media_url(p)
    return None


def find_subs(video):
    """Subtítulos externos junto al video: 'Pilot.es.srt' -> lang 'es'."""
    subs = []
    for f in sorted(video.parent.iterdir(), key=natkey):
        if f.suffix.lower() not in SUB_EXT or not f.name.startswith(video.stem):
            continue
        middle = f.name[len(video.stem):-len(f.suffix)].strip(" ._-")
        subs.append({"lang": middle or "und", "url": media_url(f)})
    return subs


def videos_in(folder):
    return sorted((f for f in folder.iterdir() if f.is_file() and f.suffix.lower() in VIDEO_EXT), key=natkey)


def parse_episode(name):
    m = EPISODE_RE.search(name)
    if not m:
        return None, None
    s, e = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
    return int(s), int(e)


def episode_of(video, default_season):
    season, number = parse_episode(video.name)
    return {
        "id": str(video.relative_to(MEDIA_ROOT)),
        "title": video.stem,
        "season": season if season is not None else default_season,
        "number": number,  # None -> la app usa el orden de la lista
        "url": media_url(video),
        "subs": find_subs(video),
    }


def scan_title(folder):
    """Una carpeta de primer nivel = una película o una serie."""
    episodes = [episode_of(v, 1) for v in videos_in(folder)]
    season_dirs = [d for d in sorted(folder.iterdir(), key=natkey) if d.is_dir() and videos_in(d)]
    for i, sub in enumerate(season_dirs, start=1):
        m = SEASON_RE.search(sub.name)
        default = int(m.group(1)) if m else i
        episodes += [episode_of(v, default) for v in videos_in(sub)]
    if not episodes:
        return None

    seasons = {}
    for ep in episodes:
        seasons.setdefault(ep["season"], []).append(ep)
    for eps in seasons.values():
        eps.sort(key=lambda e: (e["number"] if e["number"] is not None else 0, natkey(e["title"])))

    is_movie = not season_dirs and len(episodes) == 1
    return {
        "id": str(folder.relative_to(MEDIA_ROOT)),
        "title": folder.name,
        "type": "movie" if is_movie else "series",
        "poster": find_poster(folder),
        "seasons": [{"number": n, "episodes": seasons[n]} for n in sorted(seasons)],
    }


def scan():
    if not MEDIA_ROOT.is_dir():
        return []
    items = []
    for d in sorted(MEDIA_ROOT.iterdir(), key=natkey):
        if not d.is_dir() or d.name.startswith("."):
            continue
        item = scan_title(d)
        if item:
            items.append(item)
    return items


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # no envía nada, solo elige la interfaz de salida
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def library():
    items = scan()
    return {
        "items": items,
        "server": {
            "ip": lan_ip(),
            "port": PORT,
            "mediaRoot": str(MEDIA_ROOT),
            "apk": APK.is_file(),
            "episodes": sum(len(s["episodes"]) for i in items for s in i["seasons"]),
        },
    }


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LocalVOD"

    def log_message(self, fmt, *args):
        if not self.path.startswith("/media/"):  # el streaming ensuciaría el log
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    # -- helpers

    def send_bytes(self, body, ctype, status=200):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, obj, status=200):
        self.send_bytes(json.dumps(obj).encode(), "application/json; charset=utf-8", status)

    def media_path(self, rel):
        """Resuelve dentro de MEDIA_ROOT. normpath (no resolve) mata los '..'
        sin romper los symlinks que el usuario meta apuntando a otros discos."""
        p = Path(os.path.normpath(MEDIA_ROOT / unquote(rel).lstrip("/")))
        if p != MEDIA_ROOT and MEDIA_ROOT not in p.parents:
            return None
        return p

    def send_file(self, path):
        try:
            size = path.stat().st_size
        except OSError:
            return self.send_bytes(b"not found", "text/plain", 404)

        start, end = 0, size - 1
        status = 200
        rng = self.headers.get("Range")
        if rng:
            m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
            if not m or (not m.group(1) and not m.group(2)):
                return self.send_range_error(size)
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            else:  # bytes=-500 -> últimos 500
                start = max(size - int(m.group(2)), 0)
            if start >= size or start > end:
                return self.send_range_error(size)
            status = 206

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with open(path, "rb") as f:
                f.seek(start)
                while length > 0:
                    data = f.read(min(CHUNK, length))
                    if not data:
                        break
                    self.wfile.write(data)
                    length -= len(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # normal: el reproductor corta la conexión en cada seek

    def send_range_error(self, size):
        self.send_response(416)
        self.send_header("Content-Range", f"bytes */{size}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    # -- rutas

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            return self.send_bytes(DASHBOARD.read_bytes(), "text/html; charset=utf-8")
        if path == "/api/library":
            return self.send_json(library())
        if path == "/app.apk":
            if not APK.is_file():
                return self.send_bytes(b"compila la app primero", "text/plain", 404)
            return self.send_file(APK)
        if path.startswith("/media/"):
            p = self.media_path(path[len("/media/"):])
            if p is None:
                return self.send_bytes(b"fuera de la carpeta de medios", "text/plain", 403)
            if not p.is_file():
                return self.send_bytes(b"not found", "text/plain", 404)
            return self.send_file(p)
        self.send_bytes(b"not found", "text/plain", 404)

    do_HEAD = do_GET

    def do_POST(self):
        if self.path == "/api/open-folder":
            MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
            subprocess.Popen(["xdg-open", str(MEDIA_ROOT)])
            return self.send_json({"ok": True})
        self.send_bytes(b"not found", "text/plain", 404)


def main():
    MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    print(f"Local VOD -> http://{lan_ip()}:{PORT}  (medios: {MEDIA_ROOT})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
