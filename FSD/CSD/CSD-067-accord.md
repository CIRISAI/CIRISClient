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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
literals in `src/accord.rs` and `src/accord_provision.rs`.

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
| re-mint the root into a portable seed | `GET /v1/accord/genesis/remint-source`, `POST /v1/accord/genesis/{propose,cosign}` | `src/accord_provision.rs:3740,3744,3748` | **live**, loopback-only — `AccordViewModel.kt:958,1042,1088`; the `RemintTrustRootSheet` (`AccordScreen.kt:951`) |
| the seed's fingerprint, and this node's own acceptance of the root it just minted | the propose/cosign response: `fingerprint`, `node_trusts_root`, `trust_edge_error`, `seed_path`, `seed_save_error` | `src/accord_provision.rs:2530-2600` | **live, and read** (`fix/remint-fingerprint`): `GenesisSeedResponse` models all five; `remint_done_fingerprint` / `remint_done_fingerprint_absent`, `remint_node_trusts_root` / `remint_minted_untrusted` + `remint_trust_edge_error`, `remint_seed_path`, `remint_seed_save_error` — §4 |
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
each node"* (`src/accord.rs:2311-2313`). This card mints that bundle
(`RemintTrustRootSheet`), and its trust-root detail now adopts one
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

The re-mint sheet (`[+ New]` → re-mint, `sheet_remint_trust_root`), after the
second holder's cosign completes the seed and this node's acceptance of the new
root was written (`node_trusts_root` non-empty):

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
YubiKeys touched in turn. The done-state decision is pinned instead by
`RemintSeedResponseTest` (`remintOutcome`, `genesisSeedDisplay`).

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
* **Acceptance 2 on a runner** — the four arms exist and are pinned at the
  model (`AccordInvocationKindTest`), but no fixture node lists a lifecycle
  row (§4), so the fourth rendering is asserted by the test and not the flow.
* **Acceptance 4** — `signed`/`threshold` reach `AttestationCard` and are drawn
  in an untagged provenance line.
* Whether the halt banner is app-global. It is screen-local and the code says so
  (`AccordScreen.kt:321-324`); a person on another screen during a latched halt sees
  nothing, and no test here should be read as covering that.
