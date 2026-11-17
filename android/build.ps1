param(
    [string]$AndroidSdk,
    [string]$JavaHome
)

$ErrorActionPreference = "Stop"
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
    $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCommand) { $JavaHome = Split-Path -Parent $javacCommand.Source }
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $projectRoot "build\android"
$distRoot = Join-Path $projectRoot "dist"
$androidJar = Join-Path $AndroidSdk "platforms\android-35\android.jar"
$aapt2 = Join-Path $AndroidSdk "build-tools\35.0.0\aapt2.exe"
$d8 = Join-Path $AndroidSdk "build-tools\35.0.0\d8.bat"
$zipalign = Join-Path $AndroidSdk "build-tools\35.0.0\zipalign.exe"
$apksigner = Join-Path $AndroidSdk "build-tools\35.0.0\apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"

foreach ($required in @($androidJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing build dependency: $required"
    }
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
New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

$compiledResources = Join-Path $buildRoot "compiled-res.zip"
$generated = Join-Path $buildRoot "generated"
$classes = Join-Path $buildRoot "classes"
$dex = Join-Path $buildRoot "dex"
$baseApk = Join-Path $buildRoot "base.apk"
$unsignedApk = Join-Path $buildRoot "unsigned.apk"
$alignedApk = Join-Path $buildRoot "aligned.apk"
$signedApk = Join-Path $distRoot "PageVault-1.0.0-android.apk"
$classesJar = Join-Path $buildRoot "classes.jar"

New-Item -ItemType Directory -Force -Path $generated, $classes, $dex | Out-Null

& $aapt2 compile --dir (Join-Path $PSScriptRoot "res") -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& $aapt2 link `
    -o $baseApk `
    -I $androidJar `
    --manifest (Join-Path $PSScriptRoot "AndroidManifest.xml") `
    -R $compiledResources `
    --java $generated `
    --auto-add-overlay `
    --min-sdk-version 26 `
    --target-sdk-version 35
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = @()
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "src") -Filter "*.java" -File -Recurse |
    Select-Object -ExpandProperty FullName
$sources += Get-ChildItem -LiteralPath $generated -Filter "*.java" -File -Recurse |
    Select-Object -ExpandProperty FullName
$sourceList = Join-Path $buildRoot "sources.txt"
Set-Content -LiteralPath $sourceList -Value $sources -Encoding UTF8

& $javac `
    -encoding UTF-8 `
    -source 8 `
    -target 8 `
    -Xlint:deprecation `
    -classpath "$androidJar;$(Join-Path $PSScriptRoot 'libs\jsoup-1.18.3.jar')" `
    -d $classes `
    "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $jar --create --file $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& $d8 --release --min-api 26 --lib $androidJar --output $dex `
    $classesJar (Join-Path $PSScriptRoot "libs\jsoup-1.18.3.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item -LiteralPath $baseApk -Destination $unsignedApk -Force
& $jar --update --file $unsignedApk -C $dex "classes.dex"
if ($LASTEXITCODE -ne 0) { throw "Adding classes.dex failed" }

& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$signingRoot = Join-Path $projectRoot ".signing"
$keystore = Join-Path $signingRoot "pagevault-release.jks"
$credentials = Join-Path $signingRoot "pagevault-release.properties"
if (-not (Test-Path -LiteralPath $keystore)) {
    New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
    $randomBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $password = [Convert]::ToBase64String($randomBytes).Replace("=", "")
    @(
        "storePassword=$password"
        "keyPassword=$password"
        "alias=pagevault"
    ) | Set-Content -LiteralPath $credentials -Encoding ASCII
    & $keytool -genkeypair `
        -keystore $keystore `
        -storepass $password `
        -keypass $password `
        -alias pagevault `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=PageVault, OU=Release, O=PageVault, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "Android key generation failed" }
}

$settings = @{}
Get-Content -LiteralPath $credentials | ForEach-Object {
    if ($_ -match "^([^=]+)=(.*)$") { $settings[$matches[1]] = $matches[2] }
}
if (-not $settings.ContainsKey("storePassword") -or -not $settings.ContainsKey("keyPassword")) {
    throw "Signing credentials are invalid: $credentials"
}

& $apksigner sign `
    --ks $keystore `
    --ks-key-alias $settings["alias"] `
    --ks-pass "pass:$($settings['storePassword'])" `
    --key-pass "pass:$($settings['keyPassword'])" `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $signedApk `
    $alignedApk
if ($LASTEXITCODE -ne 0) { throw "APK signing failed" }

& $apksigner verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }

& (Join-Path $AndroidSdk "build-tools\35.0.0\aapt.exe") dump badging $signedApk
Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk |
    Format-List Path, Hash
Write-Host "APK: $signedApk"
