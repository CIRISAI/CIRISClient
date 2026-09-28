# CSD-052 — Trust (the attestation ladder, misnamed and misplaced)

**CSD**: CSD-052 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see how far the software they are running has climbed the
verification ladder — which checks passed, which did not, and what the failure
was — without being told this is a fact about the people in the circle.** Serves
**Integrity**: CC 3.1.2 says these prefixes *"serve integrity: each is a
checkable claim about how far a build, key, or license has climbed the trust
ladder"*, and CC 3.4.5 adds the part the placement gets wrong — artifact-integrity
verification *"scores builds, manifests, licenses and certificates — **not a
subject's conduct or capacity**"*.

**The card is called Trust and sits in the Rules tab of all five circles**
(`CirclesNav.kt`, `Placement(NavSurface.Trust, Tab.RULES, ALL)`). It is
`TrustPage` — "Full-page view of CIRISVerify attestation status"
(`TrustPage.kt:55`) — a property of this one install, identical in all five
circles, that changes not at all when you move from Just me to Everyone. CC
keeps three trust vocabularies apart on purpose: **attestation/provenance** for
a binary's integrity (CC 3.1.2, 3.4.5), **`trust:{job}:{version}`** for which
root a node obeys (CC 3.1.1), and **`licensure:*` / `capacity:*`** for a
person's or agent's standing (CC 2.4.1.2.1, 3.1.8.1). This card renders the
first and is named for the second, in a tab where a person is looking for the
third.

## 2. Surface (what)

```yaml csd:surface
surface: trust
screen: Trust
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_trust`, and the
same chain under each of the other four circles.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: attestation:self_verify
    use: display-only
    type: bool
    example: true
    renders: "Level 1 — Binary loaded. Pass / fail, with binary=<n> under it."
    tag: "proposed:row_attestation_self_verify"
  - ceg: attestation:hardware_rooted
    use: display-only
    type: bool
    example: false
    renders: "Level 2 — Environment. Pass / fail, with hw=<bool>, type=<first 10 chars>."
    tag: "proposed:row_attestation_hardware_rooted"
  - ceg: attestation:registry_consensus
    use: display-only
    type: string
    example: "2/3"
    renders: "Level 3 — Registry cross-validation. n-of-3 sources: Registry, DNS, HTTPS, each with its own mark."
    tag: "proposed:row_attestation_registry_consensus"
  - ceg: "provenance:build_manifest:{target}"
    use: display-only
    type: bool
    example: true
    renders: "Level 4 — File integrity. module=<bool>, file=<bool>."
    tag: "proposed:row_attestation_file_integrity"
  - ceg: attestation:agent_integrity
    use: display-only
    type: bool
    example: false
    renders: "Level 5 — Full trust. audit=<bool>, key=<first 10 chars of the registry key status>."
    tag: "proposed:row_attestation_agent_integrity"
  - ceg: x_private:max_level
    use: display-only
    type: int
    example: 4
    renders: "Agent Validated — Awaiting identity confirmation (amber at 4, green at 5)"
    tag: "proposed:text_trust_max_level"
