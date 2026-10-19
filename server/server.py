#!/usr/bin/env python3
"""Local VOD: sirve media/ a la app del Fire TV y un dashboard en /.

Sin dependencias: solo stdlib. Arranca con `python3 server.py`.
Config por variables de entorno: VOD_MEDIA (carpeta), VOD_PORT (puerto).
"""

import json
import hashlib
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
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
AUDIO_EXT = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac"}
SUB_EXT = {".srt", ".ass", ".ssa", ".vtt"}
AUDIO_EXT = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac"}
# Un archivo de texto con una dirección dentro vale como vídeo: así se pueden
# meter enlaces desde el explorador de archivos, sin tocar el panel.
LINK_EXT = {".url", ".link"}
POSTER_STEMS = ("poster", "cover", "folder", "caratula", "carátula")
POSTER_EXT = (".jpg", ".jpeg", ".png", ".webp")
PHOTO_EXT = set(POSTER_EXT) | {".gif"}
# La biblioteca se ordena sola en estas carpetas: lo que se sube cae en la que le
# toca por tipo, y después cada quien lo mueve a una subcarpeta desde la PC.
MOVIES, SERIES, MUSIC, PHOTOS = "Películas", "Series", "Música", "Imágenes"
CATEGORIES = (MOVIES, SERIES, MUSIC, PHOTOS)
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
        state.get("lists", {}).pop(profile_id, None)
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


# "Ver después" y "Favoritos" (ids de títulos) y los canales favoritos (su dirección),
# por perfil, el último añadido primero.
LISTS = ("later", "favorites", "channels")


def lists_of(profile_id):
    rows = load_state().get("lists", {}).get(profile_id, {})
    return {name: rows.get(name, []) for name in LISTS}


def set_in_list(profile_id, name, title_id, on):
    if not profile_id or name not in LISTS or not isinstance(title_id, str) or not title_id:
        return None

    def mutate(state):
        rows = state.setdefault("lists", {}).setdefault(profile_id, {})
        ids = [i for i in rows.get(name, []) if i != title_id]
        rows[name] = [title_id] + ids if on else ids
        return {n: rows.get(n, []) for n in LISTS}
    return with_state(mutate)


def rename_in_lists(old, new):
    """Renombrar un título no lo saca de las listas de nadie."""
    def mutate(state):
        for rows in state.get("lists", {}).values():
            for name, ids in rows.items():
                rows[name] = [new if i == old else i for i in ids]
    with_state(mutate)


# ---------------------------------------------------------------- enlaces

# Vídeos que no están en el disco sino en una URL directa (mp4, m3u8...).
# Se guardan aparte y se mezclan en la biblioteca como un título más; el
# aparato los descarga directamente, no pasan por este servidor.
def load_links():
    try:
        return json.loads(LINKS_FILE.read_text())
    except (OSError, ValueError):
        return []


def save_links(links):
    LINKS_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = LINKS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(links, indent=1, ensure_ascii=False))
    tmp.replace(LINKS_FILE)


def add_link(title, url, poster):
    url = (url or "").strip()
    if not url.startswith(("http://", "https://")):
        return None
    with _state_lock:
        links = [l for l in load_links() if l.get("url") != url]
        link = {
            "title": (title or "").strip() or url.rsplit("/", 1)[-1],
            "url": url,
            "poster": (poster or "").strip() or None,
            "addedAt": int(time.time() * 1000),
        }
        links.append(link)
        save_links(links)
    return link


def delete_link(url):
    with _state_lock:
        save_links([l for l in load_links() if l.get("url") != url])
    return True


def link_items():
    items = []
    for link in load_links():
        url = link.get("url", "")
        if not url.startswith(("http://", "https://")):
            continue
        episode = {
            "id": url,
            "title": link.get("title") or url,
            "season": 1,
            "number": None,
            "url": url,
            "remote": True,
            "subs": [],
            # un enlace no se mide ni se miniaturiza: el archivo no está aquí
            "durationSeconds": None,
            "thumb": None,
            "addedAt": link.get("addedAt", 0),
        }
        items.append({
            "id": url,
            "title": link.get("title") or url,
            "type": "movie",
            "poster": link.get("poster"),
            "preview": None,
            "remote": True,
            "addedAt": link.get("addedAt", 0),
            "seasons": [{"number": 1, "episodes": [episode]}],
        })
    return items


# ---------------------------------------------------------------- TV en vivo

# Listas IPTV (.m3u) que se añaden desde el panel. El servidor las descarga y las
# trocea en canales; los aparatos ven los canales ya agrupados y abren el stream
# directamente, sin pasar por aquí. Se guarda una copia por si un día no hay internet.
IPTV_CACHE_SECONDS = 6 * 3600
_iptv_memory = {}  # url -> (cuándo, canales)
_iptv_lock = threading.Lock()

