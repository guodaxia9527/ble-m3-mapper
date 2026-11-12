param([string]$Serial = 'fcba9896')
$ErrorActionPreference = 'Stop'
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$jdkRoot = 'C:\Program Files\Android\Android Studio\jbr'
$androidJar = Join-Path $sdkRoot 'platforms\android-35\android.jar'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Force build\media-check-classes | Out-Null
    $sources = @('MediaControl.java', 'ProfileConfig.java', 'TranscriptTimestampParser.java') | ForEach-Object {
        Join-Path 'android-app\app\src\main\java\com\guodaxia\blem3mapper' $_
    }
    & (Join-Path $jdkRoot 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $androidJar -d build\media-check-classes $sources tools\MediaControlCheck.java
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    $classFiles = @(Get-ChildItem build\media-check-classes -Recurse -Filter *.class | ForEach-Object FullName)
    & (Join-Path $jdkRoot 'bin\java.exe') -cp (Join-Path $sdkRoot 'build-tools\35.0.0\lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output build\media-check.jar $classFiles
    if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
    & (Join-Path $sdkRoot 'platform-tools\adb.exe') -s $Serial push build\media-check.jar /data/local/tmp/ble-media-check.jar
    if ($LASTEXITCODE -ne 0) { throw 'Deployment failed' }
} finally { Pop-Location }
