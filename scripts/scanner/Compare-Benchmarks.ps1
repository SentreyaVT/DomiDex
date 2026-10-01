param(
    [string]$Directory = "$PSScriptRoot/../../artifacts/scanner-v27",
    [string]$Baseline = 'baseline',
    [string]$Optimized = 'optimized',
    [string]$GeometryBaseline = $Baseline,
    [string]$GeometryOptimized = $Optimized
)
$ErrorActionPreference = 'Stop'

function Read-Report([string]$Name) {
    Get-Content -LiteralPath (Join-Path $Directory $Name) -Raw | ConvertFrom-Json
}
function Field($Object, [string]$Name) {
    if ($null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]) { $Object.$Name }
}
function Require-True($Value, [string]$Description) {
    if ($Value -isnot [bool] -or -not $Value) { throw $Description }
}
function Require-Integer($Value, [long]$Minimum, [string]$Description) {
    if ($null -eq $Value -or $Value -is [bool] -or $Value -is [string] -or
        [double]$Value -lt $Minimum -or [double]$Value -ne [Math]::Truncate([double]$Value)) { throw $Description }
}
function Require-Number($Value, [string]$Description) {
    if ($null -eq $Value -or $Value -is [bool] -or $Value -is [string] -or
        [double]::IsNaN([double]$Value) -or [double]::IsInfinity([double]$Value) -or [double]$Value -lt 0) { throw $Description }
}
function Require-Hash($Value, [string]$Description) {
    if ($Value -isnot [string] -or $Value -cnotmatch '^[0-9a-fA-F]{64}$') { throw $Description }
}
function Require-SameSet($Before, $After, [string]$Description) {
    $left = @($Before | Sort-Object -CaseSensitive)
    $right = @($After | Sort-Object -CaseSensitive)
    if ($left.Count -ne $right.Count) { throw "$Description (count mismatch)" }
    for ($index = 0; $index -lt $left.Count; $index++) {
        if ($left[$index] -cne $right[$index]) { throw "$Description (different key: $($left[$index]) / $($right[$index]))" }
    }
}
function New-Index { ,([System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)) }
function Sample-Key($Sample) { ConvertTo-Json -InputObject @($Sample.fixtureId, $Sample.case, [long]$Sample.iteration) -Compress }
function Statistics($Values) {
    $ordered = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($ordered.Count -eq 0) { return $null }
    $middle = [int][Math]::Floor($ordered.Count / 2)
    $median = if ($ordered.Count % 2) { $ordered[$middle] } else { ($ordered[$middle - 1] + $ordered[$middle]) / 2 }
    [ordered]@{ n = $ordered.Count; median = $median; p95 = $ordered[[int][Math]::Ceiling($ordered.Count * .95) - 1]; worst = $ordered[-1] }
}

