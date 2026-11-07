param([string]$Serial = 'fcba9896', [switch]$ReaddOnly)
$ErrorActionPreference = 'Stop'
$adbTool = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$projectRoot = Split-Path -Parent $PSScriptRoot
$testLog = [System.Collections.Generic.List[string]]::new()
function Adb-Call([string[]]$Arguments) {
    $output = @(& $adbTool -s $Serial @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $output
}
function Ui-Dump {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $result = Adb-Call @('shell','uiautomator','dump','/sdcard/ble-profile-test.xml')
        if (($result -join ' ') -match 'dumped') {
            return [xml]((Adb-Call @('shell','cat','/sdcard/ble-profile-test.xml')) -join "`n")
        }
        Start-Sleep -Milliseconds 750
    }
    throw 'UI dump unavailable after retries'
}
function Tap-Node($node) {
    $values = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb-Call @('shell','input','tap',([int](($values[0]+$values[2])/2)).ToString(),
        ([int](($values[1]+$values[3])/2)).ToString()) | Out-Null
}
function Tap-Text([string]$text) {
    $ui = Ui-Dump
    $nodes = @($ui.SelectNodes('//node') | Where-Object { $_.text -eq $text -and $_.enabled -eq 'true' })
    if ($nodes.Count -ne 1) { throw "Expected one enabled control: $text, found $($nodes.Count)" }
    Tap-Node $nodes[0]
}
function Read-Config {
    [xml]$prefs = (Adb-Call @('exec-out','run-as','com.guodaxia.blem3mapper','cat','shared_prefs/mapper.xml')) -join "`n"
    return (($prefs.map.string | Where-Object { $_.name -eq 'profiles_json' }).InnerText | ConvertFrom-Json)
}
function Open-Mapper {
    Adb-Call @('shell','am','start','-n','com.guodaxia.blem3mapper/.MainActivity','-f','0x14000000') | Out-Null
}
function Check([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
    $testLog.Add("PASS $message")
    Write-Output "PASS $message"
}
function Install-Prefs([string]$localPath) {
    Adb-Call @('shell','am','force-stop','com.guodaxia.blem3mapper') | Out-Null
    Adb-Call @('push',$localPath,'/data/local/tmp/ble-profile-test-prefs.xml') | Out-Null
    Adb-Call @('shell','run-as','com.guodaxia.blem3mapper','cp',
        '/data/local/tmp/ble-profile-test-prefs.xml','shared_prefs/mapper.xml') | Out-Null
}

$backupPath = Join-Path $projectRoot 'logs/013-ui-test-backup.xml'
$fixturePath = Join-Path $projectRoot 'build/profile-test-fixture.xml'
$original = (Adb-Call @('exec-out','run-as','com.guodaxia.blem3mapper','cat','shared_prefs/mapper.xml')) -join "`n"
[System.IO.File]::WriteAllText($backupPath, $original, [System.Text.UTF8Encoding]::new($false))
[xml]$fixture = $original
$configNode = $fixture.map.string | Where-Object { $_.name -eq 'profiles_json' }
$originalConfig = $configNode.InnerText | ConvertFrom-Json
$youtube = ($originalConfig.profiles | Where-Object { $_.package -eq 'com.google.android.youtube' } |
    ConvertTo-Json -Depth 8) | ConvertFrom-Json
if (-not $youtube) { throw 'A YouTube profile is required for this test fixture' }
$youtube.buttons.UP.seconds = -7
$youtube.buttons.PHOTO.action = 'none'
$temporary = ($youtube | ConvertTo-Json -Depth 8) | ConvertFrom-Json
$temporary.name = 'Delete Test'
$temporary.package = 'com.example.blem3test'
$temporary.buttons.UP.seconds = -13
$temporary.buttons.PHOTO.action = 'toggle'
$configNode.InnerText = @{version=1;profiles=@($youtube,$temporary)} | ConvertTo-Json -Depth 8 -Compress
($fixture.map.boolean | Where-Object { $_.name -eq 'like_default_migrated_v1' }).value = 'false'
if ($ReaddOnly) {
    $configNode.InnerText = '{"version":1,"profiles":[]}'
    ($fixture.map.boolean | Where-Object { $_.name -eq 'like_default_migrated_v1' }).value = 'true'
}
[System.IO.File]::WriteAllText($fixturePath, $fixture.OuterXml, [System.Text.UTF8Encoding]::new($false))

try {
    Install-Prefs $fixturePath
    Open-Mapper
    Ui-Dump | Out-Null
    if (-not $ReaddOnly) {
    $actual = Read-Config
    Check ($actual.profiles[0].buttons.PHOTO.action -eq 'like') 'Legacy unassigned sixth key migrates to like'
    Check ($actual.profiles[1].buttons.PHOTO.action -eq 'toggle') 'Existing assigned sixth key is preserved'

    Tap-Text '删除配置'
    Tap-Text '取消'
    Check (@((Read-Config).profiles).Count -eq 2) 'Cancel deletion preserves both profiles'

    $ui = Ui-Dump
    $selector = @($ui.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.Spinner' })[0]
    Tap-Node $selector
    Tap-Text 'Delete Test'
    Tap-Text '删除配置'
    Tap-Text '删除'
    $actual = Read-Config
    Check (@($actual.profiles).Count -eq 1) 'Only selected profile is deleted'
    Check ($actual.profiles[0].package -eq 'com.google.android.youtube' -and
        $actual.profiles[0].buttons.UP.seconds -eq -7) 'Other profile and custom seek remain unchanged'

    Tap-Text '开始使用'
    Start-Sleep -Seconds 2
    Adb-Call @('shell','am','start','-n','com.google.android.youtube/com.google.android.apps.youtube.app.WatchWhileActivity') | Out-Null
    Start-Sleep -Seconds 2
    $processes = (Adb-Call @('shell','ps','-A','-o','NAME')) -join "`n"
    Check ($processes -match '(?m)^com.guodaxia.blem3mapper:mapper$') 'Mapping backend starts before final deletion'
    Open-Mapper
    Tap-Text '删除配置'
    Tap-Text '删除'
    Start-Sleep -Seconds 2
    Check (@((Read-Config).profiles).Count -eq 0) 'Final deletion persists an empty profile list'
    $services = (Adb-Call @('shell','dumpsys','activity','services','com.guodaxia.blem3mapper')) -join "`n"
    Check (-not ($services -match 'ServiceRecord.*MappingForegroundService')) 'Final deletion stops mapping foreground service'

    Adb-Call @('shell','am','force-stop','com.guodaxia.blem3mapper') | Out-Null
    Open-Mapper
    $ui = Ui-Dump
    Check (@((Read-Config).profiles).Count -eq 0) 'Empty list survives reopening without recreating YouTube'
    $start = $ui.SelectNodes('//node') | Where-Object { $_.text -eq '开始使用' }
    Check ($start.enabled -eq 'false') 'Start is disabled with no profiles'
    Check ([bool]($ui.SelectNodes('//node') | Where-Object { $_.text -match '^还没有 App 配置' })) 'Empty-state guidance is visible'
    }

    Tap-Text '添加 App'
    Tap-Text '填写包名'
    $ui = Ui-Dump
    $fields = @($ui.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' })
    Tap-Node $fields[0]
    Adb-Call @('shell','input','text','YouTube') | Out-Null
    # The keyboard may move the dialog after focusing the first field.
    $ui = Ui-Dump
    $fields = @($ui.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' })
    if ($fields.Count -ne 2) { throw 'App profile dialog is no longer visible' }
    Tap-Node $fields[1]
    Adb-Call @('shell','input','text','com.google.android.youtube') | Out-Null
    Tap-Text '添加'
    $save = $null
    for ($scrollAttempt = 0; $scrollAttempt -lt 4; $scrollAttempt++) {
        $ui = Ui-Dump
        $save = $ui.SelectNodes('//node') | Where-Object {
            $_.text -eq '保存配置' -and $_.enabled -eq 'true' -and $_.bounds -ne '[0,0][0,0]'
        }
        if ($save) { break }
        # Scroll the page margin, outside the action spinners and editable fields.
        Adb-Call @('shell','input','swipe','35','1900','35','650','450') | Out-Null
    }
    if (-not $save) { throw 'Save control not visible after scrolling' }
    Tap-Node $save
    $actual = Read-Config
    Check (@($actual.profiles).Count -eq 1 -and $actual.profiles[0].buttons.PHOTO.action -eq 'like') 'Re-adding a profile uses sixth-key like default'

    # Verify the one-time migration does not override a user's later explicit none selection.
    [xml]$after = (Adb-Call @('exec-out','run-as','com.guodaxia.blem3mapper','cat','shared_prefs/mapper.xml')) -join "`n"
    $actual.profiles[0].buttons.PHOTO.action = 'none'
    ($after.map.string | Where-Object { $_.name -eq 'profiles_json' }).InnerText = $actual | ConvertTo-Json -Depth 8 -Compress
    [System.IO.File]::WriteAllText($fixturePath, $after.OuterXml, [System.Text.UTF8Encoding]::new($false))
    Install-Prefs $fixturePath
    Open-Mapper
    Ui-Dump | Out-Null
    Check ((Read-Config).profiles[0].buttons.PHOTO.action -eq 'none') 'Later explicit none survives reopening'
} finally {
    Install-Prefs $backupPath
    Open-Mapper
    $logName = if ($ReaddOnly) { 'logs/013-profile-readd-test.log' } else { 'logs/013-profile-ui-test.log' }
    $testLog | Set-Content -LiteralPath (Join-Path $projectRoot $logName) -Encoding utf8
    Write-Output 'Original user preferences restored; mapping should be started again for the physical test.'
}
