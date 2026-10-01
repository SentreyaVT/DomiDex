# 30th-anniversary catalogue supplement — 2026-09-22

## Scope and identity

The supplement adds three independently numbered RGB Mew cards to `30th` and
17 numbered anniversary promos (`MEP 094` through `MEP 110`) to `mep`.
`R/RGB`, `G/RGB`, and `B/RGB` are the printed collector identities; they are not
printings of Mew-ex `158/128`. The main set's printed denominator remains 128.

The canonical local RGB IDs are `30th-rgb-r`, `30th-rgb-g`, and `30th-rgb-b`.
Promo IDs follow the existing app convention `mep-094` through `mep-110`, so a
future upstream record does not create a second identity for an already owned
promo. These are maintained app records, **not a claim that the TCGdex endpoints
currently exist**. The merge preserves existing upstream records and fills only
missing artwork. The early detail fallback applies only to explicitly tagged
local records, leaving future upstream finish/stamp metadata authoritative.

## Upstream gap

Live TCGdex checks on this date:

- [German 30th set](https://api.tcgdex.net/v2/de/sets/30th) and
  [English 30th set](https://api.tcgdex.net/v2/en/sets/30th): exactly 158 cards,
  no RGB Mew records; `30th-158` is Mew-ex, rarity `Futuristic Rare`.
- [German MEP set](https://api.tcgdex.net/v2/de/sets/mep): 001–088.
  [English MEP set](https://api.tcgdex.net/v2/en/sets/mep): same plus
  `mep-Museum`. None of these brief records supplied artwork.
- `30th-159`, `30th-160`, `30th-161`, and `mep-089` returned HTTP 404.

## Card and image evidence

All 20 configured image URLs returned actual images and were visually inspected
for the matching name and printed collector number on 2026-09-22. Eleven promo
images are the official German Pokémon images. The other six promo images and
three RGB images are English images from Serebii's individual card records.
English artwork is an explicit image fallback; the displayed Pokémon names are
German. No card images are bundled in the APK.

| Printed number | German name | Verified image URL |
| --- | --- | --- |
| R/RGB | Mew | https://www.serebii.net/card/30thcelebration/rgb1.jpg |
| G/RGB | Mew | https://www.serebii.net/card/30thcelebration/rgb2.jpg |
| B/RGB | Mew | https://www.serebii.net/card/30thcelebration/rgb3.jpg |
| MEP 094 | Alola-Kokowei | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_94.png |
| MEP 095 | Lucario | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_95.png |
| MEP 096 | Lavados | https://www.serebii.net/card/megapromos/96.jpg |
| MEP 097 | Arktos | https://www.serebii.net/card/megapromos/97.jpg |
| MEP 098 | Zapdos | https://www.serebii.net/card/megapromos/98.jpg |
| MEP 099 | Quajutsu-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_99.png |
| MEP 100 | Feelinara-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_100.png |
| MEP 101 | Nidorina | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_101.png |
| MEP 102 | Victini | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_102.png |
| MEP 103 | Zeraora | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_103.png |
| MEP 104 | Mewtu | https://www.serebii.net/card/megapromos/104.jpg |
| MEP 105 | Mew | https://www.serebii.net/card/megapromos/105.jpg |
| MEP 106 | Ditto | https://www.serebii.net/card/megapromos/106.jpg |
| MEP 107 | Pikachu-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_107.png |
| MEP 108 | Psiana-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_108.png |
| MEP 109 | Pikachu-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_109.png |
| MEP 110 | Nachtara-ex | https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_110.png |

Serebii's [RGB R card](https://www.serebii.net/card/30thcelebration/RGB1.shtml),
[RGB G card](https://www.serebii.net/card/30thcelebration/RGB2.shtml), and
[RGB B card](https://www.serebii.net/card/30thcelebration/RGB3.shtml) explicitly
link those images. Its [MEP card list](https://www.serebii.net/card/megapromos/)
links each zero-padded card detail URL, such as
[Mew 105](https://www.serebii.net/card/megapromos/105.shtml), which supplies the
unpadded image URL above. All six fallback images show the anniversary stamp.

Official German image URLs using the same MEP filename pattern returned HTTP
404 for 096, 097, 098, 104, 105, and 106. The equivalent tested English Pokémon
image pattern also returned 404. The tested Cardmarket S3 RGB image addresses
returned 403 and are not used. TCG Collector's direct page request required a
browser challenge and is not used as an image source.

Cardmarket's exact numbered pages independently corroborate the identities,
including [red RGB Mew](https://www.cardmarket.com/de/Pokemon/Products/Singles/30th-Celebration/Mew-V1-30CRRGB),
[Alola-Kokowei 094](https://www.cardmarket.com/de/Pokemon/Products/Singles/MEP-Black-Star-Promos/Alolan-Exeggutor-MEP094),
[Nidorina V1 101](https://www.cardmarket.com/de/Pokemon/Products/Singles/MEP-Black-Star-Promos/Nidorina-V1-MEP101),
[Nidorina V2 101](https://www.cardmarket.com/de/Pokemon/Products/Singles/MEP-Black-Star-Promos/Nidorina-V2-MEP101),
[Mew 105](https://www.cardmarket.com/de/Pokemon/Products/Singles/MEP-Black-Star-Promos/Mew-MEP105),
and [Pikachu-ex 107](https://www.cardmarket.com/de/Pokemon/Products/Singles/MEP-Black-Star-Promos/Pikachu-ex-MEP107).
Nidorina's regular and Pokémon Center printings are represented separately for
manual printing selection; the shared image is the regular German card. No
jumbo printing or extra numbered card is inferred.

## Exact price links and limitations

The [public Cardmarket product catalogue](https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json)
was created at `2026-09-22T13:02:01+02:00`. It contains product IDs, expansion
IDs and attack/ability signatures, but no collector numbers, product links,
printing descriptions or image URLs. The promo expansion is 6232.

Unique promo products are matched using the independently confirmed printed
number/name plus exact attack/ability signature visible on the card:

| MEP number | Product ID | Matching English signature |
| --- | --- | --- |
| 094 | 895609 | Alolan Exeggutor — Scale Up / Mega Drain |
| 095 | 895610 | Lucario — Aura Sphere |
| 096 | 895606 | Moltres — Fiery Flapping / Fire Spin |
| 097 | 895607 | Articuno — Frosty Flapping / Hail |
| 098 | 895608 | Zapdos — Flash-Pop Flapping / Thundering Lightning |
| 102 | 895613 | Victini — Call for Family / V-Flame |
| 103 | 895614 | Zeraora — Rapid Draw / Electrobullet |
| 104 | 895620 | Mewtwo — Empower / Psydrive |
| 105 | 895621 | Mew — Psychic / MEP |
| 106 | 895619 | Ditto — Surprisingly Transform |
| 107 | 895615 | Pikachu ex — Pika-Pika Parade / Thunderbolt |
| 108 | 895616 | Espeon ex — Solar Beatdown |
| 109 | 895617 | Pikachu ex — Zip-Zap Frenzy / Thunder |
| 110 | 895618 | Umbreon ex — Lunatic Claw |

RGB Mew products 909509, 909510, and 909511 all share `Mew [Psychic | 30C]`
and metacard 465170 in expansion 6601. This payload cannot map those products
to R/G/B, so no RGB product link is assigned. Likewise, exact product links
remain unset for Quajutsu-ex (895611/910855), Feelinara-ex (895612/910856), and
Nidorina (895604/895605), whose duplicate product/printing mapping is unresolved.
No prices or availability claims are embedded in this supplement. Announced
promos can appear before their retail product releases.

The existing 27 first-partner promos MEP 037–063 remain in the upstream promo
catalogue. Retailer/event stamp variants of existing main-set cards (including
Eevee 116 and Mewtwo 063) are outside this new-numbered-card supplement and are
not silently added as extra numbered identities.

## Integration and validation

- German/English set responses are supplemented before sorting and indexing.
- The `30th` total includes the three RGB cards (161); printed denominator 128
  and the separate 30-card Classic Collection remain unchanged.
- Exact `30C` + `R/RGB`, `G/RGB`, `B/RGB` lookup resolves only the main set.
  Letter-only index keys no longer acquire an invented trailing zero. An input
  such as `R/128` is deliberately not accepted as an RGB identity.
- 51 targeted JVM tests passed, including catalogue merge, all 17 promo
  selections, RGB identity, distinct owned Binder slots, and safe price mapping.
- One read-only Android emulator integration test passed: every one of the 20
  new records resolves from the live set catalogue, and all 20 remote images
  return HTTP 200 and decode as real portrait bitmaps on Android.
- `assembleDebug` and `assembleDebugAndroidTest` passed. Debug APK SHA-256:
  `8D088023DF40688B25850427D5830DB6CBF8494C23F9C50FAAE19CA47F8A2E63`.
- No physical-device installation, user collection changes, Firebase writes,
  release/AAB build, version bump, commit, push, or deployment was performed.
