package com.dominik.cardvault.model

data class CollectionCard(val id: String, val name: String, val set: String, val number: String, val language: CardLanguage, val condition: String, val quantity: Int, val currentValueEur: Double, val changePercent: Double, val accent: Long)
enum class CardLanguage(val label: String) { GERMAN("Deutsch"), ENGLISH("Englisch"), JAPANESE("Japanisch"), CHINESE("Chinesisch") }
data class PortfolioPoint(val label: String, val value: Double)
data class PortfolioSummary(val cards: List<CollectionCard>, val history: List<PortfolioPoint>) {
    val totalValue get() = cards.sumOf { it.currentValueEur * it.quantity }
    val totalCards get() = cards.sumOf { it.quantity }
    val changePercent get() = if (history.size > 1) ((history.last().value - history.first().value) / history.first().value) * 100 else 0.0
}

/** Offline demo source; a Room-backed implementation can replace it later. */
interface CollectionRepository { fun portfolio(): PortfolioSummary }
object SampleCollectionRepository : CollectionRepository {
    private val cards = listOf(
        CollectionCard("sv3-198", "Charizard ex", "Obsidian Flames", "198/197", CardLanguage.ENGLISH, "Nahezu neuwertig", 1, 38.40, 4.8, 0xFFF06445),
        CollectionCard("sv2a-025", "Pikachu", "Pokémon Card 151", "025/165", CardLanguage.JAPANESE, "Nahezu neuwertig", 2, 7.85, 1.4, 0xFFFFC83D),
        CollectionCard("base-004", "Glurak", "Basis", "4/102", CardLanguage.GERMAN, "Sehr gut", 1, 182.00, -2.1, 0xFF4D86C6),
        CollectionCard("s12a-205", "Mew", "VSTAR Universe", "205/172", CardLanguage.JAPANESE, "Nahezu neuwertig", 1, 31.60, 6.2, 0xFFCE77B7),
    )
    private val history = listOf(PortfolioPoint("Mar", 232.0), PortfolioPoint("Apr", 241.0), PortfolioPoint("May", 238.5), PortfolioPoint("Jun", 251.0), PortfolioPoint("Jul", 259.2), PortfolioPoint("Aug", 267.7))
    override fun portfolio() = PortfolioSummary(cards, history)
}

enum class GradingArea { CENTERING, CORNERS, EDGES, SURFACE }
data class GradingFinding(val area: GradingArea, val summary: String, val confidence: Float)
data class GradeRange(val minimum: Int, val maximum: Int, val confidence: Float)
data class GradingCosts(val feeEur: Double, val shippingEur: Double, val insuranceEur: Double) {
    val totalEur get() = feeEur + shippingEur + insuranceEur
}
data class GradingPrecheck(
    val range: GradeRange,
    val findings: List<GradingFinding>,
    val rawValueEur: Double,
    val plausibleGradedValuesEur: Map<Int, Double>,
    val costs: GradingCosts,
)
