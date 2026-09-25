# CSD-086 — Add Federation ID (the catch-up, not the wizard again)

**CSD**: CSD-086 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: bool
    example: false
    renders: "the trace opt-in, and it is ENABLED ONLY when announce is on — an un-announced node never federates its traces, so the control that would say otherwise is not offered"
    tag: toggle_trace_opt_in
  - ceg: x_private:announce_ownership
    use: emit
    type: bool
    example: true
    renders: "the same first-class announce decision the wizard makes: announcing is what unlocks sending traces and joining communities. Turning it off also clears the trace opt-in, so the two cannot disagree"
    tag: toggle_announce_ownership
  - ceg: x_private:upgrade_error
    use: display-only
    type: string
    example: "no responsible-user identity yet"
    renders: "an error-container block; the screen RE-ARMS on error (submitted goes back to false) rather than leaving a spent button"
    tag: "proposed:txt_fedid_error"
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
populated: {tag: input_fed_label, renders: "the name field with its validation line, the announce card, and Add Federation ID"}
empty:     {tag: "proposed:txt_fedid_absent", renders: "this device already HAS a fed-ID — there is nothing to add and the screen should say so. Login handles this by not offering the door (`FederationIdentitySection` returns early when keyId != null), but the screen itself has no such guard and renders a form that will collide"}
loading:   {tag: "proposed:txt_fedid_progress", renders: "a spinner inside the confirm button and every field disabled; the four steps (mint → re-root → announce → opt-in) are NOT individually reported, so a slow announce looks like a stuck mint"}
error:     {tag: "proposed:txt_fedid_error", renders: "the node's own refusal, in an error container, with the button re-armed"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| mint the owner's fed-ID | `POST /v1/self/identity` | CIRISServer (`src/identity.rs`) | live — owner-gated, on the current owner session |
| re-root the node on it | `POST /v1/self/upgrade-owner` | CIRISServer (`src/claim_remote.rs`) | live — non-destructive, login preserved |
| announce the owner-binding | `POST /v1/federation/announce` | CIRISServer (`src/auth/ownership.rs`, `src/compose.rs`) | live — takes effect next boot |
| trace opt-in | accord settings update | CIRISAgent | live; non-fatal |

**No upstream ask on this card.** Every route it needs exists and is on the
right host. That is worth stating plainly, because it is the only card in this
area of which it is true.

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

Type a unique one, turn announce on, confirm:

```yaml
expect:
  visible: [toggle_trace_opt_in]
```

The screen leaves via `onDone()` back to where it was called from.

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
`btn_add_fedid_confirm` passes `onConfirm` — which itself checks `canConfirm`
— to `testableClickable` (`AddFederationIdScreen.kt:255-270`), so the guard
lives inside the lambda and a `/click` on a disabled button is a no-op by
construction. `btn_next` in the setup wizard does not (CSD-082 §5,
CSD-083 §5), which is the client half of CIRISAgent#1193.
