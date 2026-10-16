#!/usr/bin/env python3
"""Local VOD: sirve media/ a la app del Fire TV y un dashboard en /.

Sin dependencias: solo stdlib. Arranca con `python3 server.py`.
Config por variables de entorno: VOD_MEDIA (carpeta), VOD_PORT (puerto).
"""

import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

ROOT = Path(__file__).resolve().parent.parent
MEDIA_ROOT = Path(os.environ.get("VOD_MEDIA") or ROOT / "media")
APK = ROOT / "dist" / "app.apk"
PORT = int(os.environ.get("VOD_PORT") or 8000)
DASHBOARD = Path(__file__).resolve().parent / "dashboard.html"
STATE_FILE = Path(os.environ.get("VOD_STATE") or ROOT / "data" / "state.json")
MANUAL = Path(__file__).resolve().parent / "manual.html"
MANUAL_DIR = Path(__file__).resolve().parent / "manual"

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


# ---------------------------------------------------------------- estado

# Perfiles y progreso viven aquí para que la tele y los móviles vean lo mismo.
# Un solo lock: esto lo usan cuatro aparatos de una casa, no mil.
# ponytail: lock global y reescritura completa del json; si algún día son
# miles de entradas, pasar a sqlite.
_state_lock = threading.Lock()


def load_state():
    try:
        return json.loads(STATE_FILE.read_text())
    except (OSError, ValueError):
        return {"profiles": [], "progress": {}}


def save_state(state):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=1, ensure_ascii=False))
    tmp.replace(STATE_FILE)  # atómico: nunca deja el archivo a medias


def with_state(fn):
    """Lee, modifica y guarda el estado de una pieza."""
    with _state_lock:
        state = load_state()
        result = fn(state)
        save_state(state)
        return result


def profiles():
    def ensure(state):
        if not state["profiles"]:
            state["profiles"] = [{"id": uuid.uuid4().hex[:8], "name": "Casa"}]
        return state["profiles"]
    return with_state(ensure)


def add_profile(name):
    name = (name or "").strip()[:40]
    if not name:
        return None

    def mutate(state):
        profile = {"id": uuid.uuid4().hex[:8], "name": name}
        state["profiles"].append(profile)
        return profile
    return with_state(mutate)


def rename_profile(profile_id, name):
    name = (name or "").strip()[:40]

    def mutate(state):
        for profile in state["profiles"]:
            if profile["id"] == profile_id and name:
                profile["name"] = name
                return profile
        return None
    return with_state(mutate)


def delete_profile(profile_id):
    def mutate(state):
        state["profiles"] = [p for p in state["profiles"] if p["id"] != profile_id]
        state["progress"].pop(profile_id, None)
        return True
    return with_state(mutate)


def progress_of(profile_id):
    rows = load_state()["progress"].get(profile_id, {})
    return [dict(row, episodeId=episode_id) for episode_id, row in rows.items()]


def save_progress(profile_id, episode_id, title_id, position_ms, duration_ms):
    if not profile_id or not episode_id or duration_ms <= 0:
        return False

    def mutate(state):
        rows = state["progress"].setdefault(profile_id, {})
        rows[episode_id] = {
            "titleId": title_id,
            "positionMs": max(0, min(position_ms, duration_ms)),
            "durationMs": duration_ms,
            "updatedAt": int(time.time() * 1000),
        }
        return True
    return with_state(mutate)


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


# ---------------------------------------------------------------- conversión

# Si un aparato no sabe decodificar un archivo (VP9 en un móvil viejo, DTS en
# la tele), aquí se hace una copia en mp4. Primero se intenta remuxear (copiar
# el vídeo tal cual, segundos); si el códec no cabe en mp4, se recodifica.
JOBS = {}
_jobs_lock = threading.Lock()
COMPATIBLE_VIDEO = {"h264", "hevc", "mpeg4", "av1"}


def ffmpeg_available():
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def probe_duration(path):
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", str(path)],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        return float(out)
    except (OSError, ValueError, subprocess.SubprocessError):
        return 0.0


