# CSD-090 — Duty Conferral (two holders hand someone the authority to moderate)

**CSD**: CSD-090 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, leftovers
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**Two accord holders, in one room, confer a moderation duty on another self —
and the card says on whose behalf, what it unlocks, and how far it travels.**
Serves **CC 4.5.5**: moderation is a delegable *duty*, not a platform- or
fabric-assigned role. A duty nobody can grant is a duty nobody holds, and CC
4.5.4 makes that a hard stop rather than a degradation — a community federates
only while ≥1 member holds a live `moderate` duty, and fails secure otherwise.
This screen is the only surface in the client that can write that edge.

**The three things a signer must know before touching a token, and the card
states all three.** *Who is conferring* — read live from the node
(`GET /v1/accord/family`), never a client constant, because a label the client
hardcodes is a label that can disagree with the substrate. *What it unlocks* —
the enforcement ladder's own rungs, so the operator knows the difference between
"grant moderate" and "grant the authority to de-admit". *How far it travels* —
one control, not a switch plus a number, because "may not delegate, depth 3" is
a sentence a two-control form can produce and a person cannot mean.

**A duty conferral is a grant, and CC bounds who may make one.** The
enforced-admission rule (CC 4.5.5) admits a moderation action only when a live
`delegates_to` chain reaches the actor from a root that *holds the duty over the
target* and is steward-bound, depth ≤ 5 (CC 4.1.1). So the interesting question
about this screen is not whether it composes — it is whether the authority it
names is one CC lets that authority confer. §3 answers it, and the answer is
partly no.

## 2. Surface (what)

```yaml csd:surface
surface: null                     # NOT a NavSurface
flow_only: true                   # no sidebar row reaches it
screen: DutyConferral             # `object DutyConferral : Screen()` — CIRISApp.kt:5785
entry: Accord's `onConferDuty` (CIRISApp.kt:4193), shown on the Accord card once a family exists
exit: back to `Screen.Accord` (CIRISApp.kt:4179) — it interrupts, it does not relocate
```

