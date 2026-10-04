# CSD-067 — Accord (Trust Root) — the kill switch, its roster, and the four kinds

**CSD**: CSD-067 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten · **Splits from**: CSD-003, which described this surface and
the Constitutional card as one document and can no longer, because they are two
cards in one tab with two different jobs · **Leaf**: CSD-105 (this node's trust
root, `Screen.TrustRoot`), split out at the accord review because it is its own
screen and the route checker keys a card by the routes its screen calls

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see whether the kill switch is latched, who the holders are, what
is pending and how far from quorum it is — and can tell an emergency halt from a
drill, a notice, and a resumption at a glance.** Serves **Core Identity** and
**Integrity**.

The last clause is not a nicety. CC 4.2.1.2 is a **normative consumer-UI
requirement**, written because wire isolation alone does not stop a `notify`
from carrying CONSTITUTIONAL social weight:

> A CEG-Conforming Consumer presenting accord invocations to humans MUST
> visually distinguish the four kinds — `CONSTITUTIONAL`, `notify`, `drill`,
> `lifecycle:active` … MUST be shown as its own unambiguous "reactivated —
> resumed from constitutional halt" state, never conflated with an active
> CONSTITUTIONAL halt (its opposite) nor with a `notify`.

This client implemented three of the four when this CSD was written (§2.2), and
the one it dropped was the one whose meaning is the *opposite* of the kind it
rendered as. The badge and the card tone gained their fourth arm on
`feat/trust-root-posture`; the binding sentence under the card kept an `else →
notify` until the accord review. All three are exhaustive now, and §2.2 records
the one deviation that remains.

## 2. Surface (what)

```yaml csd:surface
surface: accord
screen: Accord
```

`nav_map` derives `circle_global_commons -> tab_safety -> nav_epistemic_accord`
— Everyone › Safety (`CirclesNav.kt:114`). The accord lives in exactly one
place, with the trust root, the holder flow and the `accord:*` attestations,
"because in an emergency a person should not have to know which of three tabs we
filed it under" (`docs/FSD-ui-language.md:287`). That is the right placement and
this CSD proposes no change to it.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "accord:halt_status"
    use: display-only
    type: "enum[halted,not_halted,unknown]"
    example: "halted"
    renders: "ACTIVE HALT — node execution halted by invocation inv-7c19"
    tag: accord_halt_banner
  - ceg: "accord:family"
    use: display-only
    type: string
    example: "humanity-accord"
    renders: "Humanity Accord — the entrenched family, consensus_protocol quorum:2/3"
    tag: "card_accordfamily_${family.familyKeyId}"
  - ceg: "accord:holders"
    use: display-only
    type: "list[string]"
    example: ["wa-holder-1c4f", "wa-holder-9b02", "wa-holder-33da"]
    renders: "one card per holder, SEAT or VAULT, with its hardware custody"
    tag: "card_holder_${holder.keyId}"
  - ceg: "accord:invocation"
    use: display-only
    type: "enum[constitutional,notify,drill,lifecycle]"
    example: "constitutional"
    renders: "the kind badge on an invocation card, with the CC 4.2.1.2 per-kind treatment"
    tag: "card_invocation_${inv.invocationId}"
    assert:
      one_of: {card_invocation_kind: [CONSTITUTIONAL, NOTIFY, DRILL, REACTIVATED]}
  - ceg: x_private:invocation_quorum
    use: display-only
    type: int
    example: 1
    renders: "1 of 2 signatures — pending"
    tag: "proposed:txt_invocation_quorum"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey_5_fips}
    use: display-only
    type: string
    example: "yubikey_5_fips"
    renders: "FIPS YubiKey + ML-DSA — the holder's custody class"
    tag: "proposed:txt_holder_custody"
  - ceg: x_private:notice
    use: display-only
    type: string
    example: "Saved the co-scrub partial to /media/usb."
    renders: "the notice banner"
    tag: accord_notice
  - ceg: x_private:read_error
    use: display-only
    type: string
    example: "accord read failed: 503"
    renders: "the error banner, in the error tone"
    tag: accord_error
