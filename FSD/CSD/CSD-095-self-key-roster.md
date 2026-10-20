# CSD-095 — Changes to your devices (the self-key roster's held changes)

**CSD**: CSD-095 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer#794 (CC 3.3.6.3), pushed as CIRISServer#802 (`feat/multi-device-csd4` @ `e1201b69`; same code as the `9ffcb65b` the client was built against, the tree differs in one CI comment). The rule, the rows and the executor are persist's (`self_roster`, CIRISPersist v59.0.0)
**Pairs with**: CSD-094 (Approve a new device: from two devices its approval IS an add proposal) · CSD-037 (My Identity: the revoke on the roster IS an evict proposal) · CSD-093 (the new device, which waits while the add is held)
**Flow**: unwritten. The quorum path (three keys) can be driven; the lone path cannot until persist's `W_self` seam ships (CIRISPersist#1111, §5). The server QA runs it on T10, three tests (§5)
**Card**: `ui/screens/SelfRosterCard.kt` (`SelfRosterCard`, `RosterTags`), placed on `ui/screens/IdentityManagementScreen.kt` directly below the Approve card; `viewmodels/SelfRosterViewModel.kt`; wire `api/SelfRosterApi.kt` + `CIRISApiClient.{getRosterProposals,answerRosterProposal}`; shapes `models/federation/SelfRoster.kt`; tests `SelfRosterViewModelTest` (commonTest), `SelfRosterWireTest`, `RosterHeldWireTest.aHeldApprovalAnswers202AndIsHeldNotApproved` (desktopTest)

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

`sketched`, not `building`: every §3 row is wired and wire-tested against an
in-test node shaped from the handlers, but the server is unreleased
(CIRISServer#802, open) and no run has driven it against a real node yet.

## 1. Mission (why)

**When a person has two or more devices, adding a device or removing one is a
change their other devices answer. On any device that did not propose the
change, the person sees what is proposed, who proposed it, how many signatures
it has of how many, how it can still take effect, who objected, and can agree
or object. Each answer is this device's own signed row.**

Serves **Integrity** through CC 3.3.6.3: from a roster of two or more keys, a
change to the self's own keys is a PROPOSAL (persist holds it in
`federation_self_roster_not_landed`) decided by the shared two-path function.
A strict majority of the live roster lands it at once; otherwise it lands by
itself after `W_self` (one day) unless the other keys object
(`src/self_devices.rs:1405-1414`). A stolen device alone cannot add a key to
the person or remove their other devices quietly. The person's other devices
see it here and can stop it.

The answer is signed on the node by this device's pen. A joined device's
roster key (`<fedID>-<fp>`) signs as the fedID (CIRISServer#793,
`src/self_devices.rs:1469-1477`), as a `scores` row to the fedID at
`federation` scope, ±1 (`:1514-1521`). It is federation-scoped because every
node holding the self decides landing from the answers it holds. If the
answer stayed on the person's own devices, the canonical node could land by
the lone path a change the devices had refused (`:1471-1476`).

**#793 in this client.** Nothing in the client treats only the first device as
the owner. Searched for owner, primary and founding gating: `IdentityManagementViewModel.load`
takes the identity from `owned-nodes.owner` (the fedID, the same on every
device), and no action is gated on the node key equalling the owner. A joined
device gets this card and its answers like any other.

## 2. Surface (what)

```yaml csd:surface
surface: identity-management
screen: IdentityManagement
```

**Where it lives, decided by routes.** No card called `/v1/self/roster/*`
before this one, so it is not a duplicate. Its proposals are made by two
existing controls, and it sits beside them on My Identity
(`btn_my_things -> nav_instrument_devices_keys -> nav_epistemic_identity_management`):
CSD-094's Approve, and CSD-037's revoke on the roster. Neither control gets a
new route. When the roster holds the change, the approval or the revoke IS the
proposal (`:1413-1414`).

**The list.** One row per change the node holds open (`self_roster_row_<id>`):

