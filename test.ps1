$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
$testOut = Join-Path $root "build/test-classes"
New-Item -ItemType Directory -Force $testOut | Out-Null
$tests = Get-ChildItem (Join-Path $root "src/test/java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
$mainSources = Get-ChildItem (Join-Path $root "src/main/java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
& javac --release 21 -encoding UTF-8 -d $testOut $mainSources $tests
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar testes." }
& java -ea -cp $testOut dev.swissknife.AllTests
if ($LASTEXITCODE -ne 0) { throw "Testes falharam." }
