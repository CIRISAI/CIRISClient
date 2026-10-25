# CSD-109 — Contact requests (someone added you; you say yes, or ignore it)

**CSD**: CSD-109 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the maintainer's ask of 2026-10-25 to lock membership invitations down end to end, contact requests included (CIRISServer `FSD/MEMBERSHIP_INVITES.md` §8; CIRISServer#819, #820)
**Extends**: CSD-005 (People — the request section sits above the contact list). **No new card** (§2).
**Reads with**: CSD-092 (the contact code that starts a request), CSD-091 (the pair chat a new contact can open), CSD-106 (the group-invitation inbox, which this does not merge into)
**Flow**: `testing/flows/drafts/csd-109-contact-requests.yaml` (to be written with the build; floor `unreleased`: no released client carries these tags, and no released node serves these routes — ciris-server 0.5.237 will)

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**When someone adds you by your contact code and you have not added them back,
you see it: "Someone wants to connect", who they are as far as this node can
say, and when they asked. You can accept, which makes you each other's
contact, or ignore it, which they are never told and which you can undo.
Nothing about you changes until you accept.**

A contact is a pair of `consent:replication:v1` grants, one each way (CSD-005).
Adding someone writes only your half. Until 0.5.237 the other person's node
held that half and the person had no way to see it: they learned they had been
added only if the adder told them, and adding back was the only answer. A
request is that one-sided grant made visible to the person it names. Serves
**Contextual Integrity**: accepting changes who can exchange messages with you,
so it must be the person's own act. Ignore exists because "not now, and don't
tell them" is a legitimate answer that a public "no" would deny: a decline that
anyone could see would tell everyone that X refused Y (CIRISServer `FSD`
§8.2). The decline the requester alone can see waits on CIRISPersist#1121 (§6).

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

**No new card: this extends People (CSD-005).** A request is a would-be
contact, so it is shown where contacts are, on the screen that already lists
the people you have added and the ones still pending your add (CSD-005's
pending rows). Requests are the mirror of those pending rows: they added you.

| role | where it is | the card it extends |
|---|---|---|
| **the requests section** — "Wants to connect" above the contact list: one row per pending request, Accept and Ignore | every circle's People › Contacts (CSD-005 §2) | CSD-005 (`Contacts`) |
| **the ignored section** — "Ignored", collapsed by default, below the list: one row per ignored request, Un-ignore | same screen | CSD-005 |

**Not in the group-invitation inbox (CSD-106).** `GET /v1/self/invites` also
carries pending requests as `contact_requests` (0.5.237), but the hubs show
households and communities, which the invitee is not in yet. A contact request
is about one person and belongs with the people. The pair-room split stays
(CSD-106 §2): a pair-room invitation is answered on that contact's row.

**A row says only what the node sends.** The requester is not a contact yet,
so there is no name you gave them. The row reads "Someone wants to connect" +
the requester's short key id (`requester_key_id`, first 8 characters, the
receipt's convention) + "Asked {relative time}" from `requested_at`. If the
entry's `grant` carries a display name, the row shows it as "{name} (they
named themselves)", never as a verified name (§6, open question 1). The row
opens the grant's receipt (CSD-006) on tap, as a contact row does.

**Accept is behind a ConfirmSheet with three facts.** *Who*: the requester's
key id (and their self-given name when sent). *What*: "You'll be each other's
contacts." *What it means*: "They can message you and see that you're a
contact. You can remove them later from their receipt." Confirming POSTs the
entry's own `accept` body to `accept.path` (`POST /v1/contacts {key_id}`), the
same add CSD-005 uses, so an accepted request is an ordinary contact with an
ordinary receipt.

**Ignore is one tap, no confirm.** It is private and undoable. The notice is
"Ignored. They aren't told." and the row moves to Ignored. Un-ignore moves it
back to the requests, with the notice "Back in your requests."

**Polling, because the node has no push for requests (§3).** Requests arrive
by replication, typically 10–30 s after the other person acts. The client reads
`GET /v1/contacts/requests` when People opens, after each answer, on the list's
own refresh, and every 15 s while People is on screen. It stops when the screen
leaves. A read that fails keeps the rows it has and says nothing new; the error
state is only for the first read.

