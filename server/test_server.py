#!/usr/bin/env python3
"""Self-check del servidor: `python3 test_server.py`. Sin frameworks."""

import json
import io
import tempfile
import threading
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from http.server import ThreadingHTTPServer
from pathlib import Path

import server


def start_http():
    """Usa TCP local si está permitido; si no, ejercita el handler en memoria."""
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    except PermissionError:
        def local_open(request):
            if isinstance(request, str):
                request = urllib.request.Request(request)
            handler = server.Handler.__new__(server.Handler)
            handler.path = urlsplit(request.full_url).path
            if urlsplit(request.full_url).query:
                handler.path += "?" + urlsplit(request.full_url).query
            handler.command = request.get_method()
            handler.headers = dict(request.headers)
            if request.data is not None:
                handler.headers["Content-Length"] = str(len(request.data))
            handler.rfile = io.BytesIO(request.data or b"")
            handler.wfile = io.BytesIO()
            handler.response_headers = {}
            handler.send_response = lambda status: setattr(handler, "response_status", status)
            handler.send_header = lambda key, value: handler.response_headers.__setitem__(key, value)
            handler.end_headers = lambda: None
            getattr(handler, "do_" + handler.command)()
            body = handler.wfile.getvalue()
            if handler.response_status >= 400:
                raise urllib.error.HTTPError(request.full_url, handler.response_status, "error", handler.response_headers, io.BytesIO(body))
            result = io.BytesIO(body)
            result.status = handler.response_status
            result.headers = handler.response_headers
            return result
        urllib.request.urlopen = local_open
        return None, "http://127.0.0.1"
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


def build_tree(root):
    movie = root / "Dune (2021)"
    movie.mkdir()
    (movie / "poster.jpg").write_bytes(b"\xff\xd8jpg")
    (movie / "Dune.mkv").write_bytes(bytes(range(256)) * 8)

    serie = root / "Breaking Bad"
    for season, eps in (("Temporada 01", 2), ("Temporada 2", 1)):
        d = serie / season
        d.mkdir(parents=True)
        for i in range(1, eps + 1):
            s = season[-1]
            (d / f"S0{s}E0{i} - Ep.mkv").write_bytes(b"v" * 100)
    (serie / "Temporada 01" / "S01E01 - Ep.es.srt").write_text("1\n")
    (serie / "cover.png").write_bytes(b"png")

    shorts = root / "Cortos"
    shorts.mkdir()
    for n in ("corto 2.mp4", "corto 10.mp4"):
        (shorts / n).write_bytes(b"v")

    (root / "Vacia").mkdir()

    album = root / "Viaje 2"
    (album / "Día 1").mkdir(parents=True)
    (album / "cover.jpg").write_bytes(b"portada")
    (album / "foto 10.JPG").write_bytes(b"diez")
    (album / "foto 2.png").write_bytes(b"dos")
    (album / "Día 1" / "foto 3.webp").write_bytes(b"tres")
    solo_portada = root / "Solo portada"
    solo_portada.mkdir()
    (solo_portada / "poster.jpg").write_bytes(b"x")
    (movie / "extra.png").write_bytes(b"x")
    album = root / "Música 2"
    (album / "Disco 1").mkdir(parents=True)
    (album / "cover.jpg").write_bytes(b"portada")
    (album / "canción 10.mp3").write_bytes(b"audio10")
    (album / "Disco 1" / "canción 2.flac").write_bytes(b"audio2")
    (movie / "banda sonora.mp3").write_bytes(b"audio")


