# CSD-005 — People (the Contacts surface, rebuilt on the primitives)

**CSD**: CSD-005 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 0
**Flow**: `testing/flows/csd-005-people.yaml` (floor `>=0.5.225`)
**Reads with**: CSD-006 (the receipt it opens), CSD-091 (the chat a row opens), CSD-092 (the code card in its header), CSD-104 (the key check a row will offer once CIRISServer#683 lands)

```yaml csd:stage
stage: testable
owner: CIRISClient
```

## 1. Mission (why)

**A person can see who they have consented to exchange messages with, add
someone by their code, and open the receipt on any of them — and the screen
says honestly when the node cannot answer.** The screen is wave 0's proof that
the nine primitives express a real surface: a list, an empty state, a loading
state, and the one "node fact" error the no-gating rule honours (a node too old
to serve `GET /v1/contacts`). Serves **Contextual Integrity**: a contact is a
`consent:replication:v1` grant, and its receipt says so.

**Adding someone takes their contact code, pasted or scanned (0.5.218).** The
input a person shares is their contact code (CSD-092, CC 2.6.8): a v3 code that
names their devices resolves with no directory, so a stranger across a table is
addable. A fed-ID still works when this node's directory already knows them. A
node code does not, and cannot: a node cannot consent, so the node refuses it as
`contacts.unresolvable`, and the card turns that into "That isn't a person's
code" and points at the contact code. Paste is always present. The QR scan is an
extra way to fill the same field, never the only one.

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

**Contacts is placed on People in every circle, and is the whole tab in two of
them.** `CirclesNav.placements` puts `NavSurface.Contacts` on `Tab.PEOPLE` for
`ALL` circles. In Just me and Everyone it is the only card there, so `nav_map`
derives `circle_<circle> -> tab_people` and the shell shows it directly. In
Family, Neighbours and Communities and Businesses it is one card among siblings
— the household roster (`HouseholdMembers`, CSD-101), the community and
affiliation rosters (`CommunityRoster` / `AffiliationsRoster`, CSD-103) and, in
Communities and Businesses, `Users` — so the tab is a list and the chain is
`circle_<circle> -> tab_people -> nav_epistemic_contacts`. "Contacts IS the
People tab in every circle" was true at wave 0 and stopped being true when the
rosters landed (PR #121, #122); the atlas that still shows the two-hop chain was
captured before them (`docs/atlas.json`, 2026-09-23, AGENT mode). The screen
class, the nav id and every `contacts_*` tag are unchanged from the surface
this replaces; the visible title is "People".

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: string
    example: "consent:replication:v1"
    renders: "What it is — consent:replication:v1 (fixed by the rule for this kind of record, CC 3.3.7)"
    tag: receipt_dimension
  - ceg: x_private:subject_key_ids
    use: display-only
    type: "list[string]"
    example: ["wa-peer-4a19c2"]
    renders: "Who it is about — wa-peer-4…19c2"
    tag: receipt_subject
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "Who sent it — the granting key off the wire (GET /v1/contacts carries grant_receipt since 0.5.217), glossed 'The person who consented' because since 0.5.211 that is the owner's fed-ID, not this node. On a node that sends no grant: 'This node did not send this.' — never 'this node' by rule"
    tag: receipt_attester
  - ceg: x_private:cohort_scope
    use: display-only
    type: string
    example: "federation"
    renders: "Who can see it — the grant's cohort_scope off the wire, folded to a circle. It is the consent AUDIENCE the person chose (peer.rs::add_contact, default federation), NOT a constant CC 3.3.7 fixes, so a grant-less row says 'This node did not send this.' rather than 'Everyone' by rule. What you send each other is not public either way."
    tag: receipt_scope
  - ceg: x_private:trust_state
    use: display-only
    type: "enum[trusted,untrusted,blocked,unknown]"
    example: "trusted"
    renders: "a chip on the row: Trusted / Untrusted / Blocked / Unknown — tagged per row (PeopleTags.rowTrust) so the state is readable without opening the receipt"
    tag: "contacts_row_trust_*"
  - ceg: x_private:contact_input
    use: emit
    type: string
    example: "CIRIS-V3-AB12-CD34-…"
    renders: "the one Add contact field, labelled for a contact code; a fed-ID is also accepted. Scanning fills this same field and does NOT submit: adding someone writes a consent grant (CC 3.3.7), so the person sees what they scanned before they grant it"
    tag: input_contacts_add_key
  - ceg: x_private:contact_add_outcome
    use: display-only
    type: "enum[added,already,refused]"
    example: "already"
    renders: "'Added {who}.' on a fresh grant; 'Already in your contacts.' when the node returns freshly_emitted: false (the same person twice is a no-op, not an error); the refusal block otherwise"
    tag: contacts_add_already
  - ceg: x_private:pair_room_invitation
    use: display-only
    type: string
    example: "chat:pair:v1:3f…"
    renders: "ciris-server 0.5.218 (CIRISServer#706): a two-person room opens by invitation, and the invitation waits in `GET /v1/self/invites` (`is_pair_room: true`). It is shown on the row of the contact it comes from — matched by the invitation's `group_id`, which IS that contact's `chat_community_id`, never by `proposer_key_id` (the other person's NODE) — as the flag 'Wants to talk with you', with Accept and Decline in place of the Chat chip. Tapping the row or the receipt's Chat goes to the accept confirm too, because opening the chat IS accepting it. An invitation from someone who is not a contact is not shown here (a room with a non-contact cannot carry a message, `chat.not_a_contact`); a household's or community's invitation is CSD-106's inbox"
    tag: "contacts_chat_invite_*"
  - ceg: x_private:pair_room_answer
    use: emit
    type: "enum[accept,decline]"
    example: "accept"
    renders: "each behind the shared ConfirmSheet with three facts. Accept: From (their key) · Who can read it ('You and {who}, and nobody else') · Who signs ('You. Your answer is signed for you and sent back to them.'), then the chat opens — `POST /v1/chat` accepts the held invitation from that contact (CSD-091) and the room reads 'Joining the conversation with {who}' until both devices have caught up. Decline: From · What changes ('This invitation closes. {who} can invite you again.') · Who signs ('You. The decline is signed with your own key.'), then `POST /v1/self/invites/{proposal_id}/decline`; a refusal is shown by its id (`contacts_chat_decline_refusal`) and the invitation stays"
    tag: "btn_contacts_chat_accept_*"
```

**The receipt renders all five facts every time, and reads all five off the
wire.** CIRISServer#616 closed 2026-09-25 and shipped in 0.5.217: `GET
/v1/contacts` attaches `grant_receipt` per row (`src/contacts_chat.rs:1754`
on `integ/0.5.218`, `:1704-1712` on `main`; built at `src/peer.rs:1524-1559`)
carrying `attestation_id`, `attesting_key_id`, `dimension`, `subject_key_ids`,
`cohort_scope`, `for_key_id`, `consent_prefixes`, `asserted_at`, `valid_until`
and `row_expires_at`; the row's key is `grant`, `null` when the node holds no
readable receipt. `models/federation/Contact.kt` decodes it as `ContactGrant`
and `ui/screens/PeopleSupport.kt::contactReceipt` reads every fact from it:
`subject_key_ids` → who it is about, `attesting_key_id` → who sent it,
`cohort_scope` → who can see it, `dimension` → what it is, `consent_prefixes`
→ the rule it follows, `for_key_id` → which of my agents it is for
(`PeopleSupportTest.aContactReceiptWithTheGrantEnvelopeFillsAllFiveFactsFromTheWire`:
`wireFacts == 5`).

**A grant-less row says "not sent", never a guessed value.** Against a node
older than 0.5.217, or a `grant: null` row, the subject is the row's own key
(wire); the dimension is `ByRule` — a contact IS a `consent:replication:v1`
grant (CC 3.3.7) and the route serves nothing else; and the attester, the scope
and the rule are `NotSent`. The scope USED to be `ByRule("federation")` and
that was a guess: the grant's `cohort_scope` is the consent audience the person
chose (`peer.rs::add_contact`: `audience … unwrap_or(FEDERATION)`), so a
grant-less row cannot know it. Fixed here, red first
(`aContactReceiptWithoutAGrantSaysWhatItKnowsAndWhatItDoesNot`). A member the
grant carries but leaves empty is likewise `NotSent`.

```yaml csd:states
populated: {tag: contacts_list}
empty:     {tag: contacts_empty, renders: "No contacts yet (or: No contacts match “q”) — but over an EMPTY, UNSEARCHED list the add card (card_contacts_add) is shown INSTEAD, because that list has exactly one useful next move"}
loading:   {tag: contacts_loading, renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: contacts_error, renders: "the list-level error banner; and contacts_unsupported for the node-too-old state: This node doesn't have contacts yet — This node is running {version}. Contacts and chat need ciris-server 0.5.185 or newer …"}
```

`error` and `empty` cannot look alike: `StateBlock` gives error the `danger`
tone, the `error` glyph, a hairline box and an uppercase label, and empty the
`mute` tone with none of those (`PrimitiveRulesTest.errorNeverLooksLikeEmpty`).

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| contacts | `GET /v1/contacts` at the **node URL** | CIRISServer | live since 0.5.185. It read `$baseUrl` until this review, which the route gate charged to the agent front door and which, on a with-AI install, asked an agent that before 2.12.1 did not forward `/v1/contacts` (CIRISAgent#1213, closed by #1215; forwarded from agent 2.12.1). Now `listContacts(nodeUrl)`, on the same active-node provider as the add, the code and the removal (`ContactsViewModel.nodeUrl`); pinned by `ContactCodeViewModelTest.theContactListIsReadFromTheNodeNotTheAgentFrontDoor`. Calling the node URL directly works on every agent version, so it stays |
| the picker's identities (Delegations reaches this screen in picker mode) | `GET /v1/federation/peers` | CIRISServer | live. A delegation target need not be a contact, so the picker reads the wider peer store; cited here because this screen calls it, and the checker had it as this screen's one uncited route |
| add a contact | `POST /v1/contacts` | CIRISServer | live; returns `consent_prefixes` |
| add by contact code | `POST /v1/contacts` with the code in `key_id` | CIRISServer | **live**: `AddContactRequest.key_id` takes a fed-ID **or** a fedcode and also accepts the aliases `code` and `contact` (`src/contacts_chat.rs:1732-1742` @ `046e1b39`). The client sends `key_id` (`CIRISApiClient.kt:1490`), so no client change is needed to send a code |
| adding the same person twice | same route; `freshly_emitted: false` | CIRISServer | **live**: the standing grant already covers every prefix, so nothing is written (`contacts_chat.rs:1775-1778`). The card reads it as `proposed:contacts_add_already` |
| "That isn't a person's code" | refusal `contacts.unresolvable` (400) | CIRISServer (id) · CIRISClient (copy) | the id is **live** (`contacts_chat.rs:529`); the bundle's copy is still "That identifier does not resolve to a contact." (`en.json:644`). **Ask (CIRISClient card):** "That isn't a person's code. Ask them for their contact code: People › Share my contact code." |
| a code that does not decode | refusal `contacts.malformed_code` (400) | CIRISServer | live (`contacts_chat.rs:520`); bundle key present |
| a fed-ID this directory does not know | refusal `contacts.unknown_fed_id` (404) | CIRISServer | live; the bundle's copy says "Admit the key first (peering)". With codes available, the node's own detail is the better steer: "If they handed you a CODE, paste that instead" (`contacts_chat.rs:488-491`). **Ask (CIRISClient card):** point at the contact code, as for `unresolvable` |
| a pasted code on a separate node | same route, end to end | CIRISServer | **built, unreleased** (0.5.218, CIRISServer#673 still open): readable on `origin/integ/0.5.218` — `AddContactRequest.key_id` takes a fedcode and resolves it through `ciris_edge::contact::resolve` (`src/contacts_chat.rs`). Ships with 0.5.218 |
| remove a contact | `DELETE /v1/contacts/{key_id}`, signed by the person, never the node; the response lists `remaining_grants` | CIRISServer | **built, unreleased** (0.5.218, CIRISServer#657 still open; readable on `origin/integ/0.5.218`, `src/contacts_chat.rs` router `"/v1/contacts/{key_id}"` → `remove_contact`). The client side is live (PR #115): `btn_receipt_act_remove_{keyId}` on the receipt sheet behind a ConfirmSheet (`contacts_remove_confirm` / `contacts_remove_cancel`), at the node URL (`ConsentWithdrawApi`). **Grants the node wrote before the person re-signed them cannot be withdrawn and stay live.** Each one in `remaining_grants` renders as a row in `contacts_remove_remaining` reading "Still active: written by this node before you signed your contacts, so it can't be withdrawn here", and the contact is NOT reported as removed while any remain; `contacts_remove_done` says "Removed" only when `remaining_grants` is empty. A node without the route (bare 404) shows `contacts_remove_unsupported` |
| scan a code | `QrScanAction` primitive, `btn_scan_contact_code` | CIRISClient | **live** (PR #99): `ui/primitives/QrScanAction.kt`; it fills `input_contacts_add_key` and never submits. Paste does not depend on it |
| the grant's envelope on the list route | `GET /v1/contacts` → `grant` (built by `peer.rs::grant_receipt`) | CIRISServer | **live since 0.5.217** (#616 closed); `src/contacts_chat.rs:1704-1712` on `main`, `:1754` on `integ/0.5.218` |
| reading that envelope into the sheet | — | **CIRISClient** | **live** — `Contact.kt` decodes `grant` and `PeopleSupport.contactReceipt` renders all five facts from it (§2); a grant-less row says "This node did not send this" for the attester, the scope and the rule, never a guess |
| an invitation to talk (0.5.218) | `GET /v1/self/invites` at the node URL — the pair-room rows (`is_pair_room`, `group_id` with the `chat:pair:v1:` prefix) | CIRISServer (`src/membership_invites.rs::inbox` @ `53d1ffb5`) | **0.5.218, called** — read after every contact list (`ContactsViewModel.refreshPairInvites`), keyed by contact. A node without the route (bare 404) means "no invitations" and the list still shows; any other failure is logged and the rows stay as they are. Read through CSD-106's ONE inbox model and client method (`InviteInbox` / `InboxInvite`, `listMyInvites`), narrowed by `InviteInbox.pairRooms` (`PairRoomInvitationTest`) |
| accept it | `POST /v1/chat {key_id}` (CSD-091) | CIRISServer (`src/contacts_chat.rs::start_chat`, step 1) | **0.5.218, called** — the person's own `POST /v1/chat` for that contact IS their consent to that pair room (the maintainer's ruling, `pair_intents.rs`); it accepts only an invitation whose proposer resolves to that contact. Reached only through the accept ConfirmSheet |
| decline it | `POST /v1/self/invites/{proposal_id}/decline` at the node URL | CIRISServer (`src/membership_invites.rs::answer`) | **0.5.218, called** — signed with the person's own pen; final for that invitation; the server forgets the person's standing request for that room. **Two doors on purpose, one client method (`declineInvite`):** pair-room invitations (one contact asking to talk) are declined here on that contact's row, beside Accept; group invitations (households, communities) are declined on the hubs' inbox (CSD-106). Recorded as a deliberate duplicate in `packaging/csd_routes_baseline.json`. Refusals by id (`membership.invite_expired`, `membership.already_answered`, …) |
| check a contact's key in person (CSD-104's ceremony from a People row) | `GET /v1/federation/peers/{key_id}/sas` over (my identity key, their identity key) | CIRISServer | **blocked: CIRISServer#683** (open). The node computes the code over its OWN node key and the other side's PERSON key, so two honest people never see the same code. No control is offered on the row until it lands; when it does, the row gets a control that sets `Screen.NetworkPeerDetail(contact.keyId)` — one line in `CIRISApp.kt`, no copy of that screen (CSD-104 §2) |

## 4. Flow (how)

Sign in on a node; land on `Contacts`.

```yaml
expect:
  state: populated
  count: {of: "contacts_row_*", min: 1}
  visible: [contacts_list, input_contacts_search]
```

Open a receipt without a long-press: click `btn_receipt_<keyId>`.

```yaml
expect:
  visible: [sheet_receipt, receipt_subject, receipt_attester, receipt_scope, receipt_dimension, receipt_rule, btn_receipt_close]
  matches: {receipt_dimension: "consent:replication:v1", receipt_rule: "chat:"}
```

On a node older than 0.5.217 (no `grant` on the row) the last line is instead
`text: {receipt_rule: "did not send"}`, and so are `receipt_attester` and
`receipt_scope`: the sheet says what the node did not send rather than filling
it in.

Add by contact code: open `btn_contacts_add_open`, paste a person's contact
code (CSD-092) into `input_contacts_add_key`, click `btn_contacts_add_submit`.

```yaml
expect:
  visible: [btn_contacts_add_open_chat]
  absent: [contacts_add_refusal]
```

Submit the same code again: nothing new is written and nothing is refused.

```yaml
expect:
  visible: [contacts_add_already]
  absent: [contacts_add_refusal]
```

Paste a node code (`CIRIS-V1-…`) instead:

```yaml
expect:
  visible: [contacts_add_refusal]
  text: {contacts_add_refusal: "isn't a person's code"}
```

Scan instead of paste: `btn_scan_contact_code` fills `input_contacts_add_key`
and does not submit; `btn_contacts_add_submit` is still the act.

Every row carries its trust state as a chip:

```yaml
expect:
  count: {of: "contacts_row_trust_*", min: 1}
```

Search for a string no contact matches → `contacts_empty`; clear it → the list
again. On a fresh node with no contacts → `card_contacts_add` and no
`contacts_empty`.

## 5. QA plan

**`testable`** since the five-platform run 36775704425 (2026-09-30, `flows/matrix-0.5.225` at efdac2a2, node v0.5.217): the flow (`testing/flows/csd-005-people.yaml`, floor `>=0.5.225`, `fixture: two_node`) passed on all five legs — Linux, macOS and Windows desktop, the Android emulator and the iOS simulator. What follows is how it got there. Linux desktop leg run locally the way `five-platform-live-qa.yml` runs it (2026-09-29, candidate 0.5.225, node v0.5.217, `--flows testing/flows`, the two-node fixture): **11/12 passed, 1 skipped** — the list, the seeded row with its trust chip and hamburger, the five-fact receipt and its close, the empty search and its clearing, the add card, the node-code refusal by name, and the code card from the header; `a_scan_is_offered_where_there_is_a_camera` skipped as designed (desktop has no `btn_scan_contact_code`). The matrix run of the same day (36588619656) failed this flow on every desktop leg for csd-092's open contact-code card, which now closes itself (`cleanup:`), and its fixture waited only for the peer's owner key, not the binding (`reachable_nodes`, CIRISServer#699) — both fixed. Not yet run on the other four legs. On the second matrix run (36600766576) it passed 11/12 on Linux and macOS (the scan step skipped, no camera) and could not start on Windows (the fixture's console crash, fixed in the gate); its last steps left the add card open with a refusal in it, which on macOS's shorter window pushed the list below the fold for csd_006 — the flow's `cleanup:` now clears the refusal and closes the card (`when:` guards the header toggle).

**Platforms.** All five. The Contacts entry screen is what CIRISAgent's
five-platform gate leans on; no tag it drives has changed.

**An invitation to talk is pinned below the flow** (0.5.218): showing one needs a second person who opened a chat with the leg's person first, which the two-node fixture does not stage yet, so the row flag, the matching by room id, the 0.5.217 404 and the decline are pinned in `PairRoomInvitationTest` and on the wire in `Server218ChatEvictWireTest`. Not run on any leg.

**Not tested here.** The long-press gesture (no `/long-press` endpoint); the
hamburger is the drivable equivalent. The camera half of the scan: it needs the
QrScanAction test double, and until that exists the paste path is the tested
path. The removal end to end and the pasted code from another node, until
0.5.218 is released (the flow's node floor). The `input_contact_code` tag is
deliberately NOT minted: the field already exists as `input_contacts_add_key`,
and renaming it would break the tag the five-platform gate drives.

**Stated limit.** The flow is written against real tags with a version floor;
it has not yet run on the matrix, so this card stays at `building`.
