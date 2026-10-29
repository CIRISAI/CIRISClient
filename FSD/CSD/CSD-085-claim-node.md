# CSD-085 — Claim a node (binding a responsible party to an unowned key)

**CSD**: CSD-085 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 · **Reads against**: `docs/FSD-remote-first-run-claim.md`
**Flow**: `testing/flows/drafts/csd-085-claim-node.yaml` (floor `>=0.5.224`); its three text fields are not drivable yet and its no-signer step needs the local node stopped mid-flow (see §5)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A node with no owner gets one, by the owner's own signature and nobody
else's.** Serves **CC 3.2** and **CC 3.4.5**, and this screen is where those two
rules are visible rather than described:

* The owner-binding is **single-valued** — a node key is owner-bound by at most
  one responsible steward at any time (CC 3.2, single-owner invariant).
* The **owner signs its own owner-binding**, and a node with no genesis owner is
  **unowned, not a first-come landgrab target** (CC 3.2). That is what the
  one-time claim PIN is: proof of physical custody, read from the node's own
  0600 `claim_pin` file, which never travels over HTTP. Since ciris-server
  0.5.227 the boot banner names that file instead of printing the PIN, so the
  PIN is in no log: the claim PIN is a file on disk, never the log
  (CIRISClient#184).
* `ownership:*` is owner-only, because "a third party asserting your responsible
  party is a **seizure by attestation**" (CC 3.4.5). **The app performs no
  crypto.** It hands `{node_code, claim_pin, cohort_scope}` to the *local* node;
  the local node decodes the code, hybrid-signs the `delegates_to(user →
  target)` owner-binding in its own substrate, and POSTs the signed artifact to
  the target's `/v1/setup/root`.

**The precondition is asked first, on entry.** The claim is signed by this
device's own node, so without that node running there is nothing to type a
NodeCode into — and the operator should learn that before making a trip to the
target node for its PIN file, not after (`ClaimNodeScreen.kt:90-96`,
`docs/FSD-remote-first-run-claim.md` A2).

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: ClaimNode
flow_only: true
entry: "Interact's node-switcher affordance (CIRISApp.kt:2969-2971, plumbed InteractScreen.kt:146/459/900/1000); ManageNodes → claim ownership (CIRISApp.kt:4042); and a self-loop, `onClaimedAnother` (CIRISApp.kt:4028), to claim node B after node A. BOTH real entries are in-shell — this screen is never reached from a pre-login flow"
exit: "Screen.Interact, from the legacy back map (CIRISApp.kt:4921), which wins before the placement rule"
```

No nav hop — `ClaimNode` is in `FLOW_ONLY` (`screen_atlas.py:42`); CSD-080 §2
records why `flow_only:` is a checked key.

**`screenToSurface` returns `NavSurface.Help` for this screen.**
`CIRISApp.kt:5960-5962` is ONE `when` branch, six screens, with the `-> null`
its comment implies missing:

```kotlin
// Flow-only / no sidebar
Screen.Startup, Screen.Login, Screen.Setup, Screen.ServerConnection, Screen.ClaimNode,
Screen.Help -> ai.ciris.mobile.shared.ui.nav.NavSurface.Help
```

**Two definitions of "flow-only" disagree, and each is internally consistent.**
`showSidebar` (`CIRISApp.kt:1789-1792`) suppresses the shell for **four**
screens, and the comment above it names exactly those four — "Pre-login screens
(Startup/Login/Setup/ServerConnection) have no shell". The `when` branch lumps
`ClaimNode` in with them. Comment and `showSidebar` agree with each other; both
disagree with the branch; nothing looks wrong from either end.

So the fall-through is masked six ways over six screens: four by `showSidebar`
being false, `Help` because `NavSurface.Help` is the right answer there, and
`ClaimNode` by the legacy back map at `:4921`, which returns before
`activeSurface` is consulted. **`ClaimNode` is the only screen where the wrong
value reaches the shell** — it renders with **Help highlighted**
(`activeSurface`, `CIRISApp.kt:1788`) and, because `Help` is an instrument
surface, `tabOf(Help)` is `placementOf(Help)?.tab` = **null**, so the tab row is
driven by a null tab. It reads as cosmetic until you ask what `activeSurface` is
for: a flow asserting "I am on Help" by reading the highlighted nav row would
pass while standing on the claim screen.

**The fix is not `-> null`.** The codebase does not merely have a convention for
an in-shell detail screen with no nav row of its own, it **states** it, at
`CIRISApp.kt:5935-5936`:

> A chat keeps the Contacts card lit — it is a leaf of that surface, not a
> sidebar destination of its own.

and applies it four times:

```kotlin
Screen.SkillStudio, Screen.SkillImport -> NavSurface.Skills      // CIRISApp.kt:5910
is Screen.NetworkPeerDetail            -> NavSurface.LayerGlobalCommons  // :5926
is Screen.UserChat                     -> NavSurface.Contacts    // :5937
Screen.DutyConferral                   -> NavSurface.Accord      // :5941
```

So `-> null` would not break a pattern, it would contradict a documented one.
**`Screen.ClaimNode -> NavSurface.Nodes`** (`EpistemicNav.kt:199`, id `nodes`),
on its own line — and leave `showSidebar` alone: **both of this screen's real
entries are in-shell**, so suppressing the chrome would hide the nav on a screen
the person reached by navigating.

**The wrinkle.** The two entries
have different parents: this screen is entered both from ManageNodes
(`CIRISApp.kt:4042`) and from an InteractScreen affordance
(`CIRISApp.kt:2969-2971`), and from either one the lit row is Nodes rather than
the card the person came from. Nodes is still right —
it is the screen's *subject*, and a leaf is lit by what it is about, not by the
route taken to it. The thing that should eventually go is
`Screen.ClaimNode -> Screen.Interact` in the legacy back map (`:4921`), which
sends a claim back to the agent home; not the highlight.

`Screen.VerifyAgent` has the same shape — its only entry and its exit are both
ManageNodes (CSD-087 §2) — and is currently `-> null` at `:5964`, so the Nodes
row goes dark during a verify; it should be `NavSurface.Nodes` for the same
reason. `AddFederationId` is the third of three different right answers and
should stay `null`: it returns to wherever it was called from and can be pushed
automatically over the landing screen, so it has no single parent. Three screens
needing three answers is why the branch collapsed them into one in the first
place, and a reader needs that stated or it re-collapses.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "SYSTEM_ADMIN"
    renders: "'You now own <node>. Role: SYSTEM_ADMIN.' The client RENDERS this and constitutionally cannot emit it — the target node's substrate does, on the owner's signature (CC 3.4.5)"
    tag: "proposed:txt_claim_role"
  - ceg: x_private:node_code
    use: emit
    type: string
    example: "CIRIS-V1-7QF2-...."
    renders: "the NodeCode field — pasted or scanned; dashed form accepted"
    tag: field_claim_node_code
  - ceg: x_private:claim_pin
    use: emit
    type: string
    example: "XXXX-XXXX"
    renders: "the one-time PIN field. A human reads the PIN from the target node's claim_pin file (its banner names the file, never the PIN); it never travels over HTTP by design"
    tag: field_claim_node_pin
  - ceg: x_private:owner_display_name
    use: emit
    type: string
    example: "Eric"
    renders: "the display name bound to the founder's federation identity"
    tag: field_claim_node_displayname
  - ceg: x_private:cohort_scope
    use: emit
    type: "enum[self,family,community,affiliations,species,biosphere,federation]"
    example: "self"
    renders: "'Add this node to:' with Yourself selected and shown alone; More options reveals the rest of the scopes the node accepts (its data.cohort_scopes, widening order; the three self/family/community on an older node). Required, or the claim 400s; the value rides inside the signed body"
    tag: radio_cohort_self
  - ceg: x_private:local_signer_ready
    use: display-only
    type: bool
    example: false
    renders: "an error-toned card on entry: this device's own node is not running, so there is nothing to sign with. Asked BEFORE the operator walks to the target node"
    tag: card_claim_no_signer
  - ceg: x_private:bootstrap_phase
    use: display-only
    type: "enum[IDLE,DECODING,NEED_URL,PINNING,PINNED]"
    example: "PINNING"
    renders: "a four-step progress row: decode → connect → pin identity → claimed"
    tag: "proposed:txt_claim_phase"
  - ceg: x_private:claim_error
    use: display-only
    type: string
    example: "pin_invalid"
    renders: "two DIFFERENT failures with two different remedies: 'Could not connect to that node' (bootstrap.error) and 'The claim was refused' (bootstrap.claimError)"
    tag: "proposed:txt_claim_error"
```

**The cohort picker offers what the node accepts, `self` first.** A claim is
normally `self`: the node is yours, and announcing it later re-signs it at
`federation` (CC 3.2). A group scope is for an unannounced node joining that
group; the owner then signs a second binding at the group's scope
(CIRISPersist#1140). So Yourself is selected and shown alone, and *More options*
(`btn_cohort_advanced`) reveals the rest. The list is this node's own
`data.cohort_scopes` from `GET /v1/setup/status` (CIRISServer#666, 0.5.242:
self, family, community, affiliations, species, biosphere, federation, in
widening order). A node that does not declare it accepts the three
`self | family | community`, and those are offered. A scope this client has no
label for is shown as the node names it.

```yaml csd:states
populated: {tag: field_claim_node_code, renders: "the three fields, the cohort picker and Claim; the progress row sits below and fills in as the phases advance"}
empty:     {tag: card_claim_no_signer, renders: "this device's own node is not running: the form renders but Claim is disabled, and the card says why. A claim form with no signer behind it is an empty form in the only sense that matters here"}
loading:   {tag: "proposed:txt_claim_phase", renders: "'Connecting…' then 'Claiming…' on the button, a spinner in it, and every field disabled"}
error:     {tag: "proposed:txt_claim_error", renders: "the node's own refusal — 'pin_invalid', 'already owned', a transport failure — never folded into one sentence"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the cohort scopes the claim may name | `GET {local}/v1/setup/status` → `data.cohort_scopes` (widening order) | CIRISServer 0.5.242 (CIRISServer#666, PR #835) | **unreleased.** Read once the local signer is up (`getNodeCohortScopes`, `NodeSwitcherViewModel.checkLocalSigner`); absent on an older node, which accepts `self`, `family`, `community` |
| is the local signer up | `GET /v1/identity` via `isLocalNodeUp` | CIRISServer `src/compose.rs:3132` (static JSON, a liveness probe) | live — `NodeSwitcherViewModel.kt:839` |
| the code to claim | `GET /v1/federation/node-code` | CIRISServer `src/federation_nodecode.rs` — `{code, qr_payload, key_id, alias_hint}` | live — `getNodeCode` (`NodeSwitcherViewModel.kt:675`), this device's own code for the other side to scan |
| the claimed device picks up its session | `POST /v1/setup/claimed-session {claim_pin}` | CIRISServer 0.5.218 (loopback, 15-minute hold) | **not called from this screen.** A claim by another device succeeds WITHOUT a session (`session_pickup: "local"`); the claimed device collects it here. `ClaimRemoteResponse` lacks `cohort_scope`, `owner_binding_attestation_id`, `session_pickup`, `claimer_key_admitted`, `local_directory_updated`, and nothing handles a claim that succeeds with no session. Belongs with the approve-a-new-device pass |
| decode + pin the target | NodeCode decode, then the target's identity probe | CIRISServer | live |
| sign and deliver the claim | `POST {local}/v1/setup/claim-remote` | CIRISServer (`src/claim_remote.rs:386`, integ `:405`) | live — **loopback-only and first-run-gated**; owner-gated once owned. The request also takes `target_url`, which `ClaimRemoteRequest` lacks; on 0.5.218 a code with no transport hint refuses `claim.no_route` and the client has no way to give an address. 0.5.218 types every refusal (`claim.*` ×10 and `auth.claim.*` ×10); en.json has none of the `claim.*` and most of the `auth.claim.*`, and `classifyClaimFailure` string-matches a message truncated at 200 chars that can cut the id off. Queued with the 0.5.218 ids on CIRISClient#78 |
| receive the claim | `POST {target}/v1/setup/root` | CIRISServer (`src/auth/ownership.rs`, `src/auth/bootstrap.rs`) | live — the **one** setup route reachable off-host (405 from the LAN, not 403: measured on 0.5.190, `docs/FSD-remote-first-run-claim.md` §3.1) |
| is the target already set up / owned | `GET /v1/setup/status`, `GET /v1/setup/owned-nodes` | CIRISServer | **wrong-host by construction** — loopback-only, 403 off-host. This client **cannot ask a remote node whether it has an owner**; the operator has to know |

**Two structural consequences, both upstream asks.**

1. **Case B is blocked server-side.** Claiming a *configured, unclaimed* node
   (case A) works. Running first-run config against a *bare* remote node (case
   B) needs `/v1/setup/status` and `/v1/setup/consent-disclosure`, both
   loopback-only, so no amount of client work reaches it
   (`docs/FSD-remote-first-run-claim.md` §3.2). **CIRISServer**: an off-host
   read of a node's claim posture, or an explicit statement that there will not
   be one.
2. **wasm cannot claim at all.** Every store platform runs a local node
   (`PythonRuntime.{desktop,android,ios}.kt`); the web build does not
   (`PythonRuntime.wasmJs.kt`). It must say so rather than offer the flow — and
   today it offers it.

## 4. Flow (how)

Open the screen with this device's node running.

```yaml
expect:
  state: populated
  visible: [field_claim_node_code, field_claim_node_pin, field_claim_node_displayname, radio_cohort_self, btn_claim_node_submit]
  absent: [card_claim_no_signer]
```

Open it with this device's node down.

```yaml
expect:
  state: empty
  visible: [card_claim_no_signer]
```

Enter a NodeCode and a wrong PIN, submit.

```yaml
expect:
  state: error
  visible: ["proposed:txt_claim_error"]
```

The refusal text is the bundle's, by `ClaimFailure.messageKey`
(CIRISClient#184): a rejected PIN is `mobile.claim_node_err_pin_rejected`
("check the PIN in the claim_pin file in the node's home folder"), an owned
target is `mobile.claim_node_err_already_claimed`, and a blank PIN is
`mobile.claim_node_err_pin_blank`. None of them names a console — no console
carries the PIN (`ClaimNodeCopyTest`).

Enter a NodeCode and its live PIN, choose a cohort, submit.

```yaml
expect:
  visible: ["proposed:txt_claim_role", btn_claim_node_another, btn_claim_node_to_consent]
  matches: {"proposed:txt_claim_role": "^SYSTEM_ADMIN$"}
```

**None of these steps runs today** — see §5.

## 5. QA plan

**Platforms.** Four. wasm has no local signer and should refuse the screen.

**Not tested here — and this is why the stage is `envisioned`.** All three input
fields are `Modifier.testable(...)` with **no** `rememberInputSinks` anywhere in
`ClaimNodeScreen.kt`, so `/input` has nothing listening:
`client/tools/check_ui_drivable.py --list` names `field_claim_node_code`,
`field_claim_node_pin`, `field_claim_node_displayname` and `btn_claim_node_back`.
The five-platform gate can open this screen and cannot type into it — which
means **the only constitutional act in the first-run path has no automated
coverage at all**.

Also untested: the claim itself needs a second, unclaimed node and a PIN read
from its `claim_pin` file, which no fixture provides; `testing/gate/node_fixture.py`
starts one node.

**The ask, in order.** (1) `CirisTextField` for all three fields — drivable by
construction (`ui/primitives/Controls.kt:91`). (2) Tags for the role, the phase
and the two error kinds. (3) A two-node fixture, or an explicit statement in
`testing/README.md` that the remote claim is manual. (4) **CIRISServer**: an off-host claim-posture read.

**One open defect.** `btn_claim_node_another` and
`btn_claim_node_to_consent` pass an empty lambda to `testableClickable`
(`ClaimNodeScreen.kt:320, 331`) — the real work is in the Button's own
`onClick`. A `/click` on either registers and does nothing, which is the
"a no-op click is indistinguishable from success" failure of CIRISClient#28,
present on two controls.
