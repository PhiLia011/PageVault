param(
    [string]$Dotnet
)

$ErrorActionPreference = "Stop"
if (-not $Dotnet) {
    $dotnetCommand = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($dotnetCommand) { $Dotnet = $dotnetCommand.Source }
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $PSScriptRoot "PageVault\PageVault.csproj"
$ytDlp = Join-Path $PSScriptRoot "PageVault\third_party\yt-dlp.exe"
$buildRoot = Join-Path $projectRoot "build\desktop-publish"
$distRoot = Join-Path $projectRoot "dist"
$packageRoot = Join-Path $projectRoot "build\package-win"
$outputExe = Join-Path $distRoot "PageVault-1.1.0-win-x64.exe"
$zipPath = Join-Path $distRoot "PageVault-1.1.0-win-x64.zip"

if (-not (Test-Path -LiteralPath $Dotnet)) {
    throw "Missing .NET SDK: $Dotnet"
}

function Reset-ProjectDirectory([string]$Path, [string]$ExpectedPrefix) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullPrefix = [System.IO.Path]::GetFullPath($ExpectedPrefix)
    if (-not $fullPath.StartsWith($fullPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean directory outside build root: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $fullPath | Out-Null
}

Reset-ProjectDirectory $buildRoot (Join-Path $projectRoot "build")
Reset-ProjectDirectory $packageRoot (Join-Path $projectRoot "build")
New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

if (-not (Test-Path -LiteralPath $ytDlp)) {
    & (Join-Path $projectRoot "tools\build-ytdlp.ps1") -OutputPath $ytDlp
    if ($LASTEXITCODE -ne 0) {
        throw "Embedded yt-dlp build failed"
    }
}

& $Dotnet publish $project `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:DebugType=None `
    -p:DebugSymbols=false `
    -o $buildRoot
if ($LASTEXITCODE -ne 0) {
    throw "Desktop publish failed"
}

Copy-Item -LiteralPath (Join-Path $buildRoot "PageVault.exe") -Destination $outputExe -Force

$readme = Join-Path $packageRoot "README-使用说明.txt"
$privacy = Join-Path $packageRoot "PRIVACY-隐私说明.txt"
$notices = Join-Path $packageRoot "THIRD-PARTY-NOTICES.txt"
Copy-Item -LiteralPath (Join-Path $projectRoot "docs\WINDOWS-README.txt") -Destination $readme
Copy-Item -LiteralPath (Join-Path $projectRoot "PRIVACY.md") -Destination $privacy
Copy-Item -LiteralPath (Join-Path $projectRoot "THIRD-PARTY-NOTICES.md") -Destination $notices
Copy-Item -LiteralPath $outputExe -Destination (Join-Path $packageRoot "PageVault.exe")

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -LiteralPath @(
    (Join-Path $packageRoot "PageVault.exe"),
    $readme,
    $privacy,
    $notices
) -DestinationPath $zipPath -CompressionLevel Optimal

$hashes = @(
    (Get-FileHash -Algorithm SHA256 -LiteralPath $outputExe),
    (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath)
)
$hashes | ForEach-Object {
    "$($_.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($_.Path))"
} | Set-Content -LiteralPath (Join-Path $distRoot "PageVault-1.1.0-win-x64.sha256") -Encoding ASCII

$hashes | Format-List Path, Hash
Write-Host "EXE: $outputExe"
Write-Host "ZIP: $zipPath"