EXTINF_ATTR = re.compile(r'([\w-]+)="([^"]*)"')
# las categorías de iptv-org vienen en inglés
GROUP_NAMES = {
    "animation": "Animación", "auto": "Motor", "business": "Negocios", "classic": "Clásicos",
    "comedy": "Comedia", "cooking": "Cocina", "culture": "Cultura", "documentary": "Documentales",
    "education": "Educación", "entertainment": "Entretenimiento", "family": "Familia",
    "general": "General", "kids": "Infantil", "legislative": "Gobierno", "lifestyle": "Estilo de vida",
    "movies": "Películas", "music": "Música", "news": "Noticias", "outdoor": "Naturaleza",
    "relax": "Relax", "religious": "Religión", "science": "Ciencia", "series": "Series",
    "shop": "Compras", "sports": "Deportes", "travel": "Viajes", "weather": "Clima",
    "undefined": "Otros",
}


def group_name(raw):
    raw = raw.strip()
    return GROUP_NAMES.get(raw.lower(), raw) or "Otros"


def parse_m3u(text):
    channels, info = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(EXTINF_ATTR.findall(line))
            # el nombre va tras la primera coma fuera de comillas (el nombre puede llevar comas)
            bare = re.sub(r'"[^"]*"', "", line)
            name = bare.split(",", 1)[1].strip() if "," in bare else ""
            info = {
                "name": name or attrs.get("tvg-name") or "Canal",
                "logo": attrs.get("tvg-logo") or None,
                # iptv-org pone varias categorías separadas por ';': basta la primera
                "group": group_name((attrs.get("group-title") or "").split(";")[0]),
            }
        elif line.startswith("#EXTGRP:") and info is not None:
            info["group"] = group_name(line[8:]) if line[8:].strip() else info["group"]
        elif line and not line.startswith("#"):
            if line.startswith(("http://", "https://")):
                channels.append(dict(info or {"name": line.rsplit("/", 1)[-1], "logo": None, "group": "Otros"},
                                     url=line))
            info = None
    return channels


def load_iptv():
    try:
        return json.loads(IPTV_FILE.read_text())
    except (OSError, ValueError):
        return []


def save_iptv(playlists):
    IPTV_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = IPTV_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(playlists, indent=1, ensure_ascii=False))
    tmp.replace(IPTV_FILE)


def add_playlist(name, url, only=None, exclude=None):
    """only / exclude: categorías (ya traducidas) que se quedan o se quitan de esa lista.
    Las listas grandes traen de todo; así se queda solo lo que interesa."""
    url = (url or "").strip()
    if not url.startswith(("http://", "https://")):
        return None
    clean = lambda groups: [g.strip() for g in groups or [] if isinstance(g, str) and g.strip()]
    with _state_lock:
        playlists = [p for p in load_iptv() if p.get("url") != url]
        playlist = {"name": (name or "").strip() or url.rsplit("/", 1)[-1], "url": url,
                    "addedAt": int(time.time() * 1000)}
        if clean(only):
            playlist["only"] = clean(only)
        if clean(exclude):
            playlist["exclude"] = clean(exclude)
        playlists.append(playlist)
        save_iptv(playlists)
    return playlist


def delete_playlist(url):
    with _state_lock:
        save_iptv([p for p in load_iptv() if p.get("url") != url])
    with _iptv_lock:
        _iptv_memory.pop(url, None)
    return True


def iptv_cache_file(url):
    return STATE_FILE.parent / "iptv" / (hashlib.sha256(url.encode()).hexdigest() + ".m3u")


def playlist_channels(url, refresh=False):
    with _iptv_lock:
        cached = _iptv_memory.get(url)
    if cached and not refresh and time.time() - cached[0] < IPTV_CACHE_SECONDS:
        return cached[1]
    cache = iptv_cache_file(url)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "LocalVOD"})
        with urllib.request.urlopen(request, timeout=20) as response:
            text = response.read().decode("utf-8", errors="ignore")
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(text)
    except (OSError, ValueError):
        # sin internet o la lista caída: lo último que se bajó
        try:
            text = cache.read_text()
        except OSError:
            return []
    channels = parse_m3u(text)
    with _iptv_lock:
        _iptv_memory[url] = (time.time(), channels)
    return channels


def iptv(refresh=False):
    """Canales de todas las listas, agrupados por categoría y sin repetir streams."""
    playlists = load_iptv()
    groups, seen = {}, set()
    for playlist in playlists:
        only, exclude = set(playlist.get("only") or []), set(playlist.get("exclude") or [])
        for channel in playlist_channels(playlist["url"], refresh):
            if channel["url"] in seen or (only and channel["group"] not in only) or channel["group"] in exclude:
                continue
            seen.add(channel["url"])
            groups.setdefault(channel["group"], []).append(dict(channel, id=channel["url"]))
    ordered = sorted(groups.items(), key=lambda kv: (kv[0] == "Otros", natkey(kv[0])))
    return {
        "playlists": playlists,
        "groups": [{"name": name, "channels": sorted(chs, key=lambda c: natkey(c["name"]))}
                   for name, chs in ordered],
    }


