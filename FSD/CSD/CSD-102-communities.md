# CSD-102 — Communities and affiliations: the community itself (Rules)

**CSD**: CSD-102 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B6 Neighbours / B7 Communities and Businesses
**Companion**: CSD-103 (who is in it, in People; the rooms you talk in, in Chats) — one view model per tier serves both.
**Flow**: `testing/flows/drafts/csd-102-communities.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Standing in Neighbours or in Communities and Businesses, a person can found a
group, see the rule it declared for who may change it, change a member's role,
see who holds the moderation duty, finish a change the rule is holding for more
signatures, and leave or dissolve it — and every act says, before it is signed,
who signs it.** Serves **Contextual Integrity** and **Justice**, on CC 4.4.3.2:

* **CC 4.4.3.2.3** — a membership change is admitted under the room's CURRENT
  `consensus_protocol`. The rule is on the record, so it is on the card, and a
  change it holds is shown as held, never as failed.
* **CC 4.4.3.2.1 / .2** — a community is a bounded roster under one DEK, rotated
  on removal. So "remove" and "dissolve" are forward-only, and the confirm says
  what the removed person keeps.
* **CC 4.4.3.2.8** — an *affiliation* is the same machinery gathered by
  necessity, with one declared config record visible to members on joining. That
  record has no route (CIRISServer#649), and the card says so instead of implying
  there is nothing to declare.
* **CC 4.5.5** — moderation is a duty, never a role. The roles list and the
  moderators list are two lists.

**Placement, and why there is no new Rules card.** The circle's Rules tab
already opens with its hub — `LayerLocalCommunity` / `LayerGlobalCommunities`,
the card CSD-050 describes as "who is in it … and the standing rules". The
community IS that circle's group, so the governance section is mounted on the
hub (`LayerHubScreen.kt`, the scope-specific slot where Environment already
sits) rather than as a second card that would be the same card under another
name. `tier: community` is Neighbours and `tier: affiliations` is Communities
and Businesses — `CohortScope`'s fold, `community → LOCAL_COMMUNITY`,
`affiliations → GLOBAL_COMMUNITIES` (`ui/nav/CohortScope.kt`).

## 2. Surface (what)

```yaml csd:surface
surface: layer-local-community
screen: LayerLocalCommunity
scopes: [layer-local-community, layer-global-communities]
```

`nav_map` derives `circle_local_community -> tab_rules ->
nav_epistemic_layer_local_community`; the affiliations twin derives
`circle_global_communities -> tab_rules -> nav_epistemic_layer_global_communities`.
The section is `CommunityGovernanceSection` (`ui/screens/CommunitiesScreens.kt`)
over `CommunitiesViewModel(tier)`, and every tag is the same at both tiers.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:community_name
    use: emit
    type: string
    example: "Allotment"
    renders: "one row per room at this tier, 'Allotment' with '3 members · your role: founder' under it; pair rooms are NOT here (their roster is their identity — community.pair_room_fixed)"
    tag: "community_row_*"
  - ceg: x_private:cohort_scope
    use: emit
    type: "enum[community,affiliations]"
    example: "community"
    renders: "not a control: the circle you found it from IS the tier. Neighbours founds `community`, Communities and Businesses founds `affiliations`"
    tag: section_communities
  - ceg: x_private:consensus_protocol
    use: emit
    type: string
    example: "quorum:2/3"
    renders: "'Rule — quorum:2/3' with the plain sentence for its family under it ('That many members sign a change. It must be more than half…'). At founding: four choices (The founder / Everyone / Most / A set number); a set number asks for M, and N is the founding roster"
    tag: txt_community_protocol
  - ceg: x_private:community_my_role
    use: display-only
    type: string
    example: "founder"
    renders: "'Your role — founder'"
    tag: txt_community_my_role
  - ceg: x_private:community_member_role
    use: emit
    type: string
    example: "treasurer"
    renders: "one row per member with its role word and a 'Change role' chip; the role body says a role is not a duty"
    tag: "row_community_role_*"
  - ceg: "duty:{kind}"
    bind: {kind: moderate}
    use: display-only
    type: "list[string]"
    example: ["wa-mem-9b02"]
    renders: "'Moderators' — appointed `moderate` duty holders (founder-rooted delegates_to); or 'No appointed moderator is on record.' when the node read the chain and found none; or, in the danger tone, 'This node could not read who holds the moderation duty here. That is not a report that nobody does.' when it says it could not (`moderators_readable: false`, CIRISServer#688); or 'This node did not send who holds the moderation duty here.' when the list is absent. Then 'Open Moderation', which goes to CSD-065 with this room picked"
    tag: "row_community_moderator_*"
  - ceg: x_private:community_pending_change
    use: emit
    type: string
    example: "1 of 2 signatures"
    renders: "'Waiting for signatures — 1 of 2 signatures', the rule, who may sign, the change as text to copy, a field for a signature someone sent back, and 'Apply the change' (enabled once the count is met)"
    tag: txt_community_pending_count
  - ceg: x_private:community_cosignature
    use: emit
    type: string
    example: "{\"signer\":\"k-ann\",…}"
    renders: "'Sign a change someone sent you' — paste it, the node checks it still describes the room, and the signature comes back to copy. Nothing is applied on this node"
    tag: txt_community_cosignature
  - ceg: x_private:affiliations_declared_record
    use: display-only
    type: unconfirmed
    blocked_by: CIRISServer#649
    example: "affiliation_archetype: informal_adhoc"
    renders: "affiliations only: 'What this body has declared it can do with what you put here … has no route yet (CIRISServer#649). Joining does not show it, and this app will not guess it.'"
    tag: txt_affiliations_terms_unavailable
```

