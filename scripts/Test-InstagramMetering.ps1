param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb',
    [ValidateSet('DirectMessages', 'Feed', 'Reels')][string]$Screen = 'DirectMessages',
    [ValidateRange(5, 60)][int]$Seconds = 10
)

$ErrorActionPreference = 'Stop'

# Leave Instagram on the screen under test. This reads only DoomStop's notification;
# it does not inspect conversations, navigate the phone, or change its allowance.
function Get-MeterState {
    $dump = (& $Adb -s $Serial shell dumpsys notification --noredact) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read device notifications' }
    $record = [regex]::Match($dump, '(?m)^.*NotificationRecord\([^\r\n]*pkg=dev\.personal\.doomstop\s[^\r\n]*[\s\S]*?(?=^.*NotificationRecord\(|\z)')
    if (!$record.Success) { throw 'Cannot find DoomStop notification' }
    $dump = $record.Value
    $title = [regex]::Match($dump, 'android\.title=String \((?<remaining>(?:\d+ min )?\d+ s) left today\)')
    if (!$title.Success) { throw 'DoomStop must have remaining allowance for this check' }
    $counting = $dump.Contains('android.text=String (Counting now)')
    $idle = $dump.Contains('android.text=String (Not counting')
    if (!$counting -and !$idle) { throw 'Cannot find DoomStop metering status' }
    [pscustomobject]@{ Remaining = $title.Groups['remaining'].Value; Counting = $counting }
}

# Let the guard and the adaptive notification poll observe a just-completed transition.
Start-Sleep -Seconds 3
$before = Get-MeterState
Start-Sleep -Seconds $Seconds
$after = Get-MeterState
Write-Output "$Screen ($Seconds seconds): $($before.Remaining) -> $($after.Remaining); counting=$($after.Counting)"
if ($Screen -eq 'DirectMessages') {
    if ($before.Counting -or $after.Counting -or $before.Remaining -ne $after.Remaining) {
        throw 'Direct Messages consumed allowance or were reported as counting'
    }
} elseif (!$before.Counting -or !$after.Counting -or $before.Remaining -eq $after.Remaining) {
    throw "$Screen was not metered"
}