* What changes: "Add Tablet" / "Add a new device" / "Remove Old laptop". A
  device is named by its roster label, then by the name this device gave it
  when approving, then by the short key.
* "Proposed by …", named the same way.
* "Signatures: N of M". N is the proposer plus the live approvers. M is N plus
  `needs` while pending, otherwise a strict majority of the roster.
* How it can still take effect. Pending: "Needs n more agreement from your
  other devices." plus either "If none of your devices objects, it takes effect
  by itself at <local time>" (`earliest_lone_at`) or "It can no longer take
  effect by itself…" (`earliest_lone_at: null`). Landing: by quorum, or by the
  lone path. Refused or cancelled: the reason in plain words (§2 fields).
* "Objected: …" when any key objected.
* **Agree** / **Object**, each behind a ConfirmSheet whose three facts are
  Change, Proposed by and What your answer does. On a change THIS device
  proposed, there are no buttons. The row says "This device proposed this
  change, so another of your devices answers it." (`text_self_roster_own_<id>`).

**Which changes are this device's.** The node knows: it compares the answering
pen to the proposer (`:1506-1513`). The client learns it three ways: a held
approval names its `proposal_id` (202); a held revoke names its target, which
makes the one pending evict of that target this device's; and a 409
`self.roster_own_proposal` that the node sent back. The node still refuses
anything the client missed, and the refusal renders by id.

**History.** A change that leaves the open set while the card watches is kept
under "No longer waiting", with how it ended. The roster that follows decides
this: an added key on it, or an evicted key off it, is "done". Otherwise the
change's last decision gives the cancel or refusal reason. With no such
decision it reads "no longer waiting, and your devices do not show it, so it
was cancelled or withdrawn". A CLOSED entry is looked at again on every read and becomes "done" once the roster shows it: the roster can lag the open set by a moment (T10 on #802). The node's settle row is not on this route, so the
client never claims more than this.

**Polling.** The card reads every 5 s while a change is pending and every 30 s
otherwise. It stops for good on a too-old node. It never reads while an answer
is in flight: `refresh()` stands down, a read already out is joined rather
than raced, and a read that started before an answer is discarded rather than
painted over it (the epoch). The answer re-reads once, after itself.

**The badge.** Devices & keys (the My things row, the rail row) and the My
Identity row under it carry a count of the pending changes this device can
still answer: not proposed here, not answered here (`badge_nav_instrument_devices_keys`,
`badge_nav_epistemic_identity_management`). It is as fresh as the last read,
and opening My things reads again.

