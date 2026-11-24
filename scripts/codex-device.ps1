[CmdletBinding()]
param(
    [ValidateSet('Status', 'Collect', 'Open')][string]$Action = 'Status',
    [string]$Serial,
    [string]$AdbPath,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$packageName = 'cn.classbell.app'
if (-not $AdbPath) {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    if ($env:ANDROID_SDK_ROOT) { $candidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }
    $candidates += 'D:\program\android\sdk\platform-tools\adb.exe'
    $AdbPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $AdbPath) { $AdbPath = (Get-Command adb -ErrorAction SilentlyContinue).Source }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB not found. Install Android SDK Platform Tools or pass -AdbPath.'
}
$deviceArgs = @()
if ($Serial) {
    if ($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid device serial.' }
    $deviceArgs = @('-s', $Serial)
}
function Invoke-Device([string[]]$CommandArgs) {
    $previousEncoding = [Console]::OutputEncoding
    try {
        # ADB emits UTF-8, including Chinese course names in the existing app logs.
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $text = (& $AdbPath @deviceArgs @CommandArgs 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $exitCode = $LASTEXITCODE
    } finally { [Console]::OutputEncoding = $previousEncoding }
    if ($exitCode -ne 0) { throw "ADB failed: $text" }
    return $text
}
$deviceState = Invoke-Device @('get-state')
if ($deviceState.Trim() -ne 'device') {
    throw 'Connect a USB data cable, enable USB debugging, and accept this computer on the phone. Select -Serial when several devices are connected.'
}

if ($Action -eq 'Open') {
    Invoke-Device @('shell', 'am', 'start', '-n', "$packageName/.MainActivity")
    exit 0
}
# run-as fails on a release/non-debuggable installation. This is also the data read boundary.
$null = Invoke-Device @('shell', 'run-as', $packageName, 'id')
$requestId = [Guid]::NewGuid().ToString('N')
$reply = Invoke-Device @('shell', 'am', 'broadcast', '--user', 'current', '-n', "$packageName/.CodexDebugReceiver",
    '-a', "$packageName.debug.COMMAND", '--es', 'operation', 'status', '--es', 'requestId', $requestId)
if ($reply -notmatch [Regex]::Escape("codex_debug_ready:$requestId")) {
    throw "The debug bridge did not acknowledge this request. Install the ClassBell debug APK. Device response: $reply"
}
$statusText = Invoke-Device @('exec-out', 'run-as', $packageName, 'cat', 'files/codex-debug/status.json')
$status = $statusText | ConvertFrom-Json
if ($status.schemaVersion -ne 1 -or $status.requestId -cne $requestId -or $status.app.package -cne $packageName) {
    throw 'The debug snapshot belongs to a different request or schema.'
}
if ($Action -eq 'Status') { Write-Output $statusText; exit 0 }

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path (Get-Location) ('ClassBell-diagnostics-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw 'The output directory already exists. Choose a new directory to keep earlier diagnostics.'
}
$null = New-Item -ItemType Directory -Path $OutputDirectory
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Save-Diagnostic([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $Name), $Text, $utf8)
}
Save-Diagnostic 'status.json' $statusText
$collected = New-Object 'System.Collections.Generic.List[string]'
$collected.Add('status.json')
foreach ($name in @('3.log', '2.log', '1.log', 'current.log')) {
    try {
        $text = Invoke-Device @('exec-out', 'run-as', $packageName, 'cat', "files/diagnostics/$name")
        Save-Diagnostic "app-$name" $text
        $collected.Add("app-$name")
    } catch { Write-Warning "App log $name is unavailable; continuing." }
}
$systemReports = @{
    'memory.txt' = @('shell', 'dumpsys', 'meminfo', $packageName)
    'rendering.txt' = @('shell', 'dumpsys', 'gfxinfo', $packageName)
    'process-exits.txt' = @('shell', 'dumpsys', 'activity', 'exit-info', $packageName)
}
foreach ($name in $systemReports.Keys) {
    try {
        Save-Diagnostic $name (Invoke-Device $systemReports[$name])
        $collected.Add($name)
    } catch { Write-Warning "System report $name is unavailable; continuing." }
}
$manifest = [ordered]@{ schemaVersion = 1; collectedAt = (Get-Date).ToString('o'); appVersion = $status.app.version;
    requestId = $requestId; files = @($collected.ToArray()) }
Save-Diagnostic 'collection.json' ($manifest | ConvertTo-Json -Depth 5)
Write-Output "Collected ClassBell diagnostics: $OutputDirectory"