**Old nodes show nothing new, and say nothing false.** A bare 404 (no
`reason_id`) on `GET /v1/contacts/requests` is a node older than 0.5.237: the
requests section is not drawn at all, and the rest of People is unchanged. It
never says "no requests", because the node cannot answer that.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:contact_request
    use: display-only
    type: string
    example: "Someone wants to connect · 9f3a1c2e · Asked 2 min ago"
    renders: "one row per pending request: 'Someone wants to connect', the requester's short key id, 'Asked {relative time}' from requested_at, and the requester's self-given name marked as theirs when the grant carries one. From GET /v1/contacts/requests; nothing is inferred from a contact list the requester is not on"
    tag: "proposed:row_contact_request_*"
  - ceg: x_private:contact_request_accept
    use: emit
    type: string
    example: "grant-att-7"
    renders: "Accept, behind the three-fact confirm (sheet_contact_request_accept), POSTing the entry's own accept body. The notice is 'You're now contacts.' only when the node answers without a reason_id; a 202 pending (contacts.awaiting_key_body) is CSD-005's pending row, never 'contacts'"
    tag: "proposed:btn_contact_request_accept_*"
  - ceg: x_private:contact_request_ignore
    use: emit
    type: string
    example: "grant-att-7"
    renders: "Ignore, one tap, no confirm: POST …/{grant_attestation_id}/ignore. Notice 'Ignored. They aren't told.'; the row moves to the Ignored section on the re-read"
    tag: "proposed:btn_contact_request_ignore_*"
  - ceg: x_private:contact_request_ignored
    use: display-only
    type: string
    example: "9f3a1c2e · Ignored 1 day ago"
    renders: "one row per ignored request in the collapsed 'Ignored' section, from the list's `ignored` entries (each carries ignore_id). Shown only to the person; the requester's view never changes"
    tag: "proposed:row_contact_request_ignored_*"
  - ceg: x_private:contact_request_unignore
    use: emit
    type: string
    example: "grant-att-7"
    renders: "Un-ignore, one tap: DELETE …/{grant_attestation_id}/ignore. Notice 'Back in your requests.'; the row returns to the requests section on the re-read"
    tag: "proposed:btn_contact_request_unignore_*"
