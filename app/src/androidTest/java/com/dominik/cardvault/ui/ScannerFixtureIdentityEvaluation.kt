package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.collectorIdentityForSourceLookup
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Test-only oracle. Manifest availability is NOT an image-specific physical printing label. */
internal data class ScannerFixtureIdentityReference(
    val cardId: String,
    val sourceSetId: String,
    val collectorNumber: String,
    val language: String,
    val availablePrintings: Set<Pair<String, String>>,
)

/** Actual candidate fields, never reconstructed from a best ID or synthetic market product ID. */
internal data class ScannerFixtureObservedCandidate(
    val cardId: String?, val sourceSetId: String?, val collectorNumber: String?,
    val finish: String?, val variant: String?, val language: String?,
) {
    fun toJson() = JSONObject().apply {
        put("cardId", cardId ?: JSONObject.NULL); put("sourceSetId", sourceSetId ?: JSONObject.NULL)
        put("collectorNumber", collectorNumber ?: JSONObject.NULL); put("finish", finish ?: JSONObject.NULL)
        put("variant", variant ?: JSONObject.NULL); put("language", language ?: JSONObject.NULL)
    }
}

internal enum class ScannerFixtureIdentityStatus { MATCH, MISMATCH, NO_OUTPUT, NOT_TESTED }

internal data class ScannerFixtureDimension(
    val status: ScannerFixtureIdentityStatus, val reason: String,
    val expected: String? = null, val observed: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("status", status.name); put("reason", reason)
        put("expected", expected ?: JSONObject.NULL); put("observed", observed ?: JSONObject.NULL)
    }
}

internal data class ScannerFixtureIdentityEvaluation(
    val decision: ScannerDecision,
    val targetCardPresent: Boolean,
    val observedCandidates: List<ScannerFixtureObservedCandidate>,
    val observedScript: String?,
    val dimensions: Map<String, ScannerFixtureDimension>,
    val acceptedCatalogueIdentity: String,
    val top3CandidateCardIdRecall: Boolean?,
) {
    fun toJson() = JSONObject().apply {
        put("version", 1); put("decision", decision.name); put("targetCardPresent", targetCardPresent)
        put("referenceSource", "preserved_public_fixture_manifest")
        put("observedCandidates", JSONArray(observedCandidates.map { it.toJson() }))
        put("observedScript", observedScript ?: JSONObject.NULL)
        put("dimensions", JSONObject().apply { dimensions.forEach { (key, value) -> put(key, value.toJson()) } })
        put("acceptedCatalogueIdentity", acceptedCatalogueIdentity)
        put("top3CandidateCardIdRecall", top3CandidateCardIdRecall ?: JSONObject.NULL)
        put("limitation", "Finite manifest catalogue consistency only; no physical foil/edition, language-classifier, market-product or production-pipeline accuracy proof")
    }
}

internal object ScannerFixtureIdentityEvaluator {
    private fun canonical(value: String?) = value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
    private fun language(value: String?) = when (canonical(value)) {
        "de", "deutsch", "german" -> "de"
        "en", "english", "englisch" -> "en"
        else -> canonical(value)
    }

