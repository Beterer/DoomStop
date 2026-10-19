param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$OutputPath = (Join-Path $env:LOCALAPPDATA "DoomStop\diagnostics\doomstop-$(Get-Date -Format 'yyyyMMdd-HHmmss').jsonl")
)

$ErrorActionPreference = 'Stop'
# Read-only: no PIN, window inspection, private database access or debug build required.
$lines = [System.Collections.Generic.List[string]]::new()
[long]$afterId = 0
$throughId = $null
do {
    $dumpArgs = @('-s', $Serial, 'shell', 'dumpsys', 'activity', 'service', 'dev.personal.doomstop/.monitor.UsageMonitorService', 'doomstop-diagnostics', "after=$afterId")
    if ($null -ne $throughId) { $dumpArgs += "through=$throughId" }
    $dump = @(& $Adb @dumpArgs)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read DoomStop diagnostics from the selected device' }
    $start = [Array]::FindIndex($dump, [Predicate[string]]{ param($line) $line.Trim() -eq 'DOOMSTOP_DIAGNOSTICS_BEGIN' })
    $end = [Array]::FindIndex($dump, [Predicate[string]]{ param($line) $line.Trim() -eq 'DOOMSTOP_DIAGNOSTICS_END' })
    if ($start -lt 0 -or $end -le $start + 1) { throw 'No complete diagnostic page found. DoomStop 0.4.3+ must be installed and its monitor running.' }
    $page = @($dump[($start + 1)..($end - 1)] | ForEach-Object { $_.Trim() })
    $header = $page[0] | ConvertFrom-Json
    if ($null -eq $throughId) {
        $throughId = [long]$header.throughId
        $lines.Add($page[0])
    } elseif ([long]$header.throughId -ne $throughId) { throw 'Diagnostic export cutoff changed between pages' }
    foreach ($line in $page | Select-Object -Skip 1) {
        $record = $line | ConvertFrom-Json
        if ([long]$record.id -le $afterId -or [long]$record.id -gt $throughId) { throw 'Diagnostic page IDs are inconsistent' }
        $lines.Add($line)
        $afterId = [long]$record.id
    }
    if ([long]$header.nextId -ne $afterId) { throw 'Diagnostic page was incomplete' }
    if (!$header.complete -and $page.Count -le 1) { throw 'Diagnostic export did not advance' }
} while (!$header.complete)
$snapshot = $lines[0] | ConvertFrom-Json
if ([long]$snapshot.expectedRecordCount -ne ($lines.Count - 1)) {
    throw 'Journal retention changed during export; no file was saved. Run the export again.'
}
$snapshot.complete = $true
$snapshot.nextId = $afterId
$snapshot | Add-Member -NotePropertyName exportedRecordCount -NotePropertyValue ($lines.Count - 1)
$lines[0] = $snapshot | ConvertTo-Json -Compress -Depth 10
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force ([System.IO.Path]::GetDirectoryName($resolvedOutput)) | Out-Null
[System.IO.File]::WriteAllLines($resolvedOutput, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Saved $($lines.Count - 1) diagnostic records to $resolvedOutput"
Write-Output "Day $($snapshot.day): charged=$($snapshot.chargedMs) ms; base=$($snapshot.dailyAllowanceMs) ms; carryover=$($snapshot.carriedInMs) ms"
