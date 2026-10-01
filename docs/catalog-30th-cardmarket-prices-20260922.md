# 30th Celebration Cardmarket identity evidence — 2026-09-22

## Result and scope

This audit supplies 77 additional maintained card-to-product bridges: 61 cards in
`30th` and 16 in `30th-c`. Each joins an exact TCGdex source card identity to one
Cardmarket product in expansion **6601**, using the card's English name and
attack/ability signature. These are evidence-backed registry candidates, not
direct verification of a numbered Cardmarket product page.

No mapping is derived from card-name similarity, product-ID ordering, creation
time, or rarity ordering. For example, Ninetales `30th-010` maps to **907736**,
not the ID that a sequential-number assumption would suggest.

These bridges concern the ordinary numbered card product. They do not establish
a language-specific price, foil/stamp/edition printing, condition, guide-price
availability, or current sale availability. A separately identified printing
must retain its own explicit source identity; a missing special-printing ID must
not borrow one of these ordinary-product IDs.

## Sources and audit time

### Android fix and remaining limits

- Applied the 77 additional reviewed bridges to the existing exact-ID registry;
  total coverage is **116 of 188 numbered main/Classic cards** (93 main, 23 Classic).
- Made ordinary-printing eligibility case-insensitive so the English fallback's
  `standard` retains the same link as German `Standard`. Neither finish/edition
  labels nor explicitly supplied upstream product IDs are changed.
- At **2026-09-22T20:22:54.158Z**, all 116 product IDs had positive `trend`
  values in the official public price guide; none were absent or null.
- Supplemental MEP 094–098 already carry distinct exact IDs and have current
  guide trends. MEP 102–110 have their own product IDs but **null trends in the
  guide**. There is no honest price to supply for those nine promos yet.
- Remaining 72 numbered anniversary identities, the three RGB colour identities,
  and MEP 099–101 remain unresolved where printing/product evidence is ambiguous.
  No namesake, main-set, reverse, stamped or RGB price was borrowed.
- This does not relink previously saved entries without a product ID. No stored
  user collection data, Firebase settings or deployment state was changed.
- Existing price sync continues to provide values; no static live prices were
  embedded in production code. Only source identities were added.

### Verification of the applied Android patch

- Before the patch: focused regression suite reproduced two failures (missing
  reviewed identity and lowercase English detail losing the existing Mew link).
- After the patch: **45 targeted unit tests passed**, including all 116 reviewed
  identities through German/English generated details, cache reuse, explicit
  upstream precedence, stamp/foil exclusion and existing set/variant price tests.
- **8 emulator instrumentation tests passed** on `emulator-5554`: grey preview,
  price-sync refresh, actual add action, isolated Room storage, portfolio value,
  owned-card reopen, slot preservation and no reverse/namesake price borrowing.
  Examples: Owei `30th-001`, Mew `30th-065`, MEP 094, unavailable-price MEP 105,
  and existing above-main-set card Freunde aus Paldea `sv08.5-137`.
- UI/storage tests use explicitly synthetic price fixtures and in-memory Room;
  live guide availability was verified separately above. No real account or
  collection writes, Firebase changes, or physical-device installation.
- Debug APK and instrumentation APK built successfully. Debug lint: zero errors,
  167 warnings and 2 hints in the current worktree. No release version change,
  new signed AAB, publication, commit or push in this fix run. The previously
  prepared signed AAB does **not** include this later price fix.

The complete data audit ran from **2026-09-22T20:17:15.209Z** to **2026-09-22T20:17:18.554Z** (UTC).

- [Cardmarket public Pokémon product catalogue](https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json):
  `createdAt=2026-09-22T13:02:01+0200`; 74,183 total rows;
  **191** rows in expansion 6601. Only that exact expansion is eligible.