function Validate-Ocr($Report, [string]$Name) {
    Require-True (Field $Report 'complete') "$Name OCR report is not explicitly complete; interrupted/combined reports require verified complete=true and expectedSamples"
    Require-Integer (Field $Report 'expectedSamples') 1 "$Name has no valid expectedSamples"
    Require-Integer (Field $Report 'repeats') 1 "$Name has no valid repeats"
    $samples = @(Field $Report 'samples')
    if ($samples.Count -ne $Report.expectedSamples) { throw "$Name OCR count differs from expectedSamples ($($samples.Count) / $($Report.expectedSamples))" }
    $caseIndex = New-Index
    foreach ($case in @(Field $Report 'cases')) {
        if ($case -isnot [string] -or [string]::IsNullOrWhiteSpace($case) -or $caseIndex.ContainsKey($case)) {
            throw "$Name has missing/duplicate declared OCR case"
        }
        $caseIndex.Add($case, $true)
    }
    if ($caseIndex.Count -eq 0) { throw "$Name has no declared OCR cases" }
    $sampleIndex = New-Index
    $fixtureIndex = New-Index
    foreach ($sample in $samples) {
        if ($sample.fixtureId -isnot [string] -or [string]::IsNullOrWhiteSpace($sample.fixtureId) -or
            $sample.case -isnot [string] -or -not $caseIndex.ContainsKey($sample.case)) { throw "$Name has an invalid fixture/case" }
        Require-Integer (Field $sample 'iteration') 0 "$Name invalid OCR iteration"
        if ($sample.iteration -ge $Report.repeats) { throw "$Name OCR iteration outside repeats" }
        $key = Sample-Key $sample
        if ($sampleIndex.ContainsKey($key)) { throw "$Name duplicate OCR key: $key" }
        $sampleIndex.Add($key, $sample)
        $fixtureIndex[$sample.fixtureId] = $true
        if (@('ACCEPTED', 'AMBIGUOUS', 'REJECTED') -cnotcontains $sample.fixtureCatalogueDecision) { throw "$Name invalid/missing decision: $key" }
        foreach ($flag in @('numberCorrect', 'fixtureCatalogueFalseAccept')) {
            if ((Field $sample $flag) -isnot [bool]) { throw "$Name missing boolean $flag at $key" }
        }
        foreach ($metric in @('elapsedMillis', 'processCpuMillis', 'ocrCalls', 'preparedOcrBitmaps', 'preparedOcrBitmapBytes')) {
            Require-Number (Field $sample $metric) "$Name invalid/missing metric $metric at $key"
        }
        if ($null -ne (Field $sample 'inputSha256')) { Require-Hash $sample.inputSha256 "$Name invalid inputSha256 at $key" }
    }
    $expectedRectangle = [long]$fixtureIndex.Count * $caseIndex.Count * $Report.repeats
    if ($samples.Count -ne $expectedRectangle) { throw "$Name missing fixture/case/iteration combinations ($($samples.Count) / $expectedRectangle)" }
    return $sampleIndex
}

function Ocr-SamplesSummary($Samples) {
    $all = @($Samples)
    $ocr = @($all | Where-Object { $_.ocrCalls -gt 0 })
    $pss = @($all | ForEach-Object { Field $_.memoryAfter 'totalPssKb' } | Where-Object { $null -ne $_ })
    [ordered]@{
        samples = $all.Count
        allElapsedMs = Statistics $all.elapsedMillis
        allProcessCpuMs = Statistics $all.processCpuMillis
        ocrAttemptElapsedMs = Statistics $ocr.elapsedMillis
        ocrAttemptProcessCpuMs = Statistics $ocr.processCpuMillis
        ocrCalls = Statistics $all.ocrCalls
        preparedTemporaryOcrBitmaps = Statistics $all.preparedOcrBitmaps
        cumulativePreparedOcrBitmapBytes = Statistics $all.preparedOcrBitmapBytes
        correctNumbers = @($all | Where-Object { $_.numberCorrect -eq $true }).Count
        correctNumbersScope = 'collector_local_id_only; historical numberCorrect does not validate the denominator'
        fullNumberCorrectMeasuredSamples = @($all | Where-Object { (Field $_ 'fullNumberCorrect') -is [bool] }).Count
        fullNumbersCorrect = if (@($all | Where-Object { (Field $_ 'fullNumberCorrect') -isnot [bool] }).Count -eq 0) {
            @($all | Where-Object { $_.fullNumberCorrect -eq $true }).Count
        } else { $null }
        accepted = @($all | Where-Object fixtureCatalogueDecision -CEQ ACCEPTED).Count
        ambiguous = @($all | Where-Object fixtureCatalogueDecision -CEQ AMBIGUOUS).Count
        rejected = @($all | Where-Object fixtureCatalogueDecision -CEQ REJECTED).Count
        falseAccepts = @($all | Where-Object { $_.fixtureCatalogueFalseAccept -eq $true }).Count
        failures = @($all | Where-Object { -not [string]::IsNullOrEmpty($_.failure) }).Count
        observedAfterOperationPssKiB = Statistics $pss
        maximumObservedAfterOperationPssKiB = if ($pss.Count) { ($pss | Measure-Object -Maximum).Maximum } else { $null }
        observedNativeAllocatedBytes = Statistics @($all | ForEach-Object { Field $_.memoryAfter 'nativeAllocatedBytes' })
        observedJavaUsedBytes = Statistics @($all | ForEach-Object { Field $_.memoryAfter 'javaUsedBytes' })
        phasesMs = @(foreach ($phase in @('normalize', 'probe_prepare', 'probe_ocr_wall', 'orientation_parse', 'full_prepare', 'full_ocr_wall', 'final_parse', 'descriptor', 'rarity')) {
            [ordered]@{ phase = $phase; stats = Statistics @($ocr | ForEach-Object { Field $_.phaseMillis $phase }) }
        })
    }
}
function Ocr-Summary($Report) {
    $summary = Ocr-SamplesSummary $Report.samples
    $summary['expectedSamples'] = $Report.expectedSamples
    $summary['complete'] = $Report.complete
    $summary['byCase'] = @(foreach ($case in $Report.cases) {
        [ordered]@{ case = $case; measurements = Ocr-SamplesSummary @($Report.samples | Where-Object { $_.case -ceq $case }) }
    })
    $summary['memoryLimitations'] = 'PSS/heap are process snapshots after operations, not peak/live bitmap proof. Prepared bitmap bytes are cumulative production inputs, not simultaneous RAM. Missing values remain null. Process CPU includes other process threads; the finite-catalogue decisions are not production accuracy.'
    return $summary
}

