package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.model.*
import com.dominik.cardvault.ui.theme.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun alternativesDifferMaterially(options: List<CardPriceAlternative>): Boolean {
    val amounts = options.map { it.amountMinor }.filter { it > 0 }
    if (amounts.size < 2) return false
    val lowest = amounts.min()
    val highest = amounts.max()
    return highest - lowest >= 100L && highest.toDouble() / lowest >= 1.5
}

/** Choosing a radio row is only a draft; saving always requires explicit confirmation. */
@Composable internal fun CardPriceAlternativePicker(
    card: CollectionCard,
    product: CatalogSearchResult?,
    saved: CardPriceSelection?,
    enabled: Boolean,
    onConfirm: (CardPriceSelection) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val hasTrendPrice = card.trendValueEur != null || product?.realTrendFor(card.finish) != null
    val options = if (hasTrendPrice || product == null || card.sourceSetId.isNullOrBlank() ||
        card.sourceCardId.isNullOrBlank() || isQuarantinedCardmarketIdentity(card.sourceCardId, product.productId))
        emptyList() else product.priceAlternativesFor(card.finish)
    // Removing a saved snapshot must not depend on today's guide or product availability.
    if (options.isEmpty() && saved == null) return
    var draftMetric by rememberSaveable(card.id, card.finish, card.variant, product?.productId) { mutableStateOf<String?>(null) }
    val chosen = options.firstOrNull { it.metric.wireValue == draftMetric }
    val money = remember { NumberFormat.getCurrencyInstance(Locale.GERMANY) }
    DomiGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (options.isNotEmpty()) {
                Text("Kein Trendpreis – Vergleichswert wählen", color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Nur Werte für dieses Cardmarket-Produkt und diese Variante. Deine Auswahl ist kein Trendpreis und keine Verkaufsgarantie.", color = VaultTextMuted, fontSize = 11.sp)
                if (alternativesDifferMaterially(options)) {
                    val lowest = options.minOf { it.valueEur }
                    val highest = options.maxOf { it.valueEur }
                    Text("Die Werte weichen deutlich ab: ${money.format(lowest)} bis ${money.format(highest)}. Bitte bewusst vergleichen und bestätigen.", color = MewRoseSoft, fontSize = 12.sp)
                }
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draftMetric == option.metric.wireValue, enabled = enabled,
                            onClick = { draftMetric = option.metric.wireValue })
                        Column(Modifier.weight(1f)) {
                            Text(option.metric.label, color = VaultText, fontSize = 12.sp)
                            Text("Quelle: ${priceSelectionDate(option.sourceAt)}", color = VaultTextMuted, fontSize = 10.sp)
                        }
                        Text(money.format(option.valueEur), color = MewIce, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Text("Niedrigpreise sind Angebotswerte. Sprache und Zustand können abweichen. Sobald ein Trendpreis verfügbar ist, wird dieser wieder verwendet.", color = VaultTextMuted, fontSize = 10.sp)
                Button(onClick = { chosen?.let { onConfirm(it.toSelection(card)) } }, enabled = enabled && chosen != null) {
                    Text("Als Vergleichswert bestätigen")
                }
            }
            saved?.let {
                Text("Gespeicherter Vergleichswert: ${it.metric.label} · ${money.format(it.valueEur)}", color = MewIce, fontSize = 11.sp)
                if (hasTrendPrice) Text("Der verfügbare Trendpreis wird für die Bewertung verwendet.", color = VaultTextMuted, fontSize = 10.sp)
                else if (!it.matches(card.copy(externalProductId = product?.productId ?: card.externalProductId)))
                    Text("Diese gespeicherte Auswahl passt nicht mehr zur Kartenidentität und wird nicht für die Bewertung verwendet.", color = VaultTextMuted, fontSize = 10.sp)
                if (onClear != null) TextButton(onClick = onClear, enabled = enabled) { Text("Vergleichswert entfernen") }
            }
        }
    }
}

private fun priceSelectionDate(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY))
