$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root "build/swissknife.jar"
if (-not (Test-Path $jar)) { & (Join-Path $root "build.ps1") }
& java -jar $jar @args
exit $LASTEXITCODE