function Validate-Geometry($Report, [string]$Name) {
    Require-True (Field $Report 'complete') "$Name geometry report is not explicitly complete"
    Require-Integer (Field $Report 'repetitions') 1 "$Name invalid geometry repetitions"
    $index = New-Index
    foreach ($case in @(Field $Report 'cases')) {
        if ($case.id -isnot [string] -or [string]::IsNullOrWhiteSpace($case.id) -or $index.ContainsKey($case.id)) { throw "$Name duplicate/missing geometry ID" }
        $index.Add($case.id, $case)
        Require-Hash $case.input.pixelsSha256 "$Name missing/invalid geometry pixel hash: $($case.id)"
        Require-Hash $case.input.lumaSha256 "$Name missing/invalid geometry luma hash: $($case.id)"
        foreach ($stage in @('detector', 'normalizer')) {
            Require-Hash $case.$stage.outputSha256 "$Name invalid output hash: $($case.id)/$stage"
            foreach ($metric in @('wallMs', 'processCpuMs', 'callingThreadCpuMs', 'nativeHeapAfterOperationBytes')) {
                $measurement = Field $case.$stage $metric
                Require-True (Field $measurement 'available') "$Name missing geometry metric $($case.id)/$stage/$metric"
                if (@($measurement.samples).Count -ne $Report.repetitions) { throw "$Name truncated geometry metric: $($case.id)/$stage/$metric" }
                foreach ($value in $measurement.samples) { Require-Number $value "$Name invalid $metric sample" }
            }
        }
    }
    if ($index.Count -eq 0) { throw "$Name has no geometry cases" }
    return $index
}
function Geometry-Metrics($Stage) {
    [ordered]@{
        elapsedMs = Statistics $Stage.wallMs.samples
        processCpuMs = Statistics $Stage.processCpuMs.samples
        callingThreadCpuMs = Statistics $Stage.callingThreadCpuMs.samples
        allocatedJavaBytes = if ($Stage.javaAllocatedBytes.available -eq $true) { Statistics $Stage.javaAllocatedBytes.samples } else { $null }
        observedNativeHeapAfterOperationBytes = Statistics $Stage.nativeHeapAfterOperationBytes.samples
        pssBeforeKiB = Field $Stage.memoryBefore 'totalPssKiB'
        pssAfterKiB = Field $Stage.memoryAfter 'totalPssKiB'
        memoryBefore = $Stage.memoryBefore
        memoryAfter = $Stage.memoryAfter
        geometryStageMs = if ($Stage.geometryStageMs.available -eq $true) { Statistics $Stage.geometryStageMs.samples } else { $null }
        perspectiveStageMs = if ($Stage.perspectiveStageMs.available -eq $true) { Statistics $Stage.perspectiveStageMs.samples } else { $null }
    }
}
function Reuse-Summary($Report, [string]$Name) {
    $sequence = @(Field $Report 'reusedDetectorSequence')
    if ($sequence.Count -eq 0) { return [ordered]@{ available = $false; validated = $false; note = "$Name report did not record a reused-detector sequence" } }
    $seen = New-Index
    foreach ($item in $sequence) {
        Require-Integer (Field $item 'index') 0 "$Name invalid reused-detector index"
        $key = [string]$item.index
        if ($seen.ContainsKey($key)) { throw "$Name duplicate reused-detector index $key" }
        $seen.Add($key, $true)
        Require-True (Field $item 'matchesFreshDetector') "$Name reused detector differs from fresh detector at $key"
        if ([string]::IsNullOrWhiteSpace($item.id) -or $null -eq $item.output) { throw "$Name incomplete reused-detector result" }
    }
    for ($index = 0; $index -lt $sequence.Count; $index++) {
        if (-not $seen.ContainsKey([string]$index)) { throw "$Name missing reused-detector step $index" }
    }
    return [ordered]@{ available = $true; validated = $true; entries = $sequence }
}