```yaml csd:states
populated: {tag: communities_list, renders: "the rooms at this tier as rows; tapping one opens card_community_detail"}
empty:     {tag: communities_empty, renders: "You are not in any community yet. Found one and add the people you already have as contacts."}
loading:   {tag: communities_loading, renders: "the loading block, never the empty sentence"}
error:     {tag: communities_error, renders: "Could not read your communities. The node was asked and did not answer. This is not a report that you have none. — the node's reason id, localized, with its English under it"}
```

Two further states, each its own tag, because each is a different fact:

* **`communities_not_on_this_node`** — a 404 with no `reason_id`. Every refusal
  `communities.rs` authors carries an id, so an id-less 404 is the route not
  mounted: "This node doesn't serve communities yet. It needs CIRISServer
  0.5.216 or later." A fact about the node, never "you are in none".
* **`community_detail_not_found`** — one room answered `community.not_found`.
  The node gives that answer for a room that does not exist AND for one you are
  not in, on purpose (`communities.rs:238`, "hidden, not refused"), so the card
  says both: dissolved, or you were removed.

**The acts and their confirms.** Leave (`btn_community_leave` →
`sheet_community_leave`) and dissolve (`btn_community_dissolve` →
`sheet_community_dissolve`) each go behind a ConfirmSheet with exactly three
facts: *which room*, *what changes* (dissolve: every member removed, the key
rotated, nobody left who can change it; leave: you receive nothing new, and what
you received stays), and *who signs* (leave: "You, with your own key. Leaving is
always your own act and never waits on the rule"; dissolve: "You … under the
room's rule (quorum:2/3). If the rule needs more signatures, nothing changes
until they are collected"). A role change is not behind a confirm: it is undone
by the same act.

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217; the routes landed
in 0.5.216), `src/communities.rs`. Every call goes to the NODE URL
(`nodeBaseUrl`, the local node), never `baseUrl` — on a with-AI install that is
the agent, which proxies these only from agent 2.12.1 (CIRISAgent#1213,
closed by #1215). The direct node URL works on every agent version, so it stays.
`CommunitiesViewModelTest.every_call_goes_to_the_node_url_not_the_base_url`
pins it.

| value | route | handler | shape |
|---|---|---|---|
| my rooms | `GET /v1/communities` | `list_communities` :1418 | `{communities: [room], total, resume}`; a room is `room_json` :1351 — `community_id, name, kind: pair\|room, tier, cohort_scope, consensus_protocol, founded_at, member_count, my_role, members: [{key_id, role, joined_at}] (member_json :1106), roles: {role: [key_id]}` |
| one room | `GET /v1/communities/{id}` | `read_community` :1497 | `room_json(detail = true)`: adds `moderators: [key_id]`, `widenings`, `revocations`; `community.not_found` 404 for a non-member (:238) |
| found | `POST /v1/communities` `{name, members?, tier?, consensus_protocol?}` | `create_community` :1220 | 201 + `room_json`; `community.name_empty`, `.bad_tier`, `.bad_consensus_protocol` (a quorum's N must equal the founding roster, :1276), `.not_a_contact` |
| role | `POST /v1/communities/{id}/members/{key_id}/role` `{role}` | `change_role` :1584 | `applied()` :1114 `{community_id, op, applied, members}` or `community.quorum_pending` |
| dissolve | `DELETE /v1/communities/{id}` | `dissolve_community` :1673 | the same two outcomes |
| leave | `POST /v1/communities/{id}/leave` | `leave_community` :1616 → `leave_room` :1632 | `{community_id, op: "leave", applied, key_id}`; `community.last_founder`, `.pair_room_fixed` |
| held change | any governed write | `quorum_pending` :1180 | **409** `{error, reason_id: "community.quorum_pending", change_envelope, signing_bytes_base64, signatures, valid, required, eligible_signers, consensus_protocol}` |
| envelope | `POST /v1/communities/{id}/changes/envelope` `{op, key_id?, role?}` | `change_envelope` :1693 | the same body at 200. **Not called**: every direct write already returns it when held (below) |
| cosign | `POST /v1/communities/{id}/changes/cosign` `{change_envelope}` | `change_cosign` :1764 | `{community_id, op, signature}` — stateless |
| assemble | `POST /v1/communities/{id}/changes/assemble` `{change_envelope, signatures}` | `change_assemble` :1815 | `applied()`, or `quorum_pending` again with the count |
| names, and who can be founded with | `GET /v1/contacts` | `list_contacts`, `src/contacts_chat.rs` (CSD-005) | the founding card's member chips are the caller's contacts (a room member must be a contact whose grant covers `chat:`), and every key on the card is titled by the contact's alias when this node has one. Best effort: a failed contacts read hides nothing the node said about the room |

Refusal bodies are `{error, reason_id}` (`src/auth/refusal.rs:52`), with
`refuse_with` merging the extra members (:35). The client reads them into
`NodeRefusal`, except `community.quorum_pending`, which
`CIRISApiClient.communityChangeOutcome` returns as
`CommunityChangeOutcome.Pending` — reading it as a refusal would drop the only
copy of the change, because **the node keeps nothing between the envelope and
the assemble**. All 18 `community.*` ids have English in the bundle.

**Why no `…/changes/envelope` call.** A direct write under a protocol one
signature does not meet answers `quorum_pending` WITH the envelope and the
caller's own signature (`direct_change` :1133), which is exactly what the
envelope route returns. Calling both would sign the same change twice.

### 3.1 What the node does not say, and what the card does instead

* **An unreadable moderator chain reads as none — on the node; the card is
  ready for the fix.** `room_json` fills `moderators` with
  `appointed_moderators_of(…).unwrap_or_default()` (:1384), so a persist error
  and "no moderator appointed" are the same `[]` (CIRISServer#688, filed; its
  ask is `moderators_readable: false` or a failed read). The client now decodes
  `moderators_readable` and `moderatorsShown()` renders four facts four ways:
  `txt_community_moderators_unreadable` (danger tone) when the node says it
  could not read, `txt_community_moderators_not_sent` when the list is absent,
  `txt_community_moderators_none` when it read none, and a row per holder
  (`ModeratorsShownTest`, red against the old `moderators.orEmpty()`: two
  failures, `expected Unreadable / NotSent but was None`). Until the node sends
  the flag, an unreadable chain still arrives as `[]` and reads "No appointed
  moderator is on record" — true in both cases — and the card sends the person
  to Moderation, whose named-moderator verdict
  (`operate`/`auto_promote`/`quiesce`, CSD-065) is the read that fails secure.
* **Appointing a moderator is not on this card.** Persist's appointed set is
  founder-rooted `delegates_to` scoped `moderate`; the only conferral this client
  drives is the accord's (`/v1/accord/duty/propose`, CSD-090), which is rooted
  in the accord family, not the room's founder. The server FSD says
  "moderators keep using duty conferral"; no route confers a room-scoped duty
  from its founder. Stated on the card (`txt_community_appoint_unavailable`).
* **The affiliations record** (CC 4.4.3.2.8) has no route — CIRISServer#649.

### 3.2 Moderation takes the room (CSD-065)

`ModerationScreen` takes a `communityPicker`; it is now wired. From Moderation
the picker (`CommunityModerationPicker`, `community_moderation_picker`) lists
the person's rooms of more than two at both tiers as chips
(`chip_community_moderation_<id>`), read through the same two tier view models
this card uses — no second read. Picking one fills Moderation's community
field and the quarantine rung's `community_id` with the room's id, which IS the
`community_key_id` those routes take (`communities.rs:1308`,
`new_room_community_key_id`). From a room's card, `btn_community_open_moderation`
opens Moderation with that room already picked (handed back once, so Moderation
opened later from the nav arrives empty). A failed rooms read says so
(`txt_community_moderation_pick_unreadable`) and leaves the typed field as the
way in; no rooms says so (`txt_community_moderation_pick_none`). Pair rooms are
left out: a two-person chat has no moderation duty to look up.
* **The group book** — who admitted whom, when, and the change history — is
  CSD-103's, and is CIRISServer#650.

## 4. Flow (how)

The draft is `testing/flows/drafts/csd-102-communities.yaml`. Land on
`LayerLocalCommunity` (the runner walks the derived hop).

```yaml
expect:
  visible: [section_communities, btn_community_create_open]
```

Open the founding card, name it, keep the default rule (the founder), found it.
A room of one needs no contact, so this runs on a single node.

```yaml
do:
  - click: btn_community_create_open
  - input: {input_community_name: "Flow allotment"}
  - click: opt_community_protocol_founder_only
  - click: btn_community_create_submit
expect:
  visible: [card_community_detail, txt_community_protocol, txt_community_my_role]
```

Leave is behind its three facts:

```yaml
do:
  - click: btn_community_leave
expect:
  visible: [sheet_community_leave, community_leave_fact_1, community_leave_fact_2, community_leave_fact_3]
```

*Cannot assert without a second person:* a held change (`card_community_pending`)
needs a room whose rule one signature does not meet — two nodes and a contact.

## 5. QA plan

**Platforms.** All five; the hop is derived and identical.

**Acceptance — functional**
1. A node without `/v1/communities` says so (`communities_not_on_this_node`) and
   never reads as "you are in none".
2. A change the rule holds is shown held, with its count, and survives moving
   between Rules and People (one view model per tier, app-scoped).
3. Assemble sends back the node's envelope unchanged and every signature
   collected (`CommunitiesViewModelTest.a_quorum_pending_add_is_held_then_assembled`).
4. Leave and dissolve name who signs before the signature.

**Review (2026-09-28).** Closed: the unreadable-moderators rendering (above,
red-tested); Moderation's community picker, wired from this card (§3.2, UI
wiring, verified by compile). Open: CIRISServer#688 (the node still sends `[]`
for an unreadable chain; no founder-rooted appoint route; no row envelope) and
CIRISServer#649 (the affiliations record). Stage: building → building
(`affiliations_declared_record` is `blocked_by: CIRISServer#649`).

**Not tested here.**
* Whether a signer satisfies the rule — the node's `tally` decides, and a client
  fixture that admitted a non-holder would test the wrong machine.
* Persist re-verifying a quorum supersede (`verify_membership_quorum`).
* The envelope's lifetime: it lives in the app process. An app restart before
  assembly drops it; it can be rebuilt by making the same change again, and the
  node's `community.change_stale` refuses one that no longer fits.
* This pipeline's English only: every new string is `draft` until the lead's
  translation run, which is machine-translated and machine-reviewed, not native.
