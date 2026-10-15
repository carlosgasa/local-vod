#!/usr/bin/env bash
# Deja el servidor arrancando con la sesión y pone el icono "Local VOD" en el menú.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
mkdir -p ~/.config/systemd/user ~/.local/share/applications
sed "s|__VOD_DIR__|$root|g" "$here/local-vod.service" > ~/.config/systemd/user/local-vod.service
sed "s|__VOD_DIR__|$root|g" "$here/local-vod.desktop" > ~/.local/share/applications/local-vod.desktop
systemctl --user daemon-reload
systemctl --user enable --now local-vod
update-desktop-database ~/.local/share/applications 2>/dev/null || true
systemctl --user --no-pager status local-vod | head -5
echo "Listo: http://$(hostname -I | awk '{print $1}'):8000"