No nav hop. `DutyConferral` is in `FLOW_ONLY` (`testing/gate/screen_atlas.py:41`)
and in `docs/atlas.json`'s `flow_only`. Its de-facto placement is a leaf of
Accord — **Everyone › Safety**, `circle_global_commons -> tab_safety ->
nav_epistemic_accord` (`CirclesNav.kt:101`) — and `navSurfaceForScreen` keeps
that card lit while the screen is open (`CIRISApp.kt:5941`), which is the right
shape: conferring a duty is something you do *from* the accord, not a
destination beside it.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: accord:family
    use: display-only
    type: string
    example: "humanity-accord"
    renders: "'Conferred by — HUMANITY_ACCORD / humanity-accord'. RESERVED (accord_holder-only, CC 3.4.1): the client reads the family the node names and never asserts one. First on the card, before anything the operator fills in, because it is the one fact they cannot change"
    tag: duty_source_name
  - ceg: x_private:family_consensus_protocol
    use: display-only
    type: string
    example: "quorum:2/3"
    renders: "'Adopted at quorum:2/3 — this grant does nothing until that many distinct seats have signed it', or the entrenched wording when the family says the threshold is fixed. The accord's own string, verbatim"
    tag: duty_source_quorum
  - ceg: x_private:family_live_seats
    use: display-only
    type: "list[string]"
    example: ["A1", "A2", "A3"]
    renders: "'3 live seats: A1, A2, A3' — admitted minus revoked, so a removed holder stops counting at the moment they are removed rather than at the next client release"
    tag: duty_source_seats
  - ceg: x_private:subject_key_id
    use: emit
    type: string
    example: "eric-moore-v2-portable-a41f…"
    renders: "'Subject fed-ID (key_id)' — the self the duty is conferred ON, prefilled with this node's bound owner so the first and commonest conferral is not a 50-character retype"
    tag: input_duty_subject
  - ceg: x_private:delegated_scope_set
    use: emit
    type: "list[string]"
    example: ["moderate", "takedown"]
    renders: "five checkboxes, a SET not a choice — one grant, one ceremony, several duties. Empty selection says so in the error tone: 'A grant with no scope confers nothing'"
    tag: chk_duty_box_moderate
  - ceg: "moderation:{allegation_type}"
    bind: {allegation_type: harassment}
    use: display-only
    type: string
    example: "moderation:harassment"
    renders: "what ticking `moderate` authorizes the subject to emit on the delegator's behalf (CC 4.5.5 table). Named in the checkbox's own sentence, not in a footnote"
    tag: chk_duty_box_moderate
  - ceg: "reconsideration:{grounds}"
    bind: {grounds: new_evidence}
    use: display-only
    type: string
    example: "reconsideration:new_evidence"
    renders: "what `review` authorizes — an appeal, filed on the delegator's behalf. It unlocks NO ladder rung, and the card says so rather than folding it in"
    tag: chk_duty_box_review
  - ceg: "slashing:{outcome}"
    bind: {outcome: proven_rogue}
    use: display-only
    type: string
    example: "slashing:proven_rogue"
    renders: "the tier-3/4 rungs `slash` unlocks: 'tier 3 · descend (also requires a QUORUM)', 'tier 4 · de-admit / re-admit, refuse-writes / accept-writes'"
    tag: "duty_ladder_duty.ladder_t4"
  - ceg: "quarantine:{state}"
    bind: {state: quarantined}
    use: display-only
    type: string
    example: "quarantine:quarantined"
    renders: "'tier 2 · quarantine / un-quarantine', listed under `slash` and not under `moderate` — the FSD ladder and persist's admission door disagree here, and the substrate wins, because showing it under `moderate` would promise a rung the gate refuses"
    tag: "duty_ladder_duty.ladder_t2"
  - ceg: "consent:{kind}"
    bind: {kind: revocation}
    use: display-only
    type: string
    example: "consent:revocation"
    renders: "what `consent_revocation` authorizes — revoking consent on the delegator's behalf. RESERVED (CC 3.4.5). This is the row §3 objects to: CC 4.2.1 says accord authority cannot reach the consent plane, and this grant is rooted in the accord family"
    tag: chk_duty_box_consent_revocation
  - ceg: x_private:sub_delegation_depth
    use: emit
    type: "enum[leaf,1,2,3,4,5,unbounded]"
    example: "2"
    renders: "one dropdown carrying the (sub_delegation, depth) PAIR — 'Leaf — may act, may not delegate further' … '5 further hops' … 'Unbounded (global rail: 5)'. Every option is on the menu; nothing hides behind 'advanced', because the operator is choosing how far their own authority travels"
    tag: input_duty_depth
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey}
    use: display-only
    type: string
    example: "YUBI DETECTED — FIPS COMPLIANT — 9C PROVISIONED — READY TO PROCEED"
    renders: "the token-readiness banner, placed FIRST because the ceremony cannot start without it and learning that from a failed signature wastes two people's time. It carries NO test tag and is hardcoded English (AccordCeremonyScreen.kt:553-620) — the one gate on the whole screen that automation cannot read and a non-English reader cannot read either"
    tag: "proposed:duty_yubikey_banner"
  - ceg: x_private:scrub_count
    use: display-only
    type: int
    example: 1
    renders: "'1 of 2 signatures', monospace. Below quorum: 'Not conferred yet — hand the partial to the next holder to cosign'"
    tag: duty_scrub_count
  - ceg: "trust:{job}:{version}"
    bind: {job: confers, version: v1}
    use: display-only
    type: string
    example: "moderate conferred on eric-moore-v2…; sub-delegation: 2 hops"
    renders: "the adopted grant, in the NODE's own words. RESERVED — this is the edge the ceremony writes (`TRUST_CONFERS_DIMENSION`, CIRISPersist `federation/trust_root.rs:190`), and the client renders the sentence the node signed rather than the one the form implied"
    tag: duty_conferred
