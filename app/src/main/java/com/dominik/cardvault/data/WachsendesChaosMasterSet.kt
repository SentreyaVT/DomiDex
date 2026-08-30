package com.dominik.cardvault.data

internal data class MasterSetCard(
    val id: String,
    val name: String,
    val number: String,
    val finish: String,
    val variant: String,
    val imageUrl: String,
)

/**
 * Complete German ME04 test binder: 122 numbered cards from the official
 * Pokemon checklist plus the 72 source-confirmed reverse-holo variants (194 slots).
 */
internal object WachsendesChaosMasterSet {
    private val names = """
Hornliu
Kokuna
Bibor-ex
Venuflibis
Igamaro
Igastarnish
Brigaron
Vulpix
Vulnona
Ho-Oh
Fynx
Rutena
Fennexis
Leufeo
Mega-Pyroleo-ex
Remoraid
Octillery
Botogel
Keldeo
Froxy
Amphizel
Mega-Quajutsu-ex
Arktip
Arktilas
Reißlaus
Tectass
Voltilamm
Waaty
Ampharos
Emolga
Deoxys
Deoxys
Deoxys
Deoxys
Mega-Floette-ex
Psiau
Psiaugon
Paragoni
Trombork
Irrbis
Pumpdjinn-ex
Xerneas
Mogelbaum
Phanpy
Donphan
Puppance
Lepumentas
Mega-Galagladi-ex
Zubat
Golbat
Iksbat
Baldorfish
Skunkapuh
Skuntank
Rabigator-ex
Unratütox
Deponitox
Algitt
Tanhel
Metang
Metagross
Kastadur
Tentantel
Kobalium-ex
Mega-Tandrak-ex
Viscora
Viscargot
Viscogon
Tauros
Nagelotz
Kukmarda
Picochilla
Chillabell-ex
Krisenschutz
Ange-Floette
Azetts Gelassenheit
Matière
Großes Fangnetz
Gipson
Prismaturm
Micas Aufführung
Spezielle Rote Karte
Transformationsfoliant
Sprudelnde Wasser-Energie
Magnetische Metall-Energie
Nitro-Feuer-Energie
Igamaro
Froxy
Amphizel
Ampharos
Xerneas
Lepumentas
Iksbat
Metang
Viscargot
Tauros
Kukmarda
Bibor-ex
Mega-Pyroleo-ex
Mega-Quajutsu-ex
Mega-Floette-ex
Pumpdjinn-ex
Kobalium-ex
Mega-Tandrak-ex
Chillabell-ex
Azetts Gelassenheit
Matière
Energie-Zugewinn
Rieseneis
Gipson
Prismaturm
Micas Aufführung
Spezielle Rote Karte
Surfstrand
Ausrüstungsentsorger
Mega-Quajutsu-ex
Mega-Floette-ex
Mega-Tandrak-ex
Chillabell-ex
Azetts Gelassenheit
Micas Aufführung
Mega-Quajutsu-ex
""".trim().lines()

    private val reverseNumbers = setOf(
        1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 17, 18, 19, 20, 21,
        23, 24, 25, 26, 27, 28, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 42,
        43, 44, 45, 46, 47, 49, 50, 52, 53, 54, 56, 57, 58, 59, 60, 61, 62,
        63, 66, 67, 69, 70, 71, 72, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83,
        84, 85, 86,
    )

    val cards: List<MasterSetCard> = names.flatMapIndexed { index, name ->
        val number = index + 1
        val printedNumber = number.toString().padStart(3, '0')
        val imageUrl = "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/site_search/ME04/ME04_DE_${number}.png"
        buildList {
            add(MasterSetCard("me04-$printedNumber-normal", name, "$printedNumber/122", "Normal", "Standard", imageUrl))
            if (number in reverseNumbers) add(MasterSetCard("me04-$printedNumber-reverse", name, "$printedNumber/122", "Reverse Holo", "Reverse Holo", imageUrl))
        }
    }
}