```

**`accord:*` is RESERVED** — `accord_holder-only` at CC 3.4.1, owned by
`registry`/CIRISRegistry — so `display-only` on every accord row is *enforced*,
not chosen: an `emit` here fails the load. The writes this screen offers (halt,
drill, announce, cosign, admit) go out as loopback POSTs and the node signs with
the re-inserted YubiKey; the app holds no keys, so it never emits an `accord:*`
row and could not if it wanted to.

**The registry's `accord:*` family is exactly two segments** — `literal accord`
+ `wildcard *`. CC 4.2.1 names four-segment forms (`accord:invoke:drill:{drill_id}`,
`accord:lifecycle:active`), and `check_csd_v3.py` refuses them because
`_family_for` matches segment count. So the fields above bind the two-segment
forms the registry can express, and the four-segment CC vocabulary is
**unbindable in a CSD today**. That is a generator gap in CIRISConstitution, not
a fact about the accord, and it is recorded here rather than worked around.

**`hardware_custody:{platform}` has two vocabularies and they disagree.** The
registry's description enumerates `tpm / ios_secure_enclave / android_keystore /
software_fallback`; CC 4.2.2's hardware-class table enumerates
`HSM_FIPS_140_3_L3 / Apple_Secure_Enclave / YubiKey_5_FIPS / TPM_2_0 /
placeholder_pending_provisioning / software_hsm_development`. Neither set
contains the other, the casings are incompatible under CC 3.1.7 R3 (lowercase
vocabulary, byte-exact, **refuse never fold**), and the accord's own custody
class — a FIPS YubiKey with an ML-DSA seed on removable media — appears in the
CC table and not in the registry's. The bind above uses the lowercased CC token
and the CSD says plainly that it is not the registry's.

```yaml csd:states
populated: {tag: "card_accordfamily_${family.familyKeyId}", renders: "the family card, the holder roster under it, the trust-root row (CSD-105), the family's versions, canonical servers, pending co-signs and history"}
empty:     {tag: txt_accord_family_empty, renders: "No accord family established on this node yet."}
loading:   {tag: "proposed:spinner_accord_section", renders: "each section header carries its own progress affordance; sections do not print their empty sentence while loading"}
error:     {tag: accord_error, renders: "Could not read the accord. This is NOT a report that there is no halt."}
```

**All four are real now.** `accord_error` (`AccordScreen.kt`, the `Banner`) is a
genuine error banner in the error tone, distinct from every empty sentence on
the screen, and every empty sentence is tagged: `txt_accord_family_empty`
(added at the accord review — it was the one that was missed, and the one that
matters most: "this node has no accord" and "this node could not tell us about
its accord" are, on a kill switch, opposite facts), `txt_accord_holders_empty`,
`txt_accord_pending_empty`, `accord_canonical_empty`, `accord_coscrub_empty`,
`accord_history_empty`.

`loading` is genuinely handled — `SectionHeader(title, loading)` suppresses each
section's empty sentence while the read is in flight (`:330`, `:404`) — and it
is untagged.

### 2.1 What the halt banner already gets right

`accord_halt_banner` (`AccordScreen.kt:1790`) is a real tag on a real
`errorContainer` surface with a 2dp error border, rendered above everything and
never cleared by the app. The screen-local scope is documented in the code as a
known limit rather than presented as global. This is the one CC 4.2.1.2
treatment that is fully implemented, tagged and assertable.

### 2.2 The CC 4.2.1.2 defect, and what is left of it

When this CSD was written, `invocationBadge` and `attestationStyle` each
branched on three values with `else → notify`, so an `accord:lifecycle:active`
row — the **resumption** from a constitutional halt, the only sanctioned way
back (CC 4.2.1.3) — was drawn, in badge text and colour, as a `notify`. CC
4.2.1.2 forbids precisely that pairing by name.

**Fixed, in three places, each exhaustive over `InvocationKind`** (a fifth
kind is a compile error, never a silent notify): `invocationBadgeKey`
(`AccordScreen.kt`; `mobile.accord_kind_reactivated`, and
`mobile.accord_kind_unknown` for a kind this app has not heard of — CC 4.2.1.2's
*fail-closed default: an unknown kind is rendered as unknown, never as a
notify*), `attestationStyle` (`ui/components/Attestation.kt`; tertiary tone for
a resumption, plain surface with the strong outline for unknown), and — the
accord review's find — `invocationBindingKey`, the binding sentence under the
card, which had kept its `else → notify` (`mobile.accord_binding_reactivated`
/ `_unknown`, tag `txt_invocation_binding_<id>`). Pinned by
`AccordInvocationKindTest`: every kind has a distinct badge and a distinct
binding note, and a resumption is never notify's.

**One deviation stays, and it is the wire's.** CC 4.2.1.2 says a consumer
switches on the four kinds *byte-exactly, with no case-fold*; the registered
spellings are lowercase (CIRISConstitution#112, the `.v2` label).
`InvocationKind.fromWire` lowercases before it matches, because 0.5.217 still
emits `CONSTITUTIONAL` upper and the other kinds lower
(`models/federation/Accord.kt:95`), and a byte-exact switch today would badge
every real halt as unknown. The fold comes off when CIRISServer#691 (the
server's own lowercase cut) ships; `theThreeInvokeKindsKeepTheirBadgesInEitherCase`
is the test to invert at that moment.

### 2.3 English on the safety surface

Fixed at the accord review: `"Back"` on the back button
(`mobile.common_back`), the two save-handler notices
(`mobile.accord_coscrub_saved` / `_save_failed`) and the copied-partial notice
(`mobile.accord_coscrub_copied`); `ConstitutionalScreen.kt` is done in CSD-003
§2.2. **Open:** `AccordViewModel.kt` still composes ~25 notices and errors as
English literals (`"Couldn't load the accord: …"`, `"Withdrew … from the trust
root."`, the 401/403 sentences) — a view model cannot call the composable
`localizedString`, so the fix is the shape `ProvisionAccordHolderViewModel`
now uses (a key plus detail, localized by the screen), applied to every notice.
A 29-locale build that falls back to English on a kill-switch surface is telling
a non-English holder less than it thinks.

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25), route
literals in `src/accord.rs` and `src/accord_provision.rs`. The re-mint rows
(`genesis/*`, `final-genesis/*`) are verified against CIRISServer `e357f6bf`
(0.5.220, `FSD/FINAL_GENESIS.md`, `src/final_genesis.rs`) and persist v53.0.1
`federation/genesis/ceremony.rs` (2026-10-04); their line numbers are that
tree's.

