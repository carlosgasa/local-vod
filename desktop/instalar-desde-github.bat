@echo off
REM Descarga Local VOD de GitHub e instala el servidor en este Windows.
REM Doble clic aqui. Necesita Python 3 instalado (con "Add python.exe to PATH").
setlocal
set "REPO=https://github.com/carlosgasa/local-vod/archive/refs/heads/main.zip"
set "DEST=%USERPROFILE%\local-vod"

echo Descargando Local VOD...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$zip = Join-Path $env:TEMP 'local-vod.zip';" ^
  "Invoke-WebRequest -Uri '%REPO%' -OutFile $zip;" ^
  "if (Test-Path '%DEST%') { Remove-Item '%DEST%' -Recurse -Force };" ^
  "Expand-Archive $zip -DestinationPath $env:TEMP -Force;" ^
  "Move-Item (Join-Path $env:TEMP 'local-vod-main') '%DEST%';" ^
  "Remove-Item $zip"

if errorlevel 1 (
  echo No se pudo descargar. Revisa tu conexion.
  pause
  exit /b 1
)

echo Instalando...
powershell -NoProfile -ExecutionPolicy Bypass -File "%DEST%\desktop\install-windows.ps1"
pause
