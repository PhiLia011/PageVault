param(
    [string]$Python,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $projectRoot "build\ytdlp-tool"
$venv = Join-Path $buildRoot "venv"

if (-not $Python) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) { $Python = $pythonCommand.Source }
}
if (-not $Python) {
    throw "Python 3.10+ is required to build the embedded yt-dlp executable"
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $projectRoot "desktop\PageVault\third_party\yt-dlp.exe"
}

New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
if (-not (Test-Path (Join-Path $venv "Scripts\python.exe"))) {
    & $Python -m venv $venv
}
$venvPython = Join-Path $venv "Scripts\python.exe"
& $venvPython -m pip install --disable-pip-version-check --upgrade pip
& $venvPython -m pip install --disable-pip-version-check "yt-dlp[default]" pyinstaller

$dist = Join-Path $buildRoot "dist"
$work = Join-Path $buildRoot "work"
& $venvPython -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --name yt-dlp `
    --collect-all yt_dlp `
    --collect-all yt_dlp_ejs `
    --distpath $dist `
    --workpath $work `
    --specpath $buildRoot `
    (Join-Path $projectRoot "tools\yt_dlp_entry.py")

$built = Join-Path $dist "yt-dlp.exe"
if (-not (Test-Path -LiteralPath $built)) {
    throw "yt-dlp build did not produce an executable"
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
Copy-Item -LiteralPath $built -Destination $OutputPath -Force
Write-Host "yt-dlp: $OutputPath"
