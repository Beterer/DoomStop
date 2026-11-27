<#
.SYNOPSIS
Print committed allowance charges from a DoomStop diagnostics JSON Lines export.
.PARAMETER BundlePath
Path to the exported JSONL file.
.PARAMETER Day
DoomStop accounting day (yyyy-MM-dd). Defaults to the snapshot day.
#>
param(
    [Parameter(Mandatory = $true)][string]$BundlePath,
    [string]$Day
)

$ErrorActionPreference = 'Stop'
$notes = [System.Collections.Generic.List[string]]::new()
$timeline = [System.Collections.Generic.List[object]]::new()
$eventsByBoot = @{}
$observations = @{}
$edges = @{}
$appNames = @{
    'com.instagram.android' = 'Instagram'
    'com.reddit.frontpage' = 'Reddit'
    'com.zhiliaoapp.musically' = 'TikTok'
    'com.google.android.youtube' = 'YouTube'
}
$lifecycleTypes = @('ACTIVITY_RESUMED', 'ACTIVITY_PAUSED', 'ACTIVITY_STOPPED')
$openingTypes = @('ACTIVITY_RESUMED', 'SCREEN_INTERACTIVE', 'KEYGUARD_HIDDEN', 'PACKAGE_UNMASKED')
$closingTypes = @('ACTIVITY_STOPPED', 'SCREEN_NON_INTERACTIVE', 'KEYGUARD_SHOWN',
    'PACKAGE_MASKED', 'PACKAGE_SUSPENDED', 'DEVICE_SHUTDOWN', 'DEVICE_STARTUP')

function Test-Milliseconds($Value) {
    ($Value -is [int] -or $Value -is [long])
}

function Test-WallTime($Value) {
    (Test-Milliseconds $Value) -and $Value -ge -62135596800000 -and $Value -le 253402300799999
}

