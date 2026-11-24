$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) {
    $toolsDir = Join-Path (Split-Path -Parent $proj) "android-tools"
    if (Test-Path $toolsDir) {
        if (-not $env:JAVA_HOME) {
            $jdk = (Get-ChildItem (Join-Path $toolsDir "jdk") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
            if ($jdk) {
                $env:JAVA_HOME = $jdk
                $env:PATH = "$jdk\bin;$env:PATH"
            }
        }
        if (-not $env:ANDROID_HOME) {
            $sdk = Join-Path $toolsDir "sdk"
            if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
        }
    }
}

Push-Location $proj
try {
    .\gradlew.bat testDebugUnitTest assembleRelease --no-daemon
    $apk = Join-Path $proj "app\build\outputs\apk\release\app-release.apk"
    $out = Join-Path $proj "WP2-Sideloader.apk"
    Copy-Item $apk $out -Force
    Write-Host "APK ready: $out" -ForegroundColor Green
} finally {
    Pop-Location
}
