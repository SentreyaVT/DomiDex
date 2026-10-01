# Remaining 30th Classic Cardmarket identities — 2026-09-25

## Scope and direct source evidence

Seven existing `30th-c` cards lacked a verified Cardmarket product link. Their
ordinary-printing identities are now added to the existing exact-ID registry.
This completes 30 of 30 Classic identities, alongside the already maintained
158 main-set and three RGB identities. No price values, new network requests,
cache policy, scanner logic or existing collection records are changed here.

The main agent inspected the [official Cardmarket 30th Celebration overview](https://www.cardmarket.com/en/Pokemon/Spoilers/30th-Celebration)
in the ordinary browser on **2026-09-25**. After the site's automatic loading,
each exact visible card-label link and the `img` product URL inside that same
`.card` element were read together. No CAPTCHA or access protection was bypassed.
The evidence was delivered to the implementation agent in this task's browser
verification handoff. An earlier direct HTTP attempt returned 403 and was not
used to establish the mappings.

| Source card | Official visible collector label | Product ID | Official numbered product page |
| --- | --- | ---: | --- |
| `30th-c-002` | Delcatty (30C RS 5) | 907946 | [Delcatty](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Delcatty-30CRS-5) |
| `30th-c-005` | Misty (30C GH 18) | 907941 | [Misty](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Misty-30CGH-18) |
| `30th-c-018` | Gengar (30C TM 94) | 907953 | [Gengar](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Gengar-30CTM-94) |
| `30th-c-019` | Darkrai & Cresselia LEGEND (30C TM 99) | 907954 | [Top half](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Darkrai-Cresselia-LEGEND-30CTM-99) |
| `30th-c-020` | Darkrai & Cresselia LEGEND (30C TM 100) | 907955 | [Bottom half](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Darkrai-Cresselia-LEGEND-30CTM-100) |
| `30th-c-023` | MGardevoir EX (30C PRC 106) | 907959 | [MGardevoir EX](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/MGardevoir-EX-30CPRC-106) |
| `30th-c-025` | Scizor ex (30C UF 108) | 907948 | [Scizor ex](https://www.cardmarket.com/en/Pokemon/Products/Singles/30th-Celebration/Scizor-ex-30CUF-108) |

The observed image URL of each row was
`https://product-images.s3.cardmarket.com/51/30C/<product-id>/<product-id>.jpg`.
For example, the same element as `Delcatty (30C RS 5)` contained
`https://product-images.s3.cardmarket.com/51/30C/907946/907946.jpg`.
The numeric IDs were also checked against expansion **6601** in the official
public product download. The seven products all had positive regular guide
trends in the frozen 2026-09-25 price download; this is a dated observation,
not a promise about future values or individual sale offers.

The source subset positions remain `002`, `005`, `018`, `019`, `020`, `023`,
and `025`. They are not the printed historic collector numbers. Existing
Classic collector aliases and binder ordering remain unchanged. The two
LEGEND halves retain separate products; historic original-set, main-set, JP,
and Indonesian/Thai products are not substituted.

## Safety and regression boundary

The existing eligibility rules still apply: an explicit per-printing source
ID takes precedence; stamps, foil subtypes, editions and other special
printings cannot borrow an ordinary registry bridge. Quarantined identities
remain excluded. No general card-wide `thirdParty.cardmarket` or
`pricing.cardmarket.idProduct` inheritance was introduced: the full-catalog
audit found those fields shared by different collector numbers.

Tests cover all 188 numbered main/Classic identities, the seven new bridges
through German/English variant labels, historic collector preservation,
explicit per-printing precedence, distinct LEGEND halves, and special-printing
exclusion. Test execution and release verification are recorded by the main
Release 27 run; this note does not claim an independent build or test run.
