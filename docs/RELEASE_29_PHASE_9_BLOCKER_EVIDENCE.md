# DomiDex Release 29 – Phase 9 blocker evidence

**Date:** 26 November 2026  
**Status:** evidence and remediation complete where local authority permits; release approval remains blocked by external evidence and physical validation.

## DLR price-identity remediation

The 79 unresolved Delta-Herrschaft (DLR) printings were rechecked against the
current local audit, the official [Pokémon DLR checklist](https://www.pokemon.com/static-assets/content-assets/cms2-de-de/pdf/trading-card-game/checklist/dlr_web_cardlist_de.pdf),
and Cardmarket's documented product model. The checklist proves the German
printed identity (collector number, name and rarity); it does not expose a
Cardmarket product identity. Cardmarket documents that collector-number search
and correct version selection are required, and that reverse-holo versions are
distinct ([listing help](https://help.cardmarket.com/en/finding-and-listing-pokemon-cards)).

The local public export has no collector-number or finish field. It therefore
cannot distinguish the 47 cases with multiple exact name/attack candidates, or
establish an identity for the 32 cases with no exact candidate. No mapping was
added. Android and Web retain the same 62 normal/standard mappings and the same
ten explicit UNMATCHED collector numbers.

Cardmarket's documented Product entity can provide `idProduct`, expansion,
number, rarity, image and website ([Product API](https://api.cardmarket.com/ws/documentation/API_1.1%3AEntities%3AProduct)).
That is the technically suitable authoritative source. Access is not locally
available: Cardmarket states that API applications require a professional-seller
account and manual approval ([API authentication overview](https://api.cardmarket.com/ws/documentation/API%3AAuth_Overview)).
The public product page also returned a Cloudflare verification challenge during
read-only access; no bypass was attempted. Consequently, the remaining work is
not safely automatable from the available public export.

**Required closure evidence, per unresolved printing:** an authorised
Cardmarket API Product record or manually reviewed official product page that
records the product ID/URL and matches German set, collector number, name,
finish/variant and artwork. The 79 entries must remain unpriced until that
evidence exists. The contractual approval for persistent external price display
is a separate requirement.

## Web lint remediation

All 205 remaining lint diagnostics were in `backend/market-data` test harnesses.
They were resolved without rule suppression or production-source changes:

- Node test registration promises are explicitly discarded at the registration
  boundary.
- Fixture method references are bound before a fixture replaces a method, which
  preserves the original receiver and avoids accidental recursive test doubles.
- The remaining diagnostics were corrected with equivalent sparse-fixture,
  comparator, type-safety and `const` expressions.

No market-data provider, credential, production storage, Firebase or deployment
path was contacted or changed.

## Physical/release evidence still required

Local automation cannot substitute for physical proof. The Phase-6 matrix
remains the execution record for two Android devices, real normal/holo/reverse
cards, lighting/angle/reflection, scanner camera, offline/reconnect, reboot and
WorkManager recovery. It requires the designated devices, non-production test
environment/data and explicit camera permission. Current evidence is limited to
the documented Android-17 offline emulator run; camera was not opened.

R29 also still requires separately authorised release signing, a non-production
Firebase identity for online sync tests, deployment ordering for the reviewed
Firestore rules, and explicit release/deployment approval. None was performed
in this phase.

## Executed local checks

| Check | Result |
| --- | --- |
| `node --test backend/market-data/*.test.mjs` | PASS — 174/174 |
| `npm run lint` | PASS — 0 diagnostics |
| `npm test` | PASS — 52 files / 779 tests |
| `npm run typecheck` | PASS |
| `npm run build` | PASS |

The build retained only the existing non-blocking chunk-size advisory. These
checks do not establish physical, Firebase, Play, signing, production deployment
or Cardmarket API entitlement evidence.