```

**The near-miss worth naming: `duty:{kind}` is the wrong family, and the checker
cannot tell you.** `duty:moderate` resolves cleanly against the pinned registry
— and it is not what this screen writes. `duty:{kind}` is CC 3.1.1's
licence-obligation family ("attribution, share-alike, carry-restrictions-
downstream"). A moderation duty is not an attestation in that family at all; it
is a `delegated_scope` token inside a `delegates_to` row whose dimension is
`trust:confers:v1`. A CSD that bound `duty:{kind}` here would validate green and
be wrong about what the surface moves, which is why `x_private:
delegated_scope_set` carries the set and the `trust:*` row carries the edge.

**`accord:*` resolves for ids CC never names and refuses the ones it does.**
`accord:family` above passes; `accord:lifecycle:active` and
`accord:invoke:notify:{id}` — both named verbatim at CC 4.2.1 — do **not**, because
`check_csd_v3.py:87` requires `len(segments) == len(parts)` and treats the `*`
as one segment. The registry's own `_meta` calls that class "the `*` tail; not a
segment value". A wildcard that matches exactly one segment is a tail rule
implemented as a placeholder. See §5.

```yaml csd:states
populated: {tag: duty_conferral_card, renders: "the source card naming the family, the five duty checkboxes, the ladder read-out, the depth control, the holder inputs, Propose / Cosign, and the signature counter"}
empty:     {tag: "proposed:duty_source_absent", renders: "THE NODE ANSWERED AND THERE IS NO ACCORD FAMILY — nothing can be conferred, and nothing is broken. Today this renders through `duty_source_error` in the error colour as 'Could not read the conferring authority: this node knows no accord family yet…', so the one state that is not a failure is the one that looks most like one"}
loading:   {tag: "proposed:duty_source_loading", renders: "'Reading the conferring authority from this node…' — rendered with NO tag at DutyConferralScreen.kt:195-199, so a gate cannot tell a slow read from an absent one"}
error:     {tag: duty_source_error, renders: "the node's own refusal, bold and in error colour, with 'Nothing can be conferred until this node can name who is granting it.' The top-of-card banner `duty_error` carries the propose/cosign failure separately"}
```

**`empty` and `error` are the same tag, the same colour and the same sentence
template — and the ViewModel knows they are different.** `DutyConferralViewModel.kt:220-228`
says so in as many words: "A 404 is a THIRD state, distinct from both success and
failure: this node reached the substrate and it says there is no accord family
yet. Nothing can be conferred, but nothing is broken either — and conflating it
with a transport error would send the operator debugging a network that is
working fine." The next line assigns that third state to `_sourceError`. The
comment is the specification; the assignment is the defect. CSD/3 §2.2 requires
`error` to be distinguishable from `empty`, and here it is not.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| scrub #1 | `POST /v1/accord/duty/propose` | CIRISServer (`src/accord_duty.rs:648`) | live — owner-gated, loopback; `501 accord.duty.pkcs11_required` on a build without the `pkcs11` feature |
| the next holder's scrub | `POST /v1/accord/duty/cosign` | CIRISServer (`src/accord_duty.rs:649`) | live — the partial goes back BYTE-IDENTICAL; the signature covers its exact bytes |
| who is conferring | `GET /v1/accord/family` | CIRISServer (`src/accord.rs:2628`) | live — and a 404 means "no family", not "could not ask" |
| the holder registry | `GET /v1/accord-holders` | CIRISServer (`src/accord.rs:2601`) | live — the same source the quorum is counted from, which is why the holder is a dropdown and not a text box |
| token readiness | `GET /v1/accord/yubikey-status` | CIRISServer (`src/accord_provision.rs:3770`) | live |
| prefill the subject | `GET /v1/setup/owned-nodes` | CIRISServer | live, **loopback-only** — off-host it 403s (CSD-084 measured it on 0.5.190), so on a remote node the subject field is simply blank and the screen does not say why |
| the conferrable vocabulary | `CONFERRABLE_DUTIES` | CIRISPersist `federation/admission.rs:6366` (`DELEGATED_DUTY_SCOPES`) | live — re-exported, not re-listed. The client's `ALL_DUTIES` (`DutyConferralViewModel.kt:55-57`) is a fourth hand-mirror of the same five, and a sixth scope upstream will not appear in this menu |
| **which community the duty is over** | **no field** — `conferral_envelope` carries `dimension` + `scope` + `subject_key_id` and nothing else (`src/accord_duty.rs`, the envelope builder) | CIRISServer / CIRISPersist | **missing** — see the CC delta below |

### The CC delta, stated plainly

**1. CC 4.2.1 does not admit this ceremony.** "`HUMANITY_ACCORD` signatures are
valid only on `EmergencyShutdown CONSTITUTIONAL`, `accord:invoke:notify:{id}`,
`accord:invoke:drill:{id}`, `accord:lifecycle:active`, the canonical-conferral
co-scrub, and the corresponding `FederationAnnouncement` priority
`AccordCarrier`. … humanity-accord authority cannot sign anything else.
**Wire-isolated AND scope-isolated.**" The canonical-conferral co-scrub is
named and bounded in the next paragraph — a `canonical,node` registration
envelope bearing `roles: ["infra:serve"]`, the ceremony that mints a portable
trust root. A `trust:confers:v1` row carrying `scope: ["moderate"]` and a
`subject_key_id` is a different envelope on a different plane, and the server's
own module header says so: the trust-root card confers roles "co-scrubbed INTO a
key record, which is the CEREMONY plane, a different plane entirely." CIRISServer#392
added a sixth accord power; CC 4.2.1 still enumerates five. CC 4.2 is
**entrenched** (CC 4.5.1.2), and CC 4.2.1's own rule for widening reach is
"pre-committed powers only, never act-then-ratify", with a 72 h severance window
during which a bound node may cut its edge.

**2. `consent_revocation` reaches a plane CC 4.2.1 walls off.** The same section:
"accord authority cannot reach the consent or licensure planes." The card offers
`consent_revocation` as one of five ticks, and CC 4.5.5's enforced-admission rule
refuses "an issuance whose delegator does not itself hold the underlying
authority" at admission, not merely unweighted. The accord family holds no
consent authority over anyone's Contributions. Whether persist refuses the
resulting grant is **unconfirmed** from this repo; either way the card renders a
choice that CC does not permit the conferring authority to make.

**3. `moderate` / `review` are community-scoped in CC and community-blind on the
wire.** CC 4.5.4's named-moderator binding is `delegates_to(authority → K,
scope ⊇ {moderate|takedown|review}, community_id: C)` whose root `authority` is
in **C's authority set** — a founder, or a key C's `consensus_protocol` names —
and is steward-bound. The conferral envelope carries no `community_id`, and its
root is the humanity accord, which is in no community's authority set. So the
grant this screen writes is not the shape CC 4.5.4 resolves
`is_named_moderator(K, C, moderate)` against. For `slash` the family root is
right — the enforcement ladder is federation-tier and CIRISPersist reads it
through `ConferralPlane::FamilyQuorum` — which is the tell: **one control is
doing two constitutionally different jobs.**

**4. A judgment duty is never node-holdable, and nothing checks.** "Judgment
duties (`moderate` / `takedown` / `review`; community steward) sit outside the
`infra:*` set … a `node`-only key may carry *only* `infra:*` scopes, therefore it
cannot hold a judgment duty at all" (CC 4.4.3.4.3). The subject field takes any
string. A conferral onto a node key composes, signs, costs two YubiKey touches
and is refused at admission — and the card learns that after the ceremony.

**5. Depth is right.** `GLOBAL_DEPTH_RAIL = 5` (`DutyConferralViewModel.kt:60`)
is CC 4.1.1's cap, the server re-checks it (`accord.duty.depth_over_rail`), and
`setDelegationDepth` moves both axes together so "no sub-delegation, depth 3" is
not expressible. CC 4.5.5's attenuation rule — `child.scope ⊆ parent.scope`,
constraints added and never removed — is satisfied by a control that can only
tighten.

## 4. Flow (how)

Sign in as the owner on a node that knows an accord family; open Everyone ›
Safety › Accord and click the confer-duty control.

```yaml
expect:
  state: populated
  visible: [duty_source_card, duty_source_name, duty_source_quorum, duty_source_seats, duty_conferral_card, input_duty_subject, input_duty_depth, btn_duty_propose, btn_duty_cosign]
