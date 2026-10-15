#!/usr/bin/env python3
"""Self-check del servidor: `python3 test_server.py`. Sin frameworks."""

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


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_tree(root)
        test_scan(root)
        test_http(root)
    print("todo OK")
