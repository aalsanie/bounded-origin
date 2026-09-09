$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
& .\gradlew.bat dependencies --write-locks | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& git diff --exit-code -- '*/gradle.lockfile'
if ($LASTEXITCODE -ne 0) {
    throw 'Dependency lock drift detected.'
}
