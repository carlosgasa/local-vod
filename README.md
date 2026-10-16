# Local VOD

[![Instalación en un paso](https://img.shields.io/badge/instalar-en%20un%20paso-4da3ff)](#instalar-desde-github)

Ver los videos de la PC en el Fire TV, en la red local. Sin usuarios, sin nube, sin terminal:
un servidor en Python (solo stdlib) con panel web, y una app de Android TV hecha con Kotlin,
Compose, Media3 y clean architecture.

## Qué hace

- Biblioteca por carpetas con carátula, series con temporadas y episodios.
- **Perfiles sin contraseña**: cada quien tiene su propio «seguir viendo».
- **El progreso vive en el servidor**: empiezas un capítulo en el móvil y lo sigues en la tele.
- Botón **Continuar** en cada serie, reanudar o empezar de nuevo.
- Cambio de pistas de audio y subtítulos (del archivo y de los `.srt` de al lado).
- Aviso de **siguiente episodio** con cuenta atrás y opción de cancelar.
- **Convertir a MP4** desde el panel para archivos que el aparato no decodifica.
- La app **encuentra el servidor sola** en la red si le cambia la IP a la PC.
- **Subir videos desde el panel**, sin tocar carpetas ni permisos de Android.
- **Estado del servidor en la app**: espacio libre, tamaño y duración de la biblioteca y, si el
  servidor es un celular, batería y temperatura. Comprueba cada minuto que siga vivo y avisa.
- Corre igual en una PC Linux, en Windows o en un **celular viejo con Termux**.

## Manual

Con el servidor encendido: **<http://localhost:8000/manual>** (o el botón *Manual* del panel).
Lleva diagrama, capturas y los pasos para montar el servidor en otra computadora.

## En la PC

El servidor ya queda instalado como servicio del usuario: arranca con la sesión y se reinicia solo.

- **Panel de control**: icono **Local VOD** en el menú de aplicaciones (abre <http://localhost:8000>).
- Desde el panel: ver qué contenido detectó, qué carpeta le falta carátula, **Volver a escanear**,
  **Abrir carpeta de videos**, gestionar **perfiles** y **convertir a MP4** los vídeos que algún
  aparato no aguante (usa el ffmpeg del sistema; deja el original y crea una copia al lado).
- Dirección para la app: la que muestra el panel (ej. `http://192.168.1.100:8000`).

### Instalar desde GitHub

En la computadora que vaya a hacer de servidor (necesita **Python 3**):

**Linux con escritorio** — deja el icono «Local VOD» y arranque con la sesión:

```bash
curl -fsSL https://raw.githubusercontent.com/carlosgasa/local-vod/main/desktop/get.sh | bash
```

**Máquina sin pantalla** (mini PC, thin client, Raspberry Pi, PC vieja) — servicio del sistema,
indicando dónde están los videos:

```bash
curl -fsSL https://raw.githubusercontent.com/carlosgasa/local-vod/main/desktop/get.sh | bash -s -- /mnt/videos
```

**Windows** — descarga
[`instalar-desde-github.bat`](https://raw.githubusercontent.com/carlosgasa/local-vod/main/desktop/instalar-desde-github.bat)
y haz doble clic: baja el proyecto, deja el servidor arrancando con la sesión, crea el acceso
directo al panel y abre el puerto 8000 en el cortafuegos.

> Instala Python desde [python.org](https://www.python.org/downloads/windows/) marcando
> **«Add python.exe to PATH»** antes de ejecutarlo.

Después, en todos los casos: copia tus carpetas de videos en `media/`, abre
<http://localhost:8000> y sigue el [manual](#manual).

### Montarlo desde el panel (sin internet)

Si la otra computadora no tiene internet, desde el panel de esta: **Descargar el paquete del
servidor (zip)**, que trae el servidor, el panel, el manual y los instaladores. Después, en esa
máquina:

| Dónde | Qué ejecutar |
|---|---|
| Windows | doble clic en `desktop\instalar-windows.bat` (necesita Python con *Add to PATH*) |
| Linux con escritorio | `bash desktop/install.sh` |
| Sin pantalla (mini PC, thin client, Raspberry Pi, PC vieja) | `sudo bash desktop/install-server.sh /ruta/a/los/videos` |

En todos los casos hay que dejar pasar el **puerto 8000** en el cortafuegos de esa computadora, y
conviene reservarle **IP fija** en el router.

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
   (ej. `http://192.168.1.100:8000/app.apk`) e instala.
3. Al abrirla, escribe esa misma dirección (sin `/app.apk`) y pulsa **Probar y guardar**.

Alternativa con adb: `adb connect <ip-del-firetv>:5555 && adb install -r dist/app.apk`.

La misma APK vale para **Fire TV, Android TV y móvil**: la interfaz responde al mando (foco
resaltado con el D-pad) y al dedo.

El progreso se guarda en el servidor por perfil, con una copia local para que la pantalla no
dependa de que el servidor conteste al instante.

Siguiente episodio: al abrir un episodio se encola el resto de la serie, así que los botones de
**anterior/siguiente** del reproductor funcionan con el mando, y en los últimos 25 segundos sale el
aviso **Siguiente episodio** con *Ver ahora* y *Cancelar* (cancelar vacía la cola, así que al
terminar ese episodio no salta a ninguno). El episodio que se deja atrás queda marcado como visto.

Recompilar la app: `./build-apk.sh` (deja la APK nueva en `dist/app.apk`, lista para Downloader).

La APK no se sube al repo (pesa 16 MB y cambia en cada compilación). Para compartirla públicamente,
publícala como *Release* en GitHub: en la página del repo, **Releases → Draft a new release**,
arrastra `dist/app.apk` y publica. Desde el Fire TV se instala igual, con Downloader apuntando al
enlace de la release.

## Estructura

```
server/     servidor + panel (server.py, dashboard.html, test_server.py)
app/        app Android (domain / data / presentation + Hilt)
desktop/    servicio systemd, icono del menú e instalador
media/      tus videos
data/       perfiles y progreso (state.json)
dist/       app.apk publicada
```

## Límites conocidos

- **No transcodifica**: el archivo se manda tal cual. Si un video no se oye o no se ve en el Fire
  TV, es que ese codec no lo soporta el aparato (típico con DTS); habría que remuxearlo con ffmpeg.
- Sin metadata de internet (sinopsis, repartos) ni búsqueda.
- Sin contraseñas: cualquiera en tu red puede abrir el panel y usar cualquier perfil.
  Para verlo desde fuera de casa, no abras el puerto en el router: usa Tailscale o WireGuard.