# ---------------------------------------------------------------- escaneo

# Los nombres de archivo de descargas traen de todo: firmas, calidad, idioma, géneros,
# la web de donde salió... En pantalla se enseña solo "Título (Año)". El archivo no se toca.
TITLE_SMALL_WORDS = {"de", "del", "la", "las", "el", "los", "y", "a", "en", "con", "contra", "por", "para",
                     "un", "una", "of", "the", "to", "and", "in", "on", "by", "for"}
TITLE_GENRES = {"accion", "acción", "aventura", "ciencia", "ficcion", "ficción", "comedia", "crimen", "drama",
                "suspenso", "terror", "guerra", "romance", "animacion", "animación", "fantasia", "fantasía",
                "thriller", "documental", "musical", "familiar", "misterio", "western", "biografia", "biografía"}
YEAR_RE = re.compile(r"(?<!\d)(19[3-9]\d|20[0-4]\d)(?!\d)")


def clean_title(raw):
    text = re.sub(r"@\w+", " ", raw)
    years = list(YEAR_RE.finditer(text))
    # el año que cuenta es el último: "Blade Runner 2049 (2017)"; lo de detrás es basura de la descarga
    title = text[:years[-1].start()] if years else text
    dotted = "." in title or "_" in title
    title = re.sub(r"[._]", " ", title)
    title = re.sub(r"\[.*?\]", " ", title)
    title = re.sub(r"\(\s*\)", " ", title)
    if not years:
        # sin año, al final suelen venir los géneros: "TÍTULO - acción drama guerra"
        parts = [x for x in re.split(r"\s+-\s+", title)]
        while len(parts) > 1 and all(w.lower() in TITLE_GENRES for w in parts[-1].split()):
            parts.pop()
        title = " - ".join(parts)
        title = re.sub(r"\b(1080p|720p|2160p|4k|hd|latino|lat|dual|castellano|bluray|webrip)\b", " ", title, flags=re.I)
    title = re.sub(r"\s+", " ", title).strip(" -(")
    letters = [c for c in title if c.isalpha()]
    if letters and (dotted or all(c.islower() for c in letters) or all(c.isupper() for c in letters)):
        words = title.lower().split(" ")
        title = " ".join(w if i and w in TITLE_SMALL_WORDS else w[:1].upper() + w[1:] for i, w in enumerate(words))
    title = title[:1].upper() + title[1:]
    if not title:
        return raw
    return f"{title} ({years[-1].group(1)})" if years else title


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


def read_link_file(path):
    """Acepta una línea con la dirección, o el formato .url de Windows (URL=...)."""
    try:
        for line in path.read_text(errors="ignore").splitlines():
            line = line.strip()
            if line.lower().startswith("url="):
                line = line[4:].strip()
            if line.startswith(("http://", "https://")):
                return line
    except OSError:
        pass
    return None


def videos_in(folder):
    playable = VIDEO_EXT | LINK_EXT
    files = (f for f in folder.iterdir() if f.is_file() and f.suffix.lower() in playable)
    # un .url sin dirección dentro no es nada
    return sorted(
        (f for f in files if f.suffix.lower() not in LINK_EXT or read_link_file(f)),
        key=natkey,
    )


def playable(path):
    if path.suffix.lower() in LINK_EXT:
        return read_link_file(path) is not None
    return path.suffix.lower() in VIDEO_EXT


def library_entries(*categories):
    """Títulos o álbumes: lo que hay dentro de esas categorías y, como antes de
    que existieran, las carpetas sueltas de la raíz que no son categoría."""
    if not MEDIA_ROOT.is_dir():
        return []
    entries = []
    for entry in MEDIA_ROOT.iterdir():
        if entry.name.startswith("."):
            continue
        if entry.name in CATEGORIES:
            if entry.name in categories and entry.is_dir():
                entries += [p for p in entry.iterdir() if not p.name.startswith(".")]
        elif entry.is_dir():
            entries.append(entry)
    return sorted(entries, key=lambda p: natkey(p.name))


def title_root(path):
    """La carpeta (o el video suelto) de la que cuelga un archivo de la biblioteca."""
    parts = path.relative_to(MEDIA_ROOT).parts
    depth = 2 if len(parts) > 1 and parts[0] in CATEGORIES else 1
    return MEDIA_ROOT.joinpath(*parts[:depth])


def parse_episode(name):
    m = EPISODE_RE.search(name)
    if not m:
        return None, None
    s, e = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
    return int(s), int(e)


def thumb_path(video, size):
    key = f"{video.relative_to(MEDIA_ROOT)}:{size}"
    name = hashlib.sha256(key.encode()).hexdigest() + ".jpg"
    return STATE_FILE.parent / "thumbs" / name