```

**Rung 4 is not CC's rung 4, and a CSD is where that has to be said.** CC 3.1.2's
ladder is `self_verify` → `hardware_rooted` → `registry_consensus` →
**`license_validity`** → `agent_integrity`. The screen's L4 is module and file
integrity (`TrustPage.kt:588`, `module=…, file=…`), which is
`provenance:build_manifest:{target}` territory, and `attestation:license_validity`
is rendered nowhere. So the card shows five rungs, four of which are CC's, in an
order a reader will take for CC's. Either the binding above is right and the
label "Level 4" is wrong, or CIRISVerify's ladder has diverged from CC 3.1.2;
the CSD cannot settle that, and the ask is on CIRISVerify (§3).

Every row is `proposed:` because **the tier rows carry no test tags at all**.
The ten tags on this 2,968-line screen are `btn_trust_back`,
`btn_trust_refresh`, `btn_trust_retry`, `btn_learn_more`, `btn_copy_diagnostics`,
`btn_l5_copy_diagnostics`, `item_registry_mismatch`, `item_disk_agent_mismatch`,
`item_diagnostics_log` and `trust_fabric_card` — nine controls and one card, and
not one of the five verdicts the screen exists to report.

```yaml csd:states
populated: {tag: "proposed:trust_tiers", renders: "five tier rows with their verdicts"}
empty:     {tag: "proposed:text_trust_not_attempted", renders: "No verification has been attempted. — the real `level == 0 && attestationStatus == \"not_attempted\"` branch at TrustPage.kt:411, today untagged"}
loading:   {tag: "proposed:trust_loading", renders: "the progress affordance and mobile.trust_running (TrustPage.kt:349), NOT the not-attempted sentence"}
error:     {tag: btn_trust_retry, renders: "the ErrorCard at TrustPage.kt:355 — surfaceError tone, mobile.trust_failed, the message, and this retry button"}
```

`error` is the one state with a real tag, and only because the retry button
inside it has one; the card itself does not. The distinction the standard
demands is nonetheless *drawn* here — `ErrorCard` uses `surfaceError` and
`not_attempted` uses neither — so this is a tagging gap, not a design defect.
That makes it cheap: one PR of `testable(…)` calls moves five `shows:` rows and
three states off `proposed:`, which is the whole reason to write this file at
`sketched`.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the ladder | `GET /v1/system/verify-status` | CIRISServer | live (`src/health.rs:683`) |
| substrate fabric versions | `GET /v1/system/fabric` (best-effort, never gates the page, `TrustPage.kt:119`) | **CIRISAgent** (`routes/system/fabric.py:74`) | live, **agent-only** — the owner was wrong: CIRISServer has zero `system/fabric` literals, and it is not in `NODE_OWNED_PREFIXES` either, so a node build never answers it |
| the mobile device attestation (Play Integrity / App Attest) | `GET /v1/setup/verify-status` | **CIRISAgent** (`routes/setup/attestation.py:292` on `main`) | live, and agent-only — correctly gated off on a node (`TrustPage.kt:87, 130`) |
| Android: Play Integrity nonce → verify → report failure | `GET /v1/setup/play-integrity/nonce`, `POST …/verify`, `POST …/failed` | CIRISAgent `routes/setup/attestation.py:539, 610, 653` | **live** — `CIRISApiClient.getPlayIntegrityNonce` / `verifyPlayIntegrity` / `reportPlayIntegrityFailed` (`CIRISApiClient.kt:12108, 12163, 12235`), driven by `androidApp/.../integrity/PlayIntegrityManager.kt:41, 96, 80`. The token then rides `GET /v1/setup/verify-status?mode=full` (`CIRISApiClient.kt:7138`) |
| iOS: App Attest nonce → verify, and its status | `GET /v1/setup/app-attest/nonce`, `POST /v1/setup/app-attest/verify`, `GET /v1/setup/attestation-status` | CIRISAgent `routes/setup/attestation.py:364, 437, 331` | live, **not called — there is no iOS half in the client at all**, not dead code: zero `app-attest` or `AppAttest` call sites in `shared/` (the only `DCAppAttest` mention is a doc comment, `models/federation/SelfLogin.kt:44`). An iPhone therefore never climbs the device rung Android can |
| (not this card) emit a CEG attestation | `POST /v1/auth/attestation` | CIRISServer `src/auth/attestation.rs:176` — a local/federation-tier attestation EMIT (module doc `:1-16`), not a device attestation | live, **correctly not called.** The route-coverage report filed it here as "device attestation to the node"; it is not. The client used to GET it for verify status and took a 405 every 30 s (`CIRISApiClient.kt:7141-7147` records the fix); the read is `/v1/system/verify-status`, row one |
| which family each rung IS | — | CIRISVerify | **both are emitted** — `attestation:license_validity` at `ciris-verify-core/src/federation_provenance.rs:107` (bundle field `attest_bundle.rs:160-162`), `provenance:build_manifest:{target}` via `BuildManifest::to_attestation_entries`. And there is no upstream "L4" to bind to: **CIRISVerify dropped the L1–L5 numbering from its wire strings in v3.7.0** (`docs/THREAT_MODEL.md:761` — "the ladder concept … now it lives only in consumer-side policy, where it belongs"). |

**The last row is why the five rungs are consumer-side policy, and the CSD must
say so rather than claim a CC rung.** The node's `verify-status` payload
(`CIRISServer src/health.rs:612-683`) names none of the five `attestation:*`
families: it sends a coarse `max_level` of 0/2/4 plus
`checks: {verify_loaded, key_registered, audit_chain, hardware_backed}` — no
`module_integrity_ok`, no `file_integrity_ok`, which is exactly what
`TrustPage.kt:588` draws as "Level 4". The binding in §2 is defensible as this
client's own policy over that coarse level. It cannot be justified as CC 3.1.2's
rung 4, because CIRISVerify no longer has one.

The node/agent split on this screen is handled well and is worth keeping when the
card moves: the node serves its own `verify-status`, so the tiers load and render
honestly on a bare node; only the mobile device-attestation flow is agent-gated,
and the 5-second poll is dropped on a node because a substrate attestation is
static (`TrustPage.kt:96-101`).

## 4. Flow (how)

Unwritten — every value-bearing tag is `proposed:`. A flow over today's tags
would assert that nine buttons exist, which says nothing about whether the
verdicts are right.

## 5. QA plan

**Platforms.** All five. The device-attestation branch is Android and iOS only
and is skipped on a node; three of the five platforms therefore exercise the
node path and two exercise both.

**Not tested here.**
* Whether a rung's verdict is correct. The CSD asserts what is rendered; the
  claim itself is CIRISVerify's.
* The mobile attestation callback, which needs a real Play Integrity / App
  Attest token.

**The recommendation, so it is on the record.** Trust should move to **My things
› This node**. It is one install's integrity, identical in all five circles, and
CC 3.4.5 puts artifact-integrity verification explicitly outside "a subject's
conduct or capacity" — which is what a person opening Rules in the Neighbours
circle is looking for. The word "Trust" should go with it or be given up: CSD-050
shows that `LayerHubScreen` already has a section called Trust meaning
`trust:{job}:{version}`, in the same tab, so today the Rules tab of every circle
contains two different things called Trust and neither is the one about people.
