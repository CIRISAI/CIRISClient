# CSD-065 — Moderation (the duty, the existence invariant, and the brake)

**CSD**: CSD-065 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1 (S2/G1)
**Flow**: unwritten — every tag in §2.3 is real, so it can be written
**Extended by**: CSD-108 (Steward purge). Tier 3 (Descend) is the key purge
that exists today: a previewed selection hash, a quorum of 2, and `evict_actor`
for each target (moving onto `purge_rows` by actor, CIRISPersist#1046).
CSD-108 generalises it to four selection kinds under the
GLOBAL trust root's `takedown` duty. It is a separate card under Accord, not a
fifth rung, because a community's `slash` and the root's `takedown` are two
authorities (CSD-108 §1.1).

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A member of a community can ask whether that community has a moderator at all,
file a moderation event under a duty they actually hold, and — holding the slash
duty — take a graded enforcement act whose blast radius they saw and whose
stated limits they read before they signed.** Three claims, and the third is a
brake on other people's participation.

Serves **Justice** and **Non-maleficence**, and rests on two CC clauses that
pull in opposite directions:

* **CC 4.5.4** — no unmoderated multi-party space, ever. The named-moderator
  existence invariant returns one of three verdicts (`operate` / `auto_promote`
  / `quiesce`) and **fails secure**: better no group than an unmoderated one.
* **CC 4.5.5** — moderation is a delegable *duty*, never a role. An action is
  admitted iff the signer holds the duty as-self or on a live `delegates_to`
  chain; absence of a principal field is **not** an admit condition.

**This screen carries a brake, so it must prove it draws one** (the plan's
G1). The four things a person needs to see before signing — the verdict, the
blast radius, the "what this does NOT reach" sentence, and the refusal when the
node says no — each carry a test tag (§2.3). A safety surface that silently
fails to render is worse than one that is absent (CSD-003 §1); a *brake* that
silently fails to render its limits is worse still, because the operator signs
anyway. The ladder itself is not this app's opinion: it is read from the node
(`GET /v1/operations`, §3).

## 2. Surface (what)

```yaml csd:surface
surface: moderation
screen: Moderation
```

`nav_map` derives `circle_local_community -> tab_safety ->
nav_epistemic_moderation` — Local community › Safety, placed for `NEIGHBOURS_OUT`
(`CirclesNav.kt:99`), and the chain ends on the row because Child Safety shares
that tab. Not in Just me and not in Family: a duty is exercised over a
*community*, and there is no community in those two circles for
`is_named_moderator(·, C, moderate)` to resolve against.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "duty:{kind}"
    bind: {kind: moderate}
    use: emit
    type: "enum[moderate,takedown,review]"
    example: "moderate"
    renders: "the duty radio — Moderate / Takedown / Review; the one you are acting under"
    tag: duty_moderate
  - ceg: "moderation:{allegation_type}"
    bind: {allegation_type: harassment}
    use: emit
    type: string
    example: "harassment"
    renders: "Allegation type — harassment (free vocabulary; the node signs, this app holds no keys)"
    tag: input_allegation_type
  - ceg: x_private:named_moderator_verdict
    use: display-only
    type: "enum[operate,auto_promote,quiesce]"
    example: "quiesce"
    renders: "This community has no moderator and must not operate at moderated capability. A verdict outside the closed set renders as 'not cleared to operate', never as itself"
    tag: txt_named_moderator_verdict
    assert:
      one_of: {txt_named_moderator_verdict: [operate, auto_promote, quiesce]}
  - ceg: x_private:named_moderator_fails_secure
    use: display-only
    type: bool
    example: true
    renders: "Better no group than an unmoderated one — this verdict fails secure. (CC 4.5.4 rule 3)"
    tag: txt_named_moderator_fails_secure
  - ceg: "moderation_track_record:{community_key_id}"
    bind: {community_key_id: wa-comm-4f19c2}
    use: display-only
    type: float
    range: unconfirmed
    example: 0.82
    renders: "Candidate: wa-mem-9b02. The node names who, and not yet the track record that chose them — the row carries the candidate only until the verdict body carries the record"
    tag: row_named_moderator_candidate
    blocked_by: CIRISServer#665
  - ceg: x_private:moderation_attestation_id
    use: display-only
    type: string
    example: "att-6f21c40a"
    renders: "Filed — att-6f21c40a"
    tag: txt_moderation_filed_id
  - ceg: "quarantine:{state}"
    bind: {state: active}
    use: emit
    type: string
    example: "active"
    renders: "Tier 2 — Quarantine. Filed under a community's authority; it deletes nothing."
    tag: chip_ladder_quarantine
  - ceg: "slashing:{outcome}"
    bind: {outcome: proven_rogue}
    use: emit
    type: string
    example: "proven_rogue"
    renders: "Tier 3 — Descend. There is no un-descend."
    tag: chip_ladder_descend
  - ceg: "revocation:{entity_type}:{reason}"
    bind: {entity_type: agent, reason: deadmit}
    use: emit
    type: string
    example: "deadmit"
    renders: "Tier 4 — De-admit, bounded by a revocation history date"
    tag: chip_ladder_deadmit
  - ceg: x_private:selection_hash
    use: display-only
    type: string
    example: "9f2c40ab77e1…"
    renders: "Selection hash 9f2c40ab77e1 — the commit submits THIS hash over THIS selection"
    tag: txt_ladder_selection_hash
  - ceg: x_private:preview_row_count
    use: display-only
    type: int
    example: 9811
    renders: "9,811 rows across 3 keys"
    tag: txt_ladder_preview_counts
  - ceg: x_private:enforcement_limits
    use: display-only
    type: string
    example: "Quarantine marks; it deletes nothing and does not evict held bytes."
    renders: "What this reaches / What it does NOT reach — the node's own sentences, before the signature. ALWAYS on the sheet: a rung the node states no separate limit for says so, rather than the block vanishing"
    tag: txt_ladder_not_reached
  - ceg: x_private:operation_scope
    use: display-only
    type: string
    example: "slash"
    renders: "Tier 2 · requires the slash delegation scope — the SERVED scope from GET /v1/operations, never the compiled one; a rung the node names no scope for says that"
    tag: txt_ladder_scope
  - ceg: x_private:reaches_substrate
    use: display-only
    type: bool
    example: true
    renders: "This changes what the substrate accepts: the door is shut, not just noted. / This changes only what this node records. Drawn only when the node stated it; the compiled fallback has no such column"
    tag: txt_ladder_reaches_substrate
  - ceg: x_private:owner_delegation_id
    use: emit
    type: string
    example: "att-owner-serve-1"
    renders: "One chip per delegation of the owner's that the node returns AND that carries this rung's scope (`GET /v1/admin/self` → `owner_delegations`, 0.5.218). Exactly one usable row is prefilled; a typed id is never overwritten; a row the node would refuse (`authority_scope_absent`) is not offered, and the sentence says which scopes the returned rows do carry"
    tag: chip_ladder_delegation_0
```

**`duty:{kind}`, `moderation:{allegation_type}`, `quarantine:{state}`,
`slashing:{outcome}` and `revocation:{…}` are all NON-reserved in the registry**,
so `use: emit` loads rather than failing — and that is the constitutionally
correct reading: CC 4.5.5 gates these on the *chain*, not on the prefix. The
node walks `delegates_to` upward from `attesting_key_id` and refuses if neither
(a) as-self nor (b) delegated holds. This client cannot pre-empt that check and
does not try to; it renders the refusal.

**The existence verdict is `x_private:` and that is not a shortcut.** CC 4.5.4's
`is_named_moderator(K, C, duty)` is a *resolution over existing rows* —
`delegates_to` + `community_id` + the CC 3.2 authority set — not a family. The
registry has no prefix for it because there is nothing new on the wire, which is
the clause's own "no new structural primitive" claim working. The UI still has
to name the value it draws.

**`moderation_track_record` carries `range: unconfirmed`** for CSD-004's reason:
the registry gives it `polarity: signed` and no bounds. The card does not render
it at all (§2.3), so nothing depends on the range.

```yaml csd:states
populated: {tag: txt_named_moderator_verdict, renders: "the community's verdict, the fail-secure line under it, and the report form below"}
empty:     {tag: txt_named_moderator_none, renders: "No community asked about yet. Type a community key and press Check."}
loading:   {tag: spinner_named_moderator, renders: "the Check button carries the progress affordance and the verdict area stays BLANK — never the empty sentence"}
error:     {tag: txt_named_moderator_error, renders: "Could not ask this node about that community ({detail}). This is NOT a report that it has a moderator. A node without the route draws `txt_named_moderator_not_on_this_node` instead: a fact about the node, in the neutral tone"}
```

**`error` and `empty` are different pixels.** A failed `loadNamedModerator()`
never draws like a community never asked about: the read has its own field
(`SafetyState.namedModeratorFailure`, a `ReadFailure`), the verdict area draws
one of four things by tag, and a new
community key drops the last community's verdict the moment it is typed. The
mission's distinction — "this community has no moderator" versus "we could not
ask" — is expressible, and on a fail-secure invariant those two are opposites.
`ModerationCatalogueTest` pins it; the shared `state.error` line is tagged
`txt_moderation_error` and carries only the report form's failures.

### 2.1 What is on this screen that CC does not put here

The screen carries a **fourth** duty vocabulary its own radio cannot express.
CC 4.5.5's scope table has four rows — `moderate`, `takedown`, `review`,
**`slash`** — and the enforcement ladder's tiers 2–4 every one require
`LADDER_SCOPE_SLASH` (`models/AdminLadder.kt:500,516,529`). The radio at the top
of the screen offers three. Filing a report and pulling a brake are two
different authorities sharing one card, and only one of them is named.

That is not an argument for adding a fourth radio button — a ModerationEvent is
a `moderate` act and three is right for it. It is an argument for the ladder
card saying which duty it is exercising, which it does not.

### 2.2 What is NOT on this screen that CC puts on the person

**Anyone may propose a moderation action** (CC 4.5.13, the open-labeling path):
a report → `scores` Contribution against a target, opening the 48-hour window.
That control exists — `ModerationProposalSheet.kt`, three actions
(`btn_moderation_action_report` / `_takedown` / `_question`) — and it is mounted
on **`InteractScreen.kt:765`**, the chat surface in Just me › Chats. So the
circles that carry the Moderation card carry only the duty-holder's half, and
the half CC opens to every member is reachable only from a conversation.

A person in Local community › Safety who does not hold `moderate` has, on this
screen, nothing they are permitted to do.

What the sheet does while the route does not exist
(`blocked_by: CIRISServer#665`):

* It posts to the **node** (`{nodeUrl}/v1/safety/reports`), where the FSD
  puts `src/safety/report.rs`, not to `$baseUrl` (the agent on a with-AI
  install, which does not proxy it).
* The content id it is handed is sent as `target_id`, not as `target_key_id`
  — a message id is not a key. The author's
  key rides as `target_key_id` only when the caller knows it (today, never).
* A 404 is `txt_moderation_proposal_not_on_this_node`: "this node can't take
  a community proposal yet (needs CIRISServer#665)", the submit button
  disabled, nothing filed. Not a failed submit (`txt_moderation_proposal_error`).
* **The minor gate.** Before any request the sheet reads this person's age
  assurance (`GET /v1/safety/age-assurance/{self}`); a RECORDED `minor` band is
  refused on the device (`txt_moderation_proposal_refused_minor`) with the
  steward path named (CC 3.4.13). No record is not a refusal: CC 4.5.13 says
  *anyone* may propose, the node adjudicates, and locking every unrecorded
  person out of reporting harm would serve nobody the gate protects.
  `ModerationCatalogueTest` shows the refused case makes no request at all.
* Its copy is in the bundle (`moderation.proposal.*`) and its spinner uses a
  token.

### 2.3 The tags G1 needs, and why each one

G1 is "moderation exposure contract: test tags before any brake UI ships". This
is that list. Every row is a value the screen renders, and **every tag is
real**. The `why` column is why a flow cannot go without it.

| tag | what it carries | why the flow cannot go without it |
|---|---|---|
| `screen_moderation` | the screen root (the scroll column) | "the Moderation card composed", so a blank screen and a working one differ by more than what a flow fails to find (CSD-003 §2) |
| `txt_named_moderator_verdict` | `operate` \| `auto_promote` \| `quiesce`, as its text | the CC 4.5.4 verdict; `one_of` is enforceable, and a fourth value renders as "not cleared to operate", never as itself |
| `txt_named_moderator_fails_secure` | the fail-secure sentence | CC 4.5.4 rule 3 is the reason `quiesce` is not a failure report |
| `txt_named_moderator_none` | the never-asked state | distinguishes "no community named" from "no moderator" |
| `txt_named_moderator_error` / `_not_on_this_node` | the read failure / the absent route | distinct from the never-asked state, in two tones |
| `spinner_named_moderator` | the in-flight read, on the button | the verdict area is blank while loading, never the empty sentence |
| `txt_moderation_filed_id` | the returned attestation id | the only evidence the report landed |
| `row_named_moderator_candidate` | the auto-promote candidate | who, without why: the track record is not in the body yet (§3) |
| `txt_ladder_preview_counts` | rows (as text) + targets | `number:` can read a blast radius out of it |
| `txt_ladder_not_reached` | the "does NOT reach" block, always present | **the single most important tag on this screen.** Beside it: `txt_ladder_reaches`, `txt_ladder_undone`, `txt_ladder_reaches_substrate`, and `txt_ladder_scope` on the card |
| `txt_ladder_irreversible` | tier 3's banner | "there is no un-descend" is asserted, not just coloured |
| `txt_ladder_limits_unavailable` | the missing-bundle fallback | a limit the app cannot state is its own fact; it is tagged as such wherever a limit block falls back |
| `txt_ladder_catalogue_fallback` | the compiled ladder standing in | a compiled ladder shown as the node's is the drift `/v1/operations` exists to end |
| `chip_ladder_delegation_{i}` / `txt_ladder_delegations_*` | the owner's usable delegation ids, or why there are none | the id every act is taken under, from the node, not from a paste |
| `card_moderation_flag` / `btn_flag_subject` / `txt_flag_id` | the Flag card, its send, and the returned attestation id | the only evidence a flag was recorded; the card says a flag hides nothing yet |
| `txt_file_takedown_unavailable` | "taking a file down by legal basis isn't available yet" | a blocked act (CIRISPersist#1160) is said, not left out |

**What is not offered at 0.5.245.** No room window, 2/3 threshold, expected-active base or 48-hour fallback is enforced until persist v62 (CIRISPersist#1173), so no surface may promise one: the bundle's "The 48-hour community window is open" stays unshown. `POST /v1/safety/reports` and `POST /v1/safety/moderation` are not offered for a room, and `POST /v1/safety/flag` only to a founder or a delegated holder once persist 61.0.2 narrows the duty reads (#1195).

### 2.4 Flags, and moderation from an item's menu

**The Flag card** (`POST /v1/safety/flag`, CC 4.5.13): a moderator records that
a subject key's content was `reported` or is an `infohazard` in this community,
or clears an earlier flag. The node admits it only from a holder of the
`moderate` duty and refuses anyone else 403, rendered by id. **A flag is
recorded and hides nothing yet**: no listing, file read or transcript honours it
before ciris-server 0.5.243, and the card says so.

**From an item's menu.** A chat message's ⋮ (CSD-091) and a room file's receipt
(CSD-007) offer *Moderate the author*, which opens this screen with the report
form prefilled (`SafetyViewModel.prefillFromItem`): the community is the room
and the target is the item's AUTHOR key, because a moderation event is about a
key. For a viewer who does not hold `moderate` in the room it is drawn
unavailable, "only a moderator of this room".

**Flag and clear an item (server 0.5.246).** The same menu and receipt let a
holder of the room's `moderate` duty flag THE ITEM, a message or a room file,
and clear a live flag:

- *Flag as reported…* and *Flag as potentially harmful…*
  (`mi_chat_flag_reported_{id}`, `mi_chat_flag_infohazard_{id}`;
  `btn_file_act_flag_reported_{id}`, `btn_file_act_flag_infohazard_{id}`). Each
  opens a confirm (`sheet_moderation_flag`) with three facts: the class, that
  every reader (the moderator and the author included) sees a notice in its
  place until they choose to see it and nothing is deleted, and that the moderator's own key signs it. Confirm sends
  `POST /v1/chat/{room}/items/{attestation_id}/flag {"class": "reported" | "infohazard"}`.
  It is confirmed because it widens: it changes what every member reads.
- *Clear the flag* (`mi_chat_clear_flag_{id}`, `btn_file_act_clear_flag_{id}`),
  drawn while the item's `moderation.flag` is set, sends
  `POST /v1/chat/{room}/items/{item_attestation_id}/clear` in one step. A clear
  narrows, so no confirm stands in front of it. The flag's history stays. The id
  is `moderation.item_attestation_id`, the version that carries the flag, since a
  flag on an earlier version of an edited message holds every later one; the
  current id there answers 409 `moderation.nothing_to_clear`.

They are drawn only for a moderator and never on the viewer's own message: the
node refuses anyone else (`moderation.not_a_moderator`), and a list of acts a
member may not take helps nobody. A class is offered only where flagging it
changes what readers see: the live class is not offered again, since flagging
it twice signs nothing, and *reported* is not offered under a live
*potentially harmful*, because the more severe class leads and the row would
change nothing. To move down, a moderator clears and then flags. After either act the screen reads the
transcript or the listing again and draws the node's `moderation` view; it
keeps nothing from the response. A refusal is drawn by its id:
`moderation.not_a_moderator`, `moderation.nothing_to_clear`,
`moderation.unknown_class`, `moderation.flag_failed`,
`moderation.delegate_may_not_moderate` (a delegate session may do neither).
**Not observed on a node**: read from CIRISServer `adopt/edge-v45` `05cbe665`
(`src/contacts_chat.rs`) and checked by view-model tests against a fake.
Until 0.5.246 this menu's flag item went to `POST /v1/safety/flag` on the
author's key, signed by the node, which a room's readers ignore. Moderating another member's room file (withdrawing or
hiding it) **does not work yet**: no route lets a moderator withdraw a file they
did not author, and no read consults moderation events (§3).


**Reveal (server 0.5.243).** Flags come only from holders of the room's
`moderate` duty. A withheld message shows why and offers Reveal
(`btn_moderation_reveal_{id}`); a confirm says it opens the item for this viewer,
on their own devices, and nobody else, signed as their own consent to this one
item, `POST /v1/chat/{room}/items/{item_attestation_id}/reveal`. The note beneath
(`chat_moderation_note_{id}`) gives the flag, or that it was cleared, and any
appeal's standing. Delegates are refused.

**Appeal and ruling (server 0.5.246, CC 4.5.5).** A flag can be appealed, and a
reviewer of the room rules on the appeal:

- **Appeal.** The AUTHOR of a flagged message, on their own message, and a
  holder of `review` on anyone's, sees three items, one per grounds: *there is
  new evidence*, *the process wasn't followed*, *the people who decided were
  compromised* (`mi_chat_appeal_{grounds}_{id}`). Each opens a confirm
  (`sheet_moderation_appeal`): the grounds; that a reviewer of this room is
  asked to rule and the flag stays until they do; and that the person's own key
  signs it. Confirm sends
  `POST /v1/chat/{room}/items/{item_attestation_id}/reconsider {"grounds": "new_evidence" | "procedural_error" | "quorum_compromise"}`.
  It is hidden while an appeal is already `filed` and offered again after a
  ruling that left the flag (`upheld`, `partial`); the node admits a new filing
  then. It is the one moderation act drawn on the viewer's own message.
- **Ruling.** While `moderation.reconsideration` is `filed`, a holder of
  `review` sees three items, one per outcome
  (`mi_chat_rule_{outcome}_{id}`): *the flag stays* (`upheld`), *the appeal is
  right in part, and the flag stays* (`partial`), *lift the flag* (`reversed`).
  Each opens a confirm (`sheet_moderation_rule`) and sends
  `POST /v1/chat/{room}/items/{item_attestation_id}/rule {"outcome": "upheld" | "partial" | "reversed"}`.
  Only `reversed` changes what anyone reads: it lifts the flag for every
  member. `partial` is recorded and applies nothing narrower, so the screen
  says the flag stays and never "in part".
- **The same person may hold both duties.** In a founder-moderated room the
  founder holds `moderate` and `review`, so whoever flagged may also rule. The
  Constitution promises recused reviewers and has not defined them for a room.
  When the reviewer also moderates, the confirm's note
  (`moderation_rule_note`) says this may be a ruling on their own flag and that
  the room has no separate-reviewer rule yet. It does not stop them.
- **On a room file** the same acts sit on the receipt
  (`btn_file_act_appeal_{grounds}_{id}`, `btn_file_act_rule_{outcome}_{id}`).
  The files screen does not know whose file it is, so an appeal is offered on
  any flagged file and the node says who has standing
  (`moderation.no_standing`), as it does for rename and withdraw.

Refusals are drawn by id: `moderation.nothing_to_appeal`,
`moderation.no_standing`, `moderation.unknown_grounds`,
`moderation.not_a_reviewer`, `moderation.unknown_outcome`,
`moderation.nothing_to_rule`, `moderation.appeal_failed`. **Not observed on a
node**: read from CIRISServer `adopt/edge-v45` `e9866938` and checked by tests
against a fake. Takedown is not in 0.5.246 (CIRISPersist#1260) and stays drawn
unavailable.

## 3. Contracts (who)

Verified against ciris-server 0.5.217, route literals in `src/`.

| value | endpoint | owner | state |
|---|---|---|---|
| file a ModerationEvent | `POST /v1/safety/moderation` | CIRISServer `src/safety/moderation.rs:492` | **live** |
| named-moderator verdict | `GET /v1/safety/named-moderator/{community_key_id}` | CIRISServer `src/safety/named.rs:434` | **live** |
| ladder preview | `POST /v1/admin/preview` | CIRISServer `src/admin_ops.rs:4263` | **live** — `CIRISApiClient.adminPreview` (`CIRISApiClient.kt:1152`) |
| tier 0 — annotate | `POST /v1/admin/annotate` | CIRISServer `src/admin_ops.rs:4264` | **live** — every rung below is posted by `adminLadderCommit` (`CIRISApiClient.kt:1202`, `POST {nodeUrl}{op.route}`) with the route taken from `AdminLadderOp` (`models/AdminLadder.kt:476`) |
| tier 1 — throttle | `POST /v1/admin/throttle` | `src/admin_ops.rs:4265` | **live** — `AdminLadder.kt:483` |
| tier 1 — release | `POST /v1/admin/un-throttle` | `src/admin_ops.rs:4266` | **live** — `AdminLadder.kt:490` |
| tier 2 — quarantine | `POST /v1/admin/quarantine` | `src/admin_ops.rs:4268` | **live** — `AdminLadder.kt:498` |
| tier 2 — release | `POST /v1/admin/un-quarantine` | `src/admin_ops.rs:4272` | **live** — `AdminLadder.kt:506` |
| tier 3 — descend | `POST /v1/admin/descend` | `src/admin_ops.rs:4275` | **live** — `AdminLadder.kt:515`; quorum 2, no inverse. Its payload leg calls `Engine::evict_actor` per target today. **Moving onto `purge_rows` by actor** (CIRISPersist#1046, answer 5; v54.1.0), with `evict_actor` still the bytes-plane primitive underneath. The route, its community `slash` authority and this rung are unchanged. The generic, trust-root form of the same door is CSD-108 |
| tier 4 — de-admit | `POST /v1/admin/deadmit` | `src/admin_ops.rs:4276` | **live** — `AdminLadder.kt:525` |
| tier 4 — re-admit | `POST /v1/admin/re-admit` | `src/admin_ops.rs:4277` | **live** — `AdminLadder.kt:533` |
| tier 4 — refuse writes | `POST /v1/admin/refuse-writes` | `src/admin_ops.rs:4280` | **live** — `AdminLadder.kt:541` |
| tier 4 — accept writes | `POST /v1/admin/accept-writes` | `src/admin_ops.rs:4284` | **live** — `AdminLadder.kt:550` |
| **the ladder itself, as data** — op, route, tier, scope, quorum, inverse, `reaches_substrate` | `GET /v1/operations` | CIRISServer `src/operations_catalogue.rs:50` (ROUTE), rows `:82-182`; ungated | **live and READ** (CIRISClient#109). `AdminLadderViewModel.load()` → `CIRISApiClient.getOperations` → `LadderRung.fromCatalogue` (`models/NodeCatalogue.kt`): the chips, tier, scope, quorum floor, reversal pair and `reaches_substrate` are the served row's, joined to this app's labels and message ids by op token (`AdminLadderOp.wireOp`; `throttle_release`, `de_admission` are not derivable from the enum name). A served op with no compiled entry is still a rung, under its token; the commit posts to the SERVED route. A node without the route keeps the compiled ladder and says so (`txt_ladder_catalogue_fallback`, in the error tone); a failed read likewise, with its detail. `NodeCatalogueTest` + `ModerationCatalogueTest` are the acceptance tests the issue asked for |
| the scopes a delegation may carry | `GET /v1/vocabulary` → `delegation_scope.moderation` | CIRISServer `src/vocabulary_surface.rs:50`; ungated | **live and read by CSD-090** (`getVocabulary`, CIRISClient#108). On THIS card the duty radio (`ModerationDuty`, `models/safety/SafetyModels.kt:135`) is the request enum of `moderation.rs::Duty` and is correct to be fixed; the ladder's per-rung scope now comes from the `/v1/operations` row above, so `LADDER_SCOPE_*` survive only in the compiled fallback |
| **the owner's usable delegation ids** | `GET /v1/admin/self` → `owner_delegations[]` | CIRISServer `src/admin_ops.rs:3348` (read), `owner_serve_delegations` `:3470-3526`, on `integ/0.5.218` (CIRISServer#676) | **live in 0.5.218, read.** `OwnerDelegationDto` (`models/selfreader/SelfAndReaderOps.kt`); the picker is `chip_ladder_delegation_{i}`. **On 0.5.218 every returned row carries `infra:serve`** (`REQUIRED_SCOPE_SELF_DIRECTED`, `:286`): that is the tier S/R scope, and tiers 0–4 require the NAMED row to carry `review`/`moderate`/`slash` itself (`resolve_authority` `:1039`, `authority_scope_absent`). So on this ladder the node's list will, today, carry nothing usable, and the card says exactly that (`txt_ladder_delegations_none_usable`: "returned N delegation(s) carrying infra:serve; none carries slash") rather than offering an id the node would refuse. A node before 0.5.218 returns no field (`txt_ladder_delegations_not_returned`). Upstream ask, not yet filed: return the owner's `review`/`moderate`/`slash` rows too, or let an owner session resolve the delegation (CIRISServer#676 option 2) |
| the caller's key | `GET /v1/federation/self-key-record` | CIRISServer | live — `SafetyViewModel.probeIdentityAndStatus`; the `signer_key_id` on the report |
| the caller's protective posture | `GET /v1/safety/status/{key_id}` | CIRISServer `src/safety/age.rs` | live — read on open; rendered by CSD-066 |
| whose posture | `GET /v1/setup/owned-nodes` (`owner`) | CIRISServer | live — the same probe: the posture's subject is the owner's fed-ID, not the node key (CSD-066 §3). The node key stays this card's `signer_key_id` |
| the proposer's age band (§2.2's minor gate) | `GET /v1/safety/age-assurance/{key_id}` | CIRISServer `src/safety/age.rs` | live — read by `ModerationViewModel` before a proposal; that view model is mounted on Interact, so the route checker files it under CSD-091's screen, not this one |

The line numbers above are 0.5.217's; on `integ/0.5.218` the admin rungs are at
4360-4380 with the same set, and `/v1/operations` and `/v1/vocabulary` are
byte-identical.
| the candidate's track record | **missing** — the verdict body carries `candidate_key_id`, not `moderation_track_record` | CIRISServer | blocks `row_named_moderator_candidate` |
| **anyone-may-propose** (CC 4.5.13) | `POST /v1/safety/reports` — **missing** | CIRISServer | `blocked_by: CIRISServer#665`. The client calls it (`CIRISApiClient.proposeModeration`, on the NODE url); `src/safety/report.rs` is specified in CIRISServer `FSD/MODERATION_CHILD_SAFETY.md` §4.4 and does not exist on `origin/main` or `integ/0.5.218` (`src/safety/` is six files, no `report.rs`; no route literal). The sheet says so in words (§2.2) |
| flag or clear a subject | `POST /v1/safety/flag {signer_key_id, community_key_id, subject_key_id, content_class: infohazard\|reported, action: flag\|clear}` | CIRISServer `src/safety/infohazard.rs` `flag` | **live, recorded only** (CC 4.5.13): duty-gated, a non-moderator is refused 403. The row is written, but no reader honours it yet: `GET /v1/drive`, `GET /v1/files/{id}` and the chat transcript still list, serve and show flagged content (the read side is planned for 0.5.243: a `moderation` object on flagged entries, the bytes or body behind a reveal). The card says it does not hide anything. `card_moderation_flag`; reached also from a chat message's or room file's menu, prefilled with the room and the author (`SafetyViewModel.prefillFromItem`) |
| flag or clear one room item | `POST /v1/chat/{room}/items/{attestation_id}/flag {class?: reported\|infohazard}` · `POST /v1/chat/{room}/items/{item_attestation_id}/clear` | CIRISServer `src/contacts_chat.rs` (`adopt/edge-v45` `05cbe665`, for 0.5.246) | **live on that commit, not yet on a tag**. Signed by the person's own key after `is_named_moderator(room, moderate)`; a non-holder is 403 `moderation.not_a_moderator`. Answers `{attestation_id, decision_attestation_id?, moderation}`. The read side withholds the item from every reader until each reveals it |
| appeal a flag, and rule on the appeal | `POST /v1/chat/{room}/items/{item_attestation_id}/reconsider {grounds}` · `POST /v1/chat/{room}/items/{item_attestation_id}/rule {outcome}` | CIRISServer `src/contacts_chat.rs` (`adopt/edge-v45` `e9866938`, for 0.5.246) | **live on that commit, not yet on a tag**. A filing is the flagged item's author's or a `review` holder's; a ruling is a `review` holder's only and answers the latest filing. Answers `{attestation_id, appeal_attestation_id, moderation}`. `reversed` lifts the flag for every member; `partial` changes nothing a reader sees |
| moderate another member's room file (withdraw / hide) | the moderation event above, on the file's author | CIRISServer | **does not work yet**: no route lets a moderator withdraw or hide a file they did not author (withdraw is author-only), and moderation events are not consulted by any read. Planned for 0.5.243 with the flag read side |
| take down a file by legal basis (withheld or destroyed on every node, counter-notice, then delete) | none yet | CIRISPersist · CIRISServer | **blocked_by CIRISPersist#1160**; `txt_file_takedown_unavailable` says so on the card |

**The last row is the gap that matters.** The only moderation path CC opens to a
member who holds no duty has no route behind it. The control is built, the
endpoint constant is written on both sides, and the request 404s — and the
sheet says that is what happened (§2.2).

**The filed report is not a CEG item on this screen.** `POST /v1/safety/moderation`
answers `{attestation_id, duty}` (`src/safety/moderation.rs:413`), no envelope,
so `txt_moderation_filed_id` is a line, not a hamburger (CSD-006 §1: a row
without the five facts is furniture). The ask is the envelope on the
response; without it the card does not pretend.

## 4. Flow (how)

Real tags only. Every step below is drivable against today's build; the steps
this screen most needs are the ones that cannot be written, and they are named
under each block rather than invented.

Land on `Moderation` (the runner walks the derived hop).

```yaml
expect:
  state: empty
  visible: [screen_moderation, input_moderation_community, btn_load_named_moderator,
            txt_named_moderator_none,
            duty_moderate, duty_takedown, duty_review,
            input_allegation_type, btn_file_moderation, card_enforcement_ladder]
  absent:  [txt_ladder_catalogue_fallback]
```

The last line holds on a node that serves `/v1/operations` (every 0.5.217+
node); on an older node it is `visible` instead, and the flow says which node
it was written for.

Type a community key; press `btn_load_named_moderator`.

```yaml
expect:
  state: populated
  visible: [txt_named_moderator_verdict, txt_named_moderator_fails_secure]
  absent:  [txt_named_moderator_none, txt_named_moderator_error]
  one_of:  {txt_named_moderator_verdict: [operate, auto_promote, quiesce]}
```

Against a node where the read fails (the fixture's node stopped), the same
press ends in `txt_named_moderator_error`, never in `txt_named_moderator_none`.

Select tier 2 (`chip_ladder_quarantine`), fill `input_ladder_attesting_key`,
`input_ladder_community_id`, `input_ladder_delegation_id`, `input_ladder_reason`,
then `btn_ladder_preview`.

```yaml
expect:
  visible: [block_ladder_preview, txt_ladder_selection_hash]
  absent:  [txt_ladder_preview_none]
```

Press `btn_ladder_review`.

```yaml
expect:
  visible: [sheet_ladder_confirm, txt_ladder_reaches, txt_ladder_not_reached, txt_ladder_undone,
            txt_ladder_reaches_substrate, btn_ladder_commit, btn_ladder_cancel]
```

The limits and the not-reached sentence are on the sheet, by tag — the
assertion the sheet exists to make. Select tier 3 (`chip_ladder_descend`) and
review again: `txt_ladder_irreversible` is visible and `btn_ladder_commit` is
disabled until `input_ladder_descend_ack` holds the ack word.

Preview with a selection that matches nothing.

```yaml
expect:
  visible: [txt_ladder_preview_empty]
  absent:  [txt_ladder_preview_none, txt_ladder_preview_unreadable]
```

**Those three tags are the model for the rest of the screen.** `previewError` /
`previewRefusal` / no-preview / zero-rows are four distinct facts and three of
them have their own tag (`ModerationScreen.kt:661,670,677`); the
named-moderator half draws its four by tag as well (§2).

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. A person can tell `quiesce` from "we could not ask".
2. A person reads what an op does NOT reach before the signature, not after.
3. The committed hash is the previewed hash, and editing a hash-covered field
   drops the preview.
4. A refusal from the node is shown as a refusal, with the node's own token.

**Unit-tested (desktopTest, against a socket):** `NodeCatalogueTest` (the
served scope wins, a served op is never dropped, the fallback is a ladder) and
`ModerationCatalogueTest` (catalogue absent / unreadable, the owner's
delegation ids prefilled only when one carries the rung's scope and dropped on
a rung change, a typed id never overwritten, a node before 0.5.218, the
named-moderator failure as its own fact, the proposal sheet's 404 and minor
gate). Each was shown red before being believed.

**Not tested here.**
* The chain walk. Whether the signer holds the duty is decided by the node
  (CC 4.5.5 admit-(a)/(b)); no client assertion can stand in for it, and a
  fixture that admitted a non-holder would be testing the wrong machine.
* The 48-hour window (CC 4.5.13) — it is server-side governance and this
  screen renders none of it.
* The proposal path end to end: it has no route on either host (§2.2,
  CIRISServer#665). Its sheet is mounted on Interact, not here.
* `row_named_moderator_candidate` carrying a track record: the verdict body
  has none (CIRISServer#665, ask 2).
