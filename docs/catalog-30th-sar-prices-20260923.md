# 30th Celebration numbered Cardmarket identities — 2026-09-23

## Result

Added 65 exact numbered bridges and the three RGB colour identities. The Android
registry now covers all **158 main-set cards**, the existing **23 Classic cards**,
and **3 RGB cards**. No price values are embedded in production code.

The [official Cardmarket 30th Celebration overview](https://www.cardmarket.com/en/Pokemon/Spoilers/30th-Celebration)
loaded successfully in an ordinary browser during this session, after its normal
automatic wait. No CAPTCHA interaction or challenge bypass was used. Each
reviewed card's visible collector label and product image URL were read together
from the same card element. This resolves the duplicate name/attack signatures
left open by the [previous audit](catalog-30th-cardmarket-prices-20260922.md).

For example, the element labelled `Mewtwo ex (30C 151)` links to
`Mewtwo-ex-V2-30C151` and contains the official product image
`https://product-images.s3.cardmarket.com/51/30C/908353/908353.jpg`.
Thus SAR 151 is **908353**, whereas Futuristic 157 is **907761**. Neither product
order, upload dates, similar names, nor price similarity establishes a mapping.
Meowth's visible label is **30C 144** with image ID **907751**; its stale link slug
ends in `30C143`. The collector label/image evidence takes precedence over that
stale slug. The image IDs were also checked against expansion **6601** in the
official product catalogue.

## Sources and guide availability

At **2026-09-22T23:01:14.992Z** (2026-09-23 in Europe/Berlin), a fresh download
verified **158 distinct main-set product IDs**, all in expansion 6601, all with
positive guide trends. The 30 cards numbered 129–158 are listed below. RGB IDs
exist in the guide, but all three have null `trend`, `avg1`, `avg7`, and `avg30`:
their identity can be linked while their price remains unavailable.

| Payload | Official source timestamp | SHA-256 |
| --- | --- | --- |
| [Product catalogue](https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json) | `2026-09-22T13:02:01+0200` | `8221d3cccba53e91828596c04a15a25cf462b0324a92a405ebfb6a108f66c2aa` |
| [Price guide](https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json) | `2026-09-22T02:48:57+0200` | `dc9040824bde045c1998dc6939dba0167d1fe3ed0a1af0620e2a6a8ee64e5742` |

The 60 German/English TCGdex detail endpoints for `30th-129` through `30th-158`
were checked at `2026-09-22T22:55:47Z`: all returned successfully, none supplied a
top-level or variant `thirdParty.cardmarket` identity, and all had
`pricing.cardmarket=null`. The missing TCGdex link is separate from availability
in Cardmarket's public guide. Example: [TCGdex 151](https://api.tcgdex.net/v2/en/cards/30th-151).

## Exact added collector/product pairs

Every numeric collector below belongs to source set `30th`, with printed
denominator 128. The trends are audit evidence in EUR, not hardcoded app prices,
language/condition-specific quotes, or current sale offers.

| Collector | Cardmarket product ID | Guide trend EUR |
| --- | ---: | ---: |
| 129 | 907737 | 3.75 |
| 130 | 907738 | 9.50 |
| 131 | 907739 | 10.30 |
| 132 | 907740 | 9.51 |
| 133 | 907741 | 9.36 |
| 134 | 907742 | 3.30 |
| 135 | 907743 | 5.30 |
| 136 | 907744 | 4.21 |
| 137 | 907745 | 3.36 |
| 138 | 907746 | 2.72 |
| 139 | 907747 | 4.99 |
| 140 | 907748 | 2.38 |
| 141 | 907749 | 8.01 |
| 142 | 907750 | 2.41 |
| 143 | 908352 | 2.87 |
| 144 | 907751 | 6.82 |
| 145 | 907752 | 5.90 |
| 146 | 907753 | 10.12 |
| 147 | 907754 | 15.89 |
| 148 | 907755 | 22.96 |
| 149 | 907756 | 85.55 |
| 150 | 907757 | 92.67 |
| 151 | 908353 | 99.78 |
| 152 | 908354 | 141.64 |
| 153 | 907758 | 51.61 |
| 154 | 908355 | 114.13 |
| 155 | 907759 | 25.51 |
| 156 | 907760 | 17.92 |
| 157 | 907761 | 67.46 |
| 158 | 907762 | 95.03 |
| R/RGB (`30th-rgb-r`) | 909509 | unavailable |
| G/RGB (`30th-rgb-g`) | 909510 | unavailable |
| B/RGB (`30th-rgb-b`) | 909511 | unavailable |

The same direct collector-label/image-ID observations resolved the remaining
35 ordinary main-set identities:

| Collector | Product ID | Collector | Product ID | Collector | Product ID |
| --- | ---: | --- | ---: | --- | ---: |
| 002 | 907608 | 011 | 907617 | 015 | 907621 |
| 017 | 907623 | 018 | 907624 | 021 | 907628 |
| 053 | 907660 | 054 | 907661 | 055 | 907662 |
| 060 | 907667 | 061 | 907668 | 062 | 907669 |
| 064 | 907671 | 066 | 907673 | 071 | 907678 |
| 073 | 907680 | 075 | 907682 | 085 | 907692 |
| 087 | 907694 | 089 | 907696 | 090 | 907697 |
| 094 | 907701 | 101 | 907708 | 102 | 907709 |
| 108 | 907715 | 109 | 907716 | 112 | 907719 |
| 113 | 907720 | 117 | 907724 | 118 | 907725 |
| 122 | 907729 | 125 | 907732 | 126 | 907733 |
| 127 | 907734 | 128 | 907735 | | |

## Application boundary and verification

The existing ordinary-printing eligibility and explicit upstream-ID precedence
are unchanged. A stamp, foil subtype, edition, or separately declared printing
does not obtain a new identity by name matching. Reverse finish metadata and
explicit reverse product IDs remain intact. RGB colours keep their separate
collector slots and cannot use ordinary Mew, Mew ex, or another colour's price.

Focused regression coverage checks all 181 numbered main/Classic identities
through German/English detail enrichment, distinct base/SAR/Futuristic products,
explicit reverse/stamp precedence, and RGB lookup with a missing guide price.
No bulk rewrite of existing collection entries is performed. Opening an owned,
previously unlinked `30th` card now resolves its exact source card/set/collector
and eligible printing through the existing resolver. A single proven product
link is saved through the existing owner-scoped repository and sync outbox.
Quantity, condition, notes, collector identity and binder position are retained;
an existing explicit product link is not replaced.

## Integrated verification

- 55 focused Android JVM tests passed (catalog identities, source-declared and
  visual linking, collection/private-binder sync policies).
- Debug APK and instrumentation APK builds succeeded.
- 11 isolated emulator tests passed: eight anniversary preview/add/reopen tests
  and three sync-work scheduling tests. They use local fixture data, not live
  user collections. Coverage includes 129, SAR 151, Futuristic 158, missing-price
  promo 105 and repairing an old unlinked SAR without altering ownership data.
- 135 focused Web tests passed across 12 suites; TypeScript and production build
  succeeded. The build retains its bundle-size warning.
- A local production server, using real upstream responses, returned the exact
  product/guide pairs `30th-151 -> 908353 / 99.78 EUR` and
  `30th-158 -> 907762 / 95.03 EUR`. RGB red retained `909509 / null`.
  These are dated guide observations, not fixed values or current sale offers.

## Synchronization scope

Fresh Android changes now request expedited collection/private-binder work on
Android 12+, with durable normal-work fallback. Owner separation, revision
checks, outboxes and retry ordering remain intact. Older Android versions retain
normal work scheduling. A retrying predecessor, quota limits, background limits
or connectivity can still delay a queued edit.

Web now subscribes to owner-scoped collection-layout changes, in addition to
its existing collection/private-binder listeners. Hiding a set-binder entry
does not remove it from collection valuation or private-binder candidates.
Web anniversary pricing mirrors the reviewed Android mappings and retrieves
the same official guide, preserving separate regular/reverse fields and nulls.

This is not a guarantee of immediate cross-device market-price equality.
Market values and guide timestamps are not collection-wire fields; Android and
Web fetch them separately. Web has an existing ten-minute detail cache and the
new guide cache lasts one hour. An idle view has no market-price polling timer.
Web also still requires source-card identity and an exact source-compatible
variant; Android entries having only a manually linked product can therefore
remain unpriced on Web. No identity safety checks were weakened to hide this.

No live two-device roundtrip was performed. No Firebase rules, production data,
release version, signing setup, Git history or hosted deployment were changed.
No new AAB was prepared or published during this fix.
