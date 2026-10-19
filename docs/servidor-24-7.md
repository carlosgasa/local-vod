# El servidor de casa, encendido 24/7

Cómo montar la mini PC que lleva Local VOD y, de paso, el bloqueo de anuncios de toda la casa,
el acceso desde fuera, la copia de las fotos del móvil y la vigilancia de que todo siga vivo.

## La máquina

| | |
|---|---|
| CPU | Intel Pentium J3710 (Braswell, 2016), 4 núcleos, 6,5 W |
| RAM | 8 GB DDR3, **una sola ranura, 8 GB es el techo** |
| Sistema | 64 GB eMMC (viene con Windows, se borra) |
| Expansión | una ranura **M.2 SATA** + lector microSD |
| Red | 1 GbE, Wi-Fi, Bluetooth |
| Refrigeración | pasiva, sin ventilador |

En reposo consume unos 7 W: encendida todo el año son unos 15 dólares de luz.

## Qué lleva y qué no

| Servicio | Para qué | Puerto |
|---|---|---|
| Local VOD | videos, música y fotos en la tele | 8000 |
| AdGuard Home | bloquea anuncios en toda la casa | 53 y panel en 3000 |
| Tailscale | entrar desde fuera sin abrir puertos | — |
| Syncthing | las fotos del móvil se copian solas | 8384 |
| Uptime Kuma | avisa si algo se cae | 3001 |
| Cockpit | administrar la máquina desde el navegador | 9090 |
| Dockge | administrar los contenedores con botones | 5001 |

**Immich queda fuera**, y no por falta de ganas: esta máquina tiene 8 GB como máximo absoluto y su
procesador es de 2016, sin las instrucciones AVX que Immich da por sentadas. Lo más probable es que
ni arrancara. Lo que Immich hacía se cubre así:

- **Ver las fotos**: ya lo hace Local VOD. Cada carpeta dentro de `media/` con imágenes aparece
  como un álbum en la app de la tele.
- **Que las fotos del móvil lleguen solas**: Syncthing, que pesa unas 30 veces menos.
- **Buscar por caras o por texto**: eso se pierde. Es el precio de esta máquina.

Si algún día cambias a un mini PC con N100 o mejor y 16 GB, Immich entra encima sin deshacer nada.

## Antes de empezar: comprueba el disco

La ranura de esta caja es **M.2 SATA**. Un SSD **NVMe no funciona ahí** — no es cuestión de
adaptadores, es otro protocolo. Mira la etiqueta de tu disco antes de desmontar nada.

Si resulta que el tuyo es NVMe: o compras un M.2 SATA, o metes los datos en un disco USB 3.0
(funciona, pero para estar encendido todo el día es menos fiable).

## Montaje, paso a paso

### 1. La BIOS, lo primero

Entra en la BIOS (normalmente `Supr` o `F7` al encender) y busca **«Restore on AC Power Loss»** o
«After Power Failure». Ponlo en **Power On**.

Esto no es un detalle. Esta máquina va a ser el DNS de la casa: si hay un apagón y no vuelve sola,
nadie navega hasta que alguien se acuerde de pulsar el botón.

Aprovecha y desactiva **Secure Boot**.

### 2. Debian 13, instalación mínima

Descarga la imagen `amd64` normal de Debian y grábala en un USB.

> Si el USB no arranca: algunas cajas Braswell traen UEFI de 32 bits. La imagen normal de Debian lo
> soporta, pero hay que tener Secure Boot desactivado.

En el instalador, al llegar a «Selección de programas», **desmarca todo menos «Servidor SSH» y
«Utilidades estándar»**. Nada de escritorio: esta máquina no tiene pantalla.

Cuando pida la **contraseña de root, déjala vacía**: así tu usuario queda con `sudo`.

Instala sobre el eMMC (`/dev/mmcblk0`), que es donde venía Windows.

Al terminar, la instalación mínima no trae `curl` ni `git`:

```bash
sudo apt install -y curl git
```

### 3. El disco de datos

El eMMC es lento y se desgasta con la escritura. La idea es que casi no escriba nunca y que todo lo
que escribe de verdad viva en el M.2.

