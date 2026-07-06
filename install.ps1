param(
    [string]$InstallDirectory = (Join-Path $HOME ".swissknife"),
    [switch]$Force
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $root "build/swissknife.jar"))) {
    & (Join-Path $root "build.ps1")
}
if ((Test-Path $InstallDirectory) -and -not $Force) {
    throw "$InstallDirectory já existe. Use -Force para atualizar."
}
New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
Copy-Item (Join-Path $root "build/swissknife.jar") $InstallDirectory -Force
$launcher = "@echo off`r`njava -jar `"%~dp0swissknife.jar`" %*`r`n"
Set-Content -LiteralPath (Join-Path $InstallDirectory "swissknife.cmd") -Value $launcher -Encoding ASCII
$hash = (Get-FileHash (Join-Path $InstallDirectory "swissknife.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $InstallDirectory "swissknife.jar.sha256") -Value "$hash  swissknife.jar" -Encoding ASCII
Write-Host "Instalado em $InstallDirectory. Adicione o diretório ao PATH."
