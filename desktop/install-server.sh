#!/usr/bin/env bash
# Instala Local VOD como servicio del sistema: para una máquina que vive
# encendida y sin escritorio (un mini PC, un thin client, una Raspberry Pi,
# una PC vieja...).   Uso:  sudo bash desktop/install-server.sh /mnt/videos
set -euo pipefail

media="${1:-}"
if [ -z "$media" ]; then
  echo "Uso: sudo bash desktop/install-server.sh /ruta/a/tus/videos"
  exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
  echo "Esto necesita sudo: sudo bash desktop/install-server.sh $media"
  exit 1
fi

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
owner="${SUDO_USER:-$(id -un)}"

mkdir -p "$media"
chown "$owner" "$media" 2>/dev/null || true

sed -e "s|__VOD_DIR__|$root|g" \
    -e "s|__VOD_MEDIA__|$media|g" \
    -e "s|__VOD_USER__|$owner|g" \
    "$here/local-vod-system.service" > /etc/systemd/system/local-vod.service

systemctl daemon-reload
systemctl enable --now local-vod
sleep 1
systemctl --no-pager status local-vod | head -6
echo
echo "Listo. Desde la tele o el móvil:  http://$(hostname -I | awk '{print $1}'):8000"
echo "Carpeta de videos: $media"