```

Untick every duty:

```yaml
expect:
  visible: [duty_verbs_none, duty_ladder_none]
```

Tick `slash`; the ladder read-out fills from the node's own table:

```yaml
expect:
  count: {of: "duty_ladder_*", eq: 3}
```

Tick `review` as well — one more rung, and the two duties travel as one grant:

```yaml
expect:
  count: {of: "duty_ladder_*", eq: 4}
```

Propose with no subject:

```yaml
expect:
  state: error
  visible: [duty_error]
```

On a node with no accord family, the card refuses before any of that:

```yaml
expect:
  state: empty
  absent: [duty_source_name]
```

## 5. QA plan

**Platforms.** Desktop only, and not end to end. The ceremony needs two accord
holders' YubiKeys, two humans and two PIV PINs; `testing/gate/node_fixture.py`
produces none of them. What a suite can assert is everything up to `btn_duty_propose`:
the source card, the duty set, the ladder mapping, the depth options and both
refusals.

**`input_duty_subject` cannot be typed into.** `client/tools/check_ui_drivable.py
--list` names exactly two fields in this area — `input_duty_subject` and
`input_chat_body` (CSD-091). It is an `OutlinedTextField` carrying
`Modifier.testable("input_duty_subject")` (`DutyConferralScreen.kt:266`) with no
`rememberInputSinks` call in the file, so `/input` reports success and types
nothing. The field that names **who receives moderation authority** is the one
field on the screen automation cannot set. The fix is `CirisTextField`
(`ui/primitives/Controls.kt:95`), which declares its own sink and is drivable by
construction — one line.

**The PIN is not cleared between holders, and the code says it should be.**
`clearPinBetweenHolders()` (`DutyConferralViewModel.kt:397-399`) carries the
reasoning — "The next signature is a DIFFERENT human with a different token;
leaving holder A's PIN in the box invites holder B to press submit without
noticing whose credential is in it" — and has **no call site anywhere in
`commonMain`**. `applyResult` does not call it. A documented safety property of a
two-human ceremony that does not happen is worse than one nobody thought of,
because the comment reads as evidence it was handled.

**The ladder tags carry a localization key, dots and all.**
`Modifier.testable("duty_ladder_$rungKey")` (`DutyConferralScreen.kt:345`) with
`rungKey` values of `duty.ladder_t0`…`duty.ladder_t4` renders tags spelled
`duty_ladder_duty.ladder_t0`. A `duty_ladder_*` glob still matches, which is why
the flow above counts rather than names them, and why nobody has noticed.

**Nine `duty.*` strings ship in 29 locales with no call site.**
`duty.holder_label`, `duty.holder_placeholder`, `duty.usb_label`,
`duty.usb_placeholder`, `duty.usb_browse`, `duty.usb_guidance`,
`duty.verb_label`, `duty.verb_help` and `duty.ladder_preview` were left behind
when the card adopted the shared `HolderSignInputs` component, which uses
`mobile.accord_scrub_*` instead. Nine keys × 29 bundles.

**Not tested here.** The propose/cosign round trip (two tokens); the adoption
transition (`duty_adopted` / `duty_conferred`, which needs quorum);
`duty_source_seats` against a revoked holder; the `pkcs11`-absent build, which
returns `501 accord.duty.pkcs11_required` and is the state a desktop QA build is
actually in; whether persist admits an accord-rooted `consent_revocation` grant.

**Stated limit.** This screen cannot say which community a duty is over, because
the wire has no field for it. Until it does, every `moderate` it confers is
community-blind, and CC 4.5.4's `is_named_moderator(K, C, moderate)` — the
predicate the whole no-unmoderated-space invariant rests on — will not resolve
against what this ceremony wrote. The CSD says that rather than letting the
adopted-grant read-back imply otherwise.
