# CSD-094 — Approve a new device (the device you already have)

**CSD**: CSD-094 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.231 `FSD/ADD_DEVICE.md` (CIRISServer#177). Rewritten from the 0.5.218 sketch (scan the new device's QR and drive `claim-remote` at it over HTTP), which the server replaced with a peer-to-peer join request the new device sends to this one
**Pairs with**: CSD-093 (Add this device to an existing CIRIS ID, on the new device) · CSD-037 (My Identity, the page this card sits on)
**Flow**: `testing/flows/drafts/csd-094-approve-new-device.yaml` (floor `unreleased`)
**Card**: `ui/screens/AddDeviceScreens.kt` (`ApproveDeviceCard`, `AddDeviceTags`), placed on `ui/screens/IdentityManagementScreen.kt` above the roster's older "add a key" card; `viewmodels/ApproveDeviceViewModel.kt`; wire `api/AddDeviceApi.kt` (`DeviceRequestsApi`) + `CIRISApiClient.{open,list,approve,deny}DeviceRequest(s)`; tests `ApproveDeviceViewModelTest` (commonTest), `AddDeviceWireTest.theApprovingDevicesCallsCarryItsOwnerSession` (desktopTest)

```yaml csd:stage
stage: building
owner: CIRISClient
```

`building`, not `testable`: built, every §3 row answered at CIRISServer
**v0.5.231**, no client release carries it yet (floor `unreleased`).

## 1. Mission (why)

**From a device they already use, a person can let a new device join their
CIRIS ID: open the window for new requests, see which device is asking and a
six-digit code its own screen also shows, and approve it or deny it, each
after a confirm that says what the act does.**

Serves **Integrity** through CC 3.4.5: `ownership:*` is owner-only, and the
owner's signature *is* the claim. The person's key stays on this device;
approving signs the owner-binding to the new node and the new device's
occurrence under the person's self, records both, and re-wraps the self files'
keys to it (`sign_and_record`, `src/add_device.rs:1098-1241`). It serves CC
3.3.6: the new device becomes another occurrence of the same self, not a second
person. And it serves the matching code's one job (N1): the code is derived
HERE from the requester's key and challenge, so a person comparing two screens
can tell their device from an impostor that copied the challenge.

## 2. Surface (what)

```yaml csd:surface
surface: identity-management
screen: IdentityManagement
```

**Where it lives, decided by routes.** The page `nav_map` derives as
`btn_my_things -> nav_instrument_devices_keys -> nav_epistemic_identity_management`
(My things › Devices & keys › My Identity) already holds the person's devices:
the roster (`GET /v1/self/occurrences`, CSD-037) and the older "add a key a
device already made" card (`POST /v1/self/occurrence`). No screen called
`/v1/self/device-requests` before this card, so it is not a duplicate; it is
the 0.5.231 door to the same roster. It sits directly above the older card,
and that card is renamed by what the person holds ("Add a key a device already
made", `mobile.identity_add_key_title`) so the two are not two buttons called
"Add a device" (§6 of the 0.5.218 sketch recommended exactly this).

**The window gates NEW requests only.** `POST /v1/self/device-requests/open`
opens it (10 minutes by default, 30 at most). While open, the card says
"Accepting new requests: 9:42" and counts down locally between polls. When it
closes, the node refuses new joins (`add_device.not_accepting` on the new
device) but every request already held stays listed and approvable, each with
its own "Request expires in m:ss" (REQUEST_TTL 15 min − `requested_secs_ago`),
and "Accept new requests again" reopens the window. The card polls
`GET /v1/self/device-requests` every 3 s while visible, awaited and never
overlapping.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:add_device_window
    use: display-only
    type: int
    example: 582
    renders: "'Accepting new requests: 9:42' while `window_secs_left` is a number; 'Not accepting new requests. Requests already listed can still be approved.' when it is null (the node clears it on close), with 'Accept new requests' (`btn_approve_device_open`, 'Accept new requests again' once it has been open)"
    tag: text_approve_device_window
    assert:
      one_of: {text_approve_device_window: [open, closed]}
  - ceg: x_private:device_label
    use: display-only
    type: string
    example: "S23 Phone"
    renders: "'Consent to claim S23 Phone?' per request, from the request's `label` (the name the new device's wizard sent; 'Unnamed device' when empty)"
    tag: "approve_device_request_*"
  - ceg: x_private:match_code
    use: display-only
    type: string
    example: "482 913"
    renders: "'Matching code. It must be the same on the new device:' and the six digits large. Derived by this node (N1)"
    tag: "text_approve_device_code_*"
  - ceg: x_private:add_device_request_expiry
    use: display-only
    type: int
    example: 780
    renders: "'Request expires in 13:00' — 900 − `requested_secs_ago`, aged locally between polls; a request at 0 leaves the list"
    tag: "text_approve_device_expires_*"
  - ceg: x_private:add_device_approve
    use: emit
    type: bool
    example: true
    renders: "the owner's consent; the node, not this client, signs the `ownership:` binding (CC 3.4.5). 'Approve' (`btn_approve_device_<id>`) raises a ConfirmSheet with three facts: Device (the label), Matching code (mono), Approving ('Makes it another device of your CIRIS ID, with its own key, and lets it open your private files.'). Confirm → `POST …/{id}/approve`; then 'Approved. S23 Phone can finish setting up now.'"
    tag: text_approve_device_outcome
  - ceg: x_private:add_device_deny
    use: emit
    type: bool
    example: true
    renders: "'Deny' (`btn_deny_device_<id>`) raises the same sheet, destructive, with Denying ('Refuses the request. The new device is told, and nothing is shared with it.'). Confirm → `POST …/{id}/deny`"
    tag: "btn_deny_device_*"
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: "enum[add_device.request_unknown,add_device.approve_failed]"
    example: "add_device.request_unknown"
    renders: "the refusal by id, the node's English beneath: `request_unknown` (404, the request expired or was decided elsewhere), `approve_failed` (500, signing or recording failed)"
    tag: approve_device_refusal
```

```yaml csd:states
populated: {tag: card_approve_device, renders: "the window line, and one row per pending request with its label, match code, expiry, Approve and Deny"}
empty:     {tag: approve_device_empty, renders: "'No device is asking to join.' under the window line — with the window closed too, since Accept new requests is the way on"}
loading:   {tag: card_approve_device, renders: "the card frame and the window control before the first list answers; no rows and no empty line until the node has said"}
error:     {tag: approve_device_refusal, renders: "the refusal by id inline on the card, the list kept"}
```

A node older than 0.5.231 answers the list with a bare 404: the card says
"This node is too old to add devices this way" (`approve_device_too_old`) and
offers nothing.

## 3. Contracts (who)

Verified against CIRISServer **v0.5.231** `src/add_device.rs`. Every route is
owner-session gated (`require_owner_session`), on the node this client is
signed in to (`nodeBaseUrl`), with the bearer.

| value | endpoint | owner | state |
|---|---|---|---|
| open the window | `POST /v1/self/device-requests/open {seconds?}` → `{open: true, seconds}` | CIRISServer `open_window`, `:940-969`; default/max `:53-54`; route `:1265-1268` | **live**, wired: `ApproveDeviceViewModel.open` → `CIRISApiClient.openDeviceRequests`. The body is `{}` (the node's default); an empty body is also accepted (`:948-952`) |
| the pending requests and the window | `GET /v1/self/device-requests` → `{requests: [{request_id, label, device_class, match_code, node_key_id, requested_secs_ago}], window_secs_left}` | CIRISServer `list_requests`, `:972-1000`; `sweep` `:184-201`; route `:1261-1264` | **live**, polled by the card. `window_secs_left` is null once closed (`sweep` clears it, `:197-199`); held requests survive until REQUEST_TTL (`:49`, `:187-188`) |
| approve | `POST /v1/self/device-requests/{id}/approve` → `{approved, match_code, node_key_id, occurrence_key_id, self_dek_granted}` | CIRISServer `approve`, `:1033-1094`; `sign_and_record` `:1098-1241`; route `:1269-1272` | **live**, behind the ConfirmSheet. 404 `add_device.request_unknown`, 500 `add_device.approve_failed` |
| deny | `POST /v1/self/device-requests/{id}/deny` → `{denied: true}` | CIRISServer `deny`, `:1003-1030`; route `:1273-1276` | **live**, behind the ConfirmSheet. 404 `add_device.request_unknown`. A decided request frees its slot at once (N3) |
| the new device in the roster | `GET /v1/self/occurrences` (CSD-037) | CIRISServer | live; approval records the occurrence here (`put_identity_occurrence`, `:1194-1197`), so the roster shows it on its next read |

## 4. Flow (how)

Two nodes, one person. On device A (signed in), open My things › Devices &
keys › My Identity.

```yaml
expect:
  visible: [card_approve_device, btn_approve_device_open]
```

Accept new requests. On device B, run CSD-093 up to the match code. A's card
lists B's request with the same code; approve it through the confirm:

```yaml
expect:
  visible: [text_approve_device_outcome]
  one_of: {text_approve_device_outcome: [approved]}
```

## 5. QA plan

**Unit and wire (green on this branch).** `ApproveDeviceViewModelTest`
(commonTest, 9 cases): opening the window and re-reading the list; the list
with its match codes; after the window closes, a held request still listed
and approvable (red on a planted "hide requests when the window is closed");
each request's own 15-minute countdown, aged locally and never negative;
approve and deny each through the confirm, with nothing sent before it;
dismissing sends nothing; a refusal by id; a bare 404 as the version fact.
`AddDeviceWireTest.theApprovingDevicesCallsCarryItsOwnerSession`: every owner
route carries the bearer, a closed window still lists the request, approve
sends `{}`, deny's refusal arrives by id.

**A real run** is CSD-093 §5's: two nodes, A claimed and signed in on this
client, B first-run. Here the roles invert (this client is A), so the fixture
must drive B's `POST /v1/setup/add-device` by API with A's long-form fedID and
then wait for A's approval. `two_node.py` stands up a CLAIMED peer; B needs an
UNCLAIMED one, with a mesh transport, whose loopback the fixture can reach.

## 6. Delta — card vs API vs CC

* **Where the server source differs from the FSD.** The list row carries
  `node_key_id` and `requested_secs_ago`, not `requested_at` (FSD §4); the
  window is `window_secs_left` (null when closed). `device_class` is always
  `laptop` from today's new devices (`:491`), so the card does not render it.
* **Stated limits.** A request the new device sent while this node's window
  was closed is refused, not queued (N2), so "Accept new requests" comes first.
  The new device must receive the approval within 15 minutes of it being
  given (DECIDED_TTL, `:51`); after that it reads `failed`/`expired` and the
  person starts again (open the window, send a new request).
* **CC.** CC 3.4.5 and 3.3.6 hold as §1 says. The self-DEK re-wrap is the
  owner's act and runs at approval; if it fails it is retried by `self_rewrap`
  and the approval still lands (`:1199-1210`).