function Format-Time([long]$At) {
    [DateTimeOffset]::FromUnixTimeMilliseconds($At).ToString(
        "yyyy-MM-dd HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
}

function Get-AppName([string]$Target) {
    if ($appNames.ContainsKey($Target)) { return $appNames[$Target] }
    $Target
}

function Format-Event($Event) {
    $target = if ($Event.target -and $Event.target -ne 'system') { " $(Get-AppName $Event.target)" }
    $screen = switch ($Event.source) {
        'dm_guard' { ' (Instagram DM exemption)' }
        'shorts_guard' { ' (YouTube Shorts guard)' }
        'shorts_guard_gap' { ' (unobserved Shorts state during monitor gap)' }
    }
    $late = if ($Event.dropped) { ', dropped after settlement' }
    "$($Event.type)$target$screen [$($Event.source), record #$($Event.id)$late]"
}

function Get-VisibleRanges($Trace) {
    # BudgetEngine.account starts a new boot invisible. FLUSH_OPEN_TAIL uses
    # the previous boot's observer. Both integrate ordered, in-window transitions.
    $cursor = $Trace.settledFrom
    $visible = $Trace.visibleFrom -and ($Trace.boot -eq $Trace.previousBoot -or $Trace.trigger -eq 'FLUSH_OPEN_TAIL')
    $ordered = $Trace.transitions
    if ($ordered.Count -gt 1) {
        $ordered = @($ordered | Sort-Object at, @{ Expression = { [array]::IndexOf($Trace.transitions, $_) } })
    }
    foreach ($edge in $ordered) {
        if ($edge.at -lt $Trace.settledFrom -or $edge.at -gt $Trace.settledTo) { continue }
        if ($edge.visible -eq $visible) { continue }
        if ($visible -and $edge.at -gt $cursor) { @{ start = $cursor; end = $edge.at } }
        $cursor = $edge.at
        $visible = $edge.visible
    }
    if ($visible -and $Trace.settledTo -gt $cursor) { @{ start = $cursor; end = $Trace.settledTo } }
}

function Get-FirstEventIndex($Events, [long]$At) {
    $low = 0
    $high = $Events.Count
    while ($low -lt $high) {
        $mid = $low + (($high - $low) -shr 1)
        if ($Events[$mid].at -lt $At) { $low = $mid + 1 } else { $high = $mid }
    }
    $low
}

function Get-BoundaryExplanation($Entry, [bool]$Starting, $Candidates) {
    $at = if ($Starting) { $Entry.start } else { $Entry.end }
    $types = if ($Starting) { $openingTypes } else { $closingTypes }
    $matching = @($Candidates | Where-Object { !$_.dropped -and $_.type -in $types })
    $edgeId = $edges["$($Entry.boot)|$at|$Starting"]
    if ($edgeId) {
        $change = if ($Starting) { 'visible' } else { 'not visible' }
        $explanation = "Recorded visibility became $change (record #$edgeId)"
        if ($matching.Count) { return "$explanation. Likely event(s) $(@($matching | ForEach-Object { Format-Event $_ }) -join ', ')." }
        return "$explanation. Matching lifecycle, screen, or guard evidence unavailable."
    }
    if ($Starting) {
        if ($Entry.dayStart) { return 'Charging was already underway at the accounting-day boundary.' }
        return 'Coverage starts here. The preceding visibility change is unavailable.'
    }
    if ($Entry.dayEnd) { return 'The trace charges the continuation to the next accounting day.' }
    if ($at -eq $snapshot.settledThrough) { return 'The snapshot settles charges through this point. Any later countdown is provisional or outside the export.' }
    'Coverage ends here. The event that ended charging is unavailable.'
}

$snapshot = $null
$records = [System.Collections.Generic.List[object]]::new()
$ids = [System.Collections.Generic.HashSet[long]]::new()
$lastId = 0L
$readCount = 0
$lineNumber = 0
$resolvedPath = (Get-Item -LiteralPath $BundlePath).FullName
foreach ($line in [IO.File]::ReadLines($resolvedPath)) {
    $lineNumber++
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    try {
        $row = ConvertFrom-Json -InputObject $line
    } catch {
        $notes.Add("Line $lineNumber is unreadable JSON. Its evidence is missing.")
        continue
    }
    if ($row.type -eq 'snapshot' -and !$snapshot -and !$readCount) {
        $snapshot = $row
        continue
    }
    $readCount++
    if (!(Test-Milliseconds $row.id) -or $row.id -le 0 -or !$ids.Add($row.id)) {
        $notes.Add("Skipped line $lineNumber because its record ID is missing, invalid, or duplicated.")
        continue
    }
    if ($row.id -lt $lastId) { $notes.Add("Record #$($row.id) is out of export order.") }
    $lastId = $row.id
    if ($null -ne $snapshot.throughId -and $row.id -gt $snapshot.throughId) {
        $notes.Add("Skipped record #$lastId because it is after the snapshot cutoff.")
        continue
    }
    $records.Add($row)
}

if (!$Day) { $Day = $snapshot.day }
$parsedDay = [DateTime]::MinValue
if (!$Day -or ![DateTime]::TryParseExact($Day, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None, [ref]$parsedDay)) {
    throw 'Specify an accounting day with -Day yyyy-MM-dd when the export has no valid snapshot day.'
}
# Without a Windows mapping for every IANA zone, use only a conservative search
# window for gap notices. Recorded slices, not this window, determine the charged day.
$utcMidnight = [DateTimeOffset]::new($parsedDay, [TimeSpan]::Zero).ToUnixTimeMilliseconds()
$earliestDayMs = $utcMidnight - 64800000L
$latestDayMs = $utcMidnight + 151200000L
$usageGapCount = 0
$clockGapCount = 0
if (!$snapshot) { $notes.Add('Snapshot header missing.') }
elseif ($snapshot.schema -ne 1) { throw "Unsupported snapshot schema $($snapshot.schema)." }
else {
    if ($snapshot.complete -ne $true) { $notes.Add('Export is marked incomplete.') }
    if ($null -ne $snapshot.expectedRecordCount -and $snapshot.expectedRecordCount -ne $readCount) {
        $notes.Add("Export expected $($snapshot.expectedRecordCount) records, read $readCount.")
    }
    if ($null -ne $snapshot.exportedRecordCount -and $snapshot.exportedRecordCount -ne $readCount) {
        $notes.Add("Export header reports $($snapshot.exportedRecordCount) saved records, read $readCount.")
    }
    if ($null -ne $snapshot.throughId -and $snapshot.throughId -ne $lastId) {
        $notes.Add("Last record #$lastId differs from snapshot cutoff #$($snapshot.throughId).")
    }
}

$journalMs = 0L
$placedMs = 0L
$clockEpoch = 0
$seenEvents = [System.Collections.Generic.HashSet[string]]::new()
$legacyLifecycle = $false
foreach ($row in $records) {
    if ($row.type -ne 'accounting_trace') {
        if ($row.type -eq 'recovery_acknowledged') { $clockEpoch++ }
        if ($row.type -in @('unreconciled_gap', 'clock_jump', 'monotonic_regression', 'recovery_acknowledged') -and
            (Test-WallTime $row.at) -and $row.at -ge $earliestDayMs -and $row.at -lt $latestDayMs) {
            $clockGapCount++
        }
        continue
    }
    $trace = $row.detail
    $id = $row.id
    if ($trace.schema -ne 1) {
        $notes.Add("Skipped record #$id because its accounting trace schema is unsupported or missing.")
        continue
    }
    # Recovery can re-anchor wall time within the same boot and clears pending events.
    $boot = if ($trace.boot) { "$($trace.boot)|$clockEpoch" } else { "unknown-$id" }
    if (!$eventsByBoot.ContainsKey($boot)) { $eventsByBoot[$boot] = [System.Collections.Generic.List[object]]::new() }
    foreach ($field in @('events', 'dropped')) {
        $countField = if ($field -eq 'events') { 'eventCount' } else { 'droppedCount' }
        $items = $trace.$field
        if ($items -isnot [array]) {
            $notes.Add("Record #$id is missing its $field list.")
            continue
        }
        if ($trace.$countField -gt $items.Count) {
            $notes.Add("Record #$id has its $field list truncated to $($items.Count) of $($trace.$countField) entries.")
        }
        foreach ($event in $items) {
            if (!(Test-WallTime $event.at) -or !$event.type -or !$event.target) {
                $notes.Add("Skipped an invalid entry in the $field list of record #$id.")
                continue
            }
            $key = "$boot|$field|$($event.at)|$($event.target)|$($event.type)|$($event.source)|$($event.activityRef)"
            if (!$seenEvents.Add($key)) { continue }
            if ($event.type -in $lifecycleTypes -and !$event.activityRef) { $legacyLifecycle = $true }
            $eventsByBoot[$boot].Add(@{
                at = $event.at; type = $event.type; target = $event.target; source = $event.source
                ref = $event.activityRef; id = $id; dropped = $field -eq 'dropped'
                order = $eventsByBoot[$boot].Count
            })
        }
    }
    if (Test-WallTime $trace.acceptedNow) {
        foreach ($activity in $trace.activities) {
            if ($activity.state -ne 'resumed' -or !$activity.target) { continue }
            $since = $activity.since
            if ($null -eq $since -and (Test-Milliseconds $activity.ageMs)) {
                $since = $trace.acceptedNow - $activity.ageMs
            }
            if (!(Test-WallTime $since)) { continue }
            $key = "$boot|$($activity.target)|$($activity.activityRef)"
            if (!$observations.ContainsKey($key)) { $observations[$key] = @{} }
            $byResume = $observations[$key]
            $since = [long]$since
            if (!$byResume.ContainsKey($since) -or $trace.acceptedNow -gt $byResume[$since].at) {
                $byResume[$since] = @{ at = $trace.acceptedNow; id = $id; since = $since }
            }
        }
    }

    $validWindow = (Test-WallTime $trace.settledFrom) -and (Test-WallTime $trace.settledTo) -and
        $trace.settledFrom -le $trace.settledTo
    if ($validWindow -and $trace.settledFrom -lt $latestDayMs -and $trace.settledTo -ge $earliestDayMs -and
        $trace.usageAvailable -is [bool] -and !$trace.usageAvailable -and $trace.trigger -ne 'FLUSH_OPEN_TAIL') {
        $usageGapCount++
    }
    $validTransitions = $trace.transitions -is [array]
    if ($validTransitions) {
        foreach ($edge in $trace.transitions) {
            if (!(Test-WallTime $edge.at) -or $edge.visible -isnot [bool]) {
                $validTransitions = $false
                continue
            }
            if ($validWindow -and $edge.at -ge $trace.settledFrom -and $edge.at -le $trace.settledTo) {
                $edges["$boot|$($edge.at)|$($edge.visible)"] = $id
            }
        }
    }
    if ($trace.slices -isnot [array]) {
        $notes.Add("Record #$id is missing its recorded charge slices.")
        continue
    }
    if (!$trace.slices.Count) { continue }
    $slices = [System.Collections.Generic.List[object]]::new()
    $selectedMs = 0L
    $allMs = 0L
    foreach ($slice in $trace.slices) {
        if ($slice.day -notmatch '^\d{4}-\d{2}-\d{2}$' -or !(Test-Milliseconds $slice.ms) -or $slice.ms -le 0) { continue }
        $slices.Add($slice)
        $allMs += $slice.ms
        if ($slice.day -eq $Day) { $selectedMs += $slice.ms }
    }
    $validSlices = $slices.Count -eq $trace.slices.Count
    if (!$validSlices) { $notes.Add("Counted only the readable charge slices in record #$id.") }
    if (!$selectedMs) { continue }
    $journalMs += $selectedMs
    $ranges = @()
    $visibleMs = 0L
    if ($validSlices -and $validWindow -and $validTransitions -and $trace.visibleFrom -is [bool] -and
        (!$trace.visibleFrom -or ($trace.boot -and $trace.previousBoot))) {
        $ranges = @(Get-VisibleRanges $trace)
        foreach ($range in $ranges) { $visibleMs += $range.end - $range.start }
    }
    if ($visibleMs -ne $allMs) {
        $timeline.Add(@{
            start = if ($validWindow) { $trace.settledFrom } else { [long]::MaxValue }
            end = $trace.settledTo; id = $id; boot = $boot; ms = $selectedMs; unplaced = $true
            reason = if (!$ranges.Count) { 'settled visibility is missing or insufficient' }
                else { "recorded visibility accounts for $visibleMs of $allMs ms across this trace's day slices" }
        })
        continue
    }

    # Day slices are recorded in chronological order. Consume their durations from the
    # verified visible ranges so the recorded accounting day survives DST and timezone changes.
    $sliceIndex = 0
    $remaining = $slices[0].ms
    foreach ($range in $ranges) {
        $cursor = $range.start
        while ($cursor -lt $range.end) {
            $length = [Math]::Min([long]$remaining, [long]($range.end - $cursor))
            if ($slices[$sliceIndex].day -eq $Day) {
                $timeline.Add(@{
                    start = $cursor; end = $cursor + $length; ms = $length; id = $id; boot = $boot
                    dayStart = $cursor -gt $range.start; dayEnd = $cursor + $length -lt $range.end
                    originStart = $range.start; trace = $trace
                })
                $placedMs += $length
            }
            $cursor += $length
            $remaining -= $length
            if (!$remaining) {
                $sliceIndex++
                if ($sliceIndex -lt $slices.Count) { $remaining = $slices[$sliceIndex].ms }
            }
        }
    }
}

# Index each stop's preceding lifecycle evidence once. Older exports can only match
# by package; opaque references deliberately cannot match across coordinator processes.
foreach ($boot in @($eventsByBoot.Keys)) {
    $ordered = @($eventsByBoot[$boot] | Sort-Object { $_.at }, { $_.order })
    $latest = @{}
    $masked = @{}
    foreach ($event in $ordered) {
        if ($event.dropped -and $event.type -ne 'ACTIVITY_STOPPED') { continue }
        $key = "$($event.target)|$($event.ref)"
        switch ($event.type) {
            { $_ -in @('DEVICE_SHUTDOWN', 'DEVICE_STARTUP') } { $latest.Clear() }
            { $_ -in @('SCREEN_NON_INTERACTIVE', 'KEYGUARD_SHOWN', 'PACKAGE_SUSPENDED') } {
                foreach ($activityKey in @($latest.Keys)) {
                    $tracked = $latest[$activityKey]
                    if (($event.type -eq 'PACKAGE_SUSPENDED' -and $tracked.target -eq $event.target) -or
                        ($event.type -ne 'PACKAGE_SUSPENDED' -and $tracked.type -eq 'ACTIVITY_PAUSED')) {
                        $latest.Remove($activityKey)
                    }
                }
            }
            'PACKAGE_MASKED' { $masked[$event.target] = $true }
            'PACKAGE_UNMASKED' { $masked[$event.target] = $false }
            'ACTIVITY_RESUMED' {
                $event.masked = $masked[$event.target] -eq $true
                $latest[$key] = $event
            }
            'ACTIVITY_PAUSED' { $latest[$key] = $event }
            'ACTIVITY_STOPPED' {
                if ($latest[$key] -and $latest[$key].at -lt $event.at) {
                    $event.prior = $latest[$key]
                }
                if (!$event.dropped) { $latest.Remove($key) }
            }
        }
    }
    $eventsByBoot[$boot] = $ordered
}

$sessions = [System.Collections.Generic.List[object]]::new()
foreach ($entry in @($timeline | Sort-Object { $_.start }, { $_.id }, { $_.end })) {
    $last = if ($sessions.Count) { $sessions[$sessions.Count - 1] }
    if ($last -and !$last.unplaced -and !$entry.unplaced -and $entry.start -eq $last.end -and
        $entry.boot -and $entry.boot -eq $last.boot -and $entry.id -ge $last.lastId) {
        $last.end = $entry.end
        $last.ms += $entry.ms
        $last.lastId = $entry.id
        $last.dayEnd = $entry.dayEnd
        $last.lastTrace = $entry.trace
    } else {
        $entry.lastId = $entry.id
        $entry.lastTrace = $entry.trace
        $sessions.Add($entry)
    }
}

$zone = if ($snapshot.zone) { $snapshot.zone } else { 'accounting timezone unavailable' }
Write-Output "DoomStop accounting day $Day ($zone). Timestamps in UTC."
Write-Output 'Charges below are committed journal entries. App, screen, and cause are inferred from recorded evidence.'
if (!$sessions.Count) { Write-Output 'No readable recorded charges for this day.' }
$coveredEnd = $null
foreach ($entry in $sessions) {
    Write-Output ''
    if ($entry.unplaced) {
        $window = if ($entry.start -ne [long]::MaxValue) { "$(Format-Time $entry.start) -> $(Format-Time $entry.end)" }
            else { 'Unknown settlement window' }
        Write-Output "$window | $($entry.ms) ms recorded in #$($entry.id). Exact timing unavailable ($($entry.reason))."
        continue
    }
    if ($null -ne $coveredEnd -and $entry.start -lt $coveredEnd) {
        $notes.Add("Record #$($entry.id) overlaps another charged interval in wall time. Totals include both recorded amounts.")
    }
    if ($null -eq $coveredEnd -or $entry.end -gt $coveredEnd) { $coveredEnd = $entry.end }
    $journalEvents = $eventsByBoot[$entry.boot]
    $startEvents = [System.Collections.Generic.List[object]]::new()
    $endEvents = [System.Collections.Generic.List[object]]::new()
    $during = [System.Collections.Generic.List[object]]::new()
    $targets = [System.Collections.Generic.HashSet[string]]::new()
    $evidenceStart = $entry.originStart
    $index = Get-FirstEventIndex $journalEvents $evidenceStart
    for (; $index -lt $journalEvents.Count -and $journalEvents[$index].at -le $entry.end; $index++) {
        $event = $journalEvents[$index]
        if ($event.at -eq $entry.start) { $startEvents.Add($event) }
        if ($event.at -eq $entry.end) { $endEvents.Add($event) }
        if (($event.at -gt $entry.start -and $event.at -lt $entry.end) -or $event.dropped -or
            ($event.at -eq $entry.start -and $event.type -eq 'ACTIVITY_STOPPED') -or
            ($entry.dayStart -and $event.at -lt $entry.start)) { $during.Add($event) }
        $openingEdge = $edges["$($entry.boot)|$($event.at)|True"]
        if (!$event.dropped -and $event.at -lt $entry.end -and $event.target -ne 'system' -and
            (($event.type -eq 'ACTIVITY_RESUMED' -and (!$event.masked -or $openingEdge)) -or
             ($event.type -eq 'PACKAGE_UNMASKED' -and $openingEdge))) {
            [void]$targets.Add($event.target)
        }
    }
    if (!$targets.Count) {
        foreach ($trace in @($entry.trace, $entry.lastTrace)) {
            foreach ($activity in $trace.activities) {
                $since = if ($null -ne $activity.since) { $activity.since }
                    elseif ($null -ne $activity.ageMs) { $trace.acceptedNow - $activity.ageMs }
                if ($activity.target -and $null -ne $since -and $since -lt $entry.end -and
                    $trace.acceptedNow -ge $entry.start -and $activity.state -ne 'paused (unresolved)' -and
                    !($activity.target -eq 'com.instagram.android' -and $trace.dmMasked) -and
                    !($activity.target -eq 'com.google.android.youtube' -and $trace.youtubeMasked)) {
                    [void]$targets.Add($activity.target)
                }
            }
        }
    }
    if (!$targets.Count -and $edges["$($entry.boot)|$($entry.end)|False"]) {
        foreach ($event in $endEvents) {
            if (!$event.dropped -and $event.target -ne 'system' -and $event.type -in $closingTypes) {
                [void]$targets.Add($event.target)
            }
        }
    }
    $apps = if ($targets.Count) {
        @($targets | ForEach-Object {
            if ($_ -eq 'com.google.android.youtube') { 'YouTube Shorts' } else { Get-AppName $_ }
        } | Sort-Object -Unique) -join ', '
    } else { 'unknown' }
    $recordIds = if ($entry.id -eq $entry.lastId) { "#$($entry.id)" } else { "#$($entry.id) to #$($entry.lastId)" }
    Write-Output "$(Format-Time $entry.start) -> $(Format-Time $entry.end) | $($entry.ms) ms recorded ($recordIds)"
    Write-Output "  Inferred app or screen | $apps"
    Write-Output "  Start | $(Get-BoundaryExplanation $entry $true $startEvents)"
    foreach ($event in $during) {
        Write-Output "  Event $(Format-Time $event.at) | $(Format-Event $event)"
    }
    Write-Output "  End | $(Get-BoundaryExplanation $entry $false $endEvents)"

    foreach ($event in $during) {
        if ($event.type -ne 'ACTIVITY_STOPPED' -or $event.at -ge $entry.end -or $event.at -lt $entry.start) { continue }
        $withoutPause = $event.prior.type -eq 'ACTIVITY_RESUMED'
        $key = "$($entry.boot)|$($event.target)|$($event.ref)"
        $byResume = $observations[$key]
        $observation = $null
        if ($byResume -and $withoutPause) {
            $observation = $byResume[[long]$event.prior.at]
        } elseif ($byResume -and !$event.prior -and $event.ref) {
            # A retained state can still establish an earlier resume when the event
            # fell out of retention. A package-only snapshot cannot make this link.
            $observation = $byResume.Values | Where-Object {
                $_.since -lt $event.at -and $_.at -gt $event.at
            } | Sort-Object { $_.since } -Descending | Select-Object -First 1
        }
        $retained = $observation -and $observation.at -gt $event.at
        $onlyTarget = $targets.Count -eq 1 -and $targets.Contains($event.target)
        $lateStop = $event.dropped -and ($event.prior -or $onlyTarget)
        $unpairedStop = $retained -or ($withoutPause -and $onlyTarget -and !$event.ref)
        if (!$lateStop -and !$unpairedStop) { continue }
        $after = $entry.end - $event.at
        Write-Output "  Possible stale foreground | $after ms of recorded charging followed the stop."
        if ($withoutPause) {
            Write-Output "    $(Format-Event $event) at $(Format-Time $event.at) followed a recorded resume without a recorded ACTIVITY_PAUSED between them."
        } elseif ($retained) {
            Write-Output "    The resume event is unavailable. The tracker snapshot dates its resumed state to $(Format-Time $observation.since)."
        }
        if ($lateStop) {
            Write-Output '    The journal records that this stop arrived after its interval settled and was excluded from accounting.'
        }
        if ($retained -and $event.ref) {
            Write-Output "    Record #$($observation.id) still lists the same activity reference as resumed after the stop."
        } elseif ($retained) {
            Write-Output "    Record #$($observation.id) still lists a resumed activity for this package. Activity class is unknown."
        } else {
            Write-Output '    Retained activity evidence is missing. Another activity could account for the continuation.'
        }
    }
}

Write-Output ''
Write-Output "Recorded journal charges for $Day = $journalMs ms ($placedMs ms placed, $($journalMs - $placedMs) ms unplaced)."
if ($Day -eq $snapshot.day -and (Test-Milliseconds $snapshot.chargedMs)) {
    $difference = $snapshot.chargedMs - $journalMs
    $signed = $difference.ToString('+0;-0;0', [Globalization.CultureInfo]::InvariantCulture)
    Write-Output "Recorded daily charge = $($snapshot.chargedMs) ms. Difference (daily minus journal) = $signed ms."
    if ($difference) {
        $notes.Add('Journal retention, missing records, or a balance adjustment can leave a difference.')
    }
} else {
    Write-Output 'Daily charge unavailable for the selected day. The export has no independent total to reconcile.'
}
if ($legacyLifecycle) { $notes.Add('Some lifecycle records identify only the package. Matches within a package can be ambiguous.') }
if ($usageGapCount -or $clockGapCount) {
    $notes.Add("Nearby passes with unavailable usage events = $usageGapCount. Clock or accounting-gap notices = $clockGapCount. The affected time on this day is uncertain.")
}
if ($Day -eq $snapshot.day -and $snapshot.recovery) {
    $notes.Add('Recovery was latched at the snapshot. The recorded balance can reconcile even when observation was incomplete.')
}
$adjustments = @($records | Where-Object {
    $_.type -in @('incident_usage_reset', 'incident_phantom_usage_repair') -and
    $_.detail -like "*$Day*"
})
foreach ($record in $adjustments) {
    $notes.Add("Recorded balance adjustment $($record.type) (#$($record.id)). Journal charges show the original committed amounts.")
}
foreach ($note in $notes) { Write-Output "Evidence note | $note" }