def mosaic_path(video, size):
    key = f"{video.relative_to(MEDIA_ROOT)}:{size}:mosaico"
    name = hashlib.sha256(key.encode()).hexdigest() + ".jpg"
    return STATE_FILE.parent / "thumbs" / name


def preview_path(video, size):
    key = f"{video.relative_to(MEDIA_ROOT)}:{size}"
    name = hashlib.sha256(key.encode()).hexdigest() + ".mp4"
    return STATE_FILE.parent / "previews" / name


def preview_source(folder):
    # un video suelto en la raiz llega aqui como si fuera carpeta: es su propio adelanto
    if not folder.is_dir():
        return folder if folder.suffix.lower() in VIDEO_EXT else None
    first = next((v for v in videos_in(folder) if v.suffix.lower() in VIDEO_EXT), None)
    if first is not None:
        return first
    for sub in sorted((d for d in folder.iterdir() if d.is_dir()), key=natkey):
        first = next((v for v in videos_in(sub) if v.suffix.lower() in VIDEO_EXT), None)
        if first is not None:
            return first
    return None


def episode_of(video, default_season, durations):
    season, number = parse_episode(video.name)
    remote = read_link_file(video) if video.suffix.lower() in LINK_EXT else None
    stat = video.stat()
    key = f"{video.relative_to(MEDIA_ROOT)}:{stat.st_size}"
    # un enlace no tiene fotograma que sacar: su "archivo" son cuatro letras
    thumb = None if remote else thumb_path(video, stat.st_size)
    return {
        "id": str(video.relative_to(MEDIA_ROOT)),
        "title": video.stem,
        "season": season if season is not None else default_season,
        "number": number,  # None -> la app usa el orden de la lista
        "url": remote or media_url(video),
        "remote": bool(remote),
        "subs": find_subs(video),
        "durationSeconds": int(durations[key]) if key in durations else None,
        "addedAt": int(stat.st_mtime * 1000),
        "thumb": "/thumb/" + thumb.name if thumb and thumb.is_file() else None,
    }


def scan_title(folder, durations=None):
    """Una carpeta = una película o una serie; un video suelto = una película."""
    durations = durations if durations is not None else load_durations()
    loose = not folder.is_dir()
    episodes = [episode_of(v, 1, durations) for v in ([folder] if loose else videos_in(folder))]
    season_dirs = [] if loose else [
        d for d in sorted(folder.iterdir(), key=natkey) if d.is_dir() and videos_in(d)]
    for i, sub in enumerate(season_dirs, start=1):
        m = SEASON_RE.search(sub.name)
        default = int(m.group(1)) if m else i
        episodes += [episode_of(v, default, durations) for v in videos_in(sub)]
    if not episodes:
        return None

    seasons = {}
    for ep in episodes:
        seasons.setdefault(ep["season"], []).append(ep)
    for eps in seasons.values():
        eps.sort(key=lambda e: (e["number"] if e["number"] is not None else 0, natkey(e["title"])))

    is_movie = not season_dirs and len(episodes) == 1
    first = preview_source(folder)
    preview = preview_path(first, first.stat().st_size) if first else None
    mosaic = mosaic_path(first, first.stat().st_size) if first else None
    return {
        "id": str(folder.relative_to(MEDIA_ROOT)),
        "title": clean_title(folder.stem if loose else folder.name),
        "type": "movie" if is_movie else "series",
        "poster": None if loose else find_poster(folder),
        "preview": "/preview/" + preview.name if preview and preview.is_file() and ffmpeg_available() else None,
        # cuatro fotogramas en cuadrícula: la carátula de los títulos que no tienen
        "mosaic": "/thumb/" + mosaic.name if mosaic and mosaic.is_file() else None,
        "addedAt": max(ep["addedAt"] for ep in episodes),
        "seasons": [{"number": n, "episodes": seasons[n]} for n in sorted(seasons)],
    }


def scan():
    items = []
    durations = load_durations()
    for d in library_entries(MOVIES, SERIES):
        if not d.is_dir() and not playable(d):
            continue
        item = scan_title(d, durations)
        if item:
            items.append(item)
    return items


def loose_files(extensions, category):
    """Lo subido cae suelto en su categoría (o, con versiones viejas, en la raíz):
    si no se mira ahí, la foto recién subida no aparece en ningún sitio."""
    folders = [f for f in (MEDIA_ROOT, MEDIA_ROOT / category) if f.is_dir()]
    return sorted(
        (p for folder in folders for p in folder.iterdir()
         if p.is_file() and p.suffix.lower() in extensions and p.stem.lower() not in POSTER_STEMS),
        key=lambda p: natkey(p.name),
    )


