#!/usr/bin/env bash
# Compila la app y la publica en dist/ para instalarla desde el Fire TV con Downloader.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/app.apk
echo "dist/app.apk listo -> http://$(hostname -I | awk '{print $1}'):8000/app.apk"
