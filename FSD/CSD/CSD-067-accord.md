# CSD-067 — Accord (Trust Root) — the kill switch, its roster, and the four kinds

**CSD**: CSD-067 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten · **Splits from**: CSD-003, which described this surface and
the Constitutional card as one document and can no longer, because they are two
cards in one tab with two different jobs

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

This client implements three of the four (§2.2). That is the defect this CSD
exists to pin, and it is a conformance failure rather than a polish item: the
one kind that is dropped is the one whose meaning is the *opposite* of the kind
it currently renders as.

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
populated: {tag: "card_accordfamily_${family.familyKeyId}", renders: "the family card, the holder roster under it, canonical servers, pending co-signs and history"}
empty:     {tag: "proposed:txt_accord_family_empty", renders: "No accord family on this node yet — the genesis ceremony is how one is stood up."}
loading:   {tag: "proposed:spinner_accord_section", renders: "each section header carries its own progress affordance; sections do not print their empty sentence while loading"}
error:     {tag: accord_error, renders: "Could not read the accord. This is NOT a report that there is no halt."}
```

**Three of the four are real and one of them is the important one.**
`accord_error` (`AccordScreen.kt:287`) is a genuine error banner in the error
tone, distinct from every empty sentence on the screen — and the screen already
tags three of those (`accord_canonical_empty`, `accord_coscrub_empty`,
`accord_history_empty`). The **family** empty sentence is the one that was
missed (`AccordScreen.kt:334`), and it is the one that matters most: "this node
has no accord" and "this node could not tell us about its accord" are, on a kill
switch, opposite facts.

`loading` is genuinely handled — `SectionHeader(title, loading)` suppresses each
section's empty sentence while the read is in flight (`:330`, `:404`) — and it
is untagged.

### 2.1 What the halt banner already gets right

`accord_halt_banner` (`AccordScreen.kt:1790`) is a real tag on a real
`errorContainer` surface with a 2dp error border, rendered above everything and
never cleared by the app. The screen-local scope is documented in the code as a
known limit rather than presented as global. This is the one CC 4.2.1.2
treatment that is fully implemented, tagged and assertable.

### 2.2 The CC 4.2.1.2 defect, exactly

`invocationBadge` (`AccordScreen.kt:671`) and `attestationStyle`
(`ui/components/Attestation.kt:179`) each branch on three values:

```
"CONSTITUTIONAL" -> emergency red, heavier border
"DRILL"          -> muted surfaceVariant
else             -> secondaryContainer, badge "notify"
```

`attestationStyle`'s own doc comment says *"The MANDATED per-kind visual
treatment (CC 4.2.1)"*. An `accord:lifecycle:active` row — the **resumption**
from a constitutional halt, the only sanctioned way back (CC 4.2.1.3) — falls
into `else` and is drawn, in both badge text and colour, as a `notify`.

CC 4.2.1.2 forbids precisely that pairing by name. The two are not merely
different; a resumption is the end of a halt and a notify is a communication,
and a holder reading a reactivation as a notice has been told the opposite of
what happened.

**Fix shape:** a fourth arm in both functions keyed on `lifecycle`, its own
badge (`mobile.accord_kind_reactivated`), its own tone, and a test tag on the
badge so the `one_of` above becomes enforceable. Four kinds, four arms, one
assertion.

### 2.3 Five strings on the safety surface that are not localized

`ConstitutionalScreen.kt` is the worse case (CSD-003 §2.4), but this screen
carries English literals too — `"Back"` as the only `contentDescription` on the
back button (`AccordScreen.kt:180`) and the two operator notices written inline
in the save handler (`:311-312`). A 29-locale build that falls back to English
on a kill-switch surface is telling a non-English holder less than it thinks.

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
| drill / announce | `POST /v1/accord/{drill,announce}` | `src/accord.rs:2609,2611` | **live** |
| canonical servers + co-scrubs | `/v1/accord/canonical/*` | `src/accord_provision.rs:3707,3711,3717,3721,3753,3758,3762,3766,3776` + `canonical/address` at `src/accord.rs:2632` | **live** (the earlier `3704-3776` range was loose: `:3704` is `admit-node` and `:3770` is `yubikey-status`) |
| confer a moderation duty | `POST /v1/accord/duty/{propose,cosign}` | `src/accord_duty.rs:648,649` | **live** — called from `DutyConferralViewModel.kt:335,375`, not from this screen's view model; `Screen.DutyConferral` maps to `NavSurface.Accord` (`CIRISApp.kt:5941`) and is reached from `[+ New]` (`CIRISApp.kt:4193`) |
| a holder's hardware custody class | **missing on the roster** — `list_holders` builds `HolderSummary {key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64}` (`src/accord.rs:664-668`) and `AccordHolderDto` (`models/federation/Accord.kt:53-60`) mirrors it exactly. **Served elsewhere for the charter's holders:** `GET /v1/trust-root` → `roots[].verdict.holders_hardware[]` `{key_id, class, layer_a, layer_b, refusal}` (verdict passed through verbatim, `src/trust_root_api.rs:76-79`; shape CIRISPersist v48.0.0 `federation/trust_root.rs:372`, the pin at `Cargo.toml:138`), loopback-only | CIRISServer | blocks `txt_holder_custody` on the roster; on this node's own machine the verdict carries it; **remote reach** `blocked_by: CIRISServer#652` |
| **a `lifecycle:active` row to render** | `GET /v1/accord/invocations` | CIRISServer | **live — the route serves it, and that makes §2.2 a shipped defect** |
| admit a node | `POST /v1/accord/admit-node` | `src/accord_provision.rs:3704` | **live**, loopback-only — called from `AccordViewModel.kt:402` (`[+ New]` → admit) |
| bless the CI build keys | `POST /v1/accord/ci-key/{propose,cosign}` | `src/accord_provision.rs:3729,3733` | **live**, loopback-only — `AccordViewModel.kt:827,874` |
| re-mint the root into a portable seed | `GET /v1/accord/genesis/remint-source`, `POST /v1/accord/genesis/{propose,cosign}` | `src/accord_provision.rs:3740,3744,3748` | **live**, loopback-only — `AccordViewModel.kt:958,1042,1088`; the `RemintTrustRootSheet` (`AccordScreen.kt:951`) |
| the seed's fingerprint, and this node's own acceptance of the root it just minted | the propose/cosign response: `fingerprint`, `node_trusts_root`, `trust_edge_error`, `seed_path`, `seed_save_error` | `src/accord_provision.rs:2530-2600` | **live, and dropped by the client** — see §3.1 |
| which roots this node accepts, and its genesis posture | `GET /v1/trust-root` | `src/trust_root_api.rs:415` | **live on this node's own machine, and called by nothing in the client** (no `trust-root` literal in `CIRISApiClient.kt`); **remote reach** `blocked_by: CIRISServer#652` — the router is loopback-layered (`:422-424`) |
| adopt a portable seed on this node | `POST /v1/trust-root/import` | `src/trust_root_api.rs:416` | **live on this node's own machine, not called**; **remote reach** `blocked_by: CIRISServer#652` |
| un-trust a root (withdraw this node's acceptance) | `DELETE /v1/trust-root/{root_key_id}` | `src/trust_root_api.rs:418-419` | **live on this node's own machine, not called**; **remote reach** `blocked_by: CIRISServer#652` |
| the family's supersede chain | `GET /v1/accord/family/history` | `src/accord.rs:2645` (handler `:2355`) | **live, not called** |
| change a seat by supersede | `POST /v1/accord/family/change/envelope`, `/v1/accord/family/supersede` | `src/accord.rs:2637,2641` | **live and refuses by design** for the only family it serves: `humanity-accord` answers 409 at `:2306-2315` (CIRISPersist#648). A seat changes by re-mint + import, not here — so the card should not offer it |
| open an invocation | `POST /v1/accord/invocation` | `src/accord.rs:2650` | **live, not called** — the client opens only through `/drill`, `/halt`, `/announce` and concurs through `/concur` (the §3 note below) |
| relying-node recognizers | `POST /v1/accord/verify-invocation`, `POST /v1/accord/message` | `src/accord.rs:2603,2606` | **live, not a holder's action** — unauthenticated peer/relying-node doors where the holder signatures are the authority (`:731-737`, `:2076-2080`); no card should call them |
| rebind a canonical's address | `POST /v1/accord/canonical/address` | `src/accord.rs:2632` | **live, not called** — the canonical row above cites it, but no `canonical/address` literal is in `CIRISApiClient.kt` |

### 3.1 The accord and `/v1/trust-root` — one root, two views

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
(`RemintTrustRootSheet`) and offers no way to adopt it anywhere.

**Two defects the client carries today, found reading the contract:**

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
(`:71-72`). Both are drafted as server issues rather than worked around.

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

On a node with no accord family:

```yaml
expect:
  visible: [accord_canonical_empty, accord_coscrub_empty, accord_history_empty]
```

*Cannot yet assert:* the family empty sentence, which is the one this step is
about.

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

*Cannot yet assert:* that a `lifecycle` invocation renders as its own kind
(§2.2) — the tag does not exist and neither, confirmed, does the arm.

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
* **Acceptance 2**, entirely — the fourth arm does not exist and the third one
  cannot be distinguished from the fourth.
* **Acceptance 4** — `signed`/`threshold` reach `AttestationCard` and are drawn
  in an untagged provenance line.
* Whether the halt banner is app-global. It is screen-local and the code says so
  (`AccordScreen.kt:321-324`); a person on another screen during a latched halt sees
  nothing, and no test here should be read as covering that.
