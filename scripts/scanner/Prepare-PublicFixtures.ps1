param([string]$OutputDirectory = "$PSScriptRoot/../../artifacts/scanner-v27/public-fixtures")
$ErrorActionPreference = 'Stop'
# Development-only public catalogue scans. Never consume or archive private user captures.
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$sources = @(
    @{ Language = 'de'; Id = 'swsh12-047'; Class = 'NORMAL_REVERSE_AVAILABLE' },
    @{ Language = 'de'; Id = 'sv02-269'; Class = 'SPECIAL_ILLUSTRATION' },
    @{ Language = 'en'; Id = 'base1-58'; Class = 'NORMAL_LEGACY' },
    @{ Language = 'de'; Id = 'sm1-94'; Class = 'NORMAL' },
    @{ Language = 'de'; Id = 'swsh10-162'; Class = 'FULL_ART' },
    @{ Language = 'en'; Id = 'swshp-SWSH020'; Class = 'PROMO' }
)
$fixtures = foreach ($source in $sources) {
    $metadataUrl = "https://api.tcgdex.net/v2/$($source.Language)/cards/$($source.Id)"
    $card = Invoke-RestMethod -Uri $metadataUrl
    if (-not $card.image) { throw "No public image available: $metadataUrl" }
    $set = Invoke-RestMethod -Uri "https://api.tcgdex.net/v2/$($source.Language)/sets/$($card.set.id)"
    $imageUrl = "$($card.image)/high.jpg"
    $filename = "$($source.Language)-$($card.id).jpg"
    $path = Join-Path $destination $filename
    Invoke-WebRequest -Uri $imageUrl -OutFile $path
    $collectorNumber = if ($source.Class -eq 'PROMO') { $card.localId } else { "$($card.localId)/$($card.set.cardCount.official)" }
    [ordered]@{
        id = $card.id; set = $set.tcgOnline; sourceSetId = $card.set.id
        localId = $card.localId; collectorNumber = $collectorNumber
        printedTotal = $card.set.cardCount.official; name = $card.name
        language = $source.Language; rarity = $card.rarity; variants = $card.variants
        cardClass = $source.Class; path = $filename; metadataUrl = $metadataUrl
        imageUrl = $imageUrl; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest = [ordered]@{
    provenance = 'Public TCGdex catalogue images; flat scans, not real camera or physical foil samples.'
    fixtures = @($fixtures)
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding UTF8
Write-Output "Prepared $($fixtures.Count) public fixtures in $destination"