```bash
# comprueba primero que el M.2 es sda: el que tiene el tamaño de tu SSD
lsblk
# formatear y montar el M.2 en /datos
sudo mkfs.ext4 /dev/sda
sudo mkdir /datos
echo "/dev/sda /datos ext4 defaults,noatime 0 2" | sudo tee -a /etc/fstab
sudo mount -a
sudo mkdir -p /datos/media /datos/docker
sudo chown -R $USER /datos
```

Y tres ajustes para que el eMMC dure:

```bash
# Docker es el que más escribe: que lo haga en el M.2
sudo mkdir -p /etc/docker
echo '{"data-root": "/datos/docker"}' | sudo tee /etc/docker/daemon.json

# que los logs no crezcan sin freno
sudo sed -i 's/^#SystemMaxUse=.*/SystemMaxUse=100M/' /etc/systemd/journald.conf

# swap comprimido en RAM en vez de fichero de intercambio en disco
sudo apt install -y zram-tools
```

Añade `noatime` también a la línea del eMMC en `/etc/fstab`.

### 4. IP fija

En el router, reserva una IP fija para la mini PC por su dirección MAC. Es imprescindible: va a ser
el DNS de la casa, y la app de la tele la busca por la red.

### 5. Cockpit: el panel de la máquina

```bash
sudo apt install -y cockpit
```

A partir de aquí casi todo se puede hacer desde **`http://IP:9090`**: ver servicios, logs, discos,
actualizaciones, y hasta una terminal dentro del navegador si hiciera falta.

### 6. Docker y Dockge

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # cierra sesión y vuelve a entrar
mkdir -p /datos/stacks
```

Dockge (`http://IP:5001`) es para manejar los contenedores con botones en vez de comandos:

```bash
mkdir -p /datos/stacks/dockge && cd /datos/stacks/dockge
cat > compose.yaml <<'EOF'
services:
  dockge:
    image: louislam/dockge:1
    restart: unless-stopped
    ports: ["5001:5001"]
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./data:/app/data
      - /datos/stacks:/datos/stacks
    environment:
      - DOCKGE_STACKS_DIR=/datos/stacks
EOF
docker compose up -d
```

### 7. Tailscale

Se instala nativo, no en Docker, para que pueda dar acceso a toda la red de casa:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --advertise-routes=192.168.1.0/24 --accept-dns=false
```

Cambia `192.168.1.0/24` por tu red. Después, en la consola de Tailscale, hay que **aprobar la ruta**
anunciada. Si quieres bloqueo de anuncios también fuera de casa, en la sección DNS pon la IP
tailscale de la mini PC como servidor y activa «Override local DNS».

### 8. Local VOD

```bash
sudo apt install -y ffmpeg i965-va-driver vainfo
curl -fsSL https://raw.githubusercontent.com/carlosgasa/local-vod/main/desktop/get.sh | bash -s -- /datos/media
```

El `i965-va-driver` es el que le toca a este procesador (es de la generación 8; el
`intel-media-va-driver` es para los más nuevos). Comprueba que el codificador por hardware está:

```bash
vainfo | grep H264
```

Tiene que aparecer **`VAEntrypointEncSlice`** junto a H264. Si está, convertir un video usa el chip
en vez de los cuatro núcleos, y una película pasa de tardar cuatro horas a tardar media.

El primer escaneo de la biblioteca tarda un buen rato generando carátulas y adelantos. Es una vez.

### 9. AdGuard Home

Antes de nada, comprueba que el puerto 53 está libre:

```bash
sudo ss -ulpn | grep :53   # no debería devolver nada
```

```bash
mkdir -p /datos/stacks/adguard && cd /datos/stacks/adguard
cat > compose.yaml <<'EOF'
services:
  adguard:
    image: adguard/adguardhome:latest
    restart: unless-stopped
    network_mode: host
    volumes:
      - ./work:/opt/adguardhome/work
      - ./conf:/opt/adguardhome/conf
EOF
docker compose up -d
```

El asistente está en `http://IP:3000`. **Pruébalo primero en un solo dispositivo** (poniendo la IP
de la mini PC como DNS en el móvil) antes de cambiarlo en el router para toda la casa.

### 10. Syncthing: las fotos del móvil

```bash
sudo apt install -y syncthing
sudo systemctl enable --now syncthing@$USER
```