def photo_albums():
    if not MEDIA_ROOT.is_dir():
        return []
    albums = []
    for folder in library_entries(PHOTOS):
        if not folder.is_dir():
            continue
        files = [p for p in folder.rglob("*") if p.is_file()]
        if any(p.suffix.lower() in VIDEO_EXT | LINK_EXT for p in files):
            continue
        images = sorted(
            (p for p in files if p.suffix.lower() in PHOTO_EXT and p.stem.lower() not in POSTER_STEMS),
            key=lambda p: natkey(p.relative_to(folder)),
        )
        if not images:
            continue
        photos = [{"id": str(p.relative_to(MEDIA_ROOT)), "name": p.name,
                   "url": media_url(p), "bytes": p.stat().st_size} for p in images]
        albums.append({"id": str(folder.relative_to(MEDIA_ROOT)), "title": folder.name,
                       "cover": find_poster(folder) or photos[0]["url"],
                       "count": len(photos), "photos": photos})
    loose = [{"id": str(p.relative_to(MEDIA_ROOT)), "name": p.name, "url": media_url(p),
              "bytes": p.stat().st_size} for p in loose_files(PHOTO_EXT, PHOTOS)]
    if loose:
        albums.insert(0, {"id": ".fotos-sueltas", "title": "Fotos sueltas",
                          "cover": loose[0]["url"], "count": len(loose), "photos": loose})
    return albums


def music():
    if not MEDIA_ROOT.is_dir():
        return {"albums": []}
    durations = load_durations()
    albums = []
    pending = []
    for folder in library_entries(MUSIC):
        if not folder.is_dir() or folder.is_symlink():
            continue
        files = [p for p in folder.rglob("*") if p.is_file() and not p.is_symlink()]
        if any(p.suffix.lower() in VIDEO_EXT | LINK_EXT for p in files):
            continue
        audio = sorted((p for p in files if p.suffix.lower() in AUDIO_EXT),
                       key=lambda p: (natkey(p.name), natkey(p.relative_to(folder))))
        if not audio:
            continue
        tracks = []
        for path in audio:
            size = path.stat().st_size
            key = f"{path.relative_to(MEDIA_ROOT)}:{size}"
            tracks.append({
                "id": str(path.relative_to(MEDIA_ROOT)), "title": path.stem,
                "url": media_url(path), "durationSeconds": int(durations[key]) if key in durations else None,
                "bytes": size,
            })
        albums.append({"id": str(folder.relative_to(MEDIA_ROOT)), "title": folder.name,
                       "cover": find_poster(folder), "tracks": tracks})
        pending.extend(audio)
    loose = loose_files(AUDIO_EXT, MUSIC)
    if loose:
        tracks = []
        for p in loose:
            relative, size = str(p.relative_to(MEDIA_ROOT)), p.stat().st_size
            key = f"{relative}:{size}"
            tracks.append({"id": relative, "title": p.stem, "url": media_url(p),
                           "durationSeconds": int(durations[key]) if key in durations else None,
                           "bytes": size})
        albums.insert(0, {"id": ".musica-suelta", "title": "Sueltas", "cover": None, "tracks": tracks})
        pending.extend(loose)
    start_media_scans(pending, thumbnails=False)
    return {"albums": albums}


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # no envía nada, solo elige la interfaz de salida
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        if "s" in locals():
            s.close()


def library():
    items = scan() + link_items()
    start_media_scans(video_files())
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
# el chip de video de Intel/AMD, si la maquina lo tiene (en la mini PC si)
VAAPI_DEVICE = Path("/dev/dri/renderD128")


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


def convert_plan(source, target, codec, hardware=None):
    """Los intentos, en orden: copiar el video tal cual, el chip de video, y al final la CPU.

    Si uno falla, convert_job pasa al siguiente, asi que el chip de video no
    necesita comprobaciones: o funciona o se cae solo al camino de siempre.
    """
    if hardware is None:
        hardware = VAAPI_DEVICE.exists()
    maps = ["-map", "0:v:0", "-map", "0:a?"]
    # audio siempre a aac: es lo que entienden todos los aparatos
    end = ["-c:a", "aac", "-movflags", "+faststart", str(target)]
    base = ["ffmpeg", "-nostdin", "-y", "-i", str(source)]

    remux = base + maps + ["-c:v", "copy"] + end
    # decodifica la CPU y codifica el chip: los Intel viejos no decodifican HEVC
    # bien, y de todos modos el tiempo se va en codificar
    chip = (["ffmpeg", "-nostdin", "-y", "-vaapi_device", str(VAAPI_DEVICE), "-i", str(source)]
            + maps + ["-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi", "-qp", "23"] + end)
    cpu = base + maps + ["-c:v", "libx264", "-preset", "veryfast",
                         "-crf", "20", "-pix_fmt", "yuv420p"] + end

    recode = [chip, cpu] if hardware else [cpu]
    return [remux] + recode if codec in COMPATIBLE_VIDEO else recode


