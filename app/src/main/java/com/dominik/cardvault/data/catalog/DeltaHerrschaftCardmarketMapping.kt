package com.dominik.cardvault.data.catalog

/**
 * Reviewed Cardmarket links for Mega-Entwicklung – Delta-Herrschaft (DLR).
 *
 * The public Cardmarket product download has no collector-number or finish
 * fields. A record is therefore VERIFIED only when the upstream Cardmarket ID,
 * DLR expansion, English card name and complete attack fingerprint identify one
 * physical normal/standard printing. Every other DLR printing deliberately
 * remains NEEDS_REVIEW; callers must never fall back to a root product ID.
 */
internal enum class DlrCardmarketMappingStatus { VERIFIED, NEEDS_REVIEW, UNMATCHED, REJECTED }

internal data class DlrCardmarketMappingRecord(
    val sourceCardId: String,
    val collectorNumber: String,
    val productId: Long?,
    val finish: String,
    val variant: String,
    val status: DlrCardmarketMappingStatus,
    val provenance: String,
    val verifiedAtEpochMillis: Long?,
)

internal object DeltaHerrschaftCardmarketMapping {
    const val SET_ID = "me06"
    const val EXPANSION_ID = "6704"
    private const val VERIFIED_AT = 1795449600000L // 2026-11-23T00:00:00Z
    private const val DECLARED_PROVENANCE = "TCGdex declared product + Cardmarket expansion/name/attack fingerprint"
    private const val FINGERPRINT_PROVENANCE = "Cardmarket expansion + unique English name/complete attack fingerprint"

    private fun verified(
        number: String,
        productId: Long,
        provenance: String = DECLARED_PROVENANCE,
    ) = DlrCardmarketMappingRecord(
        sourceCardId = "$SET_ID-$number", collectorNumber = number, productId = productId,
        finish = "Normal", variant = "Standard", status = DlrCardmarketMappingStatus.VERIFIED,
        provenance = provenance, verifiedAtEpochMillis = VERIFIED_AT,
    )

    private val verifiedRecords = listOf(
        verified("001", 918810), verified("002", 918811), verified("004", 918813),
        verified("005", 918814), verified("006", 918815), verified("007", 918816),
        verified("008", 918817), verified("009", 918818), verified("013", 918823),
        verified("014", 918824), verified("015", 918825), verified("016", 918826),
        verified("020", 918834), verified("022", 918837), verified("024", 918839),
        verified("027", 918842), verified("030", 918846), verified("032", 918848),
        verified("034", 918850), verified("037", 918854), verified("038", 918855),
        verified("040", 918857), verified("041", 918858), verified("042", 918859),
        verified("043", 918863), verified("044", 918864), verified("045", 918865),
        verified("046", 918867), verified("049", 918871), verified("051", 918873),
        verified("056", 918879), verified("058", 918881), verified("060", 918883),
        verified("061", 918884), verified("062", 918885), verified("064", 918888),
        verified("065", 918889), verified("067", 918892), verified("070", 918896),
        verified("072", 918900), verified("073", 918901), verified("074", 918902),
        verified("076", 918904), verified("077", 918905), verified("081", 918909),
        verified("086", 918914), verified("087", 918915), verified("093", 918921),
        verified("129", 918968),
        // These links have no usable TCGdex root reference.  They are nevertheless
        // safe: one and only one product in Cardmarket expansion 6704 has the exact
        // English card name and the complete attack-name fingerprint below.
        verified("010", 918819, FINGERPRINT_PROVENANCE),
        verified("017", 918827, FINGERPRINT_PROVENANCE),
        verified("028", 918843, FINGERPRINT_PROVENANCE),
        verified("035", 918852, FINGERPRINT_PROVENANCE),
        verified("039", 918856, FINGERPRINT_PROVENANCE),
        verified("050", 918872, FINGERPRINT_PROVENANCE),
        verified("052", 918875, FINGERPRINT_PROVENANCE),
        verified("054", 918877, FINGERPRINT_PROVENANCE),
        verified("055", 918878, FINGERPRINT_PROVENANCE),
        verified("057", 918880, FINGERPRINT_PROVENANCE),
        verified("075", 918903, FINGERPRINT_PROVENANCE),
        verified("079", 918907, FINGERPRINT_PROVENANCE),
        verified("085", 918913, FINGERPRINT_PROVENANCE),
    )
    private val byCardId = verifiedRecords.associateBy { it.sourceCardId }
    private val unmatchedCollectors = setOf("090", "094", "095", "098", "099", "101", "126", "132", "133", "134")

    /** A complete report has one record per numbered DLR card, including unresolved cards. */
    fun records(): List<DlrCardmarketMappingRecord> = (1..141).map { number ->
        val collector = number.toString().padStart(3, '0')
        byCardId["$SET_ID-$collector"] ?: DlrCardmarketMappingRecord(
            sourceCardId = "$SET_ID-$collector", collectorNumber = collector, productId = null,
            finish = "Normal", variant = "Standard",
            status = if (collector in unmatchedCollectors) DlrCardmarketMappingStatus.UNMATCHED else DlrCardmarketMappingStatus.NEEDS_REVIEW,
            provenance = if (collector in unmatchedCollectors) "No Cardmarket root reference supplied by TCGdex" else "No print-specific Cardmarket evidence yet",
            verifiedAtEpochMillis = null,
        )
    }

    fun verifiedProductId(sourceCardId: String, finish: String, variant: String): Long? {
        val record = byCardId[sourceCardId.lowercase()] ?: return null
        return record.productId.takeIf {
            record.status == DlrCardmarketMappingStatus.VERIFIED &&
                finish.equals(record.finish, ignoreCase = true) &&
                variant.equals(record.variant, ignoreCase = true)
        }
    }
}
