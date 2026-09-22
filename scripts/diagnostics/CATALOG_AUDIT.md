# Physical German catalog integrity audit

This diagnostic is separate from the application. It reads public sources and executes the existing Android catalog, printing, mapping and binder policy against a frozen snapshot. It never reads or changes a user's collection, Firebase account, Room database or production environment.

Keep the snapshot and reports **outside** either product repository. The downloads include neither credentials nor user data. Never bundle them in APK/AAB assets.

## Reproducible run

1. `node scripts/diagnostics/download-catalog-snapshot.mjs <external-snapshot-directory>`
2. `node scripts/diagnostics/check-catalog-images.mjs <external-snapshot-directory>`
3. Set `DOMIDEX_AUDIT_DIR` to the snapshot, `DOMIDEX_AUDIT_OUTPUT` to an external `before-runtime` directory, and `DOMIDEX_AUDIT_PHYSICAL_ONLY=true`.
4. Run `gradlew.bat testDebugUnitTest --tests com.dominik.cardvault.ui.AllBinderPriceAuditTest --rerun-tasks`. Do not run another Gradle build at the same time.
5. `node scripts/diagnostics/report-catalog-integrity.mjs <snapshot> <before-runtime> <before-report>`
6. After code changes, repeat step 4 with an `after-runtime` output directory and run `node scripts/diagnostics/report-catalog-integrity.mjs <snapshot> <after-runtime> <after-report> <before-report>/summary.json`.

Use the same frozen source snapshot for before/after. The report rejects snapshots with different input hashes. A downloader restart resumes successful hashes; do not refresh a baseline in place. Download a new directory for a newer upstream state.

After both runtime runs, call the image checker with each `production-printings.jsonl` path as an optional second argument, then regenerate both reports against that identical finalized image evidence. Technical HEAD failures are retried on a new checker run; genuinely missing assets stay explicit. The checker prefers same-set English logos over known unavailable universal symbols, matching the current production parser. Before-runtime files without a logo field are evaluated using the frozen pre-fix logo fallback policy and labelled as reconstructed evidence.

## Outputs

- `card-variant-coverage.jsonl`: every source printing, original print metadata, source/language identity, collector number, image check, actual canonical binder slot, exact declared/maintained product evidence, runtime mapping, price field, price state, missing reasons and audit flags.
- `sets.json`: index/detail/enumerated totals kept separate; main/above-main/special numbers; source vs binder print counts; no fabricated physical checklist completeness.
- `high-price-review.json`: prices >= EUR 1,000 are flagged for review, never automatically deleted or replaced.
- `cross-card-product-review.json`: source cards sharing a product/price field, for independent review.
- `summary.json`: honest coverage counts, remaining gaps and limitations.
- `unmapped-high-price-products.json`: high-value source products with no proven source-card link; never assigned from their title.
- `before-after.json`: comparison of two runtime evaluations on identical input bytes.
- `source-manifest.json`: source URL, retrieval time, SHA-256, response/transport failures.
- `image-checks.jsonl`: HTTP HEAD/MIME results only; no image pixels downloaded.

## Interpretation

`AVAILABLE` means an exact source-declared or existing maintained product has a positive Cardmarket trend field and the runtime mapping agrees. It is not an independently certified sale price. Cardmarket's public price guide aggregates languages and conditions. The audit must not present it as a verified German-only price.

`NOT_AVAILABLE` describes missing or ambiguous mapping, absent/zero price fields or unavailable sources. `ERROR` distinguishes technical detail failures. `LOADING` is a UI state and deliberately cannot be a completed offline audit result.

A card with `137/131` is never rejected for being above the main set. Historic denominators in `30th-c` remain intact through the production parser. Pocket is excluded using its source series registry, never guessed from names.

An exact source reference is not the same as independent physical checklist verification. Every set records this limitation until an authoritative full German checklist is actually compared. English fallback names/details, missing images and special prints outside canonical binder slots remain explicit.

The diagnostic JUnit test passing means all report rows were produced; it does **not** mean every row has a price. `node --test scripts/diagnostics/catalog-integrity-policy.test.mjs` validates the reporting safety rules independently.

`unsafeMappedBinderSlots` records actual non-empty pre-fix Binder mappings without any declared printing product ID. Removing those guesses is a safety correction, not proof of new price coverage. Exact-ID priced printing counts are kept separately so an apparent price-coverage drop cannot hide in the totals.
