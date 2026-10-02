# CSD-086 — Add Federation ID (the catch-up, not the wizard again)

**CSD**: CSD-086 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**An owner who has a node but no federation identity gets one without redoing
first run.** Serves **CC 3.2** — the owner-binding is single-valued and
*transferable through the existing structural composers, with no new primitive*:
a new binding carried as a `supersedes` replaces the prior one. So re-rooting an
already-owned node onto a freshly minted fed-ID is a supersede, not a second
claim, and the person's login survives it.

**A configured node is not a fresh one.** Sending a returning owner through
`Screen.Setup` because they happen to lack a fed-ID would run a one-time claim
against a node that is already claimed — the PIN no longer exists, and the
wizard's completion path reconfigures the runtime underneath them. This screen
is the catch-up that exists for exactly that state (`CIRISApp.kt:2572-2592`,
Codex PR #6), and the routing decides between the two on `isFirstRun`.

**The gate is the session, and that is the right gate.** Minting an identity for
an owner requires proving you are that owner — the same rule as entering as one.
`POST /v1/self/upgrade-owner` is owner-gated; `POST /v1/setup/claim-remote` is
PIN-gated and first-run-gated. Two states, two doors, and neither is a fallback
for the other.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: AddFederationId
flow_only: true
entry: "Login `btn_federation_create` (CIRISApp.kt:2591); the post-login catch-up effect, AUTOMATIC with no control (CIRISApp.kt:987-1002); ManageNodes `btn_add_federation_id` (ManageNodesScreen.kt:506 → CIRISApp.kt:4049)"
exit: "`addFederationIdReturnScreen` — wherever it was called from (CIRISApp.kt:534). It interrupts; it does not relocate"
```

`screenToSurface` maps this Screen to `null` explicitly (`CIRISApp.kt:5964`),
which is what CSD-085 §2 shows the five flow-only screens above it should have
done. CSD-080 §2 records why `flow_only:` is a checked key.

**Three entries**, and only two of them are controls:

1. Login `btn_federation_create` on a configured node (`CIRISApp.kt:2591`).
2. The post-login catch-up effect — **automatic, no control**
   (`CIRISApp.kt:987-1002`).
3. ManageNodes `btn_add_federation_id` (`ManageNodesScreen.kt:506` →
   `CIRISApp.kt:4049`).

No nav hop — `AddFederationId` is in `FLOW_ONLY` (`screen_atlas.py:43`). Unlike
every other card here it returns to wherever it was called from
(`addFederationIdReturnScreen`, `CIRISApp.kt:534`), which is the shape a
catch-up should have: it interrupts, it does not relocate.

**Entry 2 has no control, and it is the one that regressed.** A
`LaunchedEffect` gated on `currentScreen == homeTarget`, an authenticated
session (or `isHAAddonMode` — an ingress-header session keeps
`currentAccessToken` null by design) and `ownerHasFedId == false` pushes this
screen automatically, once, via the `fedIdCatchupPrompted` latch. It is keyed on
the **probed landing screen**, not on `Screen.Interact` — when node mode's home
moved to Contacts, a legacy owner with no fed-ID stopped being offered the
catch-up and landed on a Contacts surface they could not use, which is the exact
state this effect exists to prevent (`CIRISApp.kt:975-983`). A flow that only
drives `btn_federation_create` never touches it.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:federation_label
    use: emit
    type: string
    example: "eric-moore-v1"
    renders: "the fed-ID name, with live validation below it: 'A unique name is required.' / 'That name is too generic — choose a unique one.' / 'Looks good.' The generic list is the same one the wizard uses, to avoid the ciris-client-user identity collision"
    tag: input_fed_label
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "self"
    renders: "'Fed ID added. Your node stays private (self-scoped).' — or, announced, 'federation visibility takes effect on next launch.' The client reports the binding the node re-rooted; it does not assert it (CC 3.4.5)"
    tag: "proposed:txt_fedid_result"
  - ceg: x_private:announce_ownership
    use: emit
    type: bool
    example: true
    renders: "the same first-class announce decision the wizard makes: announcing is what unlocks sending traces and joining communities. The trace opt-in itself is NOT offered here (AnnounceDecisionCard showTraceOptIn=false): it is the Data card's write, and this screen points at it"
    tag: toggle_announce_ownership
  - ceg: x_private:traces_elsewhere
    use: display-only
    type: string
    example: "Sending reasoning traces is turned on later, on the Data card…"
    renders: "one line under the announce card naming where the opt-in lives (CSD-039), so a person who wants it is told rather than offered a switch this screen cannot read back"
    tag: txt_fedid_traces_elsewhere
  - ceg: x_private:fedid_confirm
    use: display-only
    type: "list[string]"
    example: ["eric-moore-v1", "A federation ID is minted under this name…", "The node, on your owner session…"]
    renders: "the three-fact confirm before the upgrade — who (the label, mono), what changes (mint + re-root, login preserved, announced or private, the old root superseded not restored), who signs (the node on your owner session; the app holds no keys). sheet_fedid, fedid_fact_1..3, btn_fedid_confirm, btn_fedid_cancel"
    tag: sheet_fedid
  - ceg: x_private:upgrade_error
    use: display-only
    type: string
    example: "no responsible-user identity yet"
    renders: "an error-container block; the screen RE-ARMS on error (submitted goes back to false) rather than leaving a spent button"
    tag: txt_fedid_error
  - ceg: x_private:upgrade_notice
    use: display-only
    type: string
    example: "Fed ID added, but announcing to the federation didn't go through — you can turn on announcing later."
    renders: "a SOFT notice, distinct from the error above: a failed announce must not undo a successful fed-ID upgrade"
    tag: "proposed:txt_fedid_notice"
```

**The soft-notice row is the interesting one.** Steps 3 and 4 of
`upgradeToFedId` (announce, trace opt-in) are explicitly non-fatal
(`NodeSwitcherViewModel.kt:571-595`): the mint and the re-root succeeded, and
reporting the whole thing as a failure would be a lie in the other direction.
But the screen leaves via `onDone()` on a clean completion, so the notice is
surfaced by the node-management surface rather than here — and there is no tag
on it anywhere. A person who half-succeeded currently finds out on a different
screen, if at all.

```yaml csd:states
populated: {tag: input_fed_label, renders: "the name field with its validation line, the announce card, the traces-elsewhere line, and Add Federation ID"}
empty:     {tag: txt_fedid_absent, renders: "this device already HAS a fed-ID (ownerHasFedId == true) — there is nothing to add, the screen says so, and no form is drawn. Login already hid the door (`FederationIdentitySection` returns early when keyId != null); the ManageNodes entry and the catch-up effect did not, and the form they reached would have minted a second identity"}
loading:   {tag: txt_fedid_progress, renders: "a spinner inside the confirm button and every field disabled; the three steps (mint → re-root → announce) are NOT individually reported, so a slow announce looks like a stuck mint"}
error:     {tag: txt_fedid_error, renders: "the node's own refusal, in an error container, with the button re-armed"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| mint the owner's fed-ID | `POST /v1/self/identity` | CIRISServer (`src/identity.rs:1945`) | live — owner-gated, on the current owner session; called with `LOCAL_NODE_URL` (`NodeSwitcherViewModel.kt:556`). Unlike associate, the mint still degrades silently on 0.5.218: `platform-sealed` (the default) walks a ladder to software and answers 200; the only signal is `hardware_type`, which the card shows. No refusal to handle |
| re-root the node on it | `POST /v1/self/upgrade-owner` | CIRISServer (`src/claim_remote.rs:1246`) | live — non-destructive, login preserved (`NodeSwitcherViewModel.kt:563`) |
| announce the owner-binding | `POST /v1/federation/announce` | CIRISServer (registered `src/claim_remote.rs:1251`; handler `src/claim_remote.rs:1077`) | live — takes effect next boot (`NodeSwitcherViewModel.kt:573`). The answer's `promoted_owner_binding_attestation_id` was read as `promoted_attestation_id` (always null) and `federation_discoverable` was dropped; both fixed (`AnnounceOwnershipWireTest`) |
| is this node already owned, and by whom | `GET /v1/setup/owned-nodes` | CIRISServer `src/auth/bootstrap.rs` (loopback-only) | live — `NodeSwitcherViewModel.kt:141`, the owner the catch-up upgrades from |
| trace opt-in | — (was `PUT /v1/my-data/accord-settings`, step 4) | CIRISAgent, via **CSD-039** | **not this card's, since 2026-09-28.** The write was issued from three screens (this catch-up, Manage Consent, Data); the Data card reads it back, so it owns it, and this screen says where it lives (`txt_fedid_traces_elsewhere`). Every remaining row is on the node. |

**Every row is on the right host now, and the fourth row used to be the one that
was not.** Step 4 was issued against `baseUrl` while steps 1-3 pin
`CIRISApiClient.LOCAL_NODE_URL`. `PUT /v1/my-data/accord-settings` is
CIRISAgent-only (`git grep 'my-data' origin/main -- src/` in CIRISServer returns
only `/v1/my-data/capacity` and `/v1/my-data/lens-identifier`), so on a
run-without-AI install — the class CSD-083 exists to describe — there was no
host for it, the failure was non-fatal and swallowed, and a person who ticked
*send traces* was told nothing. The fix was neither of the upstream asks this
row used to carry: the write is gone from here, with the toggle
(`AnnounceDecisionCard(showTraceOptIn = false)`), and the route checker's
duplicate-mutation ratchet no longer lists this screen on it. What remains is
the announce decision, which is the node's and which announcing traces depends
on. No upstream ask on this card.

## 4. Flow (how)

**The auto-entry first, because it is the path that breaks.** Sign in as a
legacy owner (`ownerHasFedId == false`) and go no further — the client lands on
`homeTarget` and this screen is pushed on top of it with no press:

```yaml
requires:
  screen: AddFederationId
expect:
  state: populated
  visible: [input_fed_label, btn_add_fedid_confirm]
```

A flow that cannot assert this step cannot tell "the catch-up fired" from "the
catch-up was silently skipped and the owner is sitting on a surface their node
cannot serve".

Then the pressed entry. Sign in as an owner whose node has no fed-ID; Login
offers the door.

```yaml
expect:
  visible: [btn_federation_create]
```

Click it and land here.

```yaml
expect:
  state: populated
  visible: [input_fed_label, toggle_announce_ownership, btn_add_fedid_confirm, btn_add_fedid_back]
```

Type a generic label (`ciris-client-user`):

```yaml
expect:
  state: error
  text: {input_fed_label: "ciris-client-user"}
```

Type a unique one, turn announce on. Sending traces is not offered here; the
line says where it is:

```yaml
expect:
  visible: [toggle_announce_ownership, txt_fedid_traces_elsewhere]
  absent: [toggle_trace_opt_in]
```

Click `btn_add_fedid_confirm`. Nothing is minted yet: the three-fact confirm
opens, and the third fact names who signs.

```yaml
expect:
  visible: [sheet_fedid, fedid_fact_1, fedid_fact_2, fedid_fact_3, btn_fedid_confirm, btn_fedid_cancel]
  text: {fedid_fact_1: "eric-moore-v1"}
```

`btn_fedid_cancel` closes it and mints nothing. `btn_fedid_confirm` runs the
upgrade; the screen leaves via `onDone()` back to where it was called from.

**A device that already has a fed-ID** (reached from ManageNodes, or by the
catch-up effect misfiring) is told so and offered no form:

```yaml
expect:
  state: empty
  visible: [txt_fedid_absent]
  absent: [input_fed_label, btn_add_fedid_confirm]
```

## 5. QA plan

**Platforms.** All five. `input_fed_label` declares its sink at
`AddFederationIdScreen.kt:117` and dispatches it on the next line, so it is
drivable — and the comment there says why the registration sits on that exact
line: "so it cannot be forgotten separately". Three screens in this area forgot
it anyway (CSD-084, CSD-085).

**Not tested here.** The upgrade itself needs an owner session against a node
that is owned the legacy way — a state `testing/gate/node_fixture.py` does not
produce, because `session_fixture` always mints a fed-ID during the wizard. That
same gap is why the auto-entry in §4 has never been driven: there is no fixture
that can produce `ownerHasFedId == false`. The announce (takes effect next
boot); the four-step progress (unreported).

**The pattern this screen gets right, and the one that should be copied.**
`btn_add_fedid_confirm` passes `onReview` — which itself checks `canConfirm`
— to `testableClickable`, so the guard lives inside the lambda and a `/click`
on a disabled button is a no-op by construction; the sheet's `btn_fedid_confirm`
checks it again before the upgrade runs. `btn_next` in the setup wizard does
not (CSD-082 §5, CSD-083 §5), which is the client half of CIRISAgent#1193.