def test_scan(root):
    server.MEDIA_ROOT = root
    items = {i["title"]: i for i in server.scan()}
    assert list(items) == ["Breaking Bad", "Cortos", "Dune (2021)"], list(items)
    assert "Viaje 2" not in items

    movie = items["Dune (2021)"]
    assert movie["type"] == "movie", movie
    assert movie["poster"] == "/media/Dune%20%282021%29/poster.jpg", movie["poster"]
    assert movie["seasons"][0]["episodes"][0]["url"] == "/media/Dune%20%282021%29/Dune.mkv"
    episode = movie["seasons"][0]["episodes"][0]
    assert episode["durationSeconds"] == 123 and episode["addedAt"] == movie["addedAt"]
    assert episode["addedAt"] == int((root / "Dune (2021)" / "Dune.mkv").stat().st_mtime * 1000)
    assert episode["thumb"].startswith("/thumb/")

    serie = items["Breaking Bad"]
    assert serie["type"] == "series"
    assert serie["poster"].endswith("cover.png")
    assert [s["number"] for s in serie["seasons"]] == [1, 2], serie["seasons"]
    s1 = serie["seasons"][0]["episodes"]
    assert [e["number"] for e in s1] == [1, 2], s1
    assert s1[0]["id"] == "Breaking Bad/Temporada 01/S01E01 - Ep.mkv", s1[0]["id"]
    assert [sub["lang"] for sub in s1[0]["subs"]] == ["es"], s1[0]["subs"]
    assert s1[1]["subs"] == []
    assert s1[0]["durationSeconds"] is None
    assert serie["addedAt"] == max(e["addedAt"] for s in serie["seasons"] for e in s["episodes"])
    assert movie["preview"].startswith("/preview/") if server.ffmpeg_available() else movie["preview"] is None

    cortos = items["Cortos"]
    assert cortos["type"] == "series"  # varios videos sueltos
    assert cortos["poster"] is None
    titles = [e["title"] for e in cortos["seasons"][0]["episodes"]]
    assert titles == ["corto 2", "corto 10"], titles  # orden natural, no alfabético
    print("scan OK")


def test_http(root):
    server.MEDIA_ROOT = root
    srv, base = start_http()

    def get(path, headers={}):
        return urllib.request.urlopen(urllib.request.Request(base + path, headers=headers))

    full = get("/media/Dune%20%282021%29/Dune.mkv")
    assert full.status == 200 and full.headers["Accept-Ranges"] == "bytes"
    body = full.read()
    assert len(body) == 2048, len(body)

    part = get("/media/Dune%20%282021%29/Dune.mkv", {"Range": "bytes=0-99"})
    assert part.status == 206, part.status
    chunk = part.read()
    assert len(chunk) == 100 and chunk == body[:100]
    assert part.headers["Content-Range"] == "bytes 0-99/2048", part.headers["Content-Range"]

    mid = get("/media/Dune%20%282021%29/Dune.mkv", {"Range": "bytes=1000-"})
    assert mid.status == 206 and mid.read() == body[1000:]

    tail = get("/media/Dune%20%282021%29/Dune.mkv", {"Range": "bytes=-50"})
    assert tail.read() == body[-50:]

    for bad, code in [("/media/../../../etc/passwd", 403), ("/media/no-existe.mkv", 404), ("/nada", 404)]:
        try:
            get(bad)
            assert False, "debería fallar: " + bad
        except urllib.error.HTTPError as e:
            assert e.code == code, (bad, e.code)

    try:
        get("/media/Dune%20%282021%29/Dune.mkv", {"Range": "bytes=9999-"})
        assert False, "range fuera de rango debería dar 416"
    except urllib.error.HTTPError as e:
        assert e.code == 416, e.code

    lib = get("/api/library")
    assert lib.headers["Content-Type"].startswith("application/json")
    payload = json.loads(lib.read())
    assert "Viaje 2" not in {item["id"] for item in payload["items"]}
    albums = json.loads(get("/api/photos").read())["albums"]
    assert "Viaje 2" in {album["id"] for album in albums}, albums
    assert "Solo portada" not in {album["id"] for album in albums}
    album = next(row for row in albums if row["id"] == "Viaje 2")
    assert album["title"] == "Viaje 2" and album["count"] == 3
    assert album["cover"] == "/media/Viaje%202/cover.jpg"
    assert [photo["name"] for photo in album["photos"]] == ["foto 3.webp", "foto 2.png", "foto 10.JPG"]
    assert album["photos"][0] == {"id": "Viaje 2/Día 1/foto 3.webp", "name": "foto 3.webp",
                                    "url": "/media/Viaje%202/D%C3%ADa%201/foto%203.webp", "bytes": 4}
    assert get(album["photos"][0]["url"]).read() == b"tres"
    assert "Dune (2021)" not in {row["id"] for row in albums}
    assert "Música 2" not in {item["id"] for item in payload["items"]}
    assert all("addedAt" in i for i in payload["items"])
    assert next(i for i in payload["items"] if i["title"] == "Remoto")["preview"] is None
    assert all("durationSeconds" in e and "thumb" in e and "addedAt" in e
               for i in payload["items"] for s in i["seasons"] for e in s["episodes"])
    thumb = next(i for i in payload["items"] if i["id"] == "Dune (2021)")["seasons"][0]["episodes"][0]["thumb"]
    assert get(thumb).read() == b"jpg"
    albums = json.loads(get("/api/music").read())["albums"]
    assert [album["id"] for album in albums] == ["Música 2"], albums
    album = albums[0]
    assert album["cover"] == "/media/M%C3%BAsica%202/cover.jpg"
    assert [track["title"] for track in album["tracks"]] == ["canción 2", "canción 10"]
    assert album["tracks"][0]["id"] == "Música 2/Disco 1/canción 2.flac"
    assert album["tracks"][0]["durationSeconds"] == 42
    assert album["tracks"][1]["durationSeconds"] is None
    assert album["tracks"][0]["bytes"] == 6
    assert get(album["tracks"][0]["url"], {"Range": "bytes=0-2"}).read() == b"aud"
    preview = next(i for i in payload["items"] if i["id"] == "Dune (2021)")["preview"]
    if server.ffmpeg_available():
        assert get(preview, {"Range": "bytes=1-3"}).read() == b"rev"
    try:
        get("/thumb/..%2F..%2Fstate.json")
        assert False, "miniatura fuera de la carpeta"
    except urllib.error.HTTPError as e:
        assert e.code == 403
    try:
        get("/preview/..%2F..%2Fstate.json")
        assert False, "adelanto fuera de la carpeta"
    except urllib.error.HTTPError as e:
        assert e.code == 403
    assert get("/").read().startswith(b"<!DOCTYPE html>")
    if srv:
        srv.shutdown()
    print("http OK")


