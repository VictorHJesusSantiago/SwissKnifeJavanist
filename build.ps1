$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "build/classes"
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem (Join-Path $root "src/main/java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
if (-not $sources) { throw "Nenhum fonte Java encontrado." }
& javac --release 21 -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Falha de compilação." }
$manifest = Join-Path $root "build/MANIFEST.MF"
Set-Content -Encoding ASCII $manifest "Main-Class: dev.swissknife.Main`n"
& java -m jdk.jartool/sun.tools.jar.Main --create --file (Join-Path $root "build/swissknife.jar") --manifest $manifest -C $out .
if ($LASTEXITCODE -ne 0) { throw "Falha ao criar JAR." }
Write-Host "Criado build/swissknife.jar"
