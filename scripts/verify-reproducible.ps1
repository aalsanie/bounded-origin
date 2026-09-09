$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("bounded-origin-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $Temp | Out-Null
try {
    & .\gradlew.bat clean assemble
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Get-ChildItem -Recurse -Filter *.jar | Where-Object { $_.FullName -match '[\\/]build[\\/]libs[\\/]' } |
        Sort-Object FullName |
        ForEach-Object { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant(), $_.FullName.Substring($Root.Length + 1) } |
        Set-Content -Encoding UTF8 (Join-Path $Temp 'first.sha256')

    & .\gradlew.bat clean assemble
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Get-ChildItem -Recurse -Filter *.jar | Where-Object { $_.FullName -match '[\\/]build[\\/]libs[\\/]' } |
        Sort-Object FullName |
        ForEach-Object { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant(), $_.FullName.Substring($Root.Length + 1) } |
        Set-Content -Encoding UTF8 (Join-Path $Temp 'second.sha256')

    $First = Get-Content (Join-Path $Temp 'first.sha256')
    $Second = Get-Content (Join-Path $Temp 'second.sha256')
    if (Compare-Object $First $Second) {
        throw 'Archive reproducibility verification failed.'
    }
}
finally {
    Remove-Item -Recurse -Force $Temp -ErrorAction SilentlyContinue
}