def video_codec(path):
    try:
        return subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name", "-of", "default=nw=1:nk=1", str(path)],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def set_job(job_id, **fields):
    with _jobs_lock:
        JOBS.setdefault(job_id, {}).update(fields)


def run_ffmpeg(args, job_id, total_seconds):
    """Lanza ffmpeg y va publicando el porcentaje en el job."""
    process = subprocess.Popen(
        args + ["-progress", "pipe:1", "-nostats", "-loglevel", "error"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    for line in process.stdout:
        if line.startswith("out_time_ms=") and total_seconds > 0:
            try:
                done = int(line.split("=", 1)[1]) / 1_000_000
                set_job(job_id, percent=min(99, int(done / total_seconds * 100)))
            except ValueError:
                pass
    error = process.stderr.read()
    return process.wait(), error


def convert_job(job_id, source):
    target = source.with_name(f"{source.stem} (convertido).mp4")
    total = probe_duration(source)
    set_job(job_id, state="running", percent=0, file=source.name, target=target.name)

    base = ["ffmpeg", "-nostdin", "-y", "-i", str(source)]
    # audio siempre a aac: es lo que entienden todos los aparatos
    remux = base + ["-map", "0:v:0", "-map", "0:a?", "-c:v", "copy", "-c:a", "aac", "-movflags", "+faststart", str(target)]
    recode = base + ["-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264", "-preset", "veryfast",
                     "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", str(target)]

    plan = [remux, recode] if video_codec(source) in COMPATIBLE_VIDEO else [recode]
    for attempt in plan:
        code, error = run_ffmpeg(attempt, job_id, total)
        if code == 0:
            set_job(job_id, state="done", percent=100, message=f"Creado: {target.name}")
            return
        lines = (error or "").strip().splitlines()
        set_job(job_id, message=lines[-1] if lines else "")
    target.unlink(missing_ok=True)
    set_job(job_id, state="error", message="ffmpeg no pudo convertirlo")


def start_conversion(source):
    if not ffmpeg_available():
        return None
    job_id = uuid.uuid4().hex[:8]
    set_job(job_id, id=job_id, state="queued", percent=0, file=source.name, message="")
    threading.Thread(target=convert_job, args=(job_id, source), daemon=True).start()
    return job_id


# ---------------------------------------------------------------- estado del servidor

DURATIONS_FILE = STATE_FILE.parent / "durations.json"
_durations_lock = threading.Lock()
_scanning_durations = False


def battery():
    """Batería del aparato que hace de servidor, si lo tiene (un celular, un portátil)."""
    # Android con Termux:API
    if shutil.which("termux-battery-status"):
        try:
            out = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=10).stdout
            data = json.loads(out)
            return {
                "percent": data.get("percentage"),
                "charging": data.get("status") == "CHARGING",
                "temperature": data.get("temperature"),
            }
        except (OSError, ValueError, subprocess.SubprocessError):
            return None
    # Linux con batería (portátil)
    for base in sorted(Path("/sys/class/power_supply").glob("BAT*")):
        try:
            return {
                "percent": int((base / "capacity").read_text().strip()),
                "charging": (base / "status").read_text().strip() == "Charging",
                "temperature": None,
            }
        except OSError:
            continue
    return None


def cpu_temperature():
    for zone in sorted(Path("/sys/class/thermal").glob("thermal_zone*/temp")):
        try:
            value = int(zone.read_text().strip()) / 1000
            if 10 < value < 120:
                return round(value, 1)
        except (OSError, ValueError):
            continue
    return None


def uptime_seconds():
    try:
        return int(float(Path("/proc/uptime").read_text().split()[0]))
    except (OSError, ValueError):
        return None


def video_files():
    if not MEDIA_ROOT.is_dir():
        return []
    return [p for p in MEDIA_ROOT.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXT]


def load_durations():
    try:
        return json.loads(DURATIONS_FILE.read_text())
    except (OSError, ValueError):
        return {}


def measure_durations(files):
    """ffprobe es lento: se mide una vez por archivo y se guarda (clave: ruta+tamaño)."""
    global _scanning_durations
    cache = load_durations()
    try:
        for path in files:
            key = f"{path.relative_to(MEDIA_ROOT)}:{path.stat().st_size}"
            if key in cache:
                continue
            cache[key] = probe_duration(path)
            with _durations_lock:
                DURATIONS_FILE.parent.mkdir(parents=True, exist_ok=True)
                DURATIONS_FILE.write_text(json.dumps(cache))
    finally:
        _scanning_durations = False


def library_stats():
    global _scanning_durations
    files = video_files()
    total_bytes = sum(f.stat().st_size for f in files)
    cache = load_durations()

    seconds, missing = 0.0, 0
    for path in files:
        key = f"{path.relative_to(MEDIA_ROOT)}:{path.stat().st_size}"
        if key in cache:
            seconds += cache[key]
        else:
            missing += 1

    # lo que falte se mide en segundo plano: el panel no se queda esperando a ffprobe
    if missing and ffmpeg_available() and not _scanning_durations:
        _scanning_durations = True
        threading.Thread(target=measure_durations, args=(files,), daemon=True).start()

    return {
        "videos": len(files),
        "bytes": total_bytes,
        "seconds": int(seconds),
        "pending": missing,
    }


def status():
    usage = shutil.disk_usage(MEDIA_ROOT if MEDIA_ROOT.is_dir() else ROOT)
    items = scan()
    stats = library_stats()
    return {
        "host": {
            "name": socket.gethostname(),
            "system": " ".join(os.uname()[:1] + os.uname()[2:3]),
            "python": sys.version.split()[0],
            "uptimeSeconds": uptime_seconds(),
            "temperature": cpu_temperature(),
        },
        "network": {"ip": lan_ip(), "port": PORT},
        "storage": {
            "path": str(MEDIA_ROOT),
            "totalBytes": usage.total,
            "freeBytes": usage.free,
            "libraryBytes": stats["bytes"],
        },
        "library": {
            "titles": len(items),
            "videos": stats["videos"],
            "seconds": stats["seconds"],
            "pendingDurations": stats["pending"],
        },
        "battery": battery(),
    }


# ---------------------------------------------------------------- subida

# Subir desde el panel evita pelearse con los permisos de Android: el servidor
# escribe en SU carpeta de medios y nadie necesita acceso al resto del aparato.
def safe_target(relative):
    """Ruta destino dentro de media/, sin trucos: nada de .. ni rutas absolutas."""
    parts = [p for p in unquote(relative).replace("\\", "/").split("/") if p not in ("", ".", "..")]
    if not parts:
        return None
    target = Path(os.path.normpath(MEDIA_ROOT.joinpath(*parts)))
    if MEDIA_ROOT not in target.parents:
        return None
    if target.suffix.lower() not in VIDEO_EXT | SUB_EXT | set(POSTER_EXT):
        return None
    return target


def receive_upload(handler, target):
    """Escribe el cuerpo de la petición en el archivo, por trozos."""
    length = int(handler.headers.get("Content-Length") or 0)
    if length <= 0:
        return False, "archivo vacío"
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".parte")
    written = 0
    try:
        with open(partial, "wb") as f:
            while written < length:
                chunk = handler.rfile.read(min(CHUNK, length - written))
                if not chunk:
                    break
                f.write(chunk)
                written += len(chunk)
        if written != length:
            partial.unlink(missing_ok=True)
            return False, "la subida se cortó"
        partial.replace(target)  # solo aparece en la biblioteca cuando está completo
        return True, str(target.relative_to(MEDIA_ROOT))
    except OSError as error:
        partial.unlink(missing_ok=True)
        return False, str(error)


