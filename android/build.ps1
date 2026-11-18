param(
    [string]$AndroidSdk,
    [string]$JavaHome,
    [string]$Gradle
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
if (-not $Gradle) {
    if ($env:GRADLE_HOME) {
        $Gradle = Join-Path $env:GRADLE_HOME "bin\gradle.bat"
    } elseif (Test-Path (Join-Path $PSScriptRoot "gradlew.bat")) {
        $Gradle = Join-Path $PSScriptRoot "gradlew.bat"
    } else {
        $gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
        if ($gradleCommand) { $Gradle = $gradleCommand.Source }
    }
}

foreach ($required in @($JavaHome, $AndroidSdk, $Gradle)) {
    if (-not $required) {
        throw "AndroidSdk, JavaHome and Gradle are required"
    }
}

$androidJar = Join-Path $AndroidSdk "platforms\android-35\android.jar"
$buildTools = Join-Path $AndroidSdk "build-tools\35.0.0"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$aapt = Join-Path $buildTools "aapt.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$projectRoot = Split-Path -Parent $PSScriptRoot
$distRoot = Join-Path $projectRoot "dist"
$releaseName = "PageVault-1.1.0-android.apk"
$signedApk = Join-Path $distRoot $releaseName

foreach ($requiredFile in @($androidJar, $zipalign, $apksigner, $aapt, $keytool, $Gradle)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "Missing build dependency: $requiredFile"
    }
}

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk

& $Gradle -p $PSScriptRoot assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Gradle release build failed"
}

$unsignedApk = Join-Path $PSScriptRoot "build\outputs\apk\release\PageVault-release-unsigned.apk"
if (-not (Test-Path -LiteralPath $unsignedApk)) {
    throw "Gradle did not produce an unsigned release APK: $unsignedApk"
}
& $zipalign -c 4 $unsignedApk
if ($LASTEXITCODE -ne 0) {
    throw "Gradle APK is not zip-aligned"
}

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
    $unsignedApk
if ($LASTEXITCODE -ne 0) { throw "APK signing failed" }

& $apksigner verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }
& $aapt dump badging $signedApk
Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk |
    Format-List Path, Hash
Write-Host "APK: $signedApk"
