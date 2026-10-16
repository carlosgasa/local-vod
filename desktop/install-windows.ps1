# Local VOD en Windows: deja el servidor arrancando con la sesion y pone
# accesos directos al panel. No instala nada mas: el servidor es un .py.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$media = Join-Path $root "media"
$server = Join-Path $root "server\server.py"

Write-Host "Local VOD -> $root" -ForegroundColor Cyan

# --- Python ---
$pythonw = $null
foreach ($candidate in @("pythonw.exe", "python.exe")) {
    $found = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($found) { $pythonw = $found.Source; break }
}
if (-not $pythonw) {
    Write-Host "Falta Python. Se abrira la pagina de descarga." -ForegroundColor Yellow
    Write-Host "Al instalarlo marca 'Add python.exe to PATH' y vuelve a ejecutar este instalador."
    Start-Process "https://www.python.org/downloads/windows/"
    exit 1
}
Write-Host "Python encontrado: $pythonw"

# --- carpeta de videos ---
New-Item -ItemType Directory -Force -Path $media | Out-Null

# --- arrancar con la sesion (pythonw = sin ventana negra) ---
$startup = [Environment]::GetFolderPath("Startup")
$launcher = Join-Path $startup "local-vod.bat"
@"
@echo off
start "" "$pythonw" "$server"
"@ | Set-Content -Encoding ASCII $launcher
Write-Host "Arranque automatico: $launcher"

# --- accesos directos al panel ---
$shortcut = @"
[InternetShortcut]
URL=http://localhost:8000
"@
$desktop = Join-Path ([Environment]::GetFolderPath("Desktop")) "Local VOD.url"
$menu = Join-Path ([Environment]::GetFolderPath("Programs")) "Local VOD.url"
$shortcut | Set-Content -Encoding ASCII $desktop
$shortcut | Set-Content -Encoding ASCII $menu
Write-Host "Acceso directo en el escritorio y en el menu Inicio"

# --- firewall: sin esto la tele no ve el servidor ---
$rule = 'name="Local VOD" dir=in action=allow protocol=TCP localport=8000'
$firewall = Start-Process netsh -ArgumentList "advfirewall firewall add rule $rule" `
    -Verb RunAs -PassThru -Wait -ErrorAction SilentlyContinue
if ($firewall -and $firewall.ExitCode -eq 0) {
    Write-Host "Firewall: puerto 8000 abierto para la red local"
} else {
    Write-Host "No se pudo abrir el puerto 8000 en el firewall (hace falta administrador)." -ForegroundColor Yellow
    Write-Host "Si la tele no ve el servidor, ejecuta este instalador como administrador."
}

# --- arrancar ya ---
Start-Process $pythonw -ArgumentList "`"$server`"" -WindowStyle Hidden
Start-Sleep -Seconds 2
$ip = (Get-NetIPAddress -AddressFamily IPv4 |
       Where-Object { $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "10.*" } |
       Select-Object -First 1).IPAddress
Write-Host ""
Write-Host "Listo. Copia tus carpetas de videos en: $media" -ForegroundColor Green
Write-Host "Panel:        http://localhost:8000"
if ($ip) { Write-Host "En la tele:   http://${ip}:8000" }
Start-Process "http://localhost:8000"