def convert_job(job_id, source):
    target = source.with_name(f"{source.stem} (convertido).mp4")
    total = probe_duration(source)
    set_job(job_id, state="running", percent=0, file=source.name, target=target.name)

    for attempt in convert_plan(source, target, video_codec(source)):
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
LINKS_FILE = STATE_FILE.parent / "links.json"
IPTV_FILE = STATE_FILE.parent / "iptv.json"
_durations_lock = threading.Lock()
_scanning_durations = False
_scanning_thumbs = False
_scanning_previews = False
_scanning_mosaics = False
_preview_lock = threading.Lock()


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
    # los enlaces no son archivos de vídeo: ni duración ni miniatura
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
            try:
                key = f"{path.relative_to(MEDIA_ROOT)}:{path.stat().st_size}"
            except OSError:
                continue
            if key in cache:
                continue
            cache[key] = probe_duration(path)
            with _durations_lock:
                DURATIONS_FILE.parent.mkdir(parents=True, exist_ok=True)
                tmp = DURATIONS_FILE.with_suffix(".tmp")
                tmp.write_text(json.dumps(cache))
                tmp.replace(DURATIONS_FILE)
    finally:
        _scanning_durations = False


def generate_thumbs(files):
    """Crea una miniatura por vídeo sin retrasar la respuesta HTTP."""
    global _scanning_thumbs
    try:
        for path in files:
            try:
                target = thumb_path(path, path.stat().st_size)
                if target.is_file():
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                partial = target.with_suffix(".tmp.jpg")
                seconds = probe_duration(path)
                result = subprocess.run(
                    ["ffmpeg", "-nostdin", "-y", "-ss", str(seconds * 0.1),
                     "-i", str(path), "-frames:v", "1", "-vf", "scale=480:-2",
                     str(partial)], capture_output=True, timeout=90,
                )
                if result.returncode == 0 and partial.is_file():
                    partial.replace(target)
                else:
                    partial.unlink(missing_ok=True)
            except (OSError, subprocess.SubprocessError):
                continue
    finally:
        _scanning_thumbs = False


def generate_previews(files):
    """Genera adelantos en serie para limitar el uso de CPU."""
    global _scanning_previews
    try:
        for path in files:
            try:
                target = preview_path(path, path.stat().st_size)
                if target.is_file():
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                partial = target.with_suffix(".tmp.mp4")
                seconds = probe_duration(path)
                start = min(seconds * 0.2, seconds - 5) if seconds > 5 else 0 if seconds > 0 else 60
                def encode(offset):
                    return subprocess.run(
                        ["ffmpeg", "-nostdin", "-y", "-ss", str(offset), "-i", str(path),
                         "-t", "5", "-an", "-vf", "scale=min(480\\,iw):-2",
                         "-c:v", "libx264", "-movflags", "+faststart", str(partial)],
                        capture_output=True, timeout=180,
                    )
                result = encode(start)
                if seconds == 0 and (result.returncode != 0 or not partial.is_file() or not partial.stat().st_size):
                    result = encode(0)
                if result.returncode == 0 and partial.is_file() and partial.stat().st_size:
                    partial.replace(target)
                else:
                    partial.unlink(missing_ok=True)
            except (OSError, subprocess.SubprocessError):
                continue
    finally:
        with _preview_lock:
            _scanning_previews = False


# cada cuadro vertical: el mosaico entero queda 2:3, como una carátula
MOSAIC_TILE = (240, 360)


def mosaic_command(path, seconds, target):
    """Un fotograma al 20, 40, 60 y 80 % del video, en una cuadrícula de 2x2."""
    times = [seconds * f for f in (0.2, 0.4, 0.6, 0.8)] if seconds > 0 else [1, 10, 30, 60]
    w, h = MOSAIC_TILE
    args = ["ffmpeg", "-nostdin", "-y"]
    for t in times:
        args += ["-ss", f"{t:.2f}", "-i", str(path)]
    tiles = ";".join(
        f"[{i}:v]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},setsar=1[v{i}]" for i in range(4))
    grid = "[v0][v1][v2][v3]xstack=inputs=4:layout=0_0|w0_0|0_h0|w0_h0[out]"
    return args + ["-filter_complex", f"{tiles};{grid}", "-map", "[out]", "-frames:v", "1", str(target)]


def generate_mosaics(files):
    """Los mosaicos se sacan de uno en uno, como los adelantos, para no ahogar la CPU."""
    global _scanning_mosaics
    try:
        for path in files:
            try:
                target = mosaic_path(path, path.stat().st_size)
                if target.is_file():
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                partial = target.with_suffix(".tmp.jpg")
                result = subprocess.run(mosaic_command(path, probe_duration(path), partial),
                                        capture_output=True, timeout=180)
                if result.returncode == 0 and partial.is_file():
                    partial.replace(target)
                else:
                    partial.unlink(missing_ok=True)
            except (OSError, subprocess.SubprocessError):
                continue
    finally:
        with _preview_lock:
            _scanning_mosaics = False


