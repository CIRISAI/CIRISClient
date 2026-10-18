# CSD-093 — Add this device to an existing CIRIS ID (the new device, first run)

**CSD**: CSD-093 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.231 `FSD/ADD_DEVICE.md` (CIRISServer#177), Eric's wizard ruling relayed by the CIRISServer session (2026-10-18). Rewritten from the 0.5.218 sketch (a QR of this node's code and claim PIN for the other device to scan), which the server replaced with a peer-to-peer join request
**Pairs with**: CSD-094 (Approve a new device, on the device the person already has). This card asks; that one consents
**Flow**: `testing/flows/drafts/csd-093-add-this-device.yaml` (floor `unreleased`)
**Card**: `ui/screens/AddDeviceScreens.kt` (`IdentityPathChoice`, `AddThisDeviceSection`, `AddDeviceProgressBlock`, `AddDeviceWaiting`, `AddDeviceTags`), wired into `ui/screens/SetupScreen.kt` (`YouStep`, `CompleteStep`); state `viewmodels/SetupState.kt` (`IdentityPath`, `AddDeviceSetupState`, `AddDevicePhase`); behaviour `viewmodels/SetupViewModel.kt` (the add-device section, `completeAddDevice`, `finishAgentSetup`); wire `api/AddDeviceApi.kt` + `CIRISApiClient.addDevice*`; tests `AddDeviceSetupTest` (commonTest), `AddDeviceWireTest` (desktopTest, a real socket)

```yaml csd:stage
stage: building
owner: CIRISClient
```

`building`, not `testable`: the card is built and every §3 row is answered at
CIRISServer **v0.5.231**, but no client release carries it, so the flow's floor
stays `unreleased`. It moves to `testable` with the first client release that
ships this branch, once the two-node run in §5 has gone green on one leg.

## 1. Mission (why)

**Someone who already has CIRIS on one device can set up another as the same
person: the new device's first question is "new CIRIS ID, or add this device to
one you have?", and on the second path it never mints an identity, never asks
for a fedID name, and joins only after the other device consents to a code both
screens show.**

Minting a second identity for the same person is the failure this card exists
to prevent. CC 3.3.6 (`identity_occurrence`) is how one self lives on several
devices; a second fedID is a second person as far as the federation is
concerned. So the choice is the wizard's FIRST step, with neither answer
preselected (`identityPath: null` keeps Next shut): a default would quietly be
the wrong answer for exactly the person this card serves.

Serves **Autonomy** and **Integrity** through CC 3.4.5: `ownership:*` is
owner-only. The binding that makes this node the person's is signed on the
device that holds their key (CSD-094), and this device only proves it is the
device being claimed, with the one-time claim PIN its own node printed. No key
material crosses in either direction (`FSD/ADD_DEVICE.md` N6).

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Setup
flow_only: true
entry: "Setup's YOU step on a first-run node (reached as in CSD-082: Login `btn_local_login`, Startup under the HA addon, or ServerConnection after a node switch). The first question renders first: `btn_setup_new_identity` (the CSD-082/083 path, unchanged) or `btn_setup_add_this_device` (this card)"
exit: "Signed in, with the owner session `POST /v1/setup/claimed-session` collects once `POST /v1/setup/add-device/complete` applied the other device's approval, handed to the app at setup's exit (`SetupViewModel.takeCollectedSession` → CIRISApp persists it like any sign-in, Codex PR #189); on an agent build the agent's own setup-complete runs after both. Login when no session could be collected (no PIN on this device, or the node refused it)"
```

**Where it lives, decided from the code.** Setup's YOU step, behind the first
question. It is not a new Screen and not `Screen.ClaimNode`: a first-run device
has no session and no identity, which is exactly the Setup wizard's situation
(CSD-082 §2). Setup is `FLOW_ONLY` (`screen_atlas.py`), so this is
`flow_only: true` with Setup's entry and a different exit.

**What the add-device path asks, and what it does not.** It asks this
device's name (optional, `input_add_device_name`, sent as the request's
`label` so the approver sees "S23 Phone") and the person's fedID the way
*Share my ID* shows it: the QR (`btn_add_device_scan`, the `QrScanAction`
primitive feeding the same handler as the paste field, so a scan fills and
never sends), the long form or the short form (`input_add_device_contact`; the
node resolves all three, `ciris_edge::contact::resolve_contact`). It does NOT
ask a fedID name (`input_fedid_label` is absent on this path), nor an age band
(the age is the identity's, already recorded where it was minted), and on a
node without an agent nor a local account (the claim on this path is applied
through `setup/root` with no owner password, `src/add_device.rs:883-893`, so a
password here would be stored nowhere). With an agent the account fields stay:
the agent's own setup-complete creates its local user from them.

**The rest of the wizard runs as usual** (consent, and with an agent the AI
step) while the person approves on the other device. Next on YOU opens as soon
as the request is out (`requested`), not when it is approved.

**A live request locks the first question** (Codex, PR #189). While the
request is being sent, waiting, or approved and held, "Set up a new CIRIS ID"
is disabled with 'A request to your other device is still open. Cancel it
below…' (`txt_setup_identity_path_locked`), and `chooseIdentityPath` refuses
the switch for a driver that ignores the disabled control: the final step
would otherwise mint and self-claim a SECOND identity over a live join. The
way out is **Cancel this request** (`btn_add_device_cancel`), behind a
ConfirmSheet (`confirm_add_device_cancel`) whose three facts say what it can
and cannot do. CIRISServer has no route to withdraw a join request or discard
a held approval (the router at `b3e05e27` mounts only `POST`/`GET
/v1/setup/add-device` and `POST …/complete`), so cancel stops polling and
forgets the request on this device only: the other device keeps listing it
until its 15 minutes run out, and an approval already verified stays held on
this node, unused. Once `applied` there is no cancel: the node already belongs
to the existing identity.

**A disabled Next always says why.** With an agent the add-device path still
asks the local account; once the request is out, a blank or mismatched account
is the reason shown (the same messages as the new-ID path).

**The claim lands at the same point in first run** for a new or an existing
fedID: the wizard's final step. `claimLocalNodeOwnership` branches on the path,
so on this path the final step is `add-device/complete` → `claimed-session` →
the node's options (announce, the trace consent, the device name) with the
session it collected; with an agent, `finishAgentSetup` holds the agent's
setup-complete until all of that settles.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:identity_path
    use: emit
    type: "enum[new,existing]"
    example: "existing"
    renders: "the first question, two options and neither preselected: 'Set up a new CIRIS ID' (`btn_setup_new_identity`) and 'Add this device to an existing CIRIS ID' (`btn_setup_add_this_device`). Nothing else on YOU renders until one is chosen. On a node older than 0.5.231 (a bare 404 on `GET /v1/setup/add-device`) the second is shown disabled with the reason (`txt_setup_add_device_too_old`)"
    tag: setup_identity_path
    assert:
      one_of: {setup_identity_path: [NEW, EXISTING]}
  - ceg: x_private:device_label
    use: emit
    type: string
    example: "S23 Phone"
    renders: "'Name this device (optional)', placeholder 'e.g. S23 Phone, 2023 Laptop'. Sent as `label`; the only naming question on this path"
    tag: input_add_device_name
  - ceg: x_private:add_device_contact
    use: emit
    type: string
    example: "CIRIS-V3-…"
    renders: "'Your CIRIS ID' with 'On your other device, open Share my ID. Scan its QR code, or paste the long or short form here.' The scan fills this field and does not send; `btn_add_device_send` sends"
    tag: input_add_device_contact
  - ceg: x_private:match_code
    use: display-only
    type: string
    example: "482 913"
    renders: "while `requested`: 'Check that this number matches the one on your other device:' and the six digits large, grouped in threes. Derived by the OTHER device from this device's key and challenge (N1), so a copied challenge shows a different code. Not shown after approval: by then it is history"
    tag: text_add_device_match_code
    assert:
      matches: {text_add_device_match_code: "^[0-9]{6}$"}
  - ceg: x_private:add_device_state
    use: display-only
    type: "enum[requested,approved,applied,denied,expired,failed,interrupted]"
    example: "requested"
    renders: "one line per state: 'Waiting for your other device to approve this one. You can carry on with setup in the meantime.' / 'Approved and checked on this device. It joins when setup finishes.' / 'This device has joined your CIRIS ID.' / 'Your other device declined this request. Nothing was added.' / 'This request ran out of time. On your other device choose Accept new requests again, then send a new request from here.' (this device's own 15 minutes) or, when `expired` carries a detail, 'Your other device no longer holds this request: it waited too long for an answer, or its approval wasn't collected in time. …' (the other device's 15 minutes, `b3e05e27` `src/add_device.rs:649-655`; on a v0.5.231 node the same fact arrives as `failed` + `detail: Unknown` and is read as this) / 'The approval could not be checked on this device, so nothing was applied.' + the node's `detail` / 'This device restarted before your other device answered, so the request was lost.' The last four carry 'Send a new request' (`btn_add_device_new_request`)"
    tag: text_add_device_status
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "eric-moore-v1"
    renders: "'Joining eric-moore-v1' once `owner_key_id` arrives with `approved`: the identity is SHOWN, never asked for. The client shows the claim; it cannot make it (CC 3.4.5)"
    tag: text_add_device_owner
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: "enum[add_device.not_accepting,add_device.unknown_identity,add_device.no_device,add_device.unreachable,add_device.already_owned,add_device.no_transport,add_device.mint_failed,add_device.rng,add_device.unexpected,add_device.not_approved,add_device.apply_failed]"
    example: "add_device.not_accepting"
    renders: "the refusal by id, the node's English beneath; 'Try again' resends. `not_accepting` is the common one: 'your other device is not accepting new devices — open Add a device on it, then try again'. The ids are the server's (`FSD/ADD_DEVICE.md` §4.1); their bundle keys arrive with the server-parity branch (`feat/server-0.5.231-parity`), and until then the English shows"
    tag: add_device_refusal
```

```yaml csd:states
populated: {tag: card_add_this_device, renders: "the name and fedID fields, the scan action and Send request; once sent, the match code and the state line"}
empty:     {tag: setup_identity_path, renders: "the first question unanswered: two options, nothing preselected, Next disabled with 'Choose first: set up a new CIRIS ID, or add this device to one you already have.'"}
loading:   {tag: btn_add_device_send, renders: "'Asking your other device…' on the disabled send button while `POST /v1/setup/add-device` is in flight (the node makes one first-contact round trip to the other device, up to 20 s)"}
error:     {tag: add_device_refusal, renders: "the refusal by id with the node's English, and Try again. Distinct from `denied`/`expired`/`failed`, which are answers about a request that WAS sent and say 'Send a new request'"}
```

**COMPLETE on this path** has one more state, `setup_add_device_waiting`:
`add-device/complete` answered 409 `add_device.not_approved`, so the rest of the
wizard is done and the other device has not approved yet. It is not an error
and does not auto-leave. On this path COMPLETE hands off by itself only once
the claim APPLIED with no error (`shouldAutoFinishSetup`): a recoverable
`apply_failed` stays on COMPLETE for Retry (Codex, PR #189). It shows the match code and state line, polls, and finishes by itself
when `approved` arrives (`retryAddDeviceFinish`); 'Check again'
(`btn_add_device_finish_retry`) does the same by hand. **No countdown here**:
once approved, the approval is held on this node with no expiry and restored
on reboot (`<home>/add_device_pending.json`, `src/add_device.rs:676-713`), and
`complete` never contacts the other device, so its window closing or going
offline no longer matters. `add_device.apply_failed` (502) renders in the
existing `setup_ownership_error` panel by id, recoverable: the node keeps what
it held, so Retry re-applies it.

## 3. Contracts (who)

Verified against CIRISServer **v0.5.231** (tag, tarball via `gh api`), `src/add_device.rs`
unless named. Every call is to THIS device's own node over loopback, with no
session (`require_loopback`, `:1257`).

| value | endpoint | owner | state |
|---|---|---|---|
| ask the other device | `POST /v1/setup/add-device {contact, label?}` → 202 `JoinProgress` `{state:"requested", match_code, target_node, occurrence_key_id}` | CIRISServer `start`, `:388-555`; route `:1248-1251` | **live**, wired: `CIRISApiClient.addDeviceStart` ← `SetupViewModel.sendAddDeviceRequest`. Refusals `{error, reason_id}` (`src/auth/refusal.rs:52`): 409 `already_owned` (`:389-395`), 503 `no_transport` (`:396-402`), 404 `unknown_identity` (`:409-415`), 404 `no_device` (`:422-428`), 500 `mint_failed` / `rng`, 502 `unreachable` (`:506-512`), 409 `not_accepting` (`:518-523`), 502 `unexpected` (`:525-527`) |
| where the request stands | `GET /v1/setup/add-device` → `{state, match_code, target_node, occurrence_key_id, owner_key_id, detail}` | CIRISServer `status`, `:923-929`; shape `JoinProgress` `:323-337` | **live**, polled every 2.5 s, awaited, one at a time, until it leaves `requested` (`startAddDevicePolling`). States `requested \| approved \| applied \| denied \| expired \| failed`, plus `idle` (`:344`), the server's starting value, which the FSD's table omits |
| apply the approval at the end of first run | `POST /v1/setup/add-device/complete` → 200 `{state:"applied", owner_key_id, …}` | CIRISServer `complete`, `:812-841`; `apply` `:883-919` | **live**, wired in `completeAddDevice` through `claimLocalNodeOwnership`. 409 `add_device.not_approved` (`:814-820`) → waiting; 502 `add_device.apply_failed` (`:835-838`) → recoverable error by id. Skipped when the phase is already `applied` (a retry after the session step), because the node clears what it held and would answer `not_approved` |
| this device's owner session | `POST /v1/setup/claimed-session {claim_pin}` | CIRISServer `src/auth/bootstrap.rs:1471-1479`, mounted `:1734-1737` | **live**, the same route CSD-093 wired at 0.5.218, now reached from `completeAddDevice` after `complete` answers `applied`. The PIN is read from this node's own `claim_pin` file (`readLocalClaimPin`, the same provider the self-claim uses) and sent nowhere else |
| the agent's setup-complete (agent builds) | `POST /v1/setup/complete` | CIRISAgent | live; runs only after the two rows above settle (`finishAgentSetup` → `claimThenComplete`). See the hand-off in §6 |

### The PIN contract

**No request this card makes carries the claim PIN off this device.** One
request carries it, to this device's own node: `claimed-session`, after the
claim was applied. The new device never shows the PIN on this path (the
0.5.218 sketch drew it in a QR; that design is retired: the other device
signs, this node verifies offline and applies through its own `setup/root`
with its own PIN, `:883-893`). `collectClaimedSession` logs the URL, never the
PIN. `AddDeviceWireTest` asserts the PIN reaches `claimed-session` and only
after `add-device/complete`.

### Restart-resume

The server documents this behaviour as the client implements it: CIRISServer `FSD/ADD_DEVICE.md` §4.2 "Restarts" (at `fix/client-copy-0.5.232` `b3e05e27`, `FSD/ADD_DEVICE.md:130`), which also tabulates the HTTP status of every refusal and says the claim carries no owner password.

On opening the wizard, `probeAddDevice` asks `GET /v1/setup/add-device` once:

* `approved` (a verified approval held across a node reboot, `restore_pending`
  `:692-713`) or `applied`: the wizard resumes the add-device path at that
  state. `requested` (the app restarted, the node did not): it resumes and
  keeps polling.
* `idle`: nothing outstanding; the first question is asked.
* a bare 404: a node older than 0.5.231; "Add this device" is disabled with
  the reason and the new-ID path is unchanged.

**Only a VERIFIED approval survives a node reboot.** The request itself lives
in the node's memory (`progress()`, `:340-347`) and in a spawned poll task
(`:540-551`); a reboot before the other device answers loses it, and GET then
answers `idle`. The wizard reads `idle` for a request it had sent as
`interrupted` ("send a new request"), never as a fresh start.

## 4. Flow (how)

On a first-run node, reach Setup's YOU step.

```yaml
expect:
  state: empty
  visible: [btn_setup_new_identity, btn_setup_add_this_device]
  absent: [input_fedid_label, input_add_device_contact]
```

Choose `btn_setup_add_this_device`, name the device, paste the other device's
long-form fedID, Send.

```yaml
expect:
  state: populated
  visible: [card_add_this_device, text_add_device_match_code, text_add_device_status]
  matches: {text_add_device_match_code: "^[0-9]{6}$"}
  absent: [input_fedid_label, age_band_adult]
```

The other device approves (CSD-094). This device notices by itself:

```yaml
expect:
  visible: [text_add_device_owner]
  text: {text_add_device_status: "approved"}
```

Next through the rest of the wizard; the final step applies the claim and
collects the session.

## 5. QA plan

**Unit and wire (green on this branch).** `AddDeviceSetupTest` (commonTest,
30 cases): the first question and no default; the add-device path asks no
fedID name and no age; the request's contact and label; each refusal by id
(`not_accepting`, `unknown_identity`, `no_device`, `unreachable`,
`already_owned`, `no_transport`) and not_accepting → Try again; polling to
`approved` with `owner_key_id`, never overlapping, stopping when answered;
`denied`/`expired`/`failed` → a new request, and `expired` → Send a new
request → a second `start`; `idle` after a sent request → interrupted;
`complete` 409 → waiting, no session; `complete` → `claimed-session` in that
order with the session applied; `apply_failed` recoverable by id; a retry
after the claim landed does not apply twice; the path never mints; with an
agent, `complete` → `claimed-session` → the agent's setup-complete, and
not-approved holds the agent until the approval arrives; restart-resume from
`approved` and from `requested`; `idle` leaves the question open; a bare 404
disables the option. `AddDeviceWireTest` (desktopTest, a real socket) repeats
the request, a refusal, the bare 404, the with-agent order and the 409 on the
wire. The Codex round on PR #189 adds: the collected session handed to the app once (and nothing handed when none was collected); a live request locking the first question until a confirmed cancel, which also stops the poll; an applied request offering no cancel; the account reason on a disabled Next with an agent; COMPLETE leaving by itself only on an applied claim. Each was red on its planted defect first. The 0.5.232 round adds: `expired` from the new detail and its new request, the older node's `failed`/"Unknown" read as expired, and a real failure staying a failure. Shown red on planted defects before being believed: a settle-await
removed, `not_approved` treated as an error, "send a new request" refused
after `expired`, and a closed window hiding held requests (CSD-094) each
failed at least one test.

**A real two-device run, and what it needs.** `testing/flows/drafts/csd-093-add-this-device.yaml`.
The other device must be a CLAIMED node with an owner fedID and a mesh
transport, and this leg's node must be first-run and able to reach it.
`testing/gate/two_node.py` stands up a claimed peer, but today it also peers it
with the leg's node using the leg's owner credentials, which a first-run leg
does not have. A real run needs a two_node mode that: (1) claims the peer only,
(2) opens its window with the peer's owner session
(`POST /v1/self/device-requests/open`), (3) exports the peer owner's long-form
fedID (`${PEER_FEDCODE}`) to the flow, and (4) approves the pending request
from the peer's side by API while the flow waits on this device. Until then
the flow stays a draft.

**Not tested here.** The camera (QrScanAction's double stands in). NAT
traversal between the two devices (edge's first contact on the opaque plane,
CIRISEdge#683).

## 6. Delta — card vs API vs CC

* **Where the server source differed from the FSD, and what became of it.** All six were answered on CIRISServer `fix/client-copy-0.5.232` at `b3e05e27` (this client's pin, `client/VENDORING.md`): (a), (b) and (e) are now documented in `FSD/ADD_DEVICE.md` §4/§4.2; (c) is fixed: the other device no longer holding the request is now `expired` with the detail "your other device no longer holds this request — send a new one" (`src/add_device.rs:649-655`), and the client keeps the `failed`/"Unknown" reading only as the v0.5.231 fallback; (d) is fixed: the request carries `host_device_class()` (`src/add_device.rs:497`, `src/backend.rs:157`), phone on mobile, which CSD-094's rows now show. As found at v0.5.231: (a) `GET` starts at
  `idle`, not in the FSD's state list (`:344`). (b) A request that has not
  been approved does not survive a node reboot (only the verified approval
  does), so "restart mid-wizard is safe" holds for the approval, not for the
  request; the wizard says "interrupted". (c) If the other device forgets the
  request (its own 15 minutes ran out, or it restarted), the poll gets
  `Unknown` and B's state is `failed` with `detail: "Unknown"`
  (`:639-645`), not `expired`; `expired` is B's own 15-minute deadline
  (`:582`, `:649`). (d) `device_class` is always `laptop`
  (`DEVICE_CLASS_LAPTOP`, `:491`), whatever the device is. (e) `mint_failed` and
  `rng` are 500, `unexpected` 502; the FSD lists them without statuses.
* **The agent hand-off.** On an agent build, setup-complete (CIRISAgent,
  cirisagent-d5) reloads the runtime, which kills the session the add-device
  calls ride. The client now runs `add-device/complete` → `claimed-session`
  → the node options → THEN `POST /v1/setup/complete`, the same E9 ≺ E10
  order the self-claim uses. The agent's setup-complete is unchanged; it should
  not assume the node was self-claimed with the account's password (on this
  path the binding came from the other device and carries no owner password).
* **Account on node-only builds.** The add-device path asks no password on a
  node without an agent, because nothing would store it. Signing in again later
  on such a device is by the fedID (self-login), not a password.
* **CC.** CC 3.4.5 holds: this device signs nothing that claims anything; the
  owner-binding and the occurrence are signed on the approving device. CC 3.3.6
  holds: the new device becomes an `identity_occurrence` of the same self
  (`put_identity_occurrence`, `:907-911`), not a second identity.