$beforeGeometry = Read-Report "$GeometryBaseline-geometry.json"
$afterGeometry = Read-Report "$GeometryOptimized-geometry.json"
$beforeGeometryIndex = Validate-Geometry $beforeGeometry $GeometryBaseline
$afterGeometryIndex = Validate-Geometry $afterGeometry $GeometryOptimized
Require-SameSet $beforeGeometryIndex.Keys $afterGeometryIndex.Keys 'Geometry case sets differ'
if ($beforeGeometry.repetitions -ne $afterGeometry.repetitions -or $beforeGeometry.fixtureVersion -cne $afterGeometry.fixtureVersion) { throw 'Geometry repetitions/fixture version differ' }
$geometry = foreach ($before in $beforeGeometry.cases) {
    $after = $afterGeometryIndex[$before.id]
    foreach ($field in @('pixelsSha256', 'lumaSha256', 'width', 'height', 'rowStride', 'pixelStride')) {
        if ((Field $before.input $field) -cne (Field $after.input $field)) { throw "Geometry inputs differ: $($before.id)/$field" }
    }
    if ((ConvertTo-Json -InputObject (Field $before.input 'crop') -Compress) -cne (ConvertTo-Json -InputObject (Field $after.input 'crop') -Compress)) { throw "Geometry crop differs: $($before.id)" }
    foreach ($stage in @('detector', 'normalizer')) {
        [ordered]@{
            case = $before.id; stage = $stage
            sameInput = $true
            identicalOutput = $before.$stage.outputSha256 -ceq $after.$stage.outputSha256
            before = Geometry-Metrics $before.$stage
            after = Geometry-Metrics $after.$stage
            beforeQualityReason = if ($stage -eq 'normalizer') { $before.$stage.output.quality.reason } else { $null }
            afterQualityReason = if ($stage -eq 'normalizer') { $after.$stage.output.quality.reason } else { $null }
            beforeHasNormalizedBitmap = if ($stage -eq 'normalizer') { $null -ne $before.$stage.output.bitmap } else { $null }
            afterHasNormalizedBitmap = if ($stage -eq 'normalizer') { $null -ne $after.$stage.output.bitmap } else { $null }
        }
    }
}
$reuseBefore = Reuse-Summary $beforeGeometry $GeometryBaseline
$reuseAfter = Reuse-Summary $afterGeometry $GeometryOptimized
$reuseComparison = $null
if ($reuseBefore.available -and $reuseAfter.available) {
    $reuseComparison = @(foreach ($before in $reuseBefore.entries) {
        $after = @($reuseAfter.entries | Where-Object { $_.index -eq $before.index })
        if ($after.Count -ne 1 -or $after[0].id -cne $before.id) { throw 'Reused-detector sequences differ' }
        [ordered]@{ index = $before.index; case = $before.id; identicalOutput =
            (ConvertTo-Json -InputObject $before.output -Depth 15 -Compress) -ceq (ConvertTo-Json -InputObject $after[0].output -Depth 15 -Compress) }
    })
    if ($reuseBefore.entries.Count -ne $reuseAfter.entries.Count) { throw 'Reused-detector sequence counts differ' }
}

