package com.dominik.cardvault.data.catalog

/**
 * Central, source-first set identity.  A scanner never derives a code from a
 * translated name.  The only explicit alias is PRE -> TCGdex sv08.5, verified
 * against the German TCGdex set record and Cardmarket's German set listing.
 */
data class PokemonSetMetadata(
    val setId: String,
    /** Official printed abbreviation, when the trusted source declares one. */
    val setCode: String?,
    val germanName: String,
    val internationalName: String? = null,
    val mainSetSize: Int,
    val totalKnownCards: Int,
    val series: String,
    val releaseDate: String,
)

object PokemonSetRegistry {
    /**
     * Printed expansion codes verified against the public Pokémon TCG set index.
     * TCGdex uses zero-padded source ids for several modern sets (for example
     * sv03); keys are therefore stored with [normalize] semantics. This map is
     * identity metadata only: it never guesses a code from a translated name.
     */
    private val verifiedExternalCodeByNormalizedSourceId = mapOf(
        "base1" to "BS", "base2" to "JU", "base3" to "FO", "base4" to "B2", "base5" to "TR", "basep" to "PR",
        "gym1" to "G1", "gym2" to "G2", "neo1" to "N1", "neo2" to "N2", "neo3" to "N3", "neo4" to "N4", "base6" to "LC",
        "ecard1" to "EX", "ecard2" to "AQ", "ecard3" to "SK",
        "ex1" to "RS", "ex2" to "SS", "ex3" to "DR", "ex4" to "MA", "ex5" to "HL", "ex6" to "RG", "ex7" to "TRR", "ex8" to "DX",
        "ex9" to "EM", "ex10" to "UF", "ex11" to "DS", "ex12" to "LM", "ex13" to "HP", "ex14" to "CG", "ex15" to "DF", "ex16" to "PK",
        "dp1" to "DP", "dp2" to "MT", "dp3" to "SW", "dp4" to "GE", "dp5" to "MD", "dp6" to "LA", "dp7" to "SF",
        "pl1" to "PL", "pl2" to "RR", "pl3" to "SV", "pl4" to "AR",
        "hgss1" to "HS", "hgss2" to "UL", "hgss3" to "UD", "hgss4" to "TM", "col1" to "CL",
        "bw1" to "BLW", "bw2" to "EPO", "bw3" to "NVI", "bw4" to "NXD", "bw5" to "DEX", "bw6" to "DRX", "bw7" to "BCR", "bw8" to "PLS", "bw9" to "PLF", "bw10" to "PLB", "bw11" to "LTR", "dv1" to "DRV",
        "xy0" to "KSS", "xy1" to "XY", "xy2" to "FLF", "xy3" to "FFI", "xy4" to "PHF", "xy5" to "PRC", "xy6" to "ROS", "xy7" to "AOR", "xy8" to "BKT", "xy9" to "BKP", "xy10" to "FCO", "xy11" to "STS", "xy12" to "EVO", "g1" to "GEN", "dc1" to "DCR",
        "sm1" to "SUM", "sm2" to "GRI", "sm3" to "BUS", "sm35" to "SLG", "sm4" to "CIN", "sm5" to "UPR", "sm6" to "FLI", "sm7" to "CES", "sm75" to "DRM", "sm8" to "LOT", "sm9" to "TEU", "sm10" to "UNB", "sm11" to "UNM", "sm12" to "CEC", "sm115" to "HIF", "sma" to "HIF", "det1" to "DET",
        "swsh1" to "SSH", "swsh2" to "RCL", "swsh3" to "DAA", "swsh35" to "CPA", "swsh4" to "VIV", "swsh45" to "SHF", "swsh5" to "BST", "swsh6" to "CRE", "swsh7" to "EVS", "swsh8" to "FST", "swsh9" to "BRS", "swsh10" to "ASR", "swsh11" to "LOR", "swsh12" to "SIT", "swsh12pt5" to "CRZ", "cel25" to "CEL", "pgo" to "PGO",
        "sv1" to "SVI", "sv2" to "PAL", "sv3" to "OBF", "sv35" to "MEW", "sv4" to "PAR", "sv45" to "PAF", "sv5" to "TEF", "sv6" to "TWM", "sv65" to "SFA", "sv7" to "SCR", "sv8" to "SSP", "sv85" to "PRE", "sv9" to "JTG", "sv10" to "DRI",
        "me1" to "MEG", "me2" to "PFL", "me25" to "ASC", "me3" to "POR", "me4" to "CRI", "me5" to "PBL",
    )

    /**
     * Existing offline master-set data has a declared code too. Keeping this
     * compatibility record here avoids a second hidden set-code table inside
     * the scanner. It is used only when the local source is explicitly chosen.
     */
    private val legacyOfflineSets = listOf(
        PokemonSet(
            id = "me04", name = "Wachsendes Chaos", series = "Mega-Entwicklung",
            printedTotal = 86, total = 122, releaseDate = "2026/05/22",
            logoUrl = "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/me04/me04_logo_169_de.png",
            ptcgoCode = "CRI",
        ),
    )

    /** Scanner safety gate: only source-verified printed codes may identify a set. */
    fun isVerifiedPrintedCode(value: String): Boolean {
        val candidate = value.uppercase().filter(Char::isLetterOrDigit)
        return verifiedExternalCodeByNormalizedSourceId.values.any { it.equals(candidate, ignoreCase = true) } ||
            legacyOfflineSets.any { it.ptcgoCode.equals(candidate, ignoreCase = true) }
    }

    fun metadata(set: PokemonSet): PokemonSetMetadata = PokemonSetMetadata(
        setId = set.id,
        // A TCGdex source ID (e.g. sv03) is useful internally, but it is not a
        // printed expansion abbreviation. Never present or match it as one.
        setCode = verifiedExternalCodeByNormalizedSourceId[normalize(set.id)] ?: set.ptcgoCode?.takeIf { it.isNotBlank() },
        germanName = set.name,
        mainSetSize = set.printedTotal,
        totalKnownCards = set.total.coerceAtLeast(set.printedTotal),
        series = set.series,
        releaseDate = set.releaseDate,
    )

    fun resolve(input: String, sourceSets: List<PokemonSet>): PokemonSet? {
        val normalized = normalize(input)
        if (normalized == "svp") return null
        return sourceSets.firstOrNull { set ->
            val metadata = metadata(set)
            normalize(metadata.setId) == normalized ||
                metadata.setCode?.let(::normalize) == normalized ||
                normalize(set.ptcgoCode.orEmpty()) == normalized
        }
    }

    fun legacyOfflineSet(input: String): PokemonSet? {
        val normalized = normalize(input)
        return legacyOfflineSets.firstOrNull { set ->
            normalized == normalize(set.id) || normalized == normalize(set.ptcgoCode.orEmpty())
        }
    }

    fun legacyOfflineSets(): List<PokemonSet> = legacyOfflineSets.toList()

    fun isAboveMainSet(collectorNumber: String, set: PokemonSet): Boolean {
        val cardNumber = collectorNumber.substringBefore('/').filter(Char::isDigit).toIntOrNull() ?: return false
        return set.printedTotal > 0 && cardNumber > set.printedTotal
    }

    fun normalize(value: String): String {
        val compact = value.lowercase().filter(Char::isLetterOrDigit)
        val numbered = Regex("([a-z]+)0*(\\d+)").matchEntire(compact)
        return numbered?.let { it.groupValues[1] + it.groupValues[2] } ?: compact
    }
}
