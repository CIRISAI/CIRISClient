# CSD-106 — Invitations to a household or a community (nobody joins without saying yes)

**CSD**: CSD-106 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the maintainer's ruling of 2026-09-30 (CIRISConstitution#133; CIRISPersist#955; CIRISServer#700)
**Extends**: CSD-100 (the household hub) · CSD-101 (the household roster) · CSD-102 (the community hub) · CSD-103 (the community roster). **No new card** (§2).
**Flow**: `testing/flows/drafts/csd-106-membership-invites.yaml` (floor `unreleased`: no released client carries these tags; client 0.5.226 will)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person is added to a household or a community only by accepting an
invitation themselves. The person invited sees every invitation addressed to
them, with who sent it, to which group, in which role and until when, and
accepts or declines it. The person who invited sees it waiting until it is
answered or expires.**

The maintainer ruled on 2026-09-30 that adding someone to a family or a
community requires their consent, **founding members named when the group is
formed included**. The normative text is CIRISConstitution#133. Until
CIRISServer 0.5.218 every roster-growing door admitted a new member on the
existing members' signatures alone (CC 4.4.3.2.3's admit predicate), so a
founder could enrol any key they could name, and the enrolled person's node
began receiving the group's rows and key wraps. Serves **Contextual
Integrity** and **Integrity**: joining a `cohort_scope: family` or `community`
roster changes who can read what, in both directions (CC 4.4.3.4.1 wraps every
extant family DEK to a new member), so it must be the joiner's own signed act.

**Reverse quorum is not a membership rule.** It stays CSD-070's commons
objection brake and is not offered here.

## 2. Surface (what)

```yaml csd:surface
surface: layer-family
screen: LayerFamily
scopes: [layer-family, layer-local-community, layer-global-communities]
```

**No new card: this extends four that exist, found by the routes they call**
(`packaging/check_csd_routes.py`). The cards that called the roster-growing
routes are the places the invitation replaces them:

| role | where it is | the card it extends | what it replaces |
|---|---|---|---|
| **the inviter's Invite and pending rows** — "Invited: waiting for them to accept · expires {date}" under the members, Withdraw on your own, and "Propose adding" on an accepted row of a quorum household | Family › People › Household; Neighbours / Communities and Businesses › People | CSD-101 (`HouseholdMembers`), CSD-103 (`CommunityRoster`) | `POST /v1/families/{id}/members`, `POST /v1/communities/{id}/members` |
| **founding alone** — on a node that carries invitations the founding cards drop their member chips and say "you form it on your own, then invite" | Family › Rules; the two community Rules hubs | CSD-100 (`LayerFamily`), CSD-102 (`LayerLocalCommunity` / `LayerGlobalCommunities`) | `members` on `POST /v1/families` and `POST /v1/communities` |
| **the invitee's inbox** — accept or decline | the same hubs, above the household or community list | CSD-100, CSD-102 | none; it is new |

The inbox sits on the hub because the person invited is by definition not in
the group yet: its roster screen has nothing to show them, while the hub is
where "You're not in a household yet" and "You are not in any community yet"
already stand. One app-scoped view model (`InvitationsViewModel`) reads
`GET /v1/self/invites` once for all three hubs; the household hub draws the
`family` rows and each community hub the `community` rows
(`inboxFor`, `ui/screens/InvitesSupport.kt`). A pair room's invitation is left
out: a two-person chat is answered by opening it (CSD-091, `POST /v1/chat`).
**A row's `tier` places it on its own community hub** (`community` →
Neighbours, `affiliations` → Communities and Businesses). 0.5.218 does not send
`tier`, so until 0.5.219 a row without it is drawn on **both** community hubs
rather than hidden from either (§6).