**The held approval (CSD-094).** A 202 `{held: true}` answers "Held: adding
Tablet is a proposal your other devices must agree to…", then "Held: another of
your devices must agree, or it takes effect after 24 hours unless one of them
objects." (`text_approve_device_held_window`). A "See changes to your devices"
button (`btn_approve_device_held_roster`) scrolls to this card. The new
device's waiting screen (CSD-093) says that a held addition can take up to a
day, that the new device stops asking after 15 minutes, and that if it is
added later the person sends a new request from there (`text_add_device_held_note`).

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:roster_change_kind
    use: display-only
    type: "enum[add,evict]"
    example: add
    renders: "'Add Tablet' / 'Add a new device' / 'Remove Old laptop' — the kind and the target, the target by roster label, else the name this device approved it under, else the short key"
    tag: "text_self_roster_kind_*"
  - ceg: x_private:roster_change_signatures
    use: display-only
    type: string
    example: "1/2"
    renders: "'Signatures: 1 of 2' — the proposer plus live approvers, of that plus `needs` (pending) or a strict majority of the roster"
    tag: "text_self_roster_signatures_*"
  - ceg: x_private:roster_change_path
    use: display-only
    type: "enum[pending:lone_open,pending:lone_closed,lands:quorum,lands:lone,refused:*,cancelled:*]"
    example: "pending:lone_open"
    renders: "'Needs 1 more agreement from your other devices. If none of your devices objects, it takes effect by itself at 2026-10-21 10:00.' / '…It can no longer take effect by itself…' / 'Your devices agreed. It takes effect now.' / 'None of your devices objected in time…' / 'Refused: <reason>' / 'Cancelled: <reason>'"
    tag: "text_self_roster_path_*"
  - ceg: x_private:roster_refusal_reason
    use: display-only
    type: "enum[proposer_not_on_roster,target_already_on_roster,target_not_on_roster,proposer_is_target,proposer_has_open_proposal,target_has_open_proposal]"
    example: target_has_open_proposal
    renders: "each in plain words (mobile.roster_refused_<token>): no longer one of your devices / already one of yours / no longer one of yours / cannot propose removing itself / an earlier change from it is decided first / an earlier change for the same device is waiting"
    tag: "text_self_roster_path_*"
  - ceg: x_private:roster_cancel_reason
    use: display-only
    type: "enum[cancel_reached,below_floor,no_reverse_path,target_spoke]"
    example: cancel_reached
    renders: "each in plain words (mobile.roster_cancelled_<token>): enough devices objected / too few devices remained / time ran out without enough agreement / the device it would remove was still in use"
    tag: "text_self_roster_path_*"
  - ceg: x_private:roster_change_objectors
    use: display-only
    type: int
    example: 1
    renders: "'Objected: Old laptop' — the objecting keys, named as above"
    tag: "text_self_roster_objectors_*"
  - ceg: x_private:roster_answer_assent
    use: emit
    type: bool
    example: true
    renders: "'Agree' (`btn_self_roster_assent_<id>`) → ConfirmSheet (`confirm_roster_answer`) → `POST …/{id}/assent`. 'Your agreement is signed.' plus 'Signed answer: <attestation id>' — the receipt of the `scores` row the node wrote (`text_self_roster_receipt`)"
    tag: text_self_roster_answer_outcome
    assert:
      one_of: {text_self_roster_answer_outcome: ["assent", "assent:settled", "object", "object:settled"]}
  - ceg: x_private:roster_answer_object
    use: emit
    type: bool
    example: true
    renders: "'Object' (`btn_self_roster_object_<id>`), destructive, → the same sheet → `POST …/{id}/object`. 'Your objection is signed.'"
    tag: "btn_self_roster_object_*"
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: "enum[self.roster_proposal_unknown,self.roster_own_proposal,self.owner_session_required,self.delegate_may_not_author,self.store_unavailable]"
    example: "self.roster_own_proposal"
    renders: "the refusal by id, inline on the card (`self_roster_answer_refusal`); an own-proposal refusal also removes the row's buttons"
    tag: self_roster_answer_refusal
