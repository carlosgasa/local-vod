#!/usr/bin/env python3
"""Self-check del servidor: `python3 test_server.py`. Sin frameworks."""

import json
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import server


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


def test_scan(root):
    server.MEDIA_ROOT = root
    items = {i["title"]: i for i in server.scan()}
    assert list(items) == ["Breaking Bad", "Cortos", "Dune (2021)"], list(items)

    movie = items["Dune (2021)"]
    assert movie["type"] == "movie", movie
    assert movie["poster"] == "/media/Dune%20%282021%29/poster.jpg", movie["poster"]
    assert movie["seasons"][0]["episodes"][0]["url"] == "/media/Dune%20%282021%29/Dune.mkv"

    serie = items["Breaking Bad"]
    assert serie["type"] == "series"
    assert serie["poster"].endswith("cover.png")
    assert [s["number"] for s in serie["seasons"]] == [1, 2], serie["seasons"]
    s1 = serie["seasons"][0]["episodes"]
    assert [e["number"] for e in s1] == [1, 2], s1
    assert s1[0]["id"] == "Breaking Bad/Temporada 01/S01E01 - Ep.mkv", s1[0]["id"]
    assert [sub["lang"] for sub in s1[0]["subs"]] == ["es"], s1[0]["subs"]
    assert s1[1]["subs"] == []

    cortos = items["Cortos"]
    assert cortos["type"] == "series"  # varios videos sueltos
    assert cortos["poster"] is None
    titles = [e["title"] for e in cortos["seasons"][0]["episodes"]]
    assert titles == ["corto 2", "corto 10"], titles  # orden natural, no alfabético
    print("scan OK")


def test_http(root):
    server.MEDIA_ROOT = root
    srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]

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
    assert get("/").read().startswith(b"<!DOCTYPE html>")
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
    srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]

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

    srv.shutdown()
    print("estado por http OK")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_tree(root)
        test_scan(root)
        test_http(root)
        test_state(tmp)
        test_state_http(tmp, root)
    print("todo OK")
