[CmdletBinding()]
param([switch]$Rollback)
$ErrorActionPreference = 'Stop'
$resultFile = Join-Path $PSScriptRoot 'repair-result.json'
$backupFile = Join-Path $PSScriptRoot 'registry-before.json'
$adbGuid = '{f72fe0d4-cbcb-407d-8814-9ed673d0dd6b}'
function Write-Result([string]$Status, [string]$Message) {
    @{status=$Status; message=$Message; time=(Get-Date).ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding utf8
}
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Windows administrator confirmation is required.' }
    if ($Rollback) {
        $backup = Get-Content -LiteralPath $backupFile -Raw | ConvertFrom-Json
        $instanceId = [string]$backup.instanceId
        if ($instanceId -notlike 'USB\VID_339B&PID_107D&MI_02\*') { throw 'Backup is not for the selected Honor ADB interface.' }
        $key = 'Registry::HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Enum\' + $instanceId + '\Device Parameters'
        if ($backup.hadMultiple) {
            New-ItemProperty -LiteralPath $key -Name DeviceInterfaceGUIDs -PropertyType MultiString -Value ([string[]]$backup.multiple) -Force | Out-Null
        } else { Remove-ItemProperty -LiteralPath $key -Name DeviceInterfaceGUIDs -ErrorAction SilentlyContinue }
        & pnputil.exe /restart-device $instanceId | Out-File -LiteralPath (Join-Path $PSScriptRoot 'device-restart.txt') -Encoding utf8
        Write-Result 'rolled_back' 'Previous DeviceInterfaceGUIDs restored.'
        exit 0
    }
    $devices = @(Get-PnpDevice -PresentOnly | Where-Object {
        $_.FriendlyName -eq 'ADB Interface' -and $_.InstanceId -like 'USB\VID_339B&PID_107D&MI_02\*'
    })
    if ($devices.Count -ne 1) { throw 'Expected exactly one selected Honor USB ADB interface.' }
    $instanceId = $devices[0].InstanceId
    $base = 'Registry::HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Enum\' + $instanceId
    $meta = Get-ItemProperty -LiteralPath $base
    if ($meta.CompatibleIDs -notcontains 'USB\Class_ff&SubClass_42&Prot_01') { throw 'USB interface is not standard ADB class ff/42/01.' }
    $key = $base + '\Device Parameters'
    $existing = Get-ItemProperty -LiteralPath $key
    if (Test-Path -LiteralPath $backupFile) {
        $saved = Get-Content -LiteralPath $backupFile -Raw | ConvertFrom-Json
        if ($saved.instanceId -cne $instanceId) { throw 'This backup belongs to another USB port; refusing to change an unbacked interface.' }
    }
    if (-not (Test-Path -LiteralPath $backupFile)) {
        @{instanceId=$instanceId; single=$existing.DeviceInterfaceGUID;
          hadMultiple=($existing.PSObject.Properties.Name -contains 'DeviceInterfaceGUIDs');
          multiple=@($existing.DeviceInterfaceGUIDs)} | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath $backupFile -Encoding utf8
        $regPath = 'HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Enum\' + $instanceId + '\Device Parameters'
        & reg.exe export $regPath (Join-Path $PSScriptRoot 'device-parameters-before.reg') /y | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Registry backup failed; no repair was performed.' }
    }
    $values = @(@($existing.DeviceInterfaceGUIDs) + @($existing.DeviceInterfaceGUID) + @($adbGuid) |
        Where-Object { $_ } | Select-Object -Unique)
    New-ItemProperty -LiteralPath $key -Name DeviceInterfaceGUIDs -PropertyType MultiString -Value ([string[]]$values) -Force | Out-Null
    $actual = (Get-ItemProperty -LiteralPath $key).DeviceInterfaceGUIDs
    if ($actual -notcontains $adbGuid) { throw 'ADB interface GUID was not written.' }
    & pnputil.exe /restart-device $instanceId | Out-File -LiteralPath (Join-Path $PSScriptRoot 'device-restart.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        Write-Result 'written_replug_required' 'Compatibility GUID added. Unplug and reconnect the USB cable once.'
    } else { Write-Result 'repaired' 'ADB compatibility GUID appended; existing vendor GUID preserved; ADB interface restarted.' }
} catch {
    Write-Result 'failed' $_.Exception.Message
    exit 1
}
