# CSD-067 — Accord (Trust Root) — the kill switch, its roster, and the five kinds

**CSD**: CSD-067 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten · **Splits from**: CSD-003 (this surface and the
Constitutional card are two cards in one tab, with two different jobs) ·
**Leaf**: CSD-105 (this node's trust root, `Screen.TrustRoot`), its own card
because it is its own screen and the route checker keys a card by the routes
its screen calls ·
**Leaf (sketched)**: CSD-108 (Steward purge, the proposed `Screen.StewardPurge`,
entered by the proposed `btn_accord_open_steward_purge`). It was built on this
card's co-scrub, which v62 retires; who signs a steward purge on v62, and at what
count, is OPEN (O3, below) (CIRISPersist#1046, CIRISConstitution#165)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: new-menu-removed-acts
  card: '[+ New] on v62 offers drill, announce, halt, confirm/resume, a later bundle, recovery and a re-bind; admit, add-canonical, bless-CI, re-mint and confer-duty are removed'
  code: 'AccordScreen still offers admit_node, add_canonical, bless_ci_workers, remint_trust_root and confer_duty'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ui/screens/AccordScreen.kt
  open_while: 'NewAttestationAction\("(admit_node|add_canonical|bless_ci_workers|remint_trust_root)"|"confer_duty"'
  blocked_on: 'O1 (0.6 route shapes)'
- id: halt-kinds-five
  card: 'five kinds; lifecycle:confirmed is the confirmation of the halt in force, never a fresh halt'
  code: 'InvocationKind has four kinds and no LIFECYCLE_CONFIRMED'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/Accord.kt
  open_until: 'LIFECYCLE_CONFIRMED'
  blocked_on: 'O1, O12'
- id: canonical-doors-deleted
  card: 'the canonical withdraw and supersede doors are deleted; only a later bundle un-seats a canonical'
  code: 'the client calls canonical/withdraw, /withdrawals and /supersede'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/canonical/(withdraw|withdrawals|supersede)'
- id: retired-accord-routes
  card: 'admit, canonical add/propose/cosign/pending, CI-key blessing and the re-mint are retired on v62'
  code: 'the client calls admit-node, canonical/{add,propose,cosign,pending}, ci-key/* and genesis/{remint-source,propose,cosign}'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/(admit-node|canonical/(add|propose|cosign|pending)|ci-key/|genesis/(remint-source|propose|cosign))'
  blocked_on: 'O1'
- id: ceremony-two-round
  card: 'the genesis is CEREMONY steps 1-5 (records, seat bindings, sealed halts, one voice per holder, the anchor)'
  code: 'the client drives the 0.5.x two-round final-genesis and its amend'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/api/CIRISApiClient.kt
  open_while: 'v1/accord/final-genesis'
  blocked_on: 'O1, O2'
- id: halt-standing-untyped
  card: 'a lone halt (lapses at T unless a strict majority confirms) and a confirmed halt are distinct states'
  code: 'AccordHaltStatusResponse keeps the standing latch as raw JSON; lone and confirmed are not typed'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/Accord.kt
  open_while: 'val halt: kotlinx\.serialization\.json\.JsonObject\?'
  model: root.halt.latched_lone root.halt.confirmed
  blocked_on: 'O1 (0.6 halt-status shape)'
```

**Model.** What this card draws and offers, cited from CIRISConstitution's formal model (pinned in `packaging/model/`) and held to it by `packaging/check_csd_model.py`: a modelled state the card leaves out, or a signer, policy or confirm that differs, fails CI.

```yaml csd:model
- var: root.halt
  states: 'not_halted latched_lone confirmed'
  code: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/Accord.kt
  code.not_halted: 'val halted: Boolean'
- act: 'accord:invoke:constitutional:{halt_id} attest'
  roles: 'holder'
  policy: 'mirror'
  confirm: 'true'
- act: 'accord:invoke:drill:{drill_id} attest'
  roles: 'holder'
  policy: 'selfAct'
  confirm: 'true'
- act: 'accord:invoke:notify:{notify_id} attest'
  roles: 'holder'
  policy: 'selfAct'
  confirm: 'true'
- act: 'accord:lifecycle:{kind} attest'
  roles: 'holder'
  policy: 'mirror'
  confirm: 'true'
- act: 'transport:canonical_rebind attest'
  roles: 'holder'
  policy: 'cosigned'
  confirm: 'true'
```

## 0. v62 (re-genesis) status

**This card's built state targets the 0.5.x accord routes. On v62 most of them
are retired, and this section says what is true there.** Audit: the CIRISServer
session's trust-root edit list (2026-11-21), read against CIRISClient
`4806bd5a`; sources are cited inline the same way.

**Total re-genesis.** This edition begins a new mesh. Nothing of the earlier
mesh carries: its roots, bundles (v1–v4), holders A1/B1/C1, canonical records
and family rows give no standing here. A node upgrading to it deletes its
earlier store and starts before its genesis, and an earlier-form bundle offered
to it is refused as `earlier_form_object`. (CEREMONY intro and §4; CC 2.6.1.12;
persist `src/store/edition_upgrade.rs:257` `delete_earlier_store`;
`edition2/genesis.rs:1745`.)

**Counts.** Every act after the genesis counts at **a strict majority of the
standing accord roster (2·yes > N; N is read from the holders of the bundle in
force — 3 at the seed, so 2 today)** (constants.tsv `root_conferral_threshold`
row 8, `accord_holder_count` row 6; CC 4.2.6). The founding genesis is
different: **every holder signs (a first bundle, version 1, no prev)**
(constants.tsv `charter_change_threshold` row 24; CC 5.3.4; CEREMONY step 4).
The two are never conflated, and N and m are shown as numbers the node reports,
never as a literal. A fire (`constitutional`, `notify`, `drill`) is ONE holder
and has no count.

**What v62 removes from this card.** The co-scrub, the canonical add / propose /
cosign / withdraw / supersede doors (withdraw and supersede are **deleted**,
Eric's ruling: only a later bundle un-seats or replaces a canonical), admit-node,
the family rows, the 2-of-3 re-mint, the amend, CI-key blessing (deferred to
v62.1, held) and duty conferral (duties come from the trust root, not the
accord; CC 4.2.1 line 108).

**What v62 adds.** The fifth kind `lifecycle:confirmed` and the fuse (§2.2);
halt coverage before publish, signed dial hints per seat, the recovery-key
rules, the anchor (thumbprints, then the object id), the voice `sub`, SCITT off
at genesis, and the first drill within 7 days (§4, *The v62 genesis
ceremony*).

**Routes.** Server 0.6 has no trust-root routes written yet. Every §3 row this
card drives is marked retired, deleted, or **0.6: pending (CIRISServer)**; no
path is invented here. The rows stay because the client code still calls them
(`check_csd_routes.py`); removing those calls is client work for the v62 build.

**OPEN, with who decides:**

| # | Question | Decides |
|---|---|---|
| O1 | Every v62 server route shape (ceremony, import, bundle, halt, holders, seats, re-bind, later bundle, recovery) | CIRISServer (0.6 ceremony crate) |
| O2 | Which card hosts the v62 ceremony: this card's `FinalGenesisSheet` or CSD-069 | Eric / CIRISClient |
| O3 | Who signs a trust-root duty grant and a steward purge, and at what count | CIRISConstitution (+ persist reader) |
| O5 | Transparency-service charter members and the receipt shape (SCITT, rc22) | CIRISConstitution |
| O6 | What `GET /v1/trust-root` lists first | CIRISServer |
| O8 | Whether the registry generator now binds four-segment `accord:*` families | CIRISConstitution |
| O9 | Which screen mints a holder's recovery key | CIRISClient / CIRISServer |
| O10 | Whether accord holders founding `ciris-canonical` and signing its rows is their accord authority or a separate founder role on the same key | CIRISConstitution — **answered**: they act as founders, at the community's `quorum:M/N` (M a strict majority of the founders), never with accord authority (CC 4.2.1, 3.2, 4.4.3.18) |
| O11 | Which moderation rungs refuse a node signer on v62, and the moderator-signed route shape (CSD-065) | CIRISConstitution (families), CIRISServer (route) |
| O12 | Tokens for "lifecycle names no halt in force" and "confirmed after the fuse" | persist / CIRISConstitution |
| O13 | Whether the server publishes ceremony halt-coverage evidence for CSD-003's "armed" | CIRISServer / CIRISConstitution |
| O14 | The reading of "duties come from the trust root": the trust-root community's authority set for root-scope duties, each community's own authority set for its own (CC 3.4.7.3) | Eric (CIRISConstitution reads it narrowly: `ciris-canonical`'s set covers `infra:serve`, `infra:attest` and the root's room; CC 4.5.4 iv–v) |
| O15 | Whether a goals declaration is an act a constitutional halt stops (CSD-130) | CIRISConstitution |
| O16 | The emergency composite shutdown's names and compose route | persist, CIRISServer |

## 1. Mission (why)

**A person can see whether the kill switch is latched, who the holders are, what
is pending and how far from quorum it is — and can tell an emergency halt from a
drill, a notice, and a resumption at a glance.** Serves **Core Identity** and
**Integrity**.

The last clause is not a nicety. CC 4.2.1.2 is a **normative consumer-UI
requirement**, written because wire isolation alone does not stop a `notify`
from carrying CONSTITUTIONAL social weight:

On v62 the requirement covers **five** kinds — `constitutional`, `notify`,
`drill`, `lifecycle:confirmed`, `lifecycle:active` — lowercase, byte-exact, with
a fail-closed default (CC 4.2.1.2, part_4:163-170). `lifecycle:confirmed` MUST
be shown as the confirmation of the named halt already in force, never as a
fresh halt and never as a notify; `lifecycle:active` is the "reactivated —
resumed from constitutional halt" state, never conflated with an active halt
(its opposite) nor with a `notify`.

The badge, the card tone and the binding sentence under the card are each
exhaustive over the four kinds the built code knows; the fifth is a gap (§2.2),
and §2.2 also records the one deviation that remains.

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
    renders: "ACTIVE HALT — node execution halted by invocation inv-7c19; on v62 a lone halt also shows when it lapses unless a strict majority confirms it (§2.2)"
    tag: accord_halt_banner
  - ceg: "accord:family"
    use: display-only
    type: string
    example: "humanity-accord"
    renders: "v62: the accord is the holders of the genesis bundle in force (N, from the bundle); its acts after the genesis count at a strict majority of the standing roster. No family row and no consensus_protocol on v62 (persist edition2/trust_root_group.rs:5-12)"
    tag: "card_accordfamily_${family.familyKeyId}"
  - ceg: "accord:holders"
    use: display-only
    type: "list[string]"
    example: ["wa-holder-1c4f", "wa-holder-9b02", "wa-holder-33da"]
    renders: "one card per holder, with its hardware custody. v62: a holder is the thumbprint of its composite key; a recovery key is a commitment in the charter's recovery_commitments, never shown as a seat (CC 4.2.6 line 343)"
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
    renders: "1 of m signatures — pending (m = a strict majority of N, both from the node). Counted rows only: lifecycle confirmed/active, later bundles, re-binds. A fire is one holder and never shows pending (persist edition2/halt.rs:4-7, :31-45)"
    tag: "proposed:txt_invocation_quorum"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey_5_fips}
    use: display-only
    type: string
    example: "yubikey_5_fips"
    renders: "v62: hardware_class YubiKey_5_FIPS; the ML-DSA-65 half removable_offline on separate media; the chain verified to Yubico Attestation Root 1. One device per holder, judged by the leaf serial (holder_device_shared, CC 4.2.3); the same f9 attestation certificate over distinct device serials is valid"
    tag: "proposed:txt_holder_custody"
  - ceg: x_private:notice
    use: display-only
    type: string
    example: "Drill sent."
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
not chosen: an `emit` here fails the load. The writes this screen offers go out
as loopback POSTs and the node signs with the re-inserted YubiKey; the app holds
no keys, so it never emits an `accord:*` row and could not if it wanted to. On
v62 the writes are halt, drill and announce (one holder each), and holder
statements over counted rows (lifecycle, later bundle, re-bind). There is no
admit and no cosign of another's record. A holder signs ONE composite (ML-DSA-65
+ Ed25519; the YubiKey signs the 127-byte representative) (CC 2.4.1.1;
CEREMONY §2).

**The registry's `accord:*` family is exactly two segments** — `literal accord`
+ `wildcard *`. CC 4.2.1 names four-segment forms (`accord:invoke:drill:{drill_id}`,
`accord:lifecycle:active`), and `check_csd_v3.py` refuses them because
`_family_for` matches segment count. So the fields above bind the two-segment
forms the registry can express, and the four-segment CC vocabulary is
**unbindable in a CSD today**. That is a generator gap in CIRISConstitution, not
a fact about the accord, and it is recorded here rather than worked around.
v62 persist registers `accord:lifecycle:{kind}` (namespace, `registry.rs:1148`);
whether CSD binding now resolves against the rc20 registry is **OPEN (O8,
CIRISConstitution)**.

**`hardware_custody:{platform}` has two vocabularies and they disagree.** The
registry's description enumerates `tpm / ios_secure_enclave / android_keystore /
software_fallback`; CC 4.2.2's hardware-class table enumerates
`HSM_FIPS_140_3_L3 / Apple_Secure_Enclave / YubiKey_5_FIPS / TPM_2_0 /
placeholder_pending_provisioning / software_hsm_development`. Neither set
contains the other, the casings are incompatible under CC 3.1.7 R3 (lowercase
vocabulary, byte-exact, **refuse never fold**), and the accord's own custody
class — a FIPS YubiKey with an ML-DSA seed on removable media — appears in the
CC table and not in the registry's. The bind above uses the lowercased CC token
and the CSD says plainly that it is not the registry's. On v62 the holder's
record states `hardware_class` + `pq_custody: removable_offline` + `chain`
(DER, leaf first), and the class is verified at seating (CEREMONY §1; CC 4.2.3).

```yaml csd:states
populated: {tag: "card_accordfamily_${family.familyKeyId}", renders: "the family card, the holder roster under it, the trust-root row (CSD-105), the seats of the bundle in force (canonical by the bundle's count), held counted rows short of their count, and history (v62: CC 4.2.1 line 90; persist edition2/genesis.rs:513)"}
empty:     {tag: txt_accord_family_empty, renders: "No accord family established on this node yet."}
loading:   {tag: "proposed:spinner_accord_section", renders: "each section header carries its own progress affordance; sections do not print their empty sentence while loading"}
error:     {tag: accord_error, renders: "Could not read the accord. This is NOT a report that there is no halt."}
```

**All four are real now.** `accord_error` (`AccordScreen.kt`, the `Banner`) is a
genuine error banner in the error tone, distinct from every empty sentence on
the screen, and every empty sentence is tagged: `txt_accord_family_empty`
(the one that matters most: "this node has no accord" and "this node could not
tell us about its accord" are, on a kill switch, opposite facts), `txt_accord_holders_empty`,
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

### 2.2 The CC 4.2.1.2 kinds (five on v62), and one deviation

**v62 gap: the fifth kind and the fuse.** `lifecycle:confirmed` is new on v62:
the confirmation of the named halt already in force, never a fresh halt (CC
4.2.1.2). The built `InvocationKind` has four arms, so the client needs a fifth
badge, tone and binding sentence. A halt also has a fuse on v62: a lone halt
shows "lapses at T unless a strict majority confirms" (the bundle's
`halt_fuse_secs`, default 86 400 s, counted from receipt on this node); a
confirmed halt shows "stands until resumed"; a lapsed halt shows "lapsed" (CC
4.2.1.1, 4.2.1.3; persist `halt.rs:38-60`).

An `accord:lifecycle:active` row — the **resumption** from a constitutional
halt, the only sanctioned way back (CC 4.2.1.3) — is never drawn, in badge text
or colour, as a `notify`: CC 4.2.1.2 forbids precisely that pairing by name.

**Three places, each exhaustive over `InvocationKind`** (a fifth
kind is a compile error, never a silent notify): `invocationBadgeKey`
(`AccordScreen.kt`; `mobile.accord_kind_reactivated`, and
`mobile.accord_kind_unknown` for a kind this app has not heard of — CC 4.2.1.2's
*fail-closed default: an unknown kind is rendered as unknown, never as a
notify*), `attestationStyle` (`ui/components/Attestation.kt`; tertiary tone for
a resumption, plain surface with the strong outline for unknown), and
`invocationBindingKey`, the binding sentence under the
card (`mobile.accord_binding_reactivated`
/ `_unknown`, tag `txt_invocation_binding_<id>`). Pinned by
`AccordInvocationKindTest`: every kind has a distinct badge and a distinct
binding note, and a resumption is never notify's.

**One deviation stays, and it is the wire's.** CC 4.2.1.2 says a consumer
switches on the four kinds *byte-exactly, with no case-fold*; the registered
spellings are lowercase (CIRISConstitution#112, the `.v2` label).
`InvocationKind.fromWire` lowercases before it matches, because 0.5.217
emits `CONSTITUTIONAL` upper and the other kinds lower
(`models/federation/Accord.kt:95`), and a byte-exact switch would badge
every real halt as unknown. The fold comes off with CIRISServer#691 (the
server's own lowercase cut); `theThreeInvokeKindsKeepTheirBadgesInEitherCase`
is the test to invert then.

### 2.3 English on the safety surface

Localized: `"Back"` on the back button
(`mobile.common_back`), the two save-handler notices
(`mobile.accord_coscrub_saved` / `_save_failed`) and the copied-partial notice
(`mobile.accord_coscrub_copied`); `ConstitutionalScreen.kt` is covered in CSD-003
§2.2. **Open:** `AccordViewModel.kt` composes ~25 notices and errors as
English literals (`"Couldn't load the accord: …"`, `"Withdrew … from the trust
root."`, the 401/403 sentences) — a view model cannot call the composable
`localizedString`, so the fix is the shape `ProvisionAccordHolderViewModel`
uses (a key plus detail, localized by the screen), applied to every notice.
A 29-locale build that falls back to English on a kill-switch surface is telling
a non-English holder less than it thinks.

## 3. Contracts (who)

**v62 (§0).** Every row below describes the 0.5.x build this card was verified
against, and starts with what v62 makes of it: retired, deleted, or re-implemented
with its shape **0.6: pending (CIRISServer)** (O1). The final-genesis module
cited below (persist `federation/genesis/ceremony.rs`) no longer exists on v62:
persist `genesis/mod.rs` exports only `posture`. Re-verify every row against
server 0.6 once its routes exist.

Verified against ciris-server 0.5.217, route literals in `src/accord.rs` and
`src/accord_provision.rs`. The re-mint rows (`genesis/*`, `final-genesis/*`)
are verified against CIRISServer 0.5.220 (`FSD/FINAL_GENESIS.md`,
`src/final_genesis.rs`) and persist v53.0.1 `federation/genesis/ceremony.rs`;
their line numbers are that tree's.

| value | endpoint | owner | state |
|---|---|---|---|
| halt status | `GET /v1/accord/halt-status` | CIRISServer `src/accord.rs:2617` | **v62:** re-implemented over persist's statement door; reads `halt_in_force`. **0.6: pending (CIRISServer)**. **live** |
| the family | `GET /v1/accord/family` | `src/accord.rs:2628` | **v62: retired** — no family row; the holders of the bundle in force replace it. **0.6: pending (CIRISServer)**. **live** |
| the holder roster | `GET /v1/accord-holders` | `src/accord.rs:2601` | **v62:** re-pointed at the bundle's holders. **0.6: pending (CIRISServer)**. **live** |
| pending invocations | `GET /v1/accord/invocations` | `src/accord.rs:2658` | **v62:** re-implemented over persist's statement door. **0.6: pending (CIRISServer)**. **live** |
| history (drills, announces) | `GET /v1/accord/events` | `src/accord.rs:2618` | **v62:** re-implemented over persist's statement door. **0.6: pending (CIRISServer)**. **live** |
| concur on an invocation | `POST /v1/accord/invocation/concur` | `src/accord.rs:2654` | **v62:** re-implemented; a concurrence is a holder's statement over a counted row. **0.6: pending (CIRISServer)**. **live** |
| raise a halt | `POST /v1/accord/halt` | `src/accord.rs:2616` | **v62:** re-implemented; one holder, no count. **0.6: pending (CIRISServer)**. **live** |
| drill | `POST /v1/accord/drill` | `src/accord.rs:2609` | **v62:** re-implemented; one holder, no count. **0.6: pending (CIRISServer)**. **live** — `initiateDrill` (`CIRISApiClient.kt:3793`) from `AccordViewModel.kt:240` |
| announce | `POST /v1/accord/announce` | `src/accord.rs:2611` | **v62:** re-implemented; one holder, no count. **0.6: pending (CIRISServer)**. **live** — `initiateAnnounce` (`CIRISApiClient.kt:3906`) from `AccordViewModel.kt:319` (`AccordScreen.kt:1654`); it is this screen's call |
| admit a node to the accord's directory | `POST /v1/accord/admit-node` | `src/accord_provision.rs:3704` | **v62: retired** — no one signs another's record. **live** — `admitNode` (`CIRISApiClient.kt:4048`) from `AccordViewModel.kt:402`, the mint sheet at `AccordScreen.kt:708` |
| open an invocation | `POST /v1/accord/invocation` | `src/accord.rs:2650` | **v62:** re-implemented over persist's statement door. **0.6: pending (CIRISServer)**. live, **not called** — the screen concurs on invocations (row above) and never opens one; `lifecycle:active` rides this route (below) |
| the canonical servers | `GET /v1/accord/canonical/servers` | `src/accord_provision.rs:3711` | **v62:** re-pointed at the seats of the bundle in force. **0.6: pending (CIRISServer)**. **live, called** — `listCanonicalServers` from `AccordViewModel.kt:116, 509` |
| the mesh-seed op (a single holder's scrub) | `POST /v1/accord/canonical/add` | `src/accord_provision.rs:3707` (handler `:1619`) | **v62: retired.** A seat is added only by a later bundle, counted at a strict majority of the predecessor's holders; CIRISServer#441 is closed by v62 (Verify `genesis.rs:1505-1517`). Later bundle: **0.6: pending (CIRISServer)**. **live, deliberately not called.** It is 1-of-N, and since persist v13.2.0 canonical admission is m-of-n — *"a single scrub no longer confers `canonical`"* (`:1785-1791`) — so a record minted here fails the admission gate on a fresh install (a worse break than the stale address it was reached for). The card's add AND its re-bless both go through propose → cosign (next row); CIRISServer#441 is the open decision on this op's quorum. `CIRISApiClient.addCanonicalServer` / `AccordViewModel.addCanonicalServer` are unreachable code until #441 is decided |
| co-scrub, scrub #1 | `POST /v1/accord/canonical/propose` | `src/accord_provision.rs:3717` | **v62: retired** with the co-scrub. Later bundle: **0.6: pending (CIRISServer)**. **live, called** — `proposeCanonical` from `AccordViewModel.kt:708`; `[+ New]` → *Propose a canonical server* (`AddCanonicalSheet`, target from the owned-nodes picker, defaulting to the first canonical so a re-bless is a confirm) |
| co-scrub, the next holder's scrub | `POST /v1/accord/canonical/cosign` | `src/accord_provision.rs:3721` | **v62: retired** with the co-scrub. Later bundle: **0.6: pending (CIRISServer)**. **live, called** — `cosignCanonical` from `AccordViewModel.kt:764`; from a pending row or `[+ New]` → paste (`CanonicalCosignSheet`) |
| the partials below quorum | `GET /v1/accord/canonical/pending` | `src/accord_provision.rs:3753` | **v62: retired** with the co-scrub; held counted rows replace it. **0.6: pending (CIRISServer)**. **live, called** — `listPendingCoscrubs` from `AccordViewModel.kt:128, 676` |
| withdraw a canonical server | `POST /v1/accord/canonical/withdraw` | `src/accord_provision.rs:3758` (handler `:3535`) | **v62: deleted (Eric's ruling).** Only a later bundle un-seats a canonical (CC 4.2.1 line 90). **live, called** — `withdrawCanonical` from `AccordViewModel.kt:558`, behind `ConfirmDestructive` with the 2-of-3 proposal digest (`input_destructive_digest_<key>`) |
| the withdrawn / superseded history | `GET /v1/accord/canonical/withdrawals` | `src/accord_provision.rs:3766` | **v62: deleted (Eric's ruling)**, with the withdraw and supersede doors. **live, called** — `listCanonicalWithdrawals` from `AccordViewModel.kt:122`; drawn in §4 History as `Withdrawn` / `Superseded` cards |
| rotate a canonical server to a successor | `POST /v1/accord/canonical/supersede` `{old_key_id, new_record, proposal_digest}` | `src/accord_provision.rs:3762` (handler `:3585`) | **v62: deleted (Eric's ruling).** Only a later bundle replaces a canonical (CC 4.2.1 line 90). **live, called** — `supersedeCanonical` from `AccordViewModel.kt:903`, from a canonical row's *Supersede* op → `SupersedeCanonicalSheet` (`dlg_supersede_canonical`: `input_supersede_record`, `input_supersede_digest`, `btn_supersede_review`; a non-object refused locally as `txt_supersede_not_json`) → ConfirmSheet `sheet_supersede_canonical` with three facts (`supersede_canonical_fact_{1,2,3}`: which server retires and which takes its place, what changes, who signs — the 2-of-3 proposal by digest). The successor is named from `record.key_id`, the field the server reads for its own `successor` (`:3603`) — `CanonicalSupersedeTest`. Response `{superseded, successor, authority_proposal_digest}` → `CanonicalSupersedeResponse`. A re-bless of the SAME key with a new address is not a rotate; its door is `[+ New]` → Propose, which only lists owned nodes (next row's caveat) |
| rebind a canonical's address | `POST /v1/accord/canonical/address` | `src/accord.rs:2632` (handler `:2454`, request `:2418-2440`: a holder-signed `{op:"canonical:address", canonical_key_id, transport_kind, destination, invocation_id, asserted_at, …}`) | **v62: retired.** A seat's transit key moves only by a counted re-bind (`transport:canonical_rebind`, a strict majority of the holders of the bundle in force) or by a later bundle; its dial hints change only the same way, and its live signed route row supersedes them once a link is up (CC part_5:738; persist `edition2/transport_rebind.rs:20-36`, `:85`). Re-bind: **0.6: pending (CIRISServer)**. live, **not called** — this is the route for an address re-bless of a canonical that is NOT one of the owner's nodes (the picker cannot resolve its keys); until it is wired, such a canonical's address changes only by a full propose → cosign from a device that owns it |
| the owner's nodes, for the target pickers | `GET /v1/setup/owned-nodes` | CIRISServer (`src/setup*.rs`) | **live, loopback-only, called** — `getOwnedNodes` from `AccordViewModel.kt:110`; admit / propose pick a target here. Off-host it 403s and the picker says `accord_canonical_no_owned`, which reads as "no owned nodes" rather than "could not ask" — open |
| the picked node's hybrid pubkeys | `GET /v1/federation/peers/{key_id}` | CIRISServer `src/federation*.rs` | **live, called** — `getFederationPeer` from `AccordViewModel.kt:360` (`resolveTargetNode`) and `:590` (`resolveCanonicalTarget`); a row missing its ML-DSA half is refused in words before any touch |
| (peer leg) receive a gossiped partial | `POST /v1/accord/canonical/gossip-partial` | `src/accord_provision.rs:3776` | **v62: retired.** live; the OPEN peer→peer counterpart of co-scrub, deliberately not loopback-gated — not a card act |
| confer a moderation duty | `POST /v1/accord/duty/{propose,cosign}` | `src/accord_duty.rs:648,649` | **v62: retired from the accord.** Duties come from the trust root's `trust:confers:v1` on the delegation plane (Eric's ruling; CC 4.2.1 line 108); **0.6: pending (CIRISServer)**; who signs it is OPEN (O3). **live** — called from `DutyConferralViewModel.kt:335,375`, not from this screen's view model; `Screen.DutyConferral` maps to `NavSurface.Accord` (`CIRISApp.kt:5941`) and is reached from `[+ New]` (`CIRISApp.kt:4193`) |
| a holder's hardware custody class | **missing on the roster** — `list_holders` builds `HolderSummary {key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64}` (`src/accord.rs:664-668`) and `AccordHolderDto` (`models/federation/Accord.kt:53-60`) mirrors it exactly. **Served elsewhere for the charter's holders:** `GET /v1/trust-root` → `roots[].verdict.holders_hardware[]` `{key_id, class, layer_a, layer_b, refusal}` (verdict passed through verbatim, `src/trust_root_api.rs:76-79`; shape CIRISPersist v48.0.0 `federation/trust_root.rs:372`, the pin at `Cargo.toml:138`), loopback-only | CIRISServer | **v62:** the class is verified at seating (chain to Yubico Root 1, touch-always, FIPS, leaf SPKI = the composite's Ed25519 half; Verify `edition2/genesis.rs:2202` `custody_gate`). Whether `holders_hardware` keeps Layer A/B naming is OPEN (O7, persist / CIRISServer). blocks `txt_holder_custody` on the roster; **called** on this node's own machine and drawn per holder in the trust-root detail (`row_trust_root_holder_<root>_<holder>`, `TrustRootScreen.kt`); **remote reach** `blocked_by: CIRISServer#652` |
| **a `lifecycle:active` row to render** | `GET /v1/accord/invocations` | CIRISServer | **v62:** lifecycle rows (`confirmed` and `active`) are counted (§3.1). **0.6: pending (CIRISServer)**. **live — the route serves it** (§2.2) |
| admit a node | `POST /v1/accord/admit-node` | `src/accord_provision.rs:3704` | **v62: retired** — no one signs another's record. **live**, loopback-only — called from `AccordViewModel.kt:402` (`[+ New]` → admit) |
| bless the CI build keys | `POST /v1/accord/ci-key/{propose,cosign}` | `src/accord_provision.rs:3729,3733` | **v62: retired.** Pipeline blessing is deferred to v62.1 (held); no route on 0.6. **live**, loopback-only — `AccordViewModel.kt:827,874` |
| the re-mint pre-fill — holders, canonicals, the family's m-of-n | `GET /v1/accord/genesis/remint-source` | `src/accord_provision.rs:2965` (handler `:2384`; note `:2440` says all three sign) | **v62: retired.** There is no family m-of-n; m is derived from N. **live**, loopback-only — `getGenesisRemintSource` from `AccordViewModel.kt:958` (the 2-of-3 sheet) and `FinalGenesisViewModel.kt:173` (the final genesis: holders and their recovery rows, the serve-node chips) |
| **which re-mint this node runs** | `GET /v1/accord/final-genesis` | CIRISServer 0.5.220 `src/final_genesis.rs:854` (handler `:568`; unplanned → 404 `final_genesis.not_planned` at `:146`) | **v62:** the ceremony is re-implemented on persist `ceremony` (CEREMONY §3; §4 below). **0.6: pending (CIRISServer)** (O1). **live, called** — `getFinalGenesisStatus` from `FinalGenesisViewModel.kt` `open`/`refresh`/after every sign and refused finish; the sheet polls it every 5 s while a ceremony is planned. Decided by the route, never a version: a **bare** 404 (no body — 0.5.219 has no such route and mounts no fallback) keeps the 2-of-3 `RemintTrustRootSheet`; 404 `final_genesis.not_planned` or 200 is the final genesis; any other refusal (403 off the node's machine) is shown as `final_genesis_unavailable`, never taken for an old node (`finalGenesisProbe`, `FinalGenesisTest.onlyABare404IsAnOldNode`) |
| what the charter will commit as each holder's recovery key | `GET /v1/accord/final-genesis/recovery-keys` → `{complete, recovery_keys:[{holder_key_id, recovery_key_id, commitment, source}]}` (a holder with nothing on record is `{holder_key_id, recovery_key_id: null}` alone) | CIRISServer `29ca1bcf` (`test/final-genesis-e2e`, for 0.5.220) `src/final_genesis.rs:918` (handler `:877`, merge `effective_recovery_keys` `:123`) | **v62:** the recovery-key rules change (§4, *The v62 genesis ceremony*): one fresh key per holder, no two holders share one, none is a signing key a bundle seats. The A1→A2 pairing and the record on file are obsolete. **0.6: pending (CIRISServer)**. **live, called, readable before a plan** — `getFinalGenesisRecoveryKeys` from `FinalGenesisViewModel.loadRecoveryKeys`, on open and after every token read. Each `final_genesis_recovery_<holder>` row shows the spare's id, a 16-hex prefix of `commitment` (persist's `recovery_commitment`, the exact value the charter carries in `recovery_commitments[holder]`) and its source — `record` *"on record"*, `hardware` *"verified with the token"*. `recovery_key_id: null` draws the row as missing and gates Plan on THAT holder only (`final_genesis_plan_blocked`). ONLY a bare 404 is a 0.5.220 build from before the route: the rows fall back to the A1→A2 pairing, *"on record"*, with no commitment until a token is read. Any other failure (5xx, a 404 with an id, no socket) draws no key the node did not supply: rows read *"not known"*, `final_genesis_recovery_error` + `btn_final_genesis_recovery_retry`, and Plan is held (`final_genesis_plan_blocked_unread`). A failed token read leaves the row as the node said it (a missing key stays missing) and shows the refusal beside it, `final_genesis_recovery_refusal_<holder>` |
| a holder's recovery key, read off the spare token | `POST /v1/accord/final-genesis/recovery-key` `{holder_key_id, recovery_key_id, mldsa_usb_path, pkcs11:{user_pin?, module_path?}}` → `{holder_key_id, recovery_key:{key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64}, recorded}` | `29ca1bcf` `src/final_genesis.rs:915` (handler `:468`; `recovery_key_mismatch` `:519`) | **v62:** as the row above; refusals become `recovery_commitment_shared` and `recovery_commitment_is_holder_key`, on every bundle [R-85d3e18]. **0.6: pending (CIRISServer)**. **live, called** — `verifyFinalGenesisRecoveryKey` from `FinalGenesisViewModel.verifyRecovery`, `btn_final_genesis_verify_<holder>` → `btn_final_genesis_verify_go_<holder>` (USB, PIN and the optional `input_final_genesis_recovery_module_<holder>`). Optional for a holder on record, required for one with nothing on record. The pairing is ENFORCED (`check_recovery_keys` `:128`): a seated holder reads only its own spare, so the form names it (`final_genesis_recovery_spare_<holder>`) and the request sends it whatever the node once listed; a wrong pair is 400 `final_genesis.recovery_key_wrong_holder`, one key for two holders 409 `final_genesis.recovery_key_shared` (both also from `plan`), shown by id. The PIN field is a SENSITIVE automation sink (`SensitiveInputs`): `/input` applies it, and neither the acknowledgement nor `/tree` / `/element` carries it. `plan` merges per holder (`effective_recovery_keys`): a key read here overlays the record for that holder only, so checking one spare and not the others still plans |
| plan the ceremony (stamp it once) | `POST /v1/accord/final-genesis/plan` `{serve_nodes:[{key_id, transport_hints:[{kind:"ip", destination:"host:port"}]}], clock_checked, replace?}` (CIRISServer e4cbedeb `ServeNodeSpec::WithHints` `:263`; at 814dd7c6 a hint counts only if it is `kind: ip` and its destination parses as Rust `SocketAddr` — `require_dial_hint` → `compose::ip_addrs_from_hints` — else 400 `final_genesis.serve_node_no_dial_hint`) → `{complete, signable_now, owed}` | `src/final_genesis.rs:859` (handler `:288`) | **v62: changed twice.** A seat's places to dial are its binding's signed `dial_hints` (at most 8, most preferred first, at least one `tcp`; CC 2.6.8 at rc22 `3004759` adds types 6 `i2p`, 7 `kiss` and 8 `ble`, and a dialler skips a type it cannot bring up), signed by the seat's own custody offline, not entered at plan. A hint's host is a DNS name or an IP literal in text form (IPv6 without brackets or zone id; the port is its own field), so hostnames ARE allowed and `[ipv6]:port` is not the form. The client may help an operator compose them; the seat signs them ([R-85d3e18]; CC 2.6.8, part_2:1428-1433). **0.6: pending (CIRISServer)**. **live, called** — `planFinalGenesis` from `FinalGenesisViewModel.planNow`. Each seated canonical has a drivable `input_final_genesis_dial_<key>`, validated as the server's dialer parses it before Plan enables (`isDialHint`: `a.b.c.d:port` or `[ipv6]:port`, never a hostname; port 1–65535 where Rust also takes `:0`; no IPv6 zone) — `final_genesis_dial_invalid_<key>` otherwise. It is prefilled ONLY from that canonical's `transport_hints` in `remint-source` (`src/accord_provision.rs:2381`; on a node holding the July bake canonical-1 is served with its address), and re-read on every new open — an edit survives only a Retry in the same form. No address is compiled into the client; a canonical with no hint (a fresh canonical-2/-3) starts empty and its address is typed. `successor_keys` and `recovery_keys` are omitted: the node defaults both to the spares on record (`:347-366`). `clock_checked` is `true` only after the `sheet_final_genesis_clock` ConfirmSheet, opened by 412 `final_genesis.clock_unverified`, and only for that ceremony instant — a successful plan and every new Plan press reset it, so "Plan again" asks again; 412 `final_genesis.clock_not_synchronized` is a refusal no confirm overrides. `replace` is sent only on the one request the destructive `sheet_final_genesis_replace` ConfirmSheet approved, opened by 409 `final_genesis.already_planned`, and cleared however that request ends (reached by `btn_final_genesis_replan` over a planned ceremony) |
| sign everything a holder owes now | `POST /v1/accord/final-genesis/sign` `{key_id, mldsa_usb_path, pkcs11:{user_pin?, module_path?}}` → `{signed, owed, complete?}` | `src/final_genesis.rs:860` (handler `:711`) | **v62:** the items below are obsolete; the v62 order is §4's steps 1–5. No holder signs a seat's record, a grant, a family row or a community birth (CEREMONY §2, §3). **0.6: pending (CIRISServer)**. **live, called** — `signFinalGenesis` from `FinalGenesisViewModel.sign`, `btn_final_genesis_sign_<holder>` on each holder's card, with the optional PKCS#11 module path `input_final_genesis_module_<holder>` (the hardware-scrub sheet's override, its keys reused); one YubiKey session per round. `complete` is absent on the dry run's software path (`:696`), so it is optional. 409 `final_genesis.nothing_to_sign` is drawn as *"Nothing to sign yet — waiting for B1, C1 to sign the charter"* (`final_genesis_note_<holder>`), not an error. The items are persist's (`ceremony.rs:560-663`): round one `record:<node>`, `row:genesis-charter`, `row:genesis-grant:<node>`, `row:genesis-lifecycle`; round two `family:humanity-accord`, `community:ciris-canonical`, `authz`, opening once no holder owes the charter |
| assemble, verify, write the bundle | `POST /v1/accord/final-genesis/finish` → `{complete, bundle_path, bundle_sha256, bundle, verified:{quorum_verified, serve_nodes, attestations, community_key_id, founders}}` (`bundle_json` — a string, the file's exact bytes — from CIRISServer e4cbedeb `:968`; the older `bundle` member of 4da726e8 still read as a fallback) | `src/final_genesis.rs:862` (handler `:794`) | **v62:** the final gate is persist `import_offer_into_throwaway` (the real doors on a throwaway node). The read-back is holders, seats (with X-Wing keys and routes) and whether the halt latched; there are no founders and no community in the bundle (the trust-root community is founded at step 10). The fingerprint is the bundle's object id (`bundle_object_id`), the anchor CC 5.3.4 publishes. **0.6: pending (CIRISServer)**. **live, called** — `finishFinalGenesis` from `FinalGenesisViewModel.finish`, `btn_final_genesis_finish` (enabled when the status says `complete`). 409 `ceremony_incomplete` names what is owed and re-reads the status; 422 `ceremony_outputs_refused` is persist's `verify_ceremony_outputs` refusing. `bundle_sha256` is `sha256:<hex>` over the file's bytes — `final_genesis_bundle_sha256`, copyable (`btn_final_genesis_copy_sha`). `bundle` is copied (`btn_final_genesis_copy_bundle`) and saved where the platform can (`btn_final_genesis_save_bundle`, `saveFileCopy`; `final_genesis_bundle_save_unavailable` where it cannot) EXACTLY as sent — the raw member, or a string member's contents, never re-serialized. Whether those bytes are the hashed ones is MEASURED (SHA-256 against `bundle_sha256`) and said at `final_genesis_bundle_match`: they are not (the server re-serializes the file compact with sorted keys), so the sheet says *"same bundle, not the hashed bytes: the fingerprint is the file at <bundle_path>"* |
| **re-scrub** — the amend's status | `GET /v1/accord/final-genesis/amend` → `{mode:"amend", needed, signed, status, complete, serve_transport_destinations}` | CIRISServer#798 (0.5.232, `76e16ccf`) `src/final_genesis.rs:1612` (handler `amend_status` `:1408`, body `amend_status_body` `:1285`) | **v62: there is no amend.** A later bundle (version = prev + 1, `prev` = the object id), counted at a strict majority of the predecessor's holders plus each holder it adds or re-commits; or, for a seat's transit key only, a counted re-bind (Verify `genesis.rs:12-14`, `:1505-1517`; persist `transport_rebind.rs`). **0.6: pending (CIRISServer)**. **live, called** — `getFinalGenesisAmendStatus` from `FinalGenesisViewModel.openAmend`/`refreshAmend` (polled every 5 s while planned). `[+ New]` → *Re-scrub the trust root (2-of-3 amend)* (`rescrub_trust_root`) opens THE SAME `FinalGenesisSheet` in amend mode — no second card. Nothing planned is 409 `final_genesis.amend_not_planned` (`amend_load` `:1235`) → the plan step; a **bare** 404 is a node older than the amend routes → the named state `final_genesis_amend_too_old`, never a generic error. CIRISConstitution#172: an AMEND of the pinned bundle (carried byte for byte, only transit rows added), authorized by the charter's quorum — no rounds, no recovery-key reads (`openAmend` reads `remint-source` for the roster only). Pinned: `FinalGenesisAmendWireTest`, `FinalGenesisAmendViewModelTest` |
| **re-scrub** — the canonical's signed transit row | `GET <canonical>/v1/trust-root/transit-row` → the `SignedTransportDestination` `{transport_destination, attesting_key_id, signed_envelope, signature}` | CIRISServer#798 `src/trust_root_api.rs:682` (handler `serve_transit_row` `:622`; PUBLIC router) | **v62: retired**, with the earlier `SignedTransportDestination`. **live, called on the CANONICAL, not this node** — `fetchCanonicalTransitRow` from `FinalGenesisViewModel.fetchTransitRow`: `input_final_genesis_amend_canonical_url` + `btn_final_genesis_amend_fetch`, the EXPECTED path. A different host: no bearer is sent. What came back is shown before planning — `final_genesis_amend_fetched_key` (`transport_destination.occurrence_key_id`) and `final_genesis_amend_fetched_pair` (a fingerprint COMPUTED HERE: SHA-256 over the decoded `signed_envelope.transit_kex_pubkeys` x25519 then ML-KEM-768 keys, first 16 hex — the server serves none, so it is labelled as client-computed for comparing on the canonical's host). Refusals by id at `final_genesis_amend_fetch_error`: 404 `trust_root.not_a_serve_node`, 404 `trust_root.no_transit_row`, 500 `trust_root.store_unavailable`; a bare 404 is a canonical older than the route → `final_genesis_amend_fetch_too_old` |
| **re-scrub** — plan the amend | `POST /v1/accord/final-genesis/amend/plan` `{pinned_bundle?, serve_transport_destinations?:[row], replace?}` → the status body | CIRISServer#798 `src/final_genesis.rs:1616` (handler `amend_plan` `:1327`, request `AmendPlanRequest` `:1311`, row check `check_serve_transport_destinations` `:633`) | **v62: no amend** (row above). **0.6: pending (CIRISServer)**. **live, called** — `planFinalGenesisAmend` from `FinalGenesisViewModel.planAmend`, `btn_final_genesis_amend_plan`. `pinned_bundle` is omitted (the bundle this node runs on). `serve_transport_destinations` carries the fetched row VERBATIM, then any pasted/attached rows about other canonicals (`input_final_genesis_amend_rows`, `btn_final_genesis_amend_rows_attach`; one object, a list, or several — `final_genesis_amend_rows_state`); empty → omitted, and the node reads its own directory. Rows that are not JSON objects send nothing. `final_genesis_amend_mode` says *amend* before and after. 409 `final_genesis.amend_already_planned` opens the destructive `sheet_final_genesis_amend_replace` ConfirmSheet, and only the request it approves carries `replace: true`. Refusals by id (`final_genesis_amend_error`): 400 `final_genesis.serve_node_without_transit_pair`, 400 `final_genesis.transit_row_not_a_serve_node`, 400 `final_genesis.amend_input_refused`, 409 `final_genesis.amend_carried_bytes_changed` (anything beyond transit rows would change — a full ceremony), 500 `final_genesis.store`. Planned shows the seeded canonicals (`final_genesis_amend_seeded`), *"2 of 3 holders needed"* (`final_genesis_amend_needed`: `needed` of the roster's size) and the node's own `status` (`final_genesis_amend_status`) |
| **re-scrub** — one holder's authorization | `POST /v1/accord/final-genesis/amend/sign` `{key_id, mldsa_usb_path, pkcs11:{user_pin?, piv_slot?, module_path?}}` → the status body | CIRISServer#798 `src/final_genesis.rs:1620` (handler `amend_sign` `:1421`, the final-genesis `SignRequest` `:864`) | **v62: no amend** (row above). **0.6: pending (CIRISServer)**. **live, called** — `signFinalGenesisAmend` from `FinalGenesisViewModel.signAmend`, `btn_final_genesis_amend_sign_<holder>` on each `final_genesis_amend_holder_<holder>` card (the final-genesis token form, `input_final_genesis_amend_{usb,pin,slot,module}_<holder>`; with no roster, one card and `input_final_genesis_amend_holder`). ANY holder, ONE authorization each, until `complete` — then no further sign is sent. 400 `final_genesis.amend_input_refused` (a bad authorization, or not a holder) sits beside that holder (`final_genesis_note_amend_<holder>`) |
| **re-scrub** — assemble, verify, write the v4 bundle | `POST /v1/accord/final-genesis/amend/finish` → `{mode, complete, version, bundle_path, bundle_sha256, bundle_json, serve_transport_destinations, verified}` | CIRISServer#798 `src/final_genesis.rs:1624` (handler `amend_finish` `:1542`) | **v62: no amend** (row above). **0.6: pending (CIRISServer)**. **live, called** — `finishFinalGenesisAmend` from `FinalGenesisViewModel.finishAmend`, `btn_final_genesis_amend_finish` (enabled at `complete`). The final-genesis finish section is REUSED — sha256, path, copy/save of `bundle_json` byte for byte — plus `final_genesis_done_version` (4) and `final_genesis_done_seeded`. 409 `final_genesis.amend_quorum_not_met` below quorum re-reads the status |
| the retired 2-of-3 re-mint | `POST /v1/accord/genesis/{propose,cosign}` | `src/accord_provision.rs:2973,2977` → `remint_superseded` (`src/final_genesis.rs:842`) | **v62: retired.** A v62 client has no ≤0.5.219 peer to serve, because the meshes share no rows; the v62 card drops this row's `RemintTrustRootSheet` branch. **410 `accord.genesis_superseded` on 0.5.220**, still **called** on ≤0.5.219 nodes only — `AccordViewModel.kt:1049,1095`, from `RemintTrustRootSheet`, which the sheet router opens only on a bare 404 from the row above. Its response fields (`fingerprint`, `node_trusts_root`, `trust_edge_error`, `seed_path`, `seed_save_error`, `src/accord_provision.rs` before 0.5.220) stay modelled by `GenesisSeedResponse` for those nodes |
| this node's side of the root — posture, acceptance, adopt, un-trust | `GET /v1/trust-root` · `POST /v1/trust-root/import` · `DELETE /v1/trust-root/{root_key_id}` | `src/trust_root_api.rs:415-419` | **v62:** kept and re-implemented; shapes **0.6: pending (CIRISServer)** (CSD-105). **live on this node's own machine, called from `Screen.TrustRoot`, not from this screen** — the leaf behind `btn_accord_open_trust_root`. Its rows, refusal ids, the two ConfirmSheets and the `blocked_by: CIRISServer#652` remote-reach fact are **CSD-105 §3**; this card's part is the door |
| the family's supersede chain | `GET /v1/accord/family/history` | `src/accord.rs:2645` (handler `:2355`) | **v62: retired** with the family rows. **live, called** — `getAccordFamilyHistory` from `AccordViewModel.kt:1137`, on the Accord card itself: `row_family_version_<n>`, `accord_family_history_{loading,empty,error,not_on_this_node}`. Not loopback-gated |
| change a seat by supersede | `POST /v1/accord/family/change/envelope`, `/v1/accord/family/supersede` | `src/accord.rs:2637,2641` | **v62: retired.** A seat changes by a later bundle; a lost holder key by a recovery (a later bundle signed by that holder's recovery key alone) (CC 4.2.6). **live and refuses by design** for the only family it serves: `humanity-accord` answers 409 at `:2306-2315` (CIRISPersist#648). A seat changes by re-mint + import, not here — so the card should not offer it |
| open an invocation | `POST /v1/accord/invocation` | `src/accord.rs:2650` | **v62:** re-implemented over persist's statement door. **0.6: pending (CIRISServer)**. **live, not called** — the client opens only through `/drill`, `/halt`, `/announce` and concurs through `/concur` (the §3 note below) |
| relying-node recognizers | `POST /v1/accord/verify-invocation`, `POST /v1/accord/message` | `src/accord.rs:2603,2606` | **live, not a holder's action** — unauthenticated peer/relying-node doors where the holder signatures are the authority (`:731-737`, `:2076-2080`); no card should call them |
| rebind a canonical's address | `POST /v1/accord/canonical/address` | `src/accord.rs:2632` | **v62: retired.** A seat's transit key moves only by a counted re-bind (`transport:canonical_rebind`, a strict majority of the holders of the bundle in force) or by a later bundle; its dial hints change only the same way, and its live signed route row supersedes them once a link is up (CC part_5:738; persist `edition2/transport_rebind.rs:20-36`, `:85`). Re-bind: **0.6: pending (CIRISServer)**. **live, not called** — the canonical row above cites it, but no `canonical/address` literal is in `CIRISApiClient.kt` |

### 3.1 The accord and `/v1/trust-root` — one root, two views

*(The second view is its own card, CSD-105; this section is the reading behind
the split.)*

**v62.** The accord is the genesis bundle's lineage (its `family_key_id`). The
trust root a node accepts is the root identity reference and its
`infrastructure` community, founded by the holders; the two are no longer one
keyless family row, and the ids A1/B1/C1 name nothing (persist
`edition2/fixture_trust_root.rs:1-15`; `trust_root_group.rs:5-12`; CC 4.2.1
line 90). Whether `GET /v1/trust-root` still lists the accord's `family_key_id`
first is **OPEN (O6, CIRISServer 0.6)**. The paragraphs below describe 0.5.x.

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
each node"* (`src/accord.rs:2311-2313`). This card mints that bundle — by the
genesis ceremony (CEREMONY §3), which every holder signs; later by a later
bundle at a strict majority of the standing roster (constants row 24; which
card hosts the v62 ceremony is O2) — and its trust-root detail adopts one
(`card_trust_root_import`). The node offers no preview of a seed before
installing it (the CIRISServer#404 comment), so the import confirm says so
(`trust_root_import_note`) instead of pretending to a fingerprint check.

**Placement.** The trust-root detail is `Screen.TrustRoot`, reached only from
`btn_accord_open_trust_root` on this card; `screenToSurface` maps it to
`NavSurface.Accord`, so Everyone › Safety › Accord stays lit. It is not a nav
row and not a new surface — it is a leaf with its own CSD (CSD-105) because it
is its own screen and the route checker keys cards by screen.

**Two facts a builder on `GET /v1/trust-root` must know.** `RootEntry.accepted`
reads `user_accepts` from the verdict (`src/trust_root_api.rs:106-109`), a field
`TrustRootVerdict` does not have — it is `edge_exists` (CIRISPersist v48.0.0
`federation/trust_root.rs:553-561`) — so `accepted` is always `false`.
And `root_kind` serializes as `Family` / `Key` (the enum has no `rename_all`,
`trust_root.rs:314`), not the lowercase the handler's doc comment promises
(`:71-72`). The first is CIRISServer#681; the client reads
`verdict.edge_exists` and ignores `accepted` (`trustRootView`, pinned by
`TrustRootTest.acceptanceIsReadFromEdgeExistsNotTheBrokenAcceptedField`). The
kind is compared case-insensitively.

**v62: lifecycle rows are counted.** One statement per holder over one payload,
naming the halt (`refs.target`) and the bundle (`refs.head`), counted at a
strict majority of the standing roster. The fuse lapses a lone halt at
`halt_fuse_secs` (default 86 400 s) unless a majority confirms it
(`lifecycle:confirmed`). Resumption (`lifecycle:active`) is verifiable offline
against the bundle in force. A lifecycle voice whose `refs.head` names a
superseded bundle is refused `accord_lifecycle_head_not_in_force` under
`accord_invocation_invalid` (persist HL-fix1, `edition2/halt.rs:83-86`, `:846`),
and a re-put of a held halt is not a new receipt: it latches nothing and does
not reset the fuse (`halt.rs:42`, `:60`). Tokens for "names no halt in force"
and "confirmed after the fuse" are OPEN — persist / CIRISConstitution (O12).
Server routes: 0.6: pending (CIRISServer) (CC 4.2.1.3; persist
`edition2/halt.rs:31-60`, `:781`). The rest of this paragraph
describes 0.5.x.

**The route carries `lifecycle:active`.** `InvocationKind` is a FOUR-variant enum whose fourth is `LifecycleActive`,
`#[serde(rename = "lifecycle:active")]` — `ciris-verify-core
src/humanity_accord.rs:79-103` in the `v16.1.0` checkout pinned at
`CIRISServer Cargo.toml:171` — and its own doc comment at `:97` says it "rides
the same concurrence flow". Decisively: `create_invocation` → `open_invocation`
(`src/accord.rs:1215-1229`, `:1385-1441`) applies **no kind filter**, unlike
`/drill` (`:1269`), `/halt` (`:1350`) and `/announce` (`:1499`), each of which
rejects a mismatched kind by name; the object is keyed into `pending` by
`(invocation_kind, invocation_id)` (`:1418-1421`) and `list_invocations` echoes
the kind verbatim (`:1763`). The closed set `{CONSTITUTIONAL, notify, drill}`
is the CC 4.2.1.1 **canonical-bytes preimage domain**
(`humanity_accord.rs:92-94`), not the route's payload — `lifecycle:active` signs
a separate `LIFECYCLE_DOMAIN_PREFIX` (`:74`) and is still listed on the same read.

So §2.2's fourth arm is live, on a route that can carry resumptions. The
primary production resumption path is offline — `build_release_request`'s
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
CIRISConstitution ask must name all three. On v62 `portable_2fa` is the
earlier-form envelope; the record states `hardware_class` + `pq_custody:
removable_offline` + `chain` (CEREMONY-READINESS §3).

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

Open `btn_accord_new` → on v62 the menu offers drill / announce / halt; confirm
or resume a halt (a counted voice); a later bundle (a roster change, adding or
removing a seat); recovery; and re-binding a seat's transit key. **Removed on
v62:** admit, add-canonical, bless-CI (deferred to v62.1, held), re-mint and
confer-duty (the trust root confers duties, not the accord). The built menu
still offers the removed items (`AccordScreen.kt:241`); taking them out is
client work for the v62 build.

```yaml
expect:
  visible: [mi_new_drill, mi_new_announce, mi_new_halt]
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

**The v62 genesis ceremony.** Which card hosts it — this card's
`FinalGenesisSheet` or CSD-069 — is **OPEN (O2, Eric / CIRISClient)**; today two
cards claim the genesis. Its route shapes are **0.6: pending (CIRISServer)**
(O1). What it must show, from CEREMONY §3 steps 1–5 and §5:

1. **Each holder signs its own `key:record`** — one composite key (Ed25519 half
   on a FIPS YubiKey, ML-DSA-65 half `removable_offline`), from a fresh
   attestation exported from that holder's own token. One device per holder,
   judged by the leaf serial (`holder_device_shared`, CC 4.2.3); the same f9
   attestation certificate over distinct device serials is valid.
2. **Each seat signs its own `key:record` and binding, offline.** The binding
   carries the seat's signed, ordered `dial_hints`: at most 8 (CC rc22 `3004759`), at least one
   `tcp`, hostnames allowed. The card shows the list per seat and that it is a
   cold-start seed only (CC part_5:738); refusals `seat_dial_hints_missing`,
   `dial_hint_malformed` [R-85d3e18].
3. **Each holder seals one halt** before the bundle. The card shows a per-holder
   "halt sealed: yes / no" row and refuses to finish or publish without full
   coverage: "Step 4 is published only once step 3 exists for every holder"
   (CEREMONY). Persist `check_offer_with_halts` (`:664`) refuses with
   `ceremony_halt_missing`, `ceremony_halt_duplicate`,
   `ceremony_halt_not_a_holder`, `ceremony_halt_invalid` (`:450-458`). Coverage
   is a ceremony gate, not an import gate: a node's import requires no halt.
4. **Every holder signs ONE voice over the bundle**, after the offer check
   passes. Each voice's `sub` is the bundle's `family_key_id`; a mismatch is
   refused as `statement_sub_not_family` (CE-fix4; [R-7beaf20]) — a ceremony
   read-back only.
5. **The anchor.** Before step 4 the anchor is the holders' thumbprints; once
   step 4 is signed the genesis's object id (`bundle_object_id`) is published
   as the anchor (CC 5.3.4). The final gate is persist
   `import_offer_into_throwaway`; the read-back is holders, seats (with X-Wing
   keys and routes) and whether the halt latched.

Alongside the steps:

* **Recovery keys.** Each holder gets one fresh recovery key in this edition
  ("no commitment of the earlier mesh is in force"), held offline and apart and
  committed in the charter's `recovery_commitments`. No two holders share one
  and none is a signing key a bundle seats (`recovery_commitment_shared`,
  `recovery_commitment_is_holder_key`, on every bundle) (CC 4.2.6 line 343;
  [R-85d3e18]; [R-7beaf20]). The card shows each commitment's hex prefix for the
  out-of-band compare. Which screen mints the recovery key is **OPEN (O9)**.
* **SCITT.** The genesis charter is witnessed-off with no witness members (Eric's
  ruling; [R-7beaf20]); the card says "no transparency services at genesis".
  What a later charter adds is **OPEN (O5, CIRISConstitution rc22)**.
* **The first drill within 7 days** of the genesis's publication (CEREMONY step
  11; CC 4.2.1.1): the card shows the due date and whether it happened (the drill
  band, CC 3.2 T4).
* **Total re-genesis** (§0): the card says the earlier mesh carries nothing.

The built `FinalGenesisSheet` runs the 0.5.220 two-round ceremony (draft flow
`testing/flows/drafts/csd-067-final-genesis.yaml`, pinned by
`FinalGenesisViewModelTest` / `FinalGenesisWireTest`). That flow, the
co-scrub, the Supersede flow and the 2-of-3 seed are obsolete on v62 and are not
described here any more; git history keeps them.

*Cannot yet assert on a runner:* any of the v62 ceremony. It has no 0.6 routes,
and each step needs a holder's FIPS token.

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
2. The CC 4.2.1.2 kinds are visibly different things: four in the built code, five on v62 (`lifecycle:confirmed`, §2.2).
3. "No accord here" and "could not read the accord" are different renderings.
4. A pending invocation shows how far from quorum it is.

**Not tested here.**
* **The signatures.** The count — a strict majority of the standing roster for
  counted rows, every holder for the first bundle — is the node's (Verify's
  reader), against canonical bytes this app never assembles. A client fixture asserting quorum would be
  asserting against the wrong machine.
* **The genesis ceremony** (§4) — every step needs a holder's FIPS token, and
  v62 has no 0.6 routes yet. That the bundle is right is persist's
  `import_offer_into_throwaway`, not the app's.
* **Acceptance 2 on a runner** — the built four arms exist and are pinned at the
  model (`AccordInvocationKindTest`), but no fixture node lists a lifecycle
  row (§4), so the fourth rendering is asserted by the test and not the flow.
* **Acceptance 4** — `signed`/`threshold` reach `AttestationCard` and are drawn
  in an untagged provenance line.
* Whether the halt banner is app-global. It is screen-local and the code says so
  (`AccordScreen.kt:321-324`); a person on another screen during a latched halt sees
  nothing, and no test here should be read as covering that.
