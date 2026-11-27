$ErrorActionPreference = 'Stop'
$analyzer = Join-Path $PSScriptRoot 'Analyze-DoomStopDiagnostics.ps1'
$fixtures = Join-Path $PSScriptRoot 'fixtures/diagnostics'

function Assert-Output($Output, [string]$Pattern) {
    if (($Output -join "`n") -notmatch $Pattern) {
        throw "Expected output matching $Pattern. Got:`n$($Output -join "`n")"
    }
}

$normalPath = Join-Path $fixtures 'normal.jsonl'
$before = (Get-FileHash -LiteralPath $normalPath).Hash
$normal = & $analyzer -BundlePath $normalPath
Assert-Output $normal '21:00:00\.000Z .*21:00:02\.000Z.*2000 ms'
Assert-Output $normal '09:00:02\.000Z .*09:00:09\.000Z.*7000 ms'
Assert-Output $normal 'Start.*ACTIVITY_RESUMED.*Reddit'
Assert-Output $normal 'End.*ACTIVITY_STOPPED.*Reddit'
Assert-Output $normal 'ACTIVITY_PAUSED'
Assert-Output $normal 'Difference.*0 ms'
if ($normal -match 'Possible stale foreground') { throw 'A stopped normal session was flagged as stale' }
if ($normal -match 'Inferred app or screen.*YouTube') { throw 'A YouTube mask event was mistaken for a charge' }
if ((Get-FileHash -LiteralPath $normalPath).Hash -ne $before) { throw 'The analyzer modified its input' }

$previous = & $analyzer -BundlePath $normalPath -Day '2026-10-18'
Assert-Output $previous '20:59:58\.000Z .*21:00:00\.000Z.*2000 ms'
Assert-Output $previous 'Daily charge unavailable'

$phantom = & $analyzer -BundlePath (Join-Path $fixtures 'instagram-modal-stopped-without-pause.jsonl') -Day '2026-10-19'
Assert-Output $phantom '10:02:18\.810Z .*10:02:29\.000Z.*10190 ms'
Assert-Output $phantom 'End.*SCREEN_NON_INTERACTIVE'
Assert-Output $phantom 'Possible stale foreground.*8814 ms'
Assert-Output $phantom 'ACTIVITY_STOPPED.*without a recorded ACTIVITY_PAUSED'
Assert-Output $phantom 'same activity reference.*resumed'
Assert-Output $phantom 'Difference.*0 ms'

$incomplete = & $analyzer -BundlePath (Join-Path $fixtures 'incomplete.jsonl')
Assert-Output $incomplete '11:00:01\.000Z .*11:00:04\.000Z.*3000 ms'
Assert-Output $incomplete '4000 ms recorded.*exact timing unavailable'
Assert-Output $incomplete 'events list truncated'
Assert-Output $incomplete 'expected 5 records, read 3'
Assert-Output $incomplete 'passes with unavailable usage events = 1'
Assert-Output $incomplete 'Recovery was latched'
Assert-Output $incomplete 'Difference.*\+5000 ms'
Assert-Output $incomplete '4000 ms.*unplaced'
Write-Output 'Passed diagnostics regressions for normal, phantom, and incomplete exports.'
