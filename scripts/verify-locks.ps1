$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path

$lockFiles = @(
    "bounded-origin-api\gradle.lockfile",
    "bounded-origin-core\gradle.lockfile",
    "bounded-origin-store-fs\gradle.lockfile",
    "bounded-origin-proxy\gradle.lockfile",
    "bounded-origin-cli\gradle.lockfile",
    "bounded-origin-benchmarks\gradle.lockfile",
    "test-infra\gradle.lockfile"
)

$tasks = @(
    ":bounded-origin-api:dependencies",
    ":bounded-origin-core:dependencies",
    ":bounded-origin-store-fs:dependencies",
    ":bounded-origin-proxy:dependencies",
    ":bounded-origin-cli:dependencies",
    ":bounded-origin-benchmarks:dependencies",
    ":test-infra:dependencies"
)

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $stream = [System.IO.File]::OpenRead($Path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()

    try {
        return ($sha256.ComputeHash($stream) |
                ForEach-Object { $_.ToString("x2") }) -join ""
    }
    finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

$before = @{}

foreach ($relativePath in $lockFiles) {
    $path = Join-Path $repoRoot $relativePath

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing dependency lock file: $relativePath"
    }

    $before[$relativePath] = Get-Sha256 -Path $path
}

& "$repoRoot\gradlew.bat" @tasks --write-locks

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$changed = @()

foreach ($relativePath in $lockFiles) {
    $path = Join-Path $repoRoot $relativePath
    $after = Get-Sha256 -Path $path

    if ($before[$relativePath] -ne $after) {
        $changed += $relativePath
    }
}

if ($changed.Count -gt 0) {
    Write-Error (
    "Dependency lock state is stale. Regeneration changed: " +
            ($changed -join ", ")
    )
    exit 1
}
