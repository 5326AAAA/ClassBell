$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'codex-device.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('classbell-adb-test-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$mock = Join-Path $testRoot 'mock-adb.ps1'
$oldMode = $env:CLASSBELL_MOCK_MODE
$oldState = $env:CLASSBELL_MOCK_STATE
$env:CLASSBELL_MOCK_STATE = Join-Path $testRoot 'mock-state.json'
$mockCode = @'
$command = @($args)
if ($command.Count -gt 1 -and $command[0] -eq '-s') { $command = @($command | Select-Object -Skip 2) }
if ($command[0] -eq 'get-state') {
    if ($env:CLASSBELL_MOCK_MODE -eq 'offline') { Write-Output 'offline'; exit 0 }
    Write-Output 'device'; exit 0
}
if ($command -contains 'id') {
    if ($env:CLASSBELL_MOCK_MODE -eq 'release') { Write-Output 'package not debuggable'; exit 1 }
    Write-Output 'uid=12345'; exit 0
}
if ($command -contains 'start') { Write-Output 'Starting ClassBell'; exit 0 }
if ($command -contains 'broadcast') {
    $index = [Array]::IndexOf($command, 'requestId')
    $id = $command[$index + 1]
    @{schemaVersion=1; requestId=$id; app=@{package='cn.classbell.app'; version='test'}} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $env:CLASSBELL_MOCK_STATE -Encoding utf8
    if ($env:CLASSBELL_MOCK_MODE -eq 'noack') { Write-Output 'Broadcast completed: result=0'; exit 0 }
    Write-Output "Broadcast completed: result=200, data=codex_debug_ready:$id"; exit 0
}
if ($command -contains 'files/codex-debug/status.json') {
    $state = Get-Content -LiteralPath $env:CLASSBELL_MOCK_STATE -Raw | ConvertFrom-Json
    if ($env:CLASSBELL_MOCK_MODE -eq 'stale') { $state.requestId = '00000000000000000000000000000000' }
    $state | ConvertTo-Json -Depth 5; exit 0
}
if ($command -contains 'dumpsys') { Write-Output 'synthetic application system report'; exit 0 }
Write-Output 'missing optional diagnostic file'; exit 1
'@
[IO.File]::WriteAllText($mock, $mockCode)
$passed = 0
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Expect-Failure([scriptblock]$Block, [string]$Message) {
    $failed = $false
    try { & $Block | Out-Null } catch { $failed = $true }
    Assert-True $failed $Message
}
try {
    $env:CLASSBELL_MOCK_MODE = 'normal'
    $status = & $helper -AdbPath $mock -Action Status | ConvertFrom-Json
    Assert-True ($status.schemaVersion -eq 1 -and $status.requestId.Length -eq 32) 'Status JSON is invalid.'
    $passed++
    $status = & $helper -AdbPath $mock -Action Status -Serial 'synthetic-serial:123' | ConvertFrom-Json
    Assert-True ($status.app.package -eq 'cn.classbell.app') 'Device selection changed the target package.'
    $passed++
    $collection = Join-Path $testRoot 'collection with spaces'
    $null = & $helper -AdbPath $mock -Action Collect -OutputDirectory $collection -WarningAction SilentlyContinue
    $manifest = Get-Content -LiteralPath (Join-Path $collection 'collection.json') -Raw | ConvertFrom-Json
    Assert-True (($manifest.files -contains 'status.json') -and ($manifest.files -contains 'memory.txt')) 'Collection files are incomplete.'
    $passed++
    $before = Get-Content -LiteralPath (Join-Path $collection 'status.json') -Raw
    Expect-Failure { & $helper -AdbPath $mock -Action Collect -OutputDirectory $collection } 'Existing output was overwritten.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $collection 'status.json') -Raw) -ceq $before) 'Old diagnostics changed.'
    $passed++
    foreach ($mode in @('stale', 'noack', 'release', 'offline')) {
        $env:CLASSBELL_MOCK_MODE = $mode
        Expect-Failure { & $helper -AdbPath $mock -Action Status } "Invalid mock mode $mode was accepted."
        $passed++
    }
    $env:CLASSBELL_MOCK_MODE = 'normal'
    Expect-Failure { & $helper -AdbPath $mock -Action Status -Serial 'bad;serial' } 'Invalid serial was accepted.'
    $passed++
    $opened = & $helper -AdbPath $mock -Action Open
    Assert-True ($opened -match 'Starting ClassBell') 'Open did not launch the app.'
    $passed++
    Write-Output "DESKTOP_HELPER_TESTS passed=$passed failed=0 (mock ADB only)"
} finally {
    $env:CLASSBELL_MOCK_MODE = $oldMode
    $env:CLASSBELL_MOCK_STATE = $oldState
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -like 'classbell-adb-test-*') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } else { throw 'Refusing to remove a test path outside the expected temporary directory.' }
}