| value | endpoint | owner | state |
|---|---|---|---|
| halt status | `GET /v1/accord/halt-status` | CIRISServer `src/accord.rs:2617` | **live** |
| the family | `GET /v1/accord/family` | `src/accord.rs:2628` | **live** |
| the holder roster | `GET /v1/accord-holders` | `src/accord.rs:2601` | **live** |
| pending invocations | `GET /v1/accord/invocations` | `src/accord.rs:2658` | **live** |
| history (drills, announces) | `GET /v1/accord/events` | `src/accord.rs:2618` | **live** |
| concur on an invocation | `POST /v1/accord/invocation/concur` | `src/accord.rs:2654` | **live** |
| raise a halt | `POST /v1/accord/halt` | `src/accord.rs:2616` | **live** |
| drill | `POST /v1/accord/drill` | `src/accord.rs:2609` | **live** — `initiateDrill` (`CIRISApiClient.kt:3793`) from `AccordViewModel.kt:240` |
| announce | `POST /v1/accord/announce` | `src/accord.rs:2611` | **live** — `initiateAnnounce` (`CIRISApiClient.kt:3906`) from `AccordViewModel.kt:319` (`AccordScreen.kt:1654`). The route-coverage report filed this under CSD-069; it is this screen's call |
| admit a node to the accord's directory | `POST /v1/accord/admit-node` | `src/accord_provision.rs:3704` | **live** — `admitNode` (`CIRISApiClient.kt:4048`) from `AccordViewModel.kt:402`, the mint sheet at `AccordScreen.kt:708`. Also filed under CSD-069 by the report |
| open an invocation | `POST /v1/accord/invocation` | `src/accord.rs:2650` | live, **not called** — the screen concurs on invocations (row above) and never opens one; `lifecycle:active` rides this route (below) |
| the canonical servers | `GET /v1/accord/canonical/servers` | `src/accord_provision.rs:3711` | **live, called** — `listCanonicalServers` from `AccordViewModel.kt:116, 509` |
| the mesh-seed op (a single holder's scrub) | `POST /v1/accord/canonical/add` | `src/accord_provision.rs:3707` (handler `:1619`) | **live, deliberately not called.** It is 1-of-N, and since persist v13.2.0 canonical admission is m-of-n — *"a single scrub no longer confers `canonical`"* (`:1785-1791`) — so a record minted here fails the admission gate on a fresh install (a worse break than the stale address it was reached for). The card's add AND its re-bless both go through propose → cosign (next row); CIRISServer#441 is the open decision on this op's quorum. `CIRISApiClient.addCanonicalServer` / `AccordViewModel.addCanonicalServer` are unreachable code until #441 is decided |
| co-scrub, scrub #1 | `POST /v1/accord/canonical/propose` | `src/accord_provision.rs:3717` | **live, called** — `proposeCanonical` from `AccordViewModel.kt:708`; `[+ New]` → *Propose a canonical server* (`AddCanonicalSheet`, target from the owned-nodes picker, defaulting to the first canonical so a re-bless is a confirm) |
| co-scrub, the next holder's scrub | `POST /v1/accord/canonical/cosign` | `src/accord_provision.rs:3721` | **live, called** — `cosignCanonical` from `AccordViewModel.kt:764`; from a pending row or `[+ New]` → paste (`CanonicalCosignSheet`) |
| the partials below quorum | `GET /v1/accord/canonical/pending` | `src/accord_provision.rs:3753` | **live, called** — `listPendingCoscrubs` from `AccordViewModel.kt:128, 676` |
| withdraw a canonical server | `POST /v1/accord/canonical/withdraw` | `src/accord_provision.rs:3758` (handler `:3535`) | **live, called** — `withdrawCanonical` from `AccordViewModel.kt:558`, behind `ConfirmDestructive` with the 2-of-3 proposal digest (`input_destructive_digest_<key>`) |
| the withdrawn / superseded history | `GET /v1/accord/canonical/withdrawals` | `src/accord_provision.rs:3766` | **live, called** — `listCanonicalWithdrawals` from `AccordViewModel.kt:122`; drawn in §4 History as `Withdrawn` / `Superseded` cards |
| rotate a canonical server to a successor | `POST /v1/accord/canonical/supersede` `{old_key_id, new_record, proposal_digest}` | `src/accord_provision.rs:3762` (handler `:3585`) | **live, called** (accord review) — `supersedeCanonical` from `AccordViewModel.kt:903`, from a canonical row's *Supersede* op → `SupersedeCanonicalSheet` (`dlg_supersede_canonical`: `input_supersede_record`, `input_supersede_digest`, `btn_supersede_review`; a non-object refused locally as `txt_supersede_not_json`) → ConfirmSheet `sheet_supersede_canonical` with three facts (`supersede_canonical_fact_{1,2,3}`: which server retires and which takes its place, what changes, who signs — the 2-of-3 proposal by digest). The successor is named from `record.key_id`, the field the server reads for its own `successor` (`:3603`) — `CanonicalSupersedeTest`. Response `{superseded, successor, authority_proposal_digest}` → `CanonicalSupersedeResponse`. **Before this review the row's Supersede op re-proposed the SAME key with a new address** — a re-bless, not the server's rotate; that door is now `[+ New]` → Propose, which only lists owned nodes (next row's caveat) |
| rebind a canonical's address | `POST /v1/accord/canonical/address` | `src/accord.rs:2632` (handler `:2454`, request `:2418-2440`: a holder-signed `{op:"canonical:address", canonical_key_id, transport_kind, destination, invocation_id, asserted_at, …}`) | live, **not called** — this is the route for an address re-bless of a canonical that is NOT one of the owner's nodes (the picker cannot resolve its keys); until it is wired, such a canonical's address changes only by a full propose → cosign from a device that owns it |
| the owner's nodes, for the target pickers | `GET /v1/setup/owned-nodes` | CIRISServer (`src/setup*.rs`) | **live, loopback-only, called** — `getOwnedNodes` from `AccordViewModel.kt:110`; admit / propose pick a target here. Off-host it 403s and the picker says `accord_canonical_no_owned`, which reads as "no owned nodes" rather than "could not ask" — open |
| the picked node's hybrid pubkeys | `GET /v1/federation/peers/{key_id}` | CIRISServer `src/federation*.rs` | **live, called** — `getFederationPeer` from `AccordViewModel.kt:360` (`resolveTargetNode`) and `:590` (`resolveCanonicalTarget`); a row missing its ML-DSA half is refused in words before any touch |
| (peer leg) receive a gossiped partial | `POST /v1/accord/canonical/gossip-partial` | `src/accord_provision.rs:3776` | live; the OPEN peer→peer counterpart of co-scrub, deliberately not loopback-gated — not a card act |
| confer a moderation duty | `POST /v1/accord/duty/{propose,cosign}` | `src/accord_duty.rs:648,649` | **live** — called from `DutyConferralViewModel.kt:335,375`, not from this screen's view model; `Screen.DutyConferral` maps to `NavSurface.Accord` (`CIRISApp.kt:5941`) and is reached from `[+ New]` (`CIRISApp.kt:4193`) |
| a holder's hardware custody class | **missing on the roster** — `list_holders` builds `HolderSummary {key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64}` (`src/accord.rs:664-668`) and `AccordHolderDto` (`models/federation/Accord.kt:53-60`) mirrors it exactly. **Served elsewhere for the charter's holders:** `GET /v1/trust-root` → `roots[].verdict.holders_hardware[]` `{key_id, class, layer_a, layer_b, refusal}` (verdict passed through verbatim, `src/trust_root_api.rs:76-79`; shape CIRISPersist v48.0.0 `federation/trust_root.rs:372`, the pin at `Cargo.toml:138`), loopback-only | CIRISServer | blocks `txt_holder_custody` on the roster; **called** on this node's own machine and drawn per holder in the trust-root detail (`row_trust_root_holder_<root>_<holder>`, `TrustRootScreen.kt`); **remote reach** `blocked_by: CIRISServer#652` |
| **a `lifecycle:active` row to render** | `GET /v1/accord/invocations` | CIRISServer | **live — the route serves it, and that makes §2.2 a shipped defect** |
| admit a node | `POST /v1/accord/admit-node` | `src/accord_provision.rs:3704` | **live**, loopback-only — called from `AccordViewModel.kt:402` (`[+ New]` → admit) |
| bless the CI build keys | `POST /v1/accord/ci-key/{propose,cosign}` | `src/accord_provision.rs:3729,3733` | **live**, loopback-only — `AccordViewModel.kt:827,874` |
| the re-mint pre-fill — holders, canonicals, the family's m-of-n | `GET /v1/accord/genesis/remint-source` | `src/accord_provision.rs:2965` (handler `:2384`; note `:2440` now says all three sign) | **live**, loopback-only — `getGenesisRemintSource` from `AccordViewModel.kt:958` (the 2-of-3 sheet) and `FinalGenesisViewModel.kt:173` (the final genesis: holders and their recovery rows, the serve-node chips) |
| **which re-mint this node runs** | `GET /v1/accord/final-genesis` | CIRISServer 0.5.220 `src/final_genesis.rs:854` (handler `:568`; unplanned → 404 `final_genesis.not_planned` at `:146`) | **live, called** — `getFinalGenesisStatus` from `FinalGenesisViewModel.kt` `open`/`refresh`/after every sign and refused finish; the sheet polls it every 5 s while a ceremony is planned. Decided by the route, never a version: a **bare** 404 (no body — 0.5.219 has no such route and mounts no fallback) keeps the 2-of-3 `RemintTrustRootSheet`; 404 `final_genesis.not_planned` or 200 is the final genesis; any other refusal (403 off the node's machine) is shown as `final_genesis_unavailable`, never taken for an old node (`finalGenesisProbe`, `FinalGenesisTest.onlyABare404IsAnOldNode`) |
| what the charter will commit as each holder's recovery key | `GET /v1/accord/final-genesis/recovery-keys` → `{complete, recovery_keys:[{holder_key_id, recovery_key_id, commitment, source}]}` (a holder with nothing on record is `{holder_key_id, recovery_key_id: null}` alone) | CIRISServer `29ca1bcf` (`test/final-genesis-e2e`, for 0.5.220) `src/final_genesis.rs:918` (handler `:877`, merge `effective_recovery_keys` `:123`) | **live, called, readable before a plan** — `getFinalGenesisRecoveryKeys` from `FinalGenesisViewModel.loadRecoveryKeys`, on open and after every token read. Each `final_genesis_recovery_<holder>` row shows the spare's id, a 16-hex prefix of `commitment` (persist's `recovery_commitment`, the exact value the charter carries in `recovery_commitments[holder]`) and its source — `record` *"on record"*, `hardware` *"verified with the token"*. `recovery_key_id: null` draws the row as missing and gates Plan on THAT holder only (`final_genesis_plan_blocked`). ONLY a bare 404 is a 0.5.220 build from before the route: the rows fall back to the A1→A2 pairing, *"on record"*, with no commitment until a token is read. Any other failure (5xx, a 404 with an id, no socket) draws no key the node did not supply: rows read *"not known"*, `final_genesis_recovery_error` + `btn_final_genesis_recovery_retry`, and Plan is held (`final_genesis_plan_blocked_unread`). A failed token read leaves the row as the node said it (a missing key stays missing) and shows the refusal beside it, `final_genesis_recovery_refusal_<holder>` |
| a holder's recovery key, read off the spare token | `POST /v1/accord/final-genesis/recovery-key` `{holder_key_id, recovery_key_id, mldsa_usb_path, pkcs11:{user_pin?, module_path?}}` → `{holder_key_id, recovery_key:{key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64}, recorded}` | `29ca1bcf` `src/final_genesis.rs:915` (handler `:468`; `recovery_key_mismatch` `:519`) | **live, called** — `verifyFinalGenesisRecoveryKey` from `FinalGenesisViewModel.verifyRecovery`, `btn_final_genesis_verify_<holder>` → `btn_final_genesis_verify_go_<holder>` (USB, PIN and the optional `input_final_genesis_recovery_module_<holder>`). Optional for a holder on record, required for one with nothing on record. The pairing is ENFORCED at 4da726e8 (`check_recovery_keys` `:128`): a seated holder reads only its own spare, so the form names it (`final_genesis_recovery_spare_<holder>`) and the request sends it whatever the node once listed; a wrong pair is 400 `final_genesis.recovery_key_wrong_holder`, one key for two holders 409 `final_genesis.recovery_key_shared` (both also from `plan`), shown by id. The PIN field is a SENSITIVE automation sink (`SensitiveInputs`): `/input` applies it, and neither the acknowledgement nor `/tree` / `/element` carries it. `plan` merges per holder (`effective_recovery_keys`): a key read here overlays the record for that holder only, so checking one spare and not the others still plans — the all-or-nothing file found on this PR is fixed upstream |
| plan the ceremony (stamp it once) | `POST /v1/accord/final-genesis/plan` `{serve_nodes:[{key_id, transport_hints:[{kind:"ip", destination:"host:port"}]}], clock_checked, replace?}` (CIRISServer e4cbedeb `ServeNodeSpec::WithHints` `:263`; at 814dd7c6 a hint counts only if it is `kind: ip` and its destination parses as Rust `SocketAddr` — `require_dial_hint` → `compose::ip_addrs_from_hints` — else 400 `final_genesis.serve_node_no_dial_hint`) → `{complete, signable_now, owed}` | `src/final_genesis.rs:859` (handler `:288`) | **live, called** — `planFinalGenesis` from `FinalGenesisViewModel.planNow`. Each seated canonical has a drivable `input_final_genesis_dial_<key>`, validated as the server's dialer parses it before Plan enables (`isDialHint`: `a.b.c.d:port` or `[ipv6]:port`, never a hostname; port 1–65535 where Rust also takes `:0`; no IPv6 zone) — `final_genesis_dial_invalid_<key>` otherwise. It is prefilled ONLY from that canonical's `transport_hints` in `remint-source` (`src/accord_provision.rs:2381`; on a node holding the July bake canonical-1 is served with its address), and re-read on every new open — an edit survives only a Retry in the same form. No address is compiled into the client; a canonical with no hint (a fresh canonical-2/-3) starts empty and its address is typed. `successor_keys` and `recovery_keys` are omitted: the node defaults both to the spares on record (`:347-366`). `clock_checked` is `true` only after the `sheet_final_genesis_clock` ConfirmSheet, opened by 412 `final_genesis.clock_unverified`, and only for that ceremony instant — a successful plan and every new Plan press reset it, so "Plan again" asks again; 412 `final_genesis.clock_not_synchronized` is a refusal no confirm overrides. `replace` is sent only on the one request the destructive `sheet_final_genesis_replace` ConfirmSheet approved, opened by 409 `final_genesis.already_planned`, and cleared however that request ends (reached by `btn_final_genesis_replan` over a planned ceremony) |
| sign everything a holder owes now | `POST /v1/accord/final-genesis/sign` `{key_id, mldsa_usb_path, pkcs11:{user_pin?, module_path?}}` → `{signed, owed, complete?}` | `src/final_genesis.rs:860` (handler `:711`) | **live, called** — `signFinalGenesis` from `FinalGenesisViewModel.sign`, `btn_final_genesis_sign_<holder>` on each holder's card, with the optional PKCS#11 module path `input_final_genesis_module_<holder>` (the hardware-scrub sheet's override, its keys reused); one YubiKey session per round. `complete` is absent on the dry run's software path (`:696`), so it is optional. 409 `final_genesis.nothing_to_sign` is drawn as *"Nothing to sign yet — waiting for B1, C1 to sign the charter"* (`final_genesis_note_<holder>`), not an error. The items are persist's (`ceremony.rs:560-663`): round one `record:<node>`, `row:genesis-charter`, `row:genesis-grant:<node>`, `row:genesis-lifecycle`; round two `family:humanity-accord`, `community:ciris-canonical`, `authz`, opening once no holder owes the charter |
| assemble, verify, write the bundle | `POST /v1/accord/final-genesis/finish` → `{complete, bundle_path, bundle_sha256, bundle, verified:{quorum_verified, serve_nodes, attestations, community_key_id, founders}}` (`bundle_json` — a string, the file's exact bytes — from CIRISServer e4cbedeb `:968`; the older `bundle` member of 4da726e8 still read as a fallback) | `src/final_genesis.rs:862` (handler `:794`) | **live, called** — `finishFinalGenesis` from `FinalGenesisViewModel.finish`, `btn_final_genesis_finish` (enabled when the status says `complete`). 409 `ceremony_incomplete` names what is owed and re-reads the status; 422 `ceremony_outputs_refused` is persist's `verify_ceremony_outputs` refusing. `bundle_sha256` is `sha256:<hex>` over the file's bytes — `final_genesis_bundle_sha256`, copyable (`btn_final_genesis_copy_sha`). `bundle` is copied (`btn_final_genesis_copy_bundle`) and saved where the platform can (`btn_final_genesis_save_bundle`, `saveFileCopy`; `final_genesis_bundle_save_unavailable` where it cannot) EXACTLY as sent — the raw member, or a string member's contents, never re-serialized. Whether those bytes are the hashed ones is MEASURED (SHA-256 against `bundle_sha256`) and said at `final_genesis_bundle_match`: at 4da726e8 they are not (the server re-serializes the file compact with sorted keys), so the sheet says *"same bundle, not the hashed bytes: the fingerprint is the file at <bundle_path>"*; raised with the server session |
| the retired 2-of-3 re-mint | `POST /v1/accord/genesis/{propose,cosign}` | `src/accord_provision.rs:2973,2977` → `remint_superseded` (`src/final_genesis.rs:842`) | **410 `accord.genesis_superseded` on 0.5.220**, still **called** on ≤0.5.219 nodes only — `AccordViewModel.kt:1049,1095`, from `RemintTrustRootSheet`, which the sheet router opens only on a bare 404 from the row above. Its response fields (`fingerprint`, `node_trusts_root`, `trust_edge_error`, `seed_path`, `seed_save_error`, `src/accord_provision.rs` before 0.5.220) stay modelled by `GenesisSeedResponse` for those nodes |
| this node's side of the root — posture, acceptance, adopt, un-trust | `GET /v1/trust-root` · `POST /v1/trust-root/import` · `DELETE /v1/trust-root/{root_key_id}` | `src/trust_root_api.rs:415-419` | **live on this node's own machine, called from `Screen.TrustRoot`, not from this screen** — the leaf behind `btn_accord_open_trust_root`. Its rows, refusal ids, the two ConfirmSheets and the `blocked_by: CIRISServer#652` remote-reach fact are **CSD-105 §3**; this card's part is the door |
| the family's supersede chain | `GET /v1/accord/family/history` | `src/accord.rs:2645` (handler `:2355`) | **live, called** — `getAccordFamilyHistory` from `AccordViewModel.kt:1137`, on the Accord card itself: `row_family_version_<n>`, `accord_family_history_{loading,empty,error,not_on_this_node}`. Not loopback-gated |
| change a seat by supersede | `POST /v1/accord/family/change/envelope`, `/v1/accord/family/supersede` | `src/accord.rs:2637,2641` | **live and refuses by design** for the only family it serves: `humanity-accord` answers 409 at `:2306-2315` (CIRISPersist#648). A seat changes by re-mint + import, not here — so the card should not offer it |
| open an invocation | `POST /v1/accord/invocation` | `src/accord.rs:2650` | **live, not called** — the client opens only through `/drill`, `/halt`, `/announce` and concurs through `/concur` (the §3 note below) |
| relying-node recognizers | `POST /v1/accord/verify-invocation`, `POST /v1/accord/message` | `src/accord.rs:2603,2606` | **live, not a holder's action** — unauthenticated peer/relying-node doors where the holder signatures are the authority (`:731-737`, `:2076-2080`); no card should call them |
| rebind a canonical's address | `POST /v1/accord/canonical/address` | `src/accord.rs:2632` | **live, not called** — the canonical row above cites it, but no `canonical/address` literal is in `CIRISApiClient.kt` |

### 3.1 The accord and `/v1/trust-root` — one root, two views

*(The second view is now its own card, CSD-105; this section keeps the reading
that justified building it, because the split is the reading.)*

**The accord family IS the default trust root, and `/v1/trust-root` is this
node's side of it.** `candidate_roots` always lists
`HUMANITY_ACCORD_FAMILY_KEY_ID` first (`src/trust_root_api.rs:156-157`), which is
`"humanity-accord"` (`ciris-verify-core v16.1.0 accord_genesis.rs:53`) — the same
keyless FAMILY this card shows, whose seats A1/B1/C1 hold the kill switch. The
server names this card the same way: *"A1/B1/C1 manage the canonical mesh from
the Trust Root card"* (`src/accord.rs:2371-2373`), and `NavSurface.Accord`'s
label is "Trust Root" (`EpistemicNav.kt:210`). There is no second, separate
anchor. What differs is the question:

* `/v1/accord/*` answers **what the root is** — its roster, quorum, invocations,
  halt latch, canonical servers — and is where holders act.
* `/v1/trust-root` answers **whether this node trusts it** — the node's own
  `trust:accepts` edge (CC 3.2 T3, `part_3_the_namespace.md:645`), persist's
  `trust_root_valid` verdict (charter quorum over the seated holders, charter
  recovery, halt latch, per-holder hardware, drill freshness as a banded signal
  per T4), the genesis `posture` (`entrenched` / `pre_genesis` / `divergent` /
  `unreadable`, CIRISPersist v48.0.0 `genesis/posture.rs:255`) and its banner — plus
  any other root this node has accepted by import.

CC 3.2 makes the second view a MUST: *"A conformant consumer MUST be able to
re-root: untrust the canonical group, pin a different … community instead or in
addition, or run with none"* (`part_3_the_namespace.md:583`), and T3 makes the
lever *one row*. `DELETE /v1/trust-root/{id}` is that row; `POST
/v1/trust-root/import` is the "instead or in addition". The server's own
refusal to supersede the family names the path the card lacks: *"run the
ceremony again … and adopt the bundle it produces: … POST /v1/trust-root/import on
each node"* (`src/accord.rs:2311-2313`). This card mints that bundle — on
0.5.220 by the 3-of-3 final genesis (`FinalGenesisSheet`, §4), before it by the
2-of-3 `RemintTrustRootSheet` — and its trust-root detail now adopts one
(`card_trust_root_import`). The node offers no preview of a seed before
installing it (the CIRISServer#404 comment), so the import confirm says so
(`trust_root_import_note`) instead of pretending to a fingerprint check.

**Placement.** The trust-root detail is `Screen.TrustRoot`, reached only from
`btn_accord_open_trust_root` on this card; `screenToSurface` maps it to
`NavSurface.Accord`, so Everyone › Safety › Accord stays lit. It is not a nav
row and not a new surface — it is a leaf with its own CSD (CSD-105) because it
is its own screen and the route checker keys cards by screen.

**Two defects this document found, both fixed on `fix/remint-fingerprint`:**

1. **The out-of-band fingerprint never renders.** CC 3.2 T5: *"out-of-band
   fingerprint comparison at attach time is a first-class step"*. The server
   returns `fingerprint` at the top level of the propose/cosign response
   (`src/accord_provision.rs:2592`); `GenesisSeedResponse`
   (`models/federation/Accord.kt:674`) does not model it, and
   `genesisSeedDisplay` reads `fingerprint` from inside the bundle
   (`Accord.kt:787`), where persist's `GenesisBundle` has no such field (v48.0.0
   `genesis/bundle.rs:121-130`). So `remint_done_fingerprint`
   (`AccordScreen.kt:1197-1213`) is always skipped by its own "only when the
   bundle carries one" guard. The fix is client-side.
2. **`node_trusts_root` / `trust_edge_error` are dropped.** On completion the
   minting node writes its own acceptance of the new root (`:2532-2545`), and a
   failure there is non-fatal and returned. The card says "done" either way.

**Two facts a builder on `GET /v1/trust-root` must know.** `RootEntry.accepted`
reads `user_accepts` from the verdict (`src/trust_root_api.rs:106-109`), a field
`TrustRootVerdict` does not have — it is `edge_exists` (CIRISPersist v48.0.0
`federation/trust_root.rs:553-561`) — so `accepted` is always `false` today.
And `root_kind` serializes as `Family` / `Key` (the enum has no `rename_all`,
`trust_root.rs:314`), not the lowercase the handler's doc comment promises
(`:71-72`). The first is CIRISServer#681; until it lands the client reads
`verdict.edge_exists` and ignores `accepted` (`trustRootView`, pinned by
`TrustRootTest.acceptanceIsReadFromEdgeExistsNotTheBrokenAcceptedField`). The
kind is compared case-insensitively.

**The last row said `unconfirmed` and the answer inverts the finding.**
`InvocationKind` is a FOUR-variant enum whose fourth is `LifecycleActive`,
`#[serde(rename = "lifecycle:active")]` — `ciris-verify-core
src/humanity_accord.rs:79-103` in the `v16.1.0` checkout pinned at
`CIRISServer Cargo.toml:171` — and its own doc comment at `:97` says it "rides
the same concurrence flow". Decisively: `create_invocation` → `open_invocation`
(`src/accord.rs:1215-1229`, `:1385-1441`) applies **no kind filter**, unlike
`/drill` (`:1269`), `/halt` (`:1350`) and `/announce` (`:1499`), each of which
rejects a mismatched kind by name; the object is keyed into `pending` by
`(invocation_kind, invocation_id)` (`:1418-1421`) and `list_invocations` echoes
the kind verbatim (`:1763`). The closed set `{CONSTITUTIONAL, notify, drill}`
this row previously cited is the CC 4.2.1.1 **canonical-bytes preimage domain**
(`humanity_accord.rs:92-94`), not the route's payload — `lifecycle:active` signs
a separate `LIFECYCLE_DOMAIN_PREFIX` (`:74`) and is still listed on the same read.

So §2.2 is not a deferred question: the `else` branch **is** badging resumptions
as `notify` today, on a route that can carry them. One caveat to carry into the
fix: the primary production resumption path is offline — `build_release_request`'s
`how_to_use` (`src/accord_release.rs:551-556`) and `main.rs:146-153` route it
through a token file and `ciris-server accord release --token`, never HTTP — and
the client calls only `/concur` (`CIRISApiClient.kt:3562`), never
`POST /v1/accord/invocation`. A lifecycle row therefore reaches the list when a
holder app opens one; the node never puts one there by itself. The flow must open
one to assert the fourth arm, which is why §5 disclaims it rather than §4.

**`hardware_custody` has three vocabularies, not two.** Beyond the registry's set
and CC 4.2.2's table, the wire carries `custody_tier: "portable_2fa"`
(`ciris-verify-core accord_custody_attestation.rs:70`, envelope key `:235`,
`ALLOWED_CUSTODY_TIERS` at `:105` containing only that one value). The
CIRISConstitution ask must name all three.

## 4. Flow (how)

Real tags only.

Land on `Accord` (Everyone › Safety, derived).

```yaml
expect:
  visible: [btn_accord_back, btn_accord_new]
```

On a node with no accord family — and never on a node that could not be read:

```yaml
expect:
  visible: [txt_accord_family_empty, txt_accord_holders_empty,
            accord_canonical_empty, accord_coscrub_empty, accord_history_empty]
  absent:  [accord_error]
```

Open `btn_accord_new` → the menu offers admit / add-canonical / bless-CI /
re-mint / confer-duty / drill / announce / halt, with confer-duty disabled while
`family == null` (`AccordScreen.kt:241`).

```yaml
expect:
  visible: [mi_new_admit_node, mi_new_drill, mi_new_announce, mi_new_halt]
```

Against a node with a latched halt:

```yaml
expect:
  state: populated
  visible: [accord_halt_banner]
```

*Cannot yet assert on a runner:* that a `lifecycle` invocation renders as its
own kind (§2.2) — the arms exist and are pinned by `AccordInvocationKindTest`,
but a lifecycle row reaches the list only when a holder app opens one through
`POST /v1/accord/invocation`, which this client never calls (§3). The binding
note's tag is `txt_invocation_binding_<id>` when a fixture can put one there.

On a canonical row, *Supersede* → the rotation form; paste a record that is
not JSON and press review — refused locally, nothing sent, no confirm:

```yaml
expect:
  visible: [dlg_supersede_canonical, txt_supersede_not_json]
  absent:  [sheet_supersede_canonical]
```

Paste a successor record whose `record.key_id` is `ciris-canonical-2-x9` and a
digest, review, and cancel — the confirm names both servers and sends nothing:

```yaml
expect:
  visible: [sheet_supersede_canonical, supersede_canonical_fact_1,
            supersede_canonical_fact_2, supersede_canonical_fact_3]
  text:
    supersede_canonical_fact_1: "ciris-canonical-2-x9"
```

**The re-mint (`[+ New]` → re-mint, `mi_new_remint_trust_root`) is two sheets,
chosen by the node.** On open the sheet asks `GET /v1/accord/final-genesis`: a
bare 404 is a ≤0.5.219 node and `sheet_remint_trust_root` (the 2-of-3 seed,
below) opens; anything else is CIRISServer 0.5.220's **final genesis**,
`sheet_final_genesis` (`FinalGenesisSheet.kt`, `FinalGenesisViewModel.kt`).
Draft flow: `testing/flows/drafts/csd-067-final-genesis.yaml`.

On a 0.5.220 node with nothing planned and the production roster, each holder's
recovery key is already filled from the record, with the commitment the charter will carry and an optional check (required only for a holder with nothing on record, which alone holds Plan):

```yaml
expect:
  visible: [sheet_final_genesis, final_genesis_recovery_a1, final_genesis_recovery_b1,
            final_genesis_recovery_c1, btn_final_genesis_verify_a1, btn_final_genesis_plan]
  absent:  [sheet_remint_trust_root, final_genesis_plan_blocked]
```

`btn_final_genesis_verify_<holder>` opens `input_final_genesis_recovery_usb_<holder>`,
`input_final_genesis_recovery_pin_<holder>` and `btn_final_genesis_verify_go_<holder>`;
a refusal (`final_genesis.recovery_key_mismatch`, `.not_a_holder`,
`.recovery_key_is_a_holder`, `.recovery_key_shared`, `.signer_unavailable`) is
drawn by id at `final_genesis_recovery_refusal_<holder>`. The serve nodes are
chips, `chip_final_genesis_serve_<key>`, the first canonical selected.

`btn_final_genesis_plan` → where the node cannot read its clock-sync state,
`sheet_final_genesis_clock` with three facts (`final_genesis_clock_fact_{1,2,3}`)
and `final_genesis_clock_note`; only its confirm sends `clock_checked: true`.
Over a planned ceremony, `btn_final_genesis_replan` → plan →
`sheet_final_genesis_replace` (destructive), and only its confirm sends
`replace: true`. Every other refusal is `final_genesis_error`, by id.

Planned, round one:

```yaml
expect:
  visible: [final_genesis_round, final_genesis_round_two_waits,
            btn_final_genesis_finish, btn_final_genesis_replan]
  text:
    final_genesis_round: "1"
  count: {of: "final_genesis_holder_state_*", eq: 3}
```

Each holder's card (`final_genesis_holder_<holder>`) shows one chip per item
(`final_genesis_cell_<holder>_<item>`: signed / sign now / waits for the
charter), `input_final_genesis_usb_<holder>`, `input_final_genesis_pin_<holder>`, the optional `input_final_genesis_module_<holder>`
(drivable, masked, never echoed to `/tree`) and `btn_final_genesis_sign_<holder>`,
enabled only while that holder owes something signable now. After a sign,
`final_genesis_note_<holder>` says what was signed, or — on
`final_genesis.nothing_to_sign` — whom the holder is waiting for. Round two
(`final_genesis_round` = `2`) opens only once all three have signed the charter.

Finished:

```yaml
expect:
  visible: [final_genesis_done_title, final_genesis_bundle_sha256, btn_final_genesis_copy_sha,
            final_genesis_bundle_path, final_genesis_verified_quorum,
            final_genesis_verified_serve_nodes, final_genesis_verified_attestations,
            final_genesis_verified_community, final_genesis_verified_founders]
  text:
    final_genesis_verified_community: "ciris-canonical"
    final_genesis_verified_founders: "3"
```

*Cannot yet assert on a runner:* anything past the plan. Each sign is a FIPS
YubiKey + USB ML-DSA session; the server's dry-run door
(`test_holder_seed_b64`, test-anchor builds with `CIRIS_TESTING_MODE=true`) is
not something the app sends. The states are pinned instead by
`FinalGenesisViewModelTest` (recovery verify ok / mismatch, clock_unverified →
confirm → `clock_checked: true`, already_planned → replace, the round-one grid,
round two opening only after three charters, nothing_to_sign, finish 409
incomplete, finish's facts, the bare-404 old node) and `FinalGenesisWireTest`
(each route's body on a real socket).

**The 2-of-3 seed (≤0.5.219 only).** After the second holder's cosign completes
the seed and this node's acceptance of the new root was written
(`node_trusts_root` non-empty):

```yaml
expect:
  visible: [remint_done_title, remint_node_trusts_root, remint_done_family,
            remint_done_holders, remint_done_serve_nodes, remint_seed_path]
  absent:  [remint_minted_untrusted]
```

and exactly one of the fingerprint's two renderings: `remint_done_fingerprint`
(with `remint_done_fingerprint_caption`, compare out of band — CC 3.2 T5) when
the node sent one, `remint_done_fingerprint_absent` when it did not. The DSL has
no "exactly one of" predicate, so the flow asserts the branch its fixture node
takes. Never a blank line.

When the seed completed but this node's acceptance was not written, the done
title is withheld and the node's reason is shown:

```yaml
expect:
  visible: [remint_minted_untrusted, remint_trust_edge_error]
  absent:  [remint_done_title, remint_node_trusts_root]
```

*Cannot yet assert on a runner:* any of the three — each needs two FIPS
YubiKeys touched in turn, on a ≤0.5.219 node. The done-state decision is pinned
instead by `RemintSeedResponseTest` (`remintOutcome`, `genesisSeedDisplay`).

On the card, the family's versions and the door to this node's trust root:

```yaml
expect:
  visible: [btn_accord_open_trust_root]
```

Open `btn_accord_open_trust_root` → `Screen.TrustRoot`; what it shows and
refuses is CSD-105 §4 (`testing/flows/drafts/csd-105-trust-root.yaml`).

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. A latched halt is the most prominent thing on the screen.
2. The four CC 4.2.1.2 kinds are four visibly different things.
3. "No accord here" and "could not read the accord" are different renderings.
4. A pending invocation shows how far from quorum it is.

**Not tested here.**
* **The signatures.** The 2-of-3 verification is the node's, against canonical
  bytes this app never assembles. A client fixture asserting quorum would be
  asserting against the wrong machine.
* **The final genesis past its plan** (§4) — every sign needs a holder's FIPS
  token, and finish needs all three twice. What the app does is pinned by
  `FinalGenesisViewModelTest` / `FinalGenesisWireTest`; that the bundle is
  right is persist's `verify_ceremony_outputs`, run by the node at finish.
* **A spare's commitment on a 0.5.220 build from before `GET …/recovery-keys`**
  — the row names the spare and says it is on record; it cannot show what it
  was never sent.
* **Acceptance 2 on a runner** — the four arms exist and are pinned at the
  model (`AccordInvocationKindTest`), but no fixture node lists a lifecycle
  row (§4), so the fourth rendering is asserted by the test and not the flow.
* **Acceptance 4** — `signed`/`threshold` reach `AttestationCard` and are drawn
  in an untagged provenance line.
* Whether the halt banner is app-global. It is screen-local and the code says so
  (`AccordScreen.kt:321-324`); a person on another screen during a latched halt sees
  nothing, and no test here should be read as covering that.