def start_media_scans(files, thumbnails=True):
    # thumbnails=False es la pista de que son audios: ni miniatura ni adelanto que sacar.
    global _scanning_durations, _scanning_thumbs, _scanning_previews, _scanning_mosaics
    if not files:
        return
    cache = load_durations()
    if shutil.which("ffprobe") and not _scanning_durations and any(
        f"{p.relative_to(MEDIA_ROOT)}:{p.stat().st_size}" not in cache for p in files
    ):
        _scanning_durations = True
        threading.Thread(target=measure_durations, args=(files,), daemon=True).start()
    if thumbnails and ffmpeg_available() and not _scanning_thumbs and any(
        not thumb_path(p, p.stat().st_size).is_file() for p in files
    ):
        _scanning_thumbs = True
        threading.Thread(target=generate_thumbs, args=(files,), daemon=True).start()
    with _preview_lock:
        if thumbnails and ffmpeg_available() and not _scanning_previews:
            sources = [preview_source(root) for root in {title_root(p) for p in files}]
            pending = [p for p in sources if p and not preview_path(p, p.stat().st_size).is_file()]
            if pending:
                _scanning_previews = True
                threading.Thread(target=generate_previews, args=(pending,), daemon=True).start()
        if thumbnails and ffmpeg_available() and not _scanning_mosaics:
            # solo hace falta donde no hay carátula
            roots = {title_root(p) for p in files}
            sources = [preview_source(r) for r in roots if not r.is_dir() or find_poster(r) is None]
            pending = [p for p in sources if p and not mosaic_path(p, p.stat().st_size).is_file()]
            if pending:
                _scanning_mosaics = True
                threading.Thread(target=generate_mosaics, args=(pending,), daemon=True).start()


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
    start_media_scans(files)

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

# El servidor escribe en SU carpeta de medios; los clientes no necesitan acceso a ella.
def category_for(name):
    """Dónde cae lo que se sube: por tipo, y un video con S01E02 en el nombre es de una serie."""
    ext = Path(name).suffix.lower()
    if ext in AUDIO_EXT:
        return MUSIC
    if ext in PHOTO_EXT:
        return PHOTOS
    return SERIES if parse_episode(name)[0] is not None else MOVIES


def safe_target(relative):
    """Ruta destino dentro de media/, sin trucos: nada de .. ni rutas absolutas."""
    parts = [p for p in unquote(relative).replace("\\", "/").split("/") if p not in ("", ".", "..")]
    if not parts:
        return None
    if parts[0] not in CATEGORIES:
        # una carpeta que ya existe en alguna categoría se respeta (la carátula de
        # una película no se va a Imágenes); si no, decide el tipo del archivo
        existing = next((c for c in CATEGORIES if len(parts) > 1 and (MEDIA_ROOT / c / parts[0]).is_dir()), None)
        parts = [existing or category_for(parts[-1])] + parts
    target = Path(os.path.normpath(MEDIA_ROOT.joinpath(*parts)))
    if MEDIA_ROOT not in target.parents:
        return None
    if target.suffix.lower() not in VIDEO_EXT | SUB_EXT | AUDIO_EXT | PHOTO_EXT:
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


def managed_path(identifier):
    """Acepta un elemento dentro de media/, nunca su raíz ni un enlace externo."""
    if not isinstance(identifier, str) or not identifier or "\\" in identifier:
        return None
    relative = Path(identifier)
    if relative.is_absolute() or any(p in ("", ".", "..") for p in relative.parts):
        return None
    root = MEDIA_ROOT.resolve()
    target = (MEDIA_ROOT / relative).resolve()
    if root not in target.parents:
        return None
    return MEDIA_ROOT / relative


def is_title(identifier):
    """Un título vive en la raíz o dentro de una categoría; la categoría en sí no se toca."""
    parts = Path(identifier).parts
    if len(parts) == 1:
        return parts[0] not in CATEGORIES
    return len(parts) == 2 and parts[0] in CATEGORIES


def rename_title(identifier, name):
    if not isinstance(name, str) or not name.strip() or "/" in name or "\\" in name or ".." in name:
        return None, 400
    name = name.strip()
    source = managed_path(identifier)
    if source is None or not is_title(identifier):
        return None, 403
    if source.is_symlink() or not (source.is_dir() or playable(source)):
        return None, 404
    # un video suelto conserva su extensión: se renombra el título, no el formato
    leaf = name if source.is_dir() else name + source.suffix
    new_id = str(Path(identifier).parent / leaf)
    target = managed_path(new_id)
    if target is None or not is_title(new_id):
        return None, 400
    if os.path.lexists(target):
        return None, 409
    try:
        source.rename(target)
    except OSError:
        return None, 400
    rename_in_lists(identifier, new_id)
    return {"ok": True, "id": new_id}, 200


def delete_media(identifier):
    target = managed_path(identifier)
    if target is None:
        return None, 403
    if not target.exists():
        return None, 404
    if target.is_dir() and not is_title(identifier):
        return None, 400
    if not target.is_dir() and not target.is_file():
        return None, 400
    try:
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
    except OSError:
        return None, 400
    return {"ok": True}, 200


