param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JdkRoot = 'C:\Program Files\Android\Android Studio\jbr'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Force -Path 'build\java' | Out-Null
    $clang = Join-Path $SdkRoot 'ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
    $androidJar = Join-Path $SdkRoot 'platforms\android-33\android.jar'
    & $clang --target=aarch64-linux-android23 -std=c11 -O2 -Wall -Wextra -Werror 'native\ble_m3_capture.c' -o 'build\ble_m3_capture'
    if($LASTEXITCODE -ne 0){throw 'Native compilation failed'}
    & (Join-Path $JdkRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d 'build\java' 'java\YouTubeControl.java'
    if($LASTEXITCODE -ne 0){throw 'Java compilation failed'}
    & (Join-Path $JdkRoot 'bin\java.exe') -cp (Join-Path $SdkRoot 'build-tools\35.0.0\lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output 'build\youtube-control.jar' 'build\java\YouTubeControl.class'
    if($LASTEXITCODE -ne 0){throw 'DEX compilation failed'}
    $adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
    & $adb -s $Serial push 'build\ble_m3_capture' /data/local/tmp/ble_m3_capture
    if($LASTEXITCODE -ne 0){throw 'Native deployment failed'}
    & $adb -s $Serial shell chmod 700 /data/local/tmp/ble_m3_capture
    if($LASTEXITCODE -ne 0){throw 'Executable permission failed'}
    & $adb -s $Serial push 'build\youtube-control.jar' /data/local/tmp/ble-youtube-control.jar
    if($LASTEXITCODE -ne 0){throw 'Helper deployment failed'}
} finally { Pop-Location }
