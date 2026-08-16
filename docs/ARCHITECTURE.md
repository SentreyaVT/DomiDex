# CardVault architecture

CardVault is local-first and account-free. Room is the source of truth for the catalog cache, the user's owned entries, raw price history, graded market evidence, and grading pre-screen history. Network importers are future adapters; they must consume permitted downloadable datasets and must never scrape listings or embed personal keys.

## Persistence boundaries

- `catalog_cards` stores stable identity across TCG, set, collector number, language, finish, and printing variant. Pokémon is first; the `tcg` field allows later One Piece, Yu-Gi-Oh!, and Dragon Ball catalogs.
- `collection_entries` stores only owned cards and user data. Catalog import must never imply ownership.
- `raw_price_snapshots` stores timestamped source/currency price variants. Portfolio totals derive from the newest persisted market value; history derives from snapshots.
- `graded_market_records` keeps company, numerical grade, sale/snapshot type, date, source, currency, aggregation confidence, and sample size separate from raw prices.
- `grading_analyses` is append-only history tied to the exact owned entry/variant. It is ready for front/back/angled photo references, four category findings, range/confidence, analyzer version, timestamps, and later submission economics. No analyzer exists yet.
- Schema JSON is exported. Every later database version requires explicit migrations; destructive fallback is forbidden.

## Batch-scan readiness (future)

Continuous scanning should resolve a catalog identity, then show only plausible variants (set/number, language, normal/reverse holo/holo/foil/other printing). The most likely variant may be preselected, but uncertain condition or variant requires the smallest possible confirmation. A confirmed duplicate should increment the matching owned entry in one tap, with haptic/sound feedback and immediate undo—never repeated form navigation.

Capture quality and recognition confidence are separate signals. Capture guidance uses a red/yellow/green state derived from sharpness, motion stability, exposure, glare, card coverage, perspective, and visibility of all edges/corners. Green means capture-ready, not recognized. Auto-capture occurs only after a short stable-green interval. Concise German hints include “Näher herangehen”, “Ruhig halten”, “Spiegelung reduzieren”, and “Alle Ecken zeigen”.

Every owned-card detail exposes a `Grading` entry point. The future guided flow captures front, back, and required angled/lighting views, stores rerunnable results against that exact entry, and clearly presents only a photo-based pre-screening range—not a guaranteed PSA grade.