# ---------------------------------------------------------------- paquete

# "Quiero montar el servidor en la otra computadora": desde el panel se baja
# un zip con lo imprescindible y sus instaladores. No hace falta git ni nada.
PACKAGE_FILES = [
    "server/server.py",
    "server/dashboard.html",
    "server/manual.html",
    "server/test_server.py",
    "desktop/install.sh",
    "desktop/install-server.sh",
    "desktop/instalar-windows.bat",
    "desktop/install-windows.ps1",
    "desktop/local-vod.service",
    "desktop/local-vod-system.service",
    "desktop/local-vod.desktop",
]

PACKAGE_README = """Local VOD - servidor de videos para la casa

Necesitas Python 3 instalado. Despues:

  Windows      doble clic en  desktop\\instalar-windows.bat
  Linux (PC)   bash desktop/install.sh
  Sin escritorio sudo bash desktop/install-server.sh /ruta/a/tus/videos

Luego copia tus carpetas de videos dentro de la carpeta "media" y abre
http://localhost:8000 para ver el panel. En la tele o el movil, abre la app
Local VOD y pulsa "Buscar en mi red".
"""


def package_zip():
    import io
    import zipfile

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for relative in PACKAGE_FILES:
            path = ROOT / relative
            if path.is_file():
                zf.write(path, f"local-vod/{relative}")
        for image in sorted(MANUAL_DIR.glob("*.png")):
            zf.write(image, f"local-vod/server/manual/{image.name}")
        zf.writestr("local-vod/LEEME.txt", PACKAGE_README)
        zf.writestr("local-vod/media/.keep", "")
    return buffer.getvalue()


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
        if path == "/api/profiles":
            return self.send_json({"profiles": profiles()})
        if path == "/api/progress":
            query = parse_qs(urlparse(self.path).query)
            profile_id = (query.get("profile") or [""])[0]
            return self.send_json({"items": progress_of(profile_id)})
        if path == "/api/status":
            return self.send_json(status())
        if path == "/api/jobs":
            with _jobs_lock:
                return self.send_json({"jobs": list(JOBS.values()), "ffmpeg": ffmpeg_available()})
        if path == "/manual":
            return self.send_bytes(MANUAL.read_bytes(), "text/html; charset=utf-8")
        if path.startswith("/manual/"):
            image = Path(os.path.normpath(MANUAL_DIR / unquote(path[len("/manual/"):])))
            if MANUAL_DIR not in image.parents or not image.is_file():
                return self.send_bytes(b"not found", "text/plain", 404)
            return self.send_file(image)
        if path == "/local-vod.zip":
            return self.send_bytes(package_zip(), "application/zip")
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

    def do_PUT(self):
        path = self.path.split("?")[0]
        if not path.startswith("/api/upload/"):
            return self.send_bytes(b"not found", "text/plain", 404)
        target = safe_target(path[len("/api/upload/"):])
        if target is None:
            return self.send_json({"error": "nombre o tipo de archivo no permitido"}, 400)
        ok, detail = receive_upload(self, target)
        return self.send_json({"ok": ok, "file": detail} if ok else {"error": detail}, 200 if ok else 400)

    def read_json(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, OSError):
            return {}

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/api/open-folder":
            MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
            subprocess.Popen(["xdg-open", str(MEDIA_ROOT)])
            return self.send_json({"ok": True})

        body = self.read_json()

        if path == "/api/profiles":
            profile = add_profile(body.get("name"))
            return self.send_json(profile or {"error": "nombre vacío"}, 200 if profile else 400)
        if path == "/api/profiles/rename":
            profile = rename_profile(body.get("id"), body.get("name"))
            return self.send_json(profile or {"error": "no existe"}, 200 if profile else 404)
        if path == "/api/profiles/delete":
            delete_profile(body.get("id"))
            return self.send_json({"ok": True})

        if path == "/api/progress":
            ok = save_progress(
                body.get("profile"), body.get("episodeId"), body.get("titleId"),
                int(body.get("positionMs") or 0), int(body.get("durationMs") or 0),
            )
            return self.send_json({"ok": ok}, 200 if ok else 400)

        if path == "/api/convert":
            source = self.media_path(body.get("id") or "")
            if source is None:
                return self.send_json({"error": "fuera de la carpeta de medios"}, 403)
            if not source.is_file():
                return self.send_json({"error": "archivo no encontrado"}, 404)
            job_id = start_conversion(source)
            if job_id is None:
                return self.send_json({"error": "ffmpeg no está instalado"}, 400)
            return self.send_json({"job": job_id})

        self.send_bytes(b"not found", "text/plain", 404)


def main():
    MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    print(f"Local VOD -> http://{lan_ip()}:{PORT}  (medios: {MEDIA_ROOT})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