**Accept and decline are each behind a ConfirmSheet with three facts**: *who
invites you* (the inviter, by your contact's name for them when you have one),
*into what* ("The Okafors (household), as Member"), and *what answering means*.
Accepting a household: "You say yes with your own key. You're in the household
once it seats you; then you can read what it already shares, and its members
see what you share with it." Accepting a community adds that under a rule
needing more signatures the members still have to sign. Declining: "Final.
This invitation can't be accepted later; joining would take a new one."

**Old nodes keep the direct add, detected by the route.** A bare 404 (no
`reason_id`) on `GET …/{id}/invites` or `GET /v1/self/invites` is a node older
than 0.5.218: the roster keeps "Add someone" and its confirm, the hub says
"This node can't carry invitations yet", never "you have none". A refusal
*with* an id (`family.not_found`, `membership.owner_session_required`) is a
node that has the route and said no, and decides nothing about support. Two
act-time answers are taken at their word: a confirmed invitation that meets a
bare 404 sends **nothing** in its place (a direct add is a different act) and
the roster switches to Add, saying why (`mobile.invites_node_adds_directly`);
a direct add answered 409 `membership.consent_required` (the interim build)
re-reads the invites route and offers Invite if it is served. A direct add
answered 202 `{state: "invited"}` (0.5.218's alias) is reported as an
invitation, never as "Added".

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:membership_invitation
    use: display-only
    type: string
    example: "Ada invited you as Member · Until 15 Oct 2026"
    renders: "the invitee's inbox row: the group's name (or a short id when this node does not hold its record), 'Ada invited you as Member', and 'Until {date}'. The group, the inviter, the role and the expiry come from the proposal row as GET /v1/self/invites sends it; nothing is inferred from a roster the invitee cannot read"
    tag: "invitation_row_*"
  - ceg: x_private:membership_acceptance
    use: emit
    type: string
    example: "att-9"
    renders: "Accept, behind the three-fact confirm, signed with the invitee's own key. The notice is 'Accepted. You're not in it until the group seats you.', never 'Joined'"
    tag: "btn_invitation_accept_*"
  - ceg: x_private:membership_decline
    use: emit
    type: string
    example: "att-9"
    renders: "Decline, behind its own three-fact confirm that says it is final. The row goes on the re-read"
    tag: "btn_invitation_decline_*"
  - ceg: x_private:membership_invitation_pending
    use: display-only
    type: "enum[pending,accepted,declined,expired]"
    example: "pending"
    renders: "on the inviter's roster (CSD-101 / CSD-103), one row per invitation under the members: 'Invited: waiting for them to accept', 'Accepted: waiting to be seated' (founder_only) or 'Accepted: waiting for the members to sign' (a quorum), 'Declined', 'Expired'. `joined` is already a member row and `withdrawn` is gone, so neither is drawn; none is counted as a member"
    tag: "invitation_pending_*"
  - ceg: x_private:membership_invitation_expires_at
    use: display-only
    type: timestamp
    example: "2026-10-15T00:00:00+00:00"
    renders: "'Until {date}' in the inbox and 'expires {date}' on a pending roster row, from the proposal's signed expires_at (14 days by default, at most 30). The client never judges expiry on its own clock: the node's `state` says expired"
    tag: "invitation_expires_*"
  - ceg: x_private:membership_invite_withdraw
    use: emit
    type: string
    example: "att-1"
    renders: "Withdraw, behind a three-fact confirm, on a pending row whose `proposer_key_id` is the viewer's own key: the list's `viewer_key_id` (0.5.219+) when sent, else the owner from GET /v1/setup/owned-nodes (`withdrawShown`, `viewerOf`). Someone else's invitation shows none. Only when no key is readable is it offered on every pending row, and the node refuses anyone but the proposer by id (`membership.not_the_proposer`)"
    tag: "btn_invitation_withdraw_*"
```

```yaml csd:states
populated: {tag: invitations_list, renders: "the 'Invitations' heading and one row per open invitation of this hub's kind, newest first"}
empty:     {tag: invitations_empty, renders: "nothing visible: no invitations is the normal case, and the hub's own empty state speaks. A zero-height marker carries the tag"}
loading:   {tag: invitations_loading, renders: "a progress affordance and no sentence"}
error:     {tag: invitations_error, renders: "'Couldn't read your invitations.' with the node's refusal by id. A node without the route is its own block, invitations_not_on_this_node: 'This node can't carry invitations yet. It needs ciris-server 0.5.218 or newer.', never 'you have none'"}
```

## 3. Contracts (who)

Node-owned, at the node URL (`nodeBaseUrl`, as CSD-100..103 call theirs),
never `$baseUrl`: on a with-AI install that is the agent, which does not proxy
them (CIRISAgent#1213). CIRISServer **0.5.218**, merged to server `main` at
`53d1ffb5` (tag pending): `src/membership_invites.rs`, `src/family_api.rs`,
`src/communities.rs`; design and as-built notes in its
`FSD/MEMBERSHIP_INVITES.md` §7.

| value | endpoint | owner | state |
|---|---|---|---|
| the inbox | `GET /v1/self/invites` → `{invitee_key_id, invites: [{proposal_id, group_kind, group_id, group_name, is_pair_room, role, proposer_key_id, proposed_at, expires_at}]}` | CIRISServer | **live at 0.5.218** (`membership_invites.rs::inbox` :801). Live, unanswered, unwithdrawn proposals naming the owner; a delegate may read. Read by `InvitationsViewModel` through `ClientMembershipInvites` |
| accept | `POST /v1/self/invites/{proposal_id}/accept` → `{state: "accepted", proposal_id, reply_id, group_kind, group_id, awaiting}` | CIRISServer | **live at 0.5.218** (`answer` :840). The owner's own session signs with the person key; `awaiting` says the group still seats them |
| decline | `POST /v1/self/invites/{proposal_id}/decline` → the same, `state: "declined"` | CIRISServer | **live at 0.5.218**. Terminal. **Two doors on purpose, one client method (`declineInvite`):** group invitations (a household or community the invitee is not in yet) are declined here on the hubs; pair-room invitations (one contact asking to talk) are declined on that contact's People row (CSD-005), where Accept lives too. Recorded as a deliberate duplicate in `packaging/csd_routes_baseline.json` |
| invite (household) | `POST /v1/families/{id}/invites {key_id, role?, expires_in_days?}` → **202** `{state: "invited", proposal_id, group_kind, group_id, invitee_key_id, role, expires_at}` | CIRISServer | **live at 0.5.218** (`family_api.rs::invite` :1121). A founder under `founder_only`, any member under a quorum; the invitee must be a registered key and not active. Called from CSD-101 |
| a household's invitations | `GET /v1/families/{id}/invites` → `{family_id, invites: [{proposal_id, invitee_key_id, role, proposer_key_id, proposed_at, expires_at, state, reply_id}], seated_now, dek_rewrap}` | CIRISServer | **live at 0.5.218** (`list_invites` :1195). Members only. Reading it is also when a `founder_only` founder's node seats an accepted invitee and re-wraps the household's content to them. Called from CSD-101, and re-read after the hub's acts |
| withdraw (household) | `DELETE /v1/families/{id}/invites/{proposal_id}` → `{state: "withdrawn", proposal_id, withdrawal_id}` | CIRISServer | **live at 0.5.218** (`withdraw_invite` :1269). The proposer only. Called from CSD-101 |
| invite / list / withdraw (community) | `POST /v1/communities/{community_id}/invites`, `GET /v1/communities/{community_id}/invites`, `DELETE /v1/communities/{community_id}/invites/{proposal_id}` | CIRISServer | **live at 0.5.218** (`communities.rs::invite` :1725, `list_invites` :1808, `withdraw_invite` :1888). A contact grant is NOT required to invite. Called from CSD-103; the GET also follows the hub's governed writes (CSD-102) |
| the `…/members` alias | `POST /v1/{families,communities}/{id}/members` answers the same 202 `{state: "invited"}` | CIRISServer | **live at 0.5.218**. The client reads it as an invitation (`InviteSent`, `CommunityChangeOutcome.Invited`), never an applied add |
| seating under a quorum | `POST /v1/families/{id}/changes/envelope {action: add, key_id}` → cosign → assemble, now a co-signed widening | CIRISServer | **live**; persist refuses it at assemble without the joiner's acceptance (`membership.awaiting_acceptance`). The household roster's "Propose adding" on an accepted row starts it (CSD-100 §2.2's ceremony). The community equivalent (`…/changes/envelope {op: add}`) is **not called** by this client (§6) |
| founding | `POST /v1/families`, `POST /v1/communities` with `members` naming anyone but the founder | CIRISServer | **refused 409 `membership.founding_member_unsigned`** at 0.5.218. The founding cards stop offering member chips on a node that carries invitations |
| refusals | 18 `membership.*` ids (`refused` and the flow's own, `membership_invites.rs` :105-300) | CIRISServer | **live**. Rendered by id through `NodeRefusal`; the bundle entries are the server-strings PR's (`feat/server-0.5.218-parity`), and until it lands the node's English shows |
| the rows | `membership:proposal:v1`, `membership:acceptance:v1`, `membership:decline:v1` | CIRISPersist#955 (v52) / CIRISEdge (v38 `membership`) | **shipped**; the admission rule is persist's alone |
| who "you" are, for Withdraw | `GET /v1/setup/owned-nodes` → `owner` | CIRISServer | **live**, loopback. Read by the household roster already (CSD-101); the community roster reads it once, only when a list arrives without `viewer_key_id` |

### 3.1 Additions planned for 0.5.219 (parsed now, optional)

| value | where | what the client does |
|---|---|---|
| `viewer_key_id` | beside `invites` on `GET /v1/{families,communities}/{id}/invites` | `GroupInviteList.viewerKeyId`. Preferred over the owner read for "is this my invitation?" (`viewerOf`); absent on 0.5.218, where `owned-nodes` stands in |
| `tier` (`community` \| `affiliations`; null for families and pair rooms) | each `GET /v1/self/invites` row | `InboxInvite.tier`. When present, each community hub shows only its own tier's rows; a row without it (0.5.218) is shown on both community hubs |


## 4. Flow (how)

`testing/flows/drafts/csd-106-membership-invites.yaml`, floor `unreleased`.
On a single node it asserts what one person sees: the household hub composes
an inbox state (empty on a fresh node, never the error), the roster's add
control is "Invite someone" on a ≥ 0.5.218 node, and the picker opens. Sending
and answering an invitation needs **two persons on two nodes**: Ada invites
Cy, Cy's node receives the proposal by replication and accepts.
`testing/gate/two_node.py` stands up the second node and peers the two; it
seeds a chat room, not a household, so it needs one more step (Cy's owner key
registered on Ada's node, which its peering already replicates) before the
optional steps can run. Those steps are marked `optional_step` until it does.

## 5. QA plan

`commonTest` `InvitationsViewModelTest` (the inbox: read, a bare 404 is an
older node not an empty inbox, a refusal with an id decides nothing about
support, accept and decline each wait for the confirm, accepted carries
`awaiting` and is never joined, all 18 refusal ids render by id; the pure
rules: hub split, pair rooms out, roster states, who may withdraw, routeOf);
`HouseholdsViewModelTest` (invite on a 0.5.218 node, a bare 404 keeps the
direct add, a confirmed invitation that meets no route sends nothing else,
`membership.consent_required` re-reads the route and switches to Invite, a 202
from the alias is an invitation, withdraw, quorum seating proposed, a
non-founder of a founder_only household invites nobody); `desktopTest`
`MembershipInvitesWireTest` over a real socket (every path and body, the 202
never decoded as an applied add, every refusal id with its status, all calls
to the node URL, the three view models' detection).

**Not guaranteed here.** Delivery of a proposal to the invitee's node, and of
the acceptance back, is persist's replication, which a single-node fixture
cannot observe.

## 6. Delta — card vs API vs CC

* **The interim 409 never shipped as a client target.** The design (and the
  first version of this card) said every roster door would answer 409
  `membership.consent_required` until persist v52. At 0.5.218 persist v52 is
  in and that refusal is gone: `POST …/members` is an alias for `…/invites`
  answering 202 `{state: "invited"}`, a quorum `add` is admitted only on the
  joiner's acceptance, and a founding roster beyond the founder is refused
  `membership.founding_member_unsigned`, not `consent_required`. The client
  still handles `consent_required` (a node on the unreleased interim branch),
  but no released node sends it.
* **Accepted is not joined.** Under `quorum:M/N` an invitee can accept and
  still not be admitted if the quorum never assembles. The inbox's notice and
  the roster's "Accepted: waiting…" never report the acceptance as
  membership; only the node's `joined` (accepted AND on the fold) does, and
  that row is the member row.
* **The inbox row does not carry the room's tier at 0.5.218.** It sends
  `group_kind: community` for both `community` and `affiliations` rooms, and
  the invitee's node may not hold the room's record. 0.5.219 adds `tier` to
  each row (§3.1); the client already filters by it when present, and until
  then draws an untiered community invitation on both community hubs.
* **A quorum ROOM cannot seat an accepted invitee from this client.** The
  server seats them through `…/changes/envelope {op: add}` → cosign →
  assemble; this client has never called the community envelope route (every
  pre-0.5.218 write returned the envelope itself on `community.quorum_pending`).
  The accepted row says so ("this app can't start that yet") rather than
  offering a control. The household equivalent works (its envelope route was
  already called from the roster).
* **"Your own invitation" is the viewer's key against `proposer_key_id`.**
  0.5.219 sends `viewer_key_id` on the list (§3.1); on 0.5.218 both rosters
  read the owner from `owned-nodes`. Only with neither readable is Withdraw
  offered on every pending row, and the node refuses non-proposers by id.
* **Pair rooms are out of scope.** A two-person chat's invitation also arrives
  in `GET /v1/self/invites` (`is_pair_room: true`); it is answered by opening
  the chat (CSD-091), and this inbox leaves it out.