```

```yaml csd:states
populated: {tag: "proposed:contact_requests_list", renders: "the 'Wants to connect' heading and one row per pending request, newest first, above the contact list"}
empty:     {tag: "proposed:contact_requests_empty", renders: "nothing visible: no requests is the normal case and People's own list speaks. A zero-height marker carries the tag"}
loading:   {tag: "proposed:contact_requests_loading", renders: "nothing visible on the first read (the contact list's own loading speaks); a zero-height marker carries the tag"}
error:     {tag: "proposed:contact_requests_error", renders: "'Couldn't read your requests.' with the node's refusal by id, above the contact list, which still shows. A node without the route is contact_requests_unsupported: a zero-height marker and no sentence, never 'no requests'"}
```

## 3. Contracts (who)

Node-owned, at the node URL (`ContactsViewModel.nodeUrl`, as CSD-005 calls
the contact routes), never `$baseUrl`. **CIRISServer 0.5.237**, unreleased:
CIRISServer#819 (requests and accept) and #820 (ignore, stacked on #819); spec
`FSD/MEMBERSHIP_INVITES.md` §8 and §8.1.

| value | endpoint | owner | state |
|---|---|---|---|
| the requests | `GET /v1/contacts/requests` → `{requests: [entry], total, ignored: [entry + ignore_id]}`; entry = `{kind: "contact", requester_key_id, grant_attestation_id, granted_by_key_id, granted_to_key_id, requested_at, grant, accept: {method: "POST", path: "/v1/contacts", body: {key_id}}, ignore: {method: "POST", path}}` | CIRISServer | **0.5.237, unreleased** (#819, `ignored` and `ignore` from #820). A request is a grant naming you from someone you have not granted back. It leaves the list once they are your contact. **The only read this card makes** |
| the same requests, in the invitation inbox | `GET /v1/self/invites` → `contact_requests: [entry]` beside `invites` | CIRISServer | **0.5.237, not read here.** Pending only, no `ignored`. CSD-106's inbox model reads this route for groups; it ignores `contact_requests`, so a request is never drawn twice |
| accept | the entry's `accept`: `POST /v1/contacts {key_id}` | CIRISServer | **live since 0.5.185** (CSD-005's add). The client sends the entry's body as given rather than building it. The outcomes are CSD-005's: added, `freshly_emitted: false`, or 202 `contacts.awaiting_key_body` (a pending row) |
| ignore | `POST /v1/contacts/requests/{grant_attestation_id}/ignore` → 200 `{state: "ignored", grant_attestation_id, ignore_id}` | CIRISServer | **0.5.237, unreleased** (#820). Private: the requester's node never learns. Reversible |
| un-ignore | `DELETE /v1/contacts/requests/{grant_attestation_id}/ignore` → 200 `{state: "pending", grant_attestation_id, withdrawal_id}` | CIRISServer | **0.5.237, unreleased** (#820) |
| refusals | `contacts.request_not_found` (404: not a pending request), `contacts.request_not_ignored` (404: un-ignore of a request that is not ignored), `contacts.delegate_may_not_answer` (403: a delegate session read the list but may not answer) | CIRISServer | **0.5.237**, queued in the server's `KNOWN_UNLOCALIZED` for this bundle. Rendered by id through `NodeRefusal`. A 404 on an answer re-reads the list (the request was answered elsewhere, or withdrawn) |
| push | none | CIRISServer | **not served**, by the server's own account: there is no event stream for invites or requests. The client polls (§2) |
| decline | `POST /v1/contacts/requests/{grant_attestation_id}/decline` → `{state: "declined", decline_id}` (shape mirrors ignore) | CIRISServer · CIRISPersist | **not available, on purpose**: a decline must be addressed to the requester's node alone (CIRISPersist#1121, after persist v60). No Decline control is drawn until the route answers; the tag is reserved, `btn_contact_request_decline_*` |

## 4. Flow (how)

**One node can assert the surface; only two can assert a request.** A request
is written by the other person's node and reaches this one by replication.

On one node (`testing/flows/drafts/csd-109-contact-requests.yaml`):

- Land on `Contacts` on a 0.5.237 node. `contact_requests_empty` is composed;
  `contact_requests_error` is not.
- On an older node, `contact_requests_unsupported` is composed and no
  `contact_requests_*` sentence is visible.

Across two nodes (`testing/gate/two_node.py`, Ada and Cy, peered), the steps
CIRISAgent's cross-platform chat stage will drive:

1. **Ada adds Cy by code** (CSD-005 `input_contacts_add_key` → `btn_contacts_add_submit`). Ada's list shows Cy as a contact or a pending row.
2. **Cy sees the request.** Within 60 s (polling, §2), `row_contact_request_{grant}` is visible on Cy's People. Cy has not added Ada.
3. **Ignore path.** Cy taps `btn_contact_request_ignore_{grant}`. The row leaves the requests and appears as `row_contact_request_ignored_{grant}` after opening the Ignored section. **Ada's view is unchanged** (her row for Cy reads exactly as before). Cy taps `btn_contact_request_unignore_{grant}`; the row returns.
4. **Accept path.** Cy taps `btn_contact_request_accept_{grant}` → `sheet_contact_request_accept` with its three facts → `btn_contact_request_accept_confirm`. Cy's list now has `contacts_row_{ada}`; the request row is gone on the re-read.
5. **Both are contacts.** Within 60 s, Ada's row for Cy is a contact, not pending.
6. **Pair chat.** Ada opens chat with Cy (CSD-091); Cy accepts the pair-room invitation on Ada's row (`btn_contacts_chat_accept_{ada}`). One message each way arrives.
7. **Decline path:** reserved until CIRISPersist#1121. Cy taps `btn_contact_request_decline_{grant}`; Ada's node sees the decline and no one else does.

Test ids this card adds: `contact_requests_list`, `contact_requests_empty`,
`contact_requests_loading`, `contact_requests_error`,
`contact_requests_unsupported`, `row_contact_request_{grant_attestation_id}`,
`btn_contact_request_accept_{id}`, `btn_contact_request_ignore_{id}`,
`contact_requests_ignored_toggle`, `row_contact_request_ignored_{id}`,
`btn_contact_request_unignore_{id}`, the confirm `sheet_contact_request_accept`
with `contact_request_accept_fact_{1..3}`, `btn_contact_request_accept_confirm`,
`btn_contact_request_accept_cancel`; reserved: `btn_contact_request_decline_{id}`.

## 5. QA plan

`commonTest` `ContactRequestsViewModelTest`: the read (a bare 404 is an older
node, never "no requests"; a refusal with an id decides nothing about
support); accept POSTs the entry's own `accept` body and re-reads; a 202
`contacts.awaiting_key_body` on accept is CSD-005's pending row, not
"contacts"; ignore and un-ignore move the row on the re-read; a 404
`request_not_found` on any answer re-reads; `delegate_may_not_answer` renders
by id and keeps the row; polling runs only while the screen is shown and stops
when it leaves; a failed poll keeps the rows. `desktopTest`
`ContactRequestsWireTest` over a real socket: every path and body at the node
URL, the three refusals with their statuses. The two-node path is the
cross-platform stage's (§4).

**Not guaranteed here.** That a request reaches the named person's node, and
that an ignore stays private, are replication properties (persist); a single
node cannot observe either.

## 6. Delta — card vs API vs CC

**Open questions for the server (sign-off):**

1. **What does `grant` carry about the requester?** If it holds a self-given
   name (from their contact code), the row shows it marked as theirs. If it
   holds only key ids, the row is "Someone wants to connect" + short key id,
   which is honest but thin. Is a name available, and is it the requester's own
   claim?
2. **Is a 202 `contacts.awaiting_key_body` possible on accept?** The request
   exists because the requester's grant replicated here, so this node should
   already hold their key. If it can still happen, the card treats it as
   CSD-005's pending row.
3. **Ordering and identity.** The card sorts newest first by `requested_at`
   and keys rows by `grant_attestation_id`. Can one person have more than one
   pending request (a re-add after removal), and should the client collapse
   them by `requester_key_id`?
4. **Expiry.** Do requests expire, or stand until accepted, ignored or the
   requester removes their grant? The card shows no expiry.
5. **What the requester sees.** The card says the requester's view is
   unchanged by ignore and shows nothing on accept beyond the contact
   completing. Is there anything on the requester's side the client should
   draw (for example, CSD-005's pending row resolving)?

**Deferred, by design:**

* **Decline** waits on CIRISPersist#1121 (an addressed decline only the
  requester's node can see). Until it lands, Accept and Ignore are the only
  answers, and no control suggests a "no" the requester would see.
* **No push channel.** The 15 s poll is the client's choice; the server offers
  to scope a stream if it is wanted.
</content>
</invoke>