```

```yaml csd:states
populated: {tag: card_self_roster, renders: "one row per open change with kind, proposer, signatures, path, objectors, and Agree/Object or the own-proposal line"}
empty:     {tag: self_roster_empty, renders: "'No change to your devices is waiting.'"}
loading:   {tag: card_self_roster, renders: "the card's title and description before the first read; no rows and no empty line until the node has said"}
error:     {tag: self_roster_refusal, renders: "the list's refusal by id (e.g. self.owner_session_required), inline"}
```

A node without CIRISServer#794 answers the list with a bare 404: the card says
"This node is too old to hold changes to your devices…"
(`self_roster_too_old`), offers nothing, and stops polling.

## 3. Contracts (who)

Verified against CIRISServer#802 @ `e1201b69` (`src/self_devices.rs`,
`src/add_device.rs`; line numbers equal to `9ffcb65b`'s) and CIRISPersist
v59.0.0 (`src/federation/self_roster.rs`, `src/federation/two_path.rs`). Every
route is owner-session gated (`owner_caller`, `false` = no delegate), with the
bearer, on the ACTIVE node read at call time (`ClientSelfRoster`, as
`ClientDeviceRequests`).

| value | endpoint | owner | state |
|---|---|---|---|
| the held changes | `GET /v1/self/roster/proposals` → `{identity_key_id, roster: [key_id], w_self_secs, proposals: [{proposal: {id, kind, proposer, target, asserted_at, received_at}, decision, decided_at, approvers, objectors, outcome, lands}]}` | CIRISServer `roster_proposals`, `src/self_devices.rs:1428-1451`; `standing_view` `:1417-1424`; route `:1572-1575`. persist `Standing` `self_roster.rs:880-892`; `TwoPathDecision` (`#[serde(tag = "outcome")]`: `lands{path}` / `pending{earliest_lone_at, needs}` / `refused{reason}` / `cancelled{reason}`) `two_path.rs:645-674`; tokens `:470-500`, `:600-643` | **unreleased (#802)**, wired; polled by the card. Bare 404 on an older node → too old |
| agree | `POST /v1/self/roster/proposals/{proposal_id}/assent` body `{}` → `{proposal_id, answer: "assent", answer_attestation_id, settled, proposal}` | CIRISServer `assent_proposal` `:1453-1459` → `answer_proposal` `:1478-1541`; route `:1576-1579` | **unreleased (#802)**, wired behind the ConfirmSheet. 404 `self.roster_proposal_unknown` ("There is no open change to your devices with that id.", `:1494-1501`); 409 `self.roster_own_proposal` ("This device proposed that change; another of your devices answers it.", `:1506-1513`) |
| object | `POST /v1/self/roster/proposals/{proposal_id}/object` body `{}` → the same, `answer: "object"` | CIRISServer `object_proposal` `:1461-1467`; route `:1580-1583` | **unreleased (#802)**, wired; the same two refusals |
| propose an add | `POST /v1/self/device-requests/{id}/approve` → **202** `{approved: false, held: true, match_code, node_key_id, occurrence_key_id, proposal_id, detail}` | CIRISServer `approve`, `src/add_device.rs:1331-1408` (held arm `:1354-1400`); resumes the approval when the roster lands the key, `resume_when_landed` `:1460-1494` | **unreleased (#802)**; CSD-094's card. The 409 `add_device.roster_change_held` of 0.5.232 without #794 is still read as held, with no proposal id |
| propose an evict | `POST /v1/self/occurrence/revoke` → `{revoked: false, held: true, occurrences_held: [{occurrence_key_id, door}]}` | CIRISServer `revoke_occurrence` (route `:1564-1567`) | live since 0.5.232 (CSD-037, `RevokeState.Held`); its target marks the evict proposal this device's |

No route applies the window: the node's executor lands or cancels on a 30 s
tick. No route reads settled changes: the history is the card's own, kept
while it watched (§2).

## 4. Flow (how)

Three devices of one person (the quorum path): A, B, C, with A and B on the
roster. On A, approve C (CSD-094); A's card shows the held line. On B, open My
things › Devices & keys (the badge reads 1) › My Identity:

```yaml
expect:
  visible: [card_self_roster]
```

B's card lists A's add with its signatures (`1/2`), and Agree. Agree through
the confirm:

```yaml
expect:
  visible: [text_self_roster_answer_outcome]
  one_of: {text_self_roster_answer_outcome: ["assent:settled", "assent"]}
```

The change lands at once (2 of 2), the next read files it under "No longer
waiting" as done, and C's approval is delivered by `resume_when_landed`.

## 5. QA plan

**Unit and wire (green on this branch).** `SelfRosterViewModelTest`
(commonTest, 10 cases):
* polling stands down while an answer is in flight, and the answer re-reads
  once, after itself. Red on a planted "no stand-down": `[list, assent:p1, list, list]`;
* a second tap signs once;
* a change this device proposed (by a 202 proposal id, or by a held revoke's
  target) offers no answer and is not counted on the badge;
* a 409 own-proposal is learned;
* settled history: LANDED by the roster or by a landing decision, CLOSED (upgraded to LANDED when a lagging roster catches up; red before the fix: `expected:<LANDED> but was:<CLOSED>`), or REFUSED with its reason,
  never repeated;
* a bare 404 is the version fact;
* the signature arithmetic.

`SelfRosterWireTest` (desktopTest, 5 cases) runs an in-test node with the
handlers' shapes:
* the list with every decision shape and the bearer;
* assent and object with `{}` bodies on their own routes;
* both refusals, in the server's English;
* a bare 404.

`RosterHeldWireTest.aHeldApprovalAnswers202AndIsHeldNotApproved` was red on
main: `a 202 held approval is held, never Approved: Approved(label=Tablet, matchCode=111222)`.

**A real run (T10, the server session's harness).** Three nodes, the quorum
path. The drive tags, in order, are:
1. `btn_my_things` → `nav_instrument_devices_keys` → `nav_epistemic_identity_management`;
2. `card_self_roster`;
3. `self_roster_row_<id>`;
4. `btn_self_roster_assent_<id>` → `btn_confirm_roster_answer_confirm`, read `text_self_roster_answer_outcome`;
5. `btn_self_roster_object_<id>` → `btn_confirm_roster_answer_confirm`;
6. on the approving device, `btn_approve_device_<id>` → `btn_confirm_device_request_confirm`, read `text_approve_device_outcome = held` and `text_approve_device_held_window`.

**The server's QA runs this card on topology T10** (CIRISServer#802 @
`003871fd`, catalogue version 3,
`harness/native/topologies/catalog/requirements.yaml:48-74`, through
`harness.native run --csd CSD-095`). Catalogue v3 adds the atom
`key.membership.proposed_add` (CC rc9 `01b47e75`). It has three tests:

```yaml
CSD-095:
  topology: T10
  tests:
    add_held:
      at: [key.membership.proposed_add, device.join_requested]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200,
              expect: {proposals.0.proposal.kind: add, proposals.0.decision.outcome: pending}}
    evict_held:
      at: [key.membership.proposed_evict, key.holder.thief, self.roster_size.three_more]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200,
              expect: {proposals.0.proposal.kind: evict, proposals.0.decision.outcome: pending}}
    evicted_by_quorum:
      at: [key.membership.evicted, key.holder.thief]
      probe: {node: A2, path: /v1/self/roster/proposals, status: 200, expect: {proposals.0: null}}
```

This copies the server's entry. It is not a typed `csd:` block, because this
repo's `check_csd_v3.py` reads CSD/4 topology only as a full `csd:topology`
layer graph. It does not yet read a named topology (`topology: T10`) or a
`tests:` map, so the entry stays here as prose until it does.

The same T10 mesh also passed this client's code live:
* at `a09c3c83`/`caf94d07`, a held evict;
* at `caf94d07`, a held ADD. This is what `add_held` asks: proposal
  `e655eaaa…` was pending on A2; after A2 assented, the third device went from
  `requested` to `approved` in about 6 s, and the history read LANDED on both
  devices.

**Not in T10 yet.** The lone path is T11 (two devices lost; the person
recovers alone after `W_self`). One card names one topology (CSD/4 §1), and
T10 does not reach it. It also waits on persist's test-anchor `W_self` seam
(CIRISPersist#1111).

**The lone path is not drivable yet.** There is no `W_self` test override:
persist's `CIRIS_TEST_W_SELF_SECS` is in its next cut, so a one-day window
cannot be run in CI.

## 6. Delta — card vs API vs CC

* **Stated limits.**
  * The list carries open changes only. A change that settled while no card
    was watching appears nowhere here; the roster (CSD-037) shows the result.
  * The new device's poll stops at 15 minutes (REQUEST_TTL). An add that
    lands later by the lone path needs the new device to send a new request,
    and its waiting screen says so.
  * "Proposed here" is the client's best knowledge (§2). The node is the
    authority.
* **Strings.** Every refusal and cancel token has a bundle string. The two
  server ids carry the server's English exactly. All other locales are
  machine-translated and MQM-reviewed by a judge of a different model family
  (`draft` / `machine_reviewed`). There is no native review, no check of
  dialect, and no cultural adaptation.
* **CC.** CC 3.3.6.3 holds as §1 says. The card adds no path around the
  proposal: it only answers.