    fun evaluate(
        reference: ScannerFixtureIdentityReference,
        candidates: List<ScannerFixtureObservedCandidate>,
        decision: ScannerDecision,
        ocrCollectorNumber: String?,
        ocrSetIdentifier: String?,
        observedScript: String?,
        case: String,
    ): ScannerFixtureIdentityEvaluation {
        val targetPresent = case != "blank"
        val best = candidates.firstOrNull()
        fun dimension(expected: String?, observed: String?, reason: String): ScannerFixtureDimension = when {
            !targetPresent -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED, "synthetic_blank_has_no_target_card", observed = observed)
            expected.isNullOrBlank() -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED, "reference_unavailable", observed = observed)
            observed.isNullOrBlank() -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NO_OUTPUT, "no_observed_value", expected)
            else -> ScannerFixtureDimension(if (expected == observed) ScannerFixtureIdentityStatus.MATCH else ScannerFixtureIdentityStatus.MISMATCH, reason, expected, observed)
        }
        fun localId(value: String?, sourceSet: String?) = value?.takeIf(String::isNotBlank)?.let {
            collectorIdentityForSourceLookup(it, sourceSet.orEmpty()).takeIf(String::isNotBlank)
        }
        // Unlike catalogue local-ID lookup, the printed full-number metric must retain promo prefixes.
        fun fullNumber(value: String?) = localId(value, "")?.let { local ->
            val suffix = value?.substringAfter('/', "").orEmpty()
            if (value?.contains('/') == true) "$local/${suffix.toIntOrNull() ?: "invalid:$suffix"}" else local
        }
        val printing = if (best?.finish.isNullOrBlank() || best?.variant.isNullOrBlank()) null
            else "${canonical(best?.finish)}|${canonical(best?.variant)}"
        val availablePrintings = reference.availablePrintings.map { "${canonical(it.first)}|${canonical(it.second)}" }.sorted()
        val dimensions = linkedMapOf(
            "cardId" to dimension(canonical(reference.cardId), canonical(best?.cardId), "manifest_card_id"),
            "sourceSetId" to dimension(canonical(reference.sourceSetId), canonical(best?.sourceSetId), "manifest_source_set_not_printed_code"),
            "candidateCollectorLocalId" to dimension(localId(reference.collectorNumber, reference.sourceSetId), localId(best?.collectorNumber, best?.sourceSetId), "candidate_collector_local_id_only"),
            "ocrCollectorLocalId" to dimension(localId(reference.collectorNumber, reference.sourceSetId), localId(ocrCollectorNumber, reference.sourceSetId), "ocr_collector_local_id_only"),
            "ocrFullCollectorNumber" to dimension(fullNumber(reference.collectorNumber), fullNumber(ocrCollectorNumber), "ocr_local_id_and_printed_denominator_or_promo_prefix"),
            "catalogueLanguage" to dimension(language(reference.language), language(best?.language), "candidate_metadata_language_not_detected_language"),
            "cataloguePrintingCompatibility" to when {
                !targetPresent -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED, "synthetic_blank_has_no_target_card", observed = printing)
                availablePrintings.isEmpty() -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED, "manifest_has_no_printing_availability", observed = printing)
                printing == null -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NO_OUTPUT, "no_candidate_finish_or_variant", availablePrintings.joinToString(";"))
                else -> ScannerFixtureDimension(if (printing in availablePrintings) ScannerFixtureIdentityStatus.MATCH else ScannerFixtureIdentityStatus.MISMATCH,
                    "catalogue_availability_only_not_physical_printing_accuracy", availablePrintings.joinToString(";"), printing)
            },
            "printedSetCode" to ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED,
                "no_independently_annotated_printed_set_code_tcg_online_is_not_ground_truth", observed = ocrSetIdentifier),
            "physicalPrinting" to ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED,
                "flat_artwork_has_no_verified_foil_edition_or_stamp_label", observed = printing),
            "decisionSafety" to when (case) {
                "blank" -> ScannerFixtureDimension(if (decision == ScannerDecision.REJECTED) ScannerFixtureIdentityStatus.MATCH else ScannerFixtureIdentityStatus.MISMATCH,
                    "synthetic_blank_must_not_be_a_card_match", "REJECTED", decision.name)
                "manual_whole" -> ScannerFixtureDimension(if (decision != ScannerDecision.ACCEPTED) ScannerFixtureIdentityStatus.MATCH else ScannerFixtureIdentityStatus.MISMATCH,
                    "manual_whole_requires_review_or_rejection", "AMBIGUOUS_OR_REJECTED", decision.name)
                else -> ScannerFixtureDimension(ScannerFixtureIdentityStatus.NOT_TESTED,
                    "no_invented_expected_decision_for_transformed_artwork", observed = decision.name)
            },
        )
        val catalogueDimensions = listOf("cardId", "sourceSetId", "candidateCollectorLocalId", "catalogueLanguage", "cataloguePrintingCompatibility")
            .map { dimensions.getValue(it).status }
        val accepted = when {
            decision != ScannerDecision.ACCEPTED -> "NOT_ACCEPTED"
            !targetPresent || ScannerFixtureIdentityStatus.MISMATCH in catalogueDimensions -> "INCORRECT_MEASURED_IDENTITY"
            catalogueDimensions.all { it == ScannerFixtureIdentityStatus.MATCH } -> "CORRECT_MEASURED_IDENTITY"
            else -> "INCOMPLETE_MEASURED_IDENTITY"
        }
        return ScannerFixtureIdentityEvaluation(decision, targetPresent, candidates, observedScript, dimensions, accepted,
            if (!targetPresent) null else candidates.take(3).any { canonical(it.cardId) == canonical(reference.cardId) })
    }
}