def duplicates():
    files = video_files()
    rows = [{"id": str(p.relative_to(MEDIA_ROOT)), "bytes": p.stat().st_size} for p in files]
    converted = {}
    for row in rows:
        path = Path(row["id"])
        stem = re.sub(r" \(convertido\)$", "", path.stem, flags=re.I)
        converted.setdefault((str(path.parent), stem.lower()), []).append(row)
    groups, used = [], set()
    for members in converted.values():
        if len(members) > 1 and any(Path(r["id"]).stem.lower().endswith(" (convertido)") for r in members):
            groups.append({"reason": "convertido", "files": members})
            used.update(r["id"] for r in members)
    sizes = {}
    for row in rows:
        if row["id"] not in used:
            sizes.setdefault(row["bytes"], []).append(row)
    groups += [{"reason": "mismo tamaño", "files": members}
               for members in sizes.values() if len(members) > 1]
    return {"groups": groups}


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
        if path == "/api/photos":
            return self.send_json({"albums": photo_albums()})
        if path == "/api/music":
            return self.send_json(music())
        if path == "/api/duplicates":
            return self.send_json(duplicates())
        if path == "/api/profiles":
            return self.send_json({"profiles": profiles()})
        if path == "/api/links":
            return self.send_json({"links": load_links()})
        if path == "/api/progress":
            query = parse_qs(urlparse(self.path).query)
            profile_id = (query.get("profile") or [""])[0]
            return self.send_json({"items": progress_of(profile_id)})
        if path == "/api/iptv":
            refresh = (parse_qs(urlparse(self.path).query).get("refresh") or [""])[0] == "1"
            return self.send_json(iptv(refresh))
        if path == "/api/lists":
            profile_id = (parse_qs(urlparse(self.path).query).get("profile") or [""])[0]
            return self.send_json(lists_of(profile_id))
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
        if path.startswith("/thumb/"):
            name = unquote(path[len("/thumb/"):])
            root = STATE_FILE.parent / "thumbs"
            thumb = (root / name).resolve()
            if root.resolve() not in thumb.parents or not re.fullmatch(r"[0-9a-f]{64}\.jpg", name):
                return self.send_bytes(b"fuera de la carpeta de miniaturas", "text/plain", 403)
            if not thumb.is_file():
                return self.send_bytes(b"not found", "text/plain", 404)
            return self.send_file(thumb)
        if path.startswith("/preview/"):
            name = unquote(path[len("/preview/"):])
            root = STATE_FILE.parent / "previews"
            preview = (root / name).resolve()
            if root.resolve() not in preview.parents or not re.fullmatch(r"[0-9a-f]{64}\.mp4", name):
                return self.send_bytes(b"fuera de la carpeta de adelantos", "text/plain", 403)
            if not preview.is_file():
                return self.send_bytes(b"not found", "text/plain", 404)
            return self.send_file(preview)
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

        if path == "/api/rename":
            result, code = rename_title(body.get("id"), body.get("name"))
            return self.send_json(result or {"error": "nombre o título no permitido"}, code)
        if path == "/api/delete":
            result, code = delete_media(body.get("id"))
            return self.send_json(result or {"error": "archivo o título no permitido"}, code)

        if path == "/api/profiles":
            profile = add_profile(body.get("name"))
            return self.send_json(profile or {"error": "nombre vacío"}, 200 if profile else 400)
        if path == "/api/profiles/rename":
            profile = rename_profile(body.get("id"), body.get("name"))
            return self.send_json(profile or {"error": "no existe"}, 200 if profile else 404)
        if path == "/api/profiles/delete":
            delete_profile(body.get("id"))
            return self.send_json({"ok": True})

        if path == "/api/links":
            link = add_link(body.get("title"), body.get("url"), body.get("poster"))
            return self.send_json(link or {"error": "la dirección debe empezar por http:// o https://"},
                                  200 if link else 400)
        if path == "/api/links/delete":
            delete_link(body.get("url"))
            return self.send_json({"ok": True})

        if path == "/api/iptv":
            playlist = add_playlist(body.get("name"), body.get("url"), body.get("only"), body.get("exclude"))
            return self.send_json(playlist or {"error": "la dirección debe empezar por http:// o https://"},
                                  200 if playlist else 400)
        if path == "/api/iptv/delete":
            delete_playlist(body.get("url"))
            return self.send_json({"ok": True})

        if path == "/api/lists":
            lists = set_in_list(body.get("profile"), body.get("list"), body.get("titleId"), bool(body.get("on")))
            return self.send_json(lists or {"error": "perfil, lista o título no válidos"}, 200 if lists else 400)

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
    for category in CATEGORIES:
        (MEDIA_ROOT / category).mkdir(parents=True, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    print(f"Local VOD -> http://{lan_ip()}:{PORT}  (medios: {MEDIA_ROOT})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