El panel está en `http://IP:8384`. Instala Syncthing en el móvil desde F-Droid o Play Store,
empareja los dos aparatos y comparte la carpeta de la cámara contra
**`/datos/media/Fotos del móvil/`**.

Dos cosas importantes:

- **Deja el versionado desactivado** (es como viene). Si lo activas, Syncthing guarda copias viejas
  en `.stversions` y esas fotos aparecerían duplicadas dentro del álbum en la tele.
- **Si tu móvil guarda en HEIC, las fotos no se verán.** Local VOD entiende jpg, png, webp y gif.
  Pon la cámara en modo «compatible» o JPEG.

### 11. Uptime Kuma

```bash
mkdir -p /datos/stacks/kuma && cd /datos/stacks/kuma
cat > compose.yaml <<'EOF'
services:
  kuma:
    image: louislam/uptime-kuma:1
    restart: unless-stopped
    ports: ["3001:3001"]
    volumes: ["./data:/app/data"]
EOF
docker compose up -d
```

En `http://IP:3001`, estos monitores:

| Monitor | Tipo | Dirección |
|---|---|---|
| Local VOD | HTTP(s) | `http://IP:8000/api/status` |
| AdGuard (panel) | HTTP(s) | `http://IP:3000` |
| **AdGuard (DNS)** | DNS | resolver `google.com` contra `IP`, puerto 53 |
| Syncthing | HTTP(s) | `http://IP:8384` |

El de DNS es el que de verdad importa: el panel puede estar respondiendo y el DNS caído, que es lo
que deja a la casa sin internet.

### 12. El respaldo

Un disco USB conectado a la mini PC, y una tarea semanal:

```bash
sudo crontab -e
# cada domingo a las 4 de la mañana
0 4 * * 0 rsync -a --delete /datos/media/ /mnt/respaldo/media/
```

Las fotos del móvil son lo único irremplazable de toda esta máquina. Todo lo demás se vuelve a
descargar; eso no.

## Lo que se rompe si no lo previenes

**AdGuard es el único DNS de la casa.** Si la mini PC se cae, nadie navega. Poner un DNS público
como secundario en el router *no* lo arregla: los aparatos preguntan a los dos y el bloqueo se
salta de forma aleatoria. Lo que sí sirve: el auto-encendido de la BIOS, `restart: unless-stopped`
en los contenedores, y tener apuntado en algún sitio cómo cambiar el DNS del router a mano.

**Uptime Kuma no te puede avisar de que la mini PC está muerta**, porque vive dentro de ella. Para
eso hace falta un vigilante de fuera: abre una cuenta gratis en healthchecks.io y añade

```bash
*/5 * * * * curl -fsS -m 10 https://hc-ping.com/TU-UUID > /dev/null
```

Si dejan de llegar los avisos, te mandan un correo. Sin esto el monitoreo tiene un agujero justo en
el fallo que más duele.

**La caja no tiene ventilador.** A 7 W da igual, pero no encadenes diez conversiones de video
seguidas. Con el chip de video funcionando (paso 8) deja de ser un problema.

**Las fotos se sirven a tamaño completo.** No hay miniaturas para imágenes como sí las hay para los
videos, así que un álbum con dos mil fotos del móvil va a tardar en pintarse en la tele. Si llega a
molestar, se arregla generando miniaturas igual que ya se hace con los videos.

## Cuando esté montado, comprueba

1. `vainfo | grep H264` lista `VAEntrypointEncSlice`.
2. Convierte un video desde el panel y cronométralo: tiene que ir más rápido que el tiempo real, y
   en Cockpit los cuatro núcleos **no** deben estar al 100 %. Si lo están, el chip no entró y está
   tirando de CPU.
3. `http://IP:8000/api/status` devuelve el espacio libre.
4. La app de la tele encuentra el servidor sola y reproduce.
5. Haces una foto con el móvil y, al rato, aparece en el álbum de la tele.
6. Un aparato con el DNS de la mini PC navega, y AdGuard registra consultas bloqueadas.
7. Desde datos móviles, con Tailscale, se abre `http://IP-local:8000`.
8. **Prueba de apagón**: desenchufa y vuelve a enchufar. Tiene que arrancar sola y levantar todo
   sin que toques nada.
9. **Prueba de respaldo**: restaura una foto del disco USB a otra carpeta. Un respaldo que no has
   probado a restaurar no es un respaldo.