def test_state(tmp):
    """Perfiles y progreso compartido: lo que ve la tele lo ve el móvil."""
    server.STATE_FILE = Path(tmp) / "state.json"

    first = server.profiles()
    assert len(first) == 1 and first[0]["name"] == "Casa", first
    assert server.profiles() == first, "no debe crear uno nuevo en cada consulta"

    ana = server.add_profile("  Ana  ")
    assert ana["name"] == "Ana" and ana["id"] != first[0]["id"]
    assert server.add_profile("   ") is None, "nombre vacío no crea perfil"
    assert len(server.profiles()) == 2

    assert server.save_progress(ana["id"], "Serie/S01E01.mkv", "Serie", 60_000, 600_000)
    assert not server.save_progress(ana["id"], "Serie/S01E01.mkv", "Serie", 60_000, 0), "sin duración no se guarda"
    assert server.save_progress(ana["id"], "Serie/S01E01.mkv", "Serie", 999_999_999, 600_000)
    rows = server.progress_of(ana["id"])
    assert len(rows) == 1 and rows[0]["positionMs"] == 600_000, rows  # recortado a la duración
    assert rows[0]["episodeId"] == "Serie/S01E01.mkv"
    assert server.progress_of(first[0]["id"]) == [], "cada perfil ve solo lo suyo"

    renamed = server.rename_profile(ana["id"], "Ana María")
    assert renamed["name"] == "Ana María"
    assert server.rename_profile("noexiste", "X") is None

    server.delete_profile(ana["id"])
    assert [p["id"] for p in server.profiles()] == [first[0]["id"]]
    assert server.progress_of(ana["id"]) == [], "borrar el perfil borra su progreso"
    print("estado OK")