$beforeOcr = Read-Report "$Baseline.json"
$afterOcr = Read-Report "$Optimized.json"
$beforeIndex = Validate-Ocr $beforeOcr $Baseline
$afterIndex = Validate-Ocr $afterOcr $Optimized
Require-SameSet $beforeIndex.Keys $afterIndex.Keys 'OCR sample key sets differ'
Require-SameSet $beforeOcr.cases $afterOcr.cases 'Declared OCR case sets differ'
if ($beforeOcr.repeats -ne $afterOcr.repeats -or $beforeOcr.expectedSamples -ne $afterOcr.expectedSamples) { throw 'OCR repeats/expected sample counts differ' }
$verifiedInputs = 0
$unverifiedInputs = 0
$changes = foreach ($before in $beforeOcr.samples) {
    $key = Sample-Key $before
    $after = $afterIndex[$key]
    if ($null -ne (Field $before 'inputSha256') -and $null -ne (Field $after 'inputSha256')) {
        if ($before.inputSha256 -cne $after.inputSha256) { throw "OCR input hash mismatch: $key" }
        $verifiedInputs++
    } else { $unverifiedInputs++ }
    if ($before.expectedCollectorNumber -cne $after.expectedCollectorNumber) { throw "OCR expected identity changed: $key" }
    $differences = [ordered]@{}
    foreach ($field in @('collectorNumber', 'setIdentifier', 'fixtureCatalogueDecision', 'fixtureCatalogueBestId',
        'fixtureCatalogueFalseAccept', 'numberCorrect', 'qualityReason', 'orientation', 'failure')) {
        if ((Field $before $field) -cne (Field $after $field)) {
            $differences[$field] = [ordered]@{ before = Field $before $field; after = Field $after $field }
        }
    }
    if ($differences.Count) { [ordered]@{ fixture = $before.fixtureId; case = $before.case; iteration = $before.iteration; changes = $differences } }
}
[ordered]@{
    note = 'Emulator debug measurements only. Finite public-fixture catalogue, not complete production-catalogue accuracy. p95 uses nearest-rank and is weak with small samples. PSS/heap observations are not true peak or leak proof; CPU is process-wide/coarsely sampled. Bitmap preparation bytes are cumulative, not simultaneous RAM. Geometry pixel hashes cover the first measured output; repeat checks cover structure/quality.'
    validation = [ordered]@{ completeReports = $true; matchingOcrSampleKeys = $beforeIndex.Count; matchingGeometryCases = $beforeGeometryIndex.Count
        matchingOcrInputHashes = $verifiedInputs; ocrInputsWithoutTwoSidedHash = $unverifiedInputs
        inputHashLimitation = if ($unverifiedInputs) { 'Some historical samples have no two-sided input hash; identity/case/iteration match is verified but exact input bytes are not independently proven for those samples.' } else { $null } }
    baseline = Ocr-Summary $beforeOcr
    optimized = Ocr-Summary $afterOcr
    geometry = @($geometry)
    reusedDetector = [ordered]@{ baseline = $reuseBefore; optimized = $reuseAfter; comparison = $reuseComparison }
    recognitionChanges = @($changes)
} | ConvertTo-Json -Depth 25
