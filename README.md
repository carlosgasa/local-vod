# Local VOD

Ver los videos de la PC en el Fire TV, en la red local. Sin usuarios, sin nube, sin terminal:
un servidor en Python (solo stdlib) con panel web, y una app de Android TV hecha con Kotlin,
Compose, Media3 y clean architecture.

## En la PC

El servidor ya queda instalado como servicio del usuario: arranca con la sesión y se reinicia solo.

- **Panel de control**: icono **Local VOD** en el menú de aplicaciones (abre <http://localhost:8000>).
- Desde el panel: ver qué contenido detectó, qué carpeta le falta carátula, **Volver a escanear** y
  **Abrir carpeta de videos**.
- Dirección para la app: la que muestra el panel (ej. `http://192.168.1.128:8000`).

Comandos, solo si hacen falta:

```bash
systemctl --user restart local-vod    # reiniciar
systemctl --user status local-vod     # ver estado
bash desktop/install.sh               # reinstalar servicio + icono
python3 server/test_server.py         # autochequeo del servidor
```

## Cómo meter contenido

Arrastra carpetas dentro de `media/`. Una carpeta = una película o una serie:

```
media/
├── Dune (2021)/                 → película (un solo video)
│   ├── poster.jpg
│   └── Dune.mkv
└── Breaking Bad/                → serie (tiene carpetas de temporada)
    ├── poster.jpg
    └── Temporada 01/
        ├── S01E01 - Pilot.mkv
        └── S01E01 - Pilot.es.srt   → subtítulo externo, idioma "es"
```

- El nombre de la carpeta es el título que se ve en la tele.
- Carátula: `poster`, `cover`, `folder` o `caratula` en `.jpg/.jpeg/.png/.webp`.
- Temporadas: subcarpetas con `Temporada N` / `Season N` / `S01`.
- Episodios: se ordenan por el `S01E02` del nombre; si no lo tienen, por orden natural.
- Varios videos sueltos en la carpeta = serie de una temporada.
- Si los videos viven en otro disco, mete **symlinks** dentro de `media/`.

## App en el Fire TV

1. En el Fire TV, instala la app **Downloader** (de la tienda de Amazon) y activa
   *Ajustes → Mi Fire TV → Opciones de desarrollador → Apps de fuentes desconocidas*.
2. En Downloader escribe la dirección que muestra el panel + `/app.apk`
   (ej. `http://192.168.1.128:8000/app.apk`) e instala.
3. Al abrirla, escribe esa misma dirección (sin `/app.apk`) y pulsa **Probar y guardar**.

Alternativa con adb: `adb connect <ip-del-firetv>:5555 && adb install -r dist/app.apk`.

Qué hace la app: carátulas por carpeta, fila de **Seguir viendo**, selector de temporada y
episodio, **Reanudar** o **Empezar de nuevo**, y cambio de pistas de audio y subtítulos desde los
ajustes del reproductor (incluye los `.srt` externos). El progreso se guarda en la tele (Room).

Recompilar la app: `./build-apk.sh` (deja la APK nueva en `dist/app.apk`, lista para Downloader).

## Estructura

```
server/     servidor + panel (server.py, dashboard.html, test_server.py)
app/        app Android (domain / data / presentation + Hilt)
desktop/    servicio systemd, icono del menú e instalador
media/      tus videos
dist/       app.apk publicada
```

## Límites conocidos

- **No transcodifica**: el archivo se manda tal cual. Si un video no se oye o no se ve en el Fire
  TV, es que ese codec no lo soporta el aparato (típico con DTS); habría que remuxearlo con ffmpeg.
- Sin metadata de internet (sinopsis, repartos) ni búsqueda.
- El progreso es por dispositivo: no se comparte con otra tele.
