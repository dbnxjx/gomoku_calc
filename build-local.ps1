$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$webDir = Join-Path $root 'web'
$androidDir = Join-Path $root 'android'
$publicDir = Join-Path $androidDir 'app\src\main\assets\public'

Push-Location $webDir
try {
    npm ci
    $env:NODE_OPTIONS = '--openssl-legacy-provider'
    npm run build
}
finally {
    Pop-Location
}

# The Capacitor plugin files live beside the Web UI and must survive a Web build.
$staleJs = @(
    (Join-Path $publicDir 'js\*.js'),
    (Join-Path $publicDir 'js\*.map'),
    (Join-Path $publicDir 'precache-manifest.*.js')
)
foreach ($pattern in $staleJs) {
    Remove-Item -Path $pattern -Force -ErrorAction SilentlyContinue
}

Copy-Item -Path (Join-Path $webDir 'dist\*') -Destination $publicDir -Recurse -Force

Push-Location $androidDir
try {
    .\gradlew.bat :app:assembleDebug --no-daemon
}
finally {
    Pop-Location
}

Write-Host "APK: $androidDir\app\build\outputs\apk\debug\app-debug.apk"