def test_state_http(tmp, root):
    server.STATE_FILE = Path(tmp) / "state_http.json"
    server.MEDIA_ROOT = root
    srv, base = start_http()

    def post(path, payload):
        request = urllib.request.Request(
            base + path, data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"}, method="POST",
        )
        return json.loads(urllib.request.urlopen(request).read())

    def get(path):
        return json.loads(urllib.request.urlopen(base + path).read())

    casa = get("/api/profiles")["profiles"][0]
    assert post("/api/progress", {
        "profile": casa["id"], "episodeId": "Demo/ep.mkv", "titleId": "Demo",
        "positionMs": 5_000, "durationMs": 50_000,
    })["ok"]
    items = get("/api/progress?profile=" + casa["id"])["items"]
    assert len(items) == 1 and items[0]["positionMs"] == 5_000, items
    assert get("/api/progress?profile=otro")["items"] == []

    # subida: entra en media/, respeta subcarpetas y rechaza lo que no toca
    def put(path, data):
        request = urllib.request.Request(base + path, data=data, method="PUT")
        return json.loads(urllib.request.urlopen(request).read())

    assert put("/api/upload/Nueva%20Serie/T01/ep1.mkv", b"video!")["ok"]
    assert (root / "Nueva Serie" / "T01" / "ep1.mkv").read_bytes() == b"video!"
    assert not list(root.rglob("*.parte")), "no deben quedar archivos a medias"

    # un intento de salirse termina dentro de media, nunca fuera
    assert put("/api/upload/..%2F..%2Ffuera.mkv", b"x")["ok"]
    assert (root / "fuera.mkv").is_file(), "debe caer dentro de la carpeta de medios"
    assert not (root.parent / "fuera.mkv").exists(), "jamás fuera de la carpeta de medios"

    for bad in ("/api/upload/malo.exe", "/api/upload/"):
        try:
            put(bad, b"x")
            assert False, "debería rechazar: " + bad
        except urllib.error.HTTPError as e:
            assert e.code in (400, 404), (bad, e.code)

    try:
        post("/api/convert", {"id": "../../etc/passwd"})
        assert False, "convertir fuera de media debería dar 403"
    except urllib.error.HTTPError as e:
        assert e.code == 403, e.code

    def rejects(path, payload, code):
        try:
            post(path, payload)
            assert False, (path, payload)
        except urllib.error.HTTPError as e:
            assert e.code == code, (path, payload, e.code)

    # Las dos copias de Cortos tienen exactamente el mismo tamaño.
    groups = get("/api/duplicates")["groups"]
    assert any(g["reason"] == "mismo tamaño" and
               {"Cortos/corto 2.mp4", "Cortos/corto 10.mp4"} <= {f["id"] for f in g["files"]}
               for g in groups), groups
    rejects("/api/rename", {"id": "Cortos", "name": "Mala/Carpeta"}, 400)
    rejects("/api/rename", {"id": "Cortos", "name": "..mala"}, 400)
    rejects("/api/rename", {"id": "Cortos", "name": "Breaking Bad"}, 409)
    assert post("/api/rename", {"id": "Cortos", "name": "Cortos nuevos"})["id"] == "Cortos nuevos"
    assert (root / "Cortos nuevos").is_dir() and not (root / "Cortos").exists()
    rejects("/api/delete", {"id": "../fuera"}, 403)
    rejects("/api/delete", {"id": str(root.parent / "fuera")}, 403)
    with tempfile.TemporaryDirectory() as outside:
        external = Path(outside)
        (external / "archivo.mkv").write_bytes(b"intacto")
        (root / "enlace").symlink_to(external, target_is_directory=True)
        rejects("/api/delete", {"id": "enlace/archivo.mkv"}, 403)
        assert (external / "archivo.mkv").read_bytes() == b"intacto"
        (root / "enlace").unlink()
    assert post("/api/delete", {"id": "Cortos nuevos/corto 2.mp4"})["ok"]
    assert not (root / "Cortos nuevos" / "corto 2.mp4").exists()
    assert post("/api/delete", {"id": "Cortos nuevos"})["ok"]
    assert "Cortos nuevos" not in {i["id"] for i in get("/api/library")["items"]}

    if srv:
        srv.shutdown()
    print("estado por http OK")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "media"
        root.mkdir()
        build_tree(root)
        server.MEDIA_ROOT = root
        # las cachés (miniaturas, adelantos) cuelgan de STATE_FILE: fuera de media,
        # o el propio caché se colaría en la biblioteca como un título más
        server.STATE_FILE = Path(tmp) / "state.json"
        server.DURATIONS_FILE = Path(tmp) / "durations.json"
        server.LINKS_FILE = Path(tmp) / "links.json"
        server.DURATIONS_FILE.write_text(json.dumps({"Dune (2021)/Dune.mkv:2048": 123.9,
                                                     "Música 2/Disco 1/canción 2.flac:6": 42.5}))
        thumb = server.thumb_path(root / "Dune (2021)" / "Dune.mkv", 2048)
        thumb.parent.mkdir(parents=True)
        thumb.write_bytes(b"jpg")
        preview = server.preview_path(root / "Dune (2021)" / "Dune.mkv", 2048)
        preview.parent.mkdir(parents=True)
        preview.write_bytes(b"preview")
        server.save_links([{"title": "Remoto", "url": "https://example.com/video.mp4"}])
        assert server.link_items()[0]["preview"] is None
        test_scan(root)
        test_http(root)
        test_state(tmp)
        test_state_http(tmp, root)
        video = root / "Dune (2021)" / "Dune.mkv"
        video.write_bytes(video.read_bytes() + b"x")
        assert server.preview_path(video, video.stat().st_size) != preview
    print("todo OK")
