# Cardmarket import boundary

CardVault uses only Cardmarket's public Pokémon downloads, never listing-page scraping or an API key:

- Product catalog: `https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json`
- Price guide: `https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json`
- Official pages: `https://www.cardmarket.com/en/Pokemon/Data/Product-List` and `https://www.cardmarket.com/en/Pokemon/Data/Price-Guide`
- Official availability announcements: `https://news.cardmarket.com/en/Magic/product-catalog-and-price-guide-now-available` and `https://news.cardmarket.com/en/Magic/cardmarket-price-guide-and-product-catalog-is-now-public`

The downloaded product ID is stored as an external identity (`CARDMARKET_PUBLIC_DOWNLOAD`, `idProduct`). Imported catalog rows use stable internal IDs of the form `cm-pokemon-{idProduct}`. The public product file does not provide a trustworthy collector number, language, or finish in every row; CardVault leaves those unknown rather than inferring them.

Portfolio valuation uses the raw `trend` field in EUR, without fallback to another field. Low, average, 1/7/30-day averages and available holo fields are retained separately and historically. A failed import does not delete old snapshots or collection data. Phase-1 `DEMO_LOCAL` prices are removed by migration because they were explicitly fictional demonstration values.

WorkManager schedules a network-constrained daily update. Startup requests work only when the last successful import is at least 24 hours old; manual refresh uses unique work to avoid duplicate concurrent imports. Regular tests use local fixtures. The opt-in live smoke test runs only with JVM property `cardvault.liveSmoke=true`.

The source adapter and external identity fields remain TCG-neutral. A future public release must independently re-check Cardmarket's then-current commercial data terms; no publishing is part of this phase.
