#!/usr/bin/env bash
# Instala Local VOD en esta máquina bajándolo de GitHub.
#
#   curl -fsSL https://raw.githubusercontent.com/carlosgasa/local-vod/main/desktop/get.sh | bash
#
# Con escritorio deja el icono "Local VOD" y arranque con la sesión.
# Sin escritorio (mini PC, thin client, Raspberry Pi), pásale la carpeta de videos:
#   curl -fsSL .../get.sh | bash -s -- /mnt/videos
set -euo pipefail

repo="https://github.com/carlosgasa/local-vod"
dest="${VOD_DIR:-$HOME/local-vod}"
media="${1:-}"

command -v python3 >/dev/null || { echo "Falta python3: instálalo y vuelve a intentar."; exit 1; }

echo "Descargando Local VOD en $dest"
if [ -d "$dest/.git" ]; then
  git -C "$dest" pull --ff-only
elif command -v git >/dev/null; then
  git clone --depth 1 "$repo" "$dest"
else
  tmp="$(mktemp -d)"
  curl -fsSL "$repo/archive/refs/heads/main.tar.gz" -o "$tmp/vod.tar.gz"
  mkdir -p "$dest"
  tar -xzf "$tmp/vod.tar.gz" -C "$dest" --strip-components=1
  rm -rf "$tmp"
fi

if [ -n "$media" ]; then
  echo "Instalando como servicio del sistema (videos en $media)"
  sudo bash "$dest/desktop/install-server.sh" "$media"
else
  echo "Instalando para esta sesión de escritorio"
  bash "$dest/desktop/install.sh"
fi