- [TCGdex English main-set index](https://api.tcgdex.net/v2/en/sets/30th): **158** cards.
- [TCGdex English Classic index](https://api.tcgdex.net/v2/en/sets/30th-c): **30** cards.
- All **188** card-detail URLs listed by those two indices returned HTTP 200.
  The 77 accepted additional detail URLs are linked individually in the tables below.

The following SHA-256 hashes identify the exact response bodies used in the audit:

| Payload | SHA-256 |
| --- | --- |
| Cardmarket product catalogue | `8221d3cccba53e91828596c04a15a25cf462b0324a92a405ebfb6a108f66c2aa` |
| TCGdex main-set index | `97174c210f6022d91bf23b7b646f227eeff3f31c6b67b43dbbd41b7f7c4c6b5a` |
| TCGdex Classic index | `289a0cfa752ed0073457bf53c67fdd84f9790f811ae1f5686a657be5b57de1a6` |

Cardmarket's download exposes `idProduct`, `name`, `idExpansion`, `idMetacard`,
`idCategory`, `categoryName`, and `dateAdded`; it does not supply structured
collector numbers, rarity, finish, or numbered-product-page URLs.

## Matching and one-to-one verification

1. Read every English card detail in both indices, preserving its exact source
   ID, source set and local collector ID.
2. Form the signature from all `abilities[].name` and `attacks[].name` values.
   Compare complete token collections after sorting, not substrings. Strip only
   the `{` and `}` typography around TCGdex's `{GX}` attack suffix.
3. Require the exact English card name as the Cardmarket product-name prefix.
   Parse the complete bracketed Cardmarket signature into ` | `-separated tokens.
   Exclude the standalone terminal `30` title disambiguator from the comparison;
   it is not treated as an attack or a collector number.
4. Require exactly one source card with that name/signature across **all 188**
   source cards, and exactly one matching product across **all 191** expansion
   rows. Reject absent signatures and every duplicate match.
5. Check accepted product IDs for uniqueness and compare the 77 additions with
   the pre-audit 39-entry registry.

| Check | Result |
| --- | --- |
| Source detail coverage | 188 / 188 |
| Cardmarket expansion rows included in duplicate detection | 191 / 191 |
| Strict signature matches | 110 source cards / 110 distinct product IDs |
| Proposed additional matches | 77 source cards / 77 distinct product IDs |
| New main / Classic matches | 61 / 16 |
| Product-ID overlap between additions and existing 39 entries | 0 |
| Combined maintained registry if additions are applied | 116 source cards / 116 distinct product IDs |

The strict audit reproduces 33 existing entries and supplies 77 new ones. Six
pre-existing Classic bridges use additional identity evidence or title
normalization outside this strict matcher and were not re-established by this
audit. The audit does **not** establish a complete 188-to-191 bijection: 78
source cards fail the strict matcher, including those six existing bridges.

### Limit of the completeness claim

Within the currently published datasets, each proposed bridge is unique. An
extra Cardmarket product with the same normalized name/signature would reject
the candidate even if its corresponding printing were absent from TCGdex,
because all 191 expansion products participate in the comparison.

**It is not possible to confirm that unlisted printings cannot collide.** A
printing absent from both catalogues, represented under a different product
title, grouped under another expansion, or added later is outside what this
snapshot can prove. TCGdex's generated ordinary variant is not evidence that
all physical foils, stamps or editions are enumerated. These static bridges
therefore must remain restricted to an ordinary card identity and must not be
used as a universal name/attack-based runtime linking rule. Explicit future
upstream printing identities take precedence.

## Additional main-set bridges

The source collector identity is the numeric suffix of each `30th-...` ID,
with printed denominator 128. The final column preserves the exact Cardmarket
catalogue title, including any non-attack disambiguator.

| Exact source card and detail evidence | Product ID | Exact Cardmarket name/signature |
| --- | ---: | --- |
| [`30th-001`](https://api.tcgdex.net/v2/en/cards/30th-001) | 907607 | Exeggcute [Hypnosis] |
| [`30th-003`](https://api.tcgdex.net/v2/en/cards/30th-003) | 907609 | Volbeat [Luring Glow \| Bug Buzz] |
| [`30th-004`](https://api.tcgdex.net/v2/en/cards/30th-004) | 907610 | Illumise [Supereffective Pheromones \| Ram] |
| [`30th-005`](https://api.tcgdex.net/v2/en/cards/30th-005) | 907611 | Tropius [Rally Back \| Cutting Wind] |
| [`30th-006`](https://api.tcgdex.net/v2/en/cards/30th-006) | 907612 | Cherubi [Hide \| Flop] |
| [`30th-007`](https://api.tcgdex.net/v2/en/cards/30th-007) | 907613 | Cherrim [Energy Gift \| Leafage] |
| [`30th-008`](https://api.tcgdex.net/v2/en/cards/30th-008) | 907614 | Vivillon [Guiding Dance \| Poison Powder] |
| [`30th-009`](https://api.tcgdex.net/v2/en/cards/30th-009) | 907615 | Vulpix [Wild Kick] |
| [`30th-010`](https://api.tcgdex.net/v2/en/cards/30th-010) | 907736 | Ninetales [Flame Tail \| 30] |
| [`30th-012`](https://api.tcgdex.net/v2/en/cards/30th-012) | 907618 | Ho-Oh [Sacred Breath \| Fire Wing] |
| [`30th-013`](https://api.tcgdex.net/v2/en/cards/30th-013) | 907619 | Victini [Call for Family \| V-Flame] |
| [`30th-014`](https://api.tcgdex.net/v2/en/cards/30th-014) | 907620 | Reshiram [Slash \| Laser Flame] |
| [`30th-016`](https://api.tcgdex.net/v2/en/cards/30th-016) | 907622 | Slowpoke [Well-Hidden \| Water Gun] |
| [`30th-019`](https://api.tcgdex.net/v2/en/cards/30th-019) | 907625 | Kyogre [Hydro Pump] |
| [`30th-020`](https://api.tcgdex.net/v2/en/cards/30th-020) | 907627 | Palkia [Wormhole \| 30] |
| [`30th-022`](https://api.tcgdex.net/v2/en/cards/30th-022) | 907629 | Wishiwashi [Counterattack Grouping \| Surprise Attack] |
| [`30th-056`](https://api.tcgdex.net/v2/en/cards/30th-056) | 907663 | Zekrom [Slash \| Nitro Thunder] |
| [`30th-057`](https://api.tcgdex.net/v2/en/cards/30th-057) | 907664 | Zeraora [Rapid Draw \| Electrobullet] |
| [`30th-058`](https://api.tcgdex.net/v2/en/cards/30th-058) | 907665 | Toxel [Ram \| 30] |
| [`30th-059`](https://api.tcgdex.net/v2/en/cards/30th-059) | 907666 | Toxtricity [Mach Bolt] |
| [`30th-063`](https://api.tcgdex.net/v2/en/cards/30th-063) | 907670 | Mewtwo [Empower \| Psydrive] |
| [`30th-067`](https://api.tcgdex.net/v2/en/cards/30th-067) | 907674 | Marill [Tackle] |
| [`30th-068`](https://api.tcgdex.net/v2/en/cards/30th-068) | 907675 | Azumarill [Body Slam] |
| [`30th-069`](https://api.tcgdex.net/v2/en/cards/30th-069) | 907676 | Espeon [Miraculous Shine \| Super Psy Bolt] |
| [`30th-070`](https://api.tcgdex.net/v2/en/cards/30th-070) | 907677 | Espeon ex [Solar Beatdown] |
| [`30th-072`](https://api.tcgdex.net/v2/en/cards/30th-072) | 907679 | Unown [Mysterious Signal] |
| [`30th-074`](https://api.tcgdex.net/v2/en/cards/30th-074) | 907681 | Cresselia [Aurora Gain \| Lunar Blast] |
| [`30th-076`](https://api.tcgdex.net/v2/en/cards/30th-076) | 907683 | Xerneas [Geonavigation \| Aurora Horns] |
| [`30th-077`](https://api.tcgdex.net/v2/en/cards/30th-077) | 907684 | Comfey [Comforting Aroma \| Magical Shot] |
| [`30th-078`](https://api.tcgdex.net/v2/en/cards/30th-078) | 907685 | Cosmog [Splash \| 30] |
| [`30th-079`](https://api.tcgdex.net/v2/en/cards/30th-079) | 907686 | Cosmoem [Stiffen \| 30] |
| [`30th-080`](https://api.tcgdex.net/v2/en/cards/30th-080) | 907687 | Lunala [Midnight Ray \| Lunar Blast] |
| [`30th-081`](https://api.tcgdex.net/v2/en/cards/30th-081) | 907688 | Gimmighoul [Strolls So Much] |
| [`30th-082`](https://api.tcgdex.net/v2/en/cards/30th-082) | 907689 | Groudon [Break Ground] |
| [`30th-083`](https://api.tcgdex.net/v2/en/cards/30th-083) | 907690 | Lucario [Aura Sphere] |
| [`30th-084`](https://api.tcgdex.net/v2/en/cards/30th-084) | 907691 | Seismitoad [Quaking Fist \| Mega Punch] |
| [`30th-086`](https://api.tcgdex.net/v2/en/cards/30th-086) | 907693 | Koraidon [Low Kick \| Collision Course] |
| [`30th-088`](https://api.tcgdex.net/v2/en/cards/30th-088) | 907695 | Nidorina [Share Happiness \| Bite] |
| [`30th-091`](https://api.tcgdex.net/v2/en/cards/30th-091) | 907698 | Umbreon [Retaliate \| Darkness Fang] |
| [`30th-092`](https://api.tcgdex.net/v2/en/cards/30th-092) | 907699 | Umbreon ex [Lunatic Claw] |
| [`30th-093`](https://api.tcgdex.net/v2/en/cards/30th-093) | 907700 | Murkrow [Clumsily Clutch] |
| [`30th-095`](https://api.tcgdex.net/v2/en/cards/30th-095) | 907702 | Zorua [Darkness Fang] |
| [`30th-096`](https://api.tcgdex.net/v2/en/cards/30th-096) | 907703 | Zoroark [Nighttime Byway \| Slashing Claw] |
| [`30th-097`](https://api.tcgdex.net/v2/en/cards/30th-097) | 907704 | Deino [Gnaw \| Headbutt] |
| [`30th-098`](https://api.tcgdex.net/v2/en/cards/30th-098) | 907705 | Zweilous [Bite \| Hammer In] |
| [`30th-099`](https://api.tcgdex.net/v2/en/cards/30th-099) | 907706 | Hydreigon [Three-Headed Bite \| Pitch-Black Fangs] |
| [`30th-100`](https://api.tcgdex.net/v2/en/cards/30th-100) | 907707 | Yveltal [Life-Locked \| Dark Cutter] |
| [`30th-103`](https://api.tcgdex.net/v2/en/cards/30th-103) | 907710 | Dialga [Reversed Clock \| Heavy Impact] |
| [`30th-104`](https://api.tcgdex.net/v2/en/cards/30th-104) | 907711 | Ferrothorn [Spike Sting \| Kaboom Needles] |
| [`30th-105`](https://api.tcgdex.net/v2/en/cards/30th-105) | 907712 | Solgaleo [Sunrise \| Sunsteel Strike] |
| [`30th-106`](https://api.tcgdex.net/v2/en/cards/30th-106) | 907713 | Zacian [Hardened Blade \| Slashing Strike] |
| [`30th-107`](https://api.tcgdex.net/v2/en/cards/30th-107) | 907714 | Zamazenta [Fend Off \| Shield Press] |
| [`30th-110`](https://api.tcgdex.net/v2/en/cards/30th-110) | 907717 | Jangmo-o [Screech \| Dragon Claw] |
| [`30th-111`](https://api.tcgdex.net/v2/en/cards/30th-111) | 907718 | Hakamo-o [Sharp Fang \| Dragon Claw] |
| [`30th-114`](https://api.tcgdex.net/v2/en/cards/30th-114) | 907721 | Kangaskhan [Rage \| Mega Punch] |
| [`30th-115`](https://api.tcgdex.net/v2/en/cards/30th-115) | 907722 | Ditto [Surprisingly Transform] |
| [`30th-116`](https://api.tcgdex.net/v2/en/cards/30th-116) | 907723 | Eevee [Fetch and Hide \| Tackle] |
| [`30th-119`](https://api.tcgdex.net/v2/en/cards/30th-119) | 907726 | Snorlax [Good Sleep \| Collapse] |
| [`30th-120`](https://api.tcgdex.net/v2/en/cards/30th-120) | 907727 | Igglybuff [Bouncy Circle] |
| [`30th-123`](https://api.tcgdex.net/v2/en/cards/30th-123) | 907730 | Hisuian Zoroark [Scratch \| Swirling Resentment] |
| [`30th-124`](https://api.tcgdex.net/v2/en/cards/30th-124) | 907731 | Minior [Shoot Meteors] |

## Additional Classic bridges

Classic source IDs `001`–`030` are subset positions, not the historic numbers
printed on the cards. The application retains the separately maintained printed
collector-number mapping in `PokemonVisualCatalog.kt`; this audit does not
renumber those cards.

| Exact source card and detail evidence | Product ID | Exact Cardmarket name/signature |
| --- | ---: | --- |
| [`30th-c-001`](https://api.tcgdex.net/v2/en/cards/30th-c-001) | 907940 | Charizard [Energy Burn \| Fire Spin] |
| [`30th-c-004`](https://api.tcgdex.net/v2/en/cards/30th-c-004) | 907958 | Genesect EX [Red Signal \| Megalo Cannon] |
| [`30th-c-006`](https://api.tcgdex.net/v2/en/cards/30th-c-006) | 907947 | Dark Tyranitar [Grind \| Spinning Tail \| Bite Off] |
| [`30th-c-007`](https://api.tcgdex.net/v2/en/cards/30th-c-007) | 907943 | Sneasel [Fury Swipes \| Beat Up] |
| [`30th-c-008`](https://api.tcgdex.net/v2/en/cards/30th-c-008) | 907963 | Pikachu & Zekrom GX [Full Blitz \| Tag Bolt GX] |
| [`30th-c-009`](https://api.tcgdex.net/v2/en/cards/30th-c-009) | 907960 | Greninja BREAK [Giant Water Shuriken] |
| [`30th-c-012`](https://api.tcgdex.net/v2/en/cards/30th-c-012) | 907965 | Raikou [Amazing Shot] |
| [`30th-c-013`](https://api.tcgdex.net/v2/en/cards/30th-c-013) | 907962 | Buzzwole GX [Jet Punch \| Knuckle Impact \| Absorption GX] |
| [`30th-c-015`](https://api.tcgdex.net/v2/en/cards/30th-c-015) | 907942 | Erika's Jigglypuff [Group Therapy \| Pulled Punch] |
| [`30th-c-016`](https://api.tcgdex.net/v2/en/cards/30th-c-016) | 907957 | Rayquaza EX [Celestial Roar \| Dragon Burst] |
| [`30th-c-017`](https://api.tcgdex.net/v2/en/cards/30th-c-017) | 907961 | Solgaleo GX [Ultra Road \| Sunsteel Strike \| Sol Burst GX] |
| [`30th-c-024`](https://api.tcgdex.net/v2/en/cards/30th-c-024) | 907944 | Shining Celebi [Healing Water \| Miracle Leaf] |
| [`30th-c-026`](https://api.tcgdex.net/v2/en/cards/30th-c-026) | 907966 | Mew VMAX [Cross Fusion Strike \| Max Miracle] |
| [`30th-c-027`](https://api.tcgdex.net/v2/en/cards/30th-c-027) | 907967 | Arceus VSTAR [Trinity Nova \| Starbirth] |
| [`30th-c-028`](https://api.tcgdex.net/v2/en/cards/30th-c-028) | 907964 | Zacian V [Intrepid Sword \| Brave Blade] |
| [`30th-c-030`](https://api.tcgdex.net/v2/en/cards/30th-c-030) | 907968 | Magikarp [Expert Splasher] |

## Deliberately unresolved products and printings

Repeated gameplay signatures do not distinguish base artwork from illustration
or special illustration variants. Examples from the complete rejection audit:

| Source cards | Same-signature Cardmarket product IDs |
| --- | --- |
| `30th-002`, `30th-129` | 907608, 907737 |
| `30th-011`, `30th-130` | 907617, 907738 |
| `30th-015`, `30th-147` | 907621, 907754 |
| `30th-017`, `30th-131` | 907623, 907739 |
| `30th-018`, `30th-132` | 907624, 907740 |
| `30th-021`, `30th-148` | 907628, 907755 |
| `30th-053`, `30th-149` | 907660, 907756 |
| `30th-054`, `30th-150` | 907661, 907757 |
| `30th-055`, `30th-133` | 907662, 907741 |
| `30th-060`, `30th-134` | 907667, 907742 |
| `30th-061`, `30th-135` | 907668, 907743 |
| `30th-064`, `30th-151`, `30th-157` | 907671, 907761, 908353 |
| `30th-066`, `30th-152`, `30th-158` | 907673, 907762, 908354 |
| `30th-071`, `30th-153` | 907678, 907758 |
| `30th-073`, `30th-136` | 907680, 907744 |
| `30th-075`, `30th-137` | 907682, 907745 |
| `30th-085`, `30th-138` | 907692, 907746 |
| `30th-089`, `30th-139` | 907696, 907747 |
| `30th-090`, `30th-154` | 907697, 908355 |
| `30th-094`, `30th-140` | 907701, 907748 |
| `30th-101`, `30th-141` | 907708, 907749 |
| `30th-102`, `30th-155` | 907709, 907759 |
| `30th-108`, `30th-142` | 907715, 907750 |
| `30th-109`, `30th-156` | 907716, 907760 |
| `30th-112`, `30th-143` | 907719, 908352 |
| `30th-113`, `30th-144` | 907720, 907751 |
| `30th-117`, `30th-118` | 907724, 907725 |
| `30th-122`, `30th-145` | 907729, 907752 |
| `30th-125`, `30th-146` | 907732, 907753 |

The three RGB Mew products **909509, 909510 and 909511** all use
`Mew [Psychic | 30C]` and metacard **465170** in expansion 6601. The current
TCGdex main index omits those cards. This payload cannot assign their product
IDs to R/RGB, G/RGB and B/RGB, so those bridges remain unset.

Other exclusions include trainers with no attack/ability signature, source
title differences outside the strictly allowed normalization, and incomplete
or conflicting source text. For example, TCGdex Miraidon `30th-062` provides
`Mach Bolt` / `Electro Drift`, whereas the Cardmarket product title repeats
`Mach Bolt` / `Mach Bolt`. Its name alone does not make it an accepted match.

### Numbered Cardmarket page limitation

The following public pages were attempted during the preceding read-only
investigation on 2026-09-22 and returned **HTTP 403** to the web reader:

- [30th Celebration expansion](https://www.cardmarket.com/en/Pokemon/Expansions/30th-Celebration)
- [Exeggcute 30C 001](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Exeggcute-30C001)
- [Red RGB Mew](https://www.cardmarket.com/de/Pokemon/Products/Singles/30th-Celebration/Mew-V1-30CRRGB)

No challenge bypass or listing-page scraper was used. Search-index snippets
that supplied collector numbers but no numeric product ID were not used to
resolve ambiguous products. Consequently the 77 bridges above are explicitly
catalogue/signature evidence, not claims that their direct numbered Cardmarket
pages were successfully inspected.

## Separate English fallback case observation

A fresh four-endpoint check at **2026-09-22T20:17:42.414Z** found:

| Detail | Declared `variants_detailed` |
| --- | --- |
| [English 30th-001](https://api.tcgdex.net/v2/en/cards/30th-001) | `type=normal`, `size=standard`, `variantId=generated` |
| [German 30th-001](https://api.tcgdex.net/v2/de/cards/30th-001) | `type=normal`, `size=Standard`, `variantId=generated` |
| [English 30th-065](https://api.tcgdex.net/v2/en/cards/30th-065) | `type=normal`, `size=standard`, `variantId=generated` |
| [German 30th-065](https://api.tcgdex.net/v2/de/cards/30th-065) | `type=normal`, `size=Standard`, `variantId=generated` |

These samples contain no `thirdParty.cardmarket` identity. The pre-change
registry attachment check recognizes `Standard` case-sensitively, so the
lowercase English fallback can lose an otherwise available maintained bridge
after detail expansion. This is a fallback robustness issue; these observations
do **not** show a lowercase-size failure in the German examples. The absent
77 maintained mappings are independent of that casing issue.

## Change boundary

This artifact records read-only source research. It does not itself modify
application code, prices, a collection, remote services, or the existing
supplement document. Product identity evidence must not be presented as proof
that a Cardmarket guide currently supplies a non-null trend price.
