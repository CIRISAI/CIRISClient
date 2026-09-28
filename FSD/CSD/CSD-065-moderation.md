# CSD-065 — Moderation (the duty, the existence invariant, and the brake)

**CSD**: CSD-065 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1 (S2/G1)
**Flow**: unwritten — every tag in §2.3 is real as of the 2026-09-27 review, so it can be

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

The reason this CSD was written before its UI was changed is stated in the plan
as G1: **this screen already carried a brake and could not prove it drew one.**
The ten `/v1/admin` rungs were wired, the preview→confirm-with-hash pattern was
implemented, and the four things a person needs to see before signing — the
verdict, the blast radius, the "what this does NOT reach" sentence, and the
refusal when the node says no — had **no test tag between them**. A safety
surface that silently fails to render is worse than one that is absent
(CSD-003 §1); a *brake* that silently fails to render its limits is worse
still, because the operator signs anyway. §2.3 lists the tags; all of them are
real now, and the ladder itself is no longer this app's opinion: it is read
from the node (`GET /v1/operations`, §3).

## 2. Surface (what)

```yaml csd:surface
surface: moderation
screen: Moderation
```

`nav_map` derives `circle_local_community -> tab_safety ->
nav_epistemic_moderation` — Neighbours › Safety, placed for `NEIGHBOURS_OUT`
(`CirclesNav.kt:99`), and the chain ends on the row because Child Safety shares
that tab. Not in Just me and not in Family: a duty is exercised over a
*community*, and there is no community in those two circles for
`is_named_moderator(·, C, moderate)` to resolve against.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
it at all today (§2.3), so nothing depends on the range yet.

```yaml csd:states
populated: {tag: txt_named_moderator_verdict, renders: "the community's verdict, the fail-secure line under it, and the report form below"}
empty:     {tag: txt_named_moderator_none, renders: "No community asked about yet. Type a community key and press Check."}
loading:   {tag: spinner_named_moderator, renders: "the Check button carries the progress affordance and the verdict area stays BLANK — never the empty sentence"}
error:     {tag: txt_named_moderator_error, renders: "Could not ask this node about that community ({detail}). This is NOT a report that it has a moderator. A node without the route draws `txt_named_moderator_not_on_this_node` instead: a fact about the node, in the neutral tone"}
```

**`error` and `empty` were the same pixels until 2026-09-27.** A failed
`loadNamedModerator()` set the shared `state.error` and left the verdict null,
which drew nothing at all, exactly like a community never asked about. The
read now has its own field (`SafetyState.namedModeratorFailure`, a
`ReadFailure`), the verdict area draws one of four things by tag, and a new
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

A person in Neighbours › Safety who does not hold `moderate` has, on this
screen, nothing they are permitted to do.

What the sheet does since 2026-09-27, on the way to a route that does not
exist yet (`blocked_by: CIRISServer#665`):

* It posts to the **node** (`{nodeUrl}/v1/safety/reports`), where the FSD
  puts `src/safety/report.rs`, not to `$baseUrl` (the agent on a with-AI
  install, which does not proxy it).
* The content id it is handed is sent as `target_id`, not as `target_key_id`
  — a message id is not a key, and the old body claimed it was. The author's
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
  token; it was hardcoded English over `Color.White`.

### 2.3 The tags G1 needs, and why each one

G1 is "moderation exposure contract: test tags before any brake UI ships". This
is that list. Every row is a value the screen renders; **all eleven are real
as of 2026-09-27** (they were `proposed:` when this CSD was written, which is
why it was `sketched`). The `why` column is why a flow cannot go without it.

| tag | what it carries | why the flow cannot go without it |
|---|---|---|
| `screen_moderation` | the screen root (the scroll column) | "the Moderation card composed", so a blank screen and a working one differ by more than what a flow fails to find (CSD-003 §2) |
| `txt_named_moderator_verdict` | `operate` \| `auto_promote` \| `quiesce`, as its text | the CC 4.5.4 verdict; `one_of` is enforceable, and a fourth value renders as "not cleared to operate", never as itself |
| `txt_named_moderator_fails_secure` | the fail-secure sentence | CC 4.5.4 rule 3 is the reason `quiesce` is not a failure report |
| `txt_named_moderator_none` | the never-asked state | distinguishes "no community named" from "no moderator" |
| `txt_named_moderator_error` / `_not_on_this_node` | the read failure / the absent route | today distinct from the never-asked state, in two tones |
| `spinner_named_moderator` | the in-flight read, on the button | the verdict area is blank while loading, never the empty sentence |
| `txt_moderation_filed_id` | the returned attestation id | the only evidence the report landed |
| `row_named_moderator_candidate` | the auto-promote candidate | who, without why: the track record is not in the body yet (§3) |
| `txt_ladder_preview_counts` | rows (as text) + targets | `number:` can read a blast radius out of it |
| `txt_ladder_not_reached` | the "does NOT reach" block, always present | **the single most important tag on this screen.** Beside it: `txt_ladder_reaches`, `txt_ladder_undone`, `txt_ladder_reaches_substrate`, and `txt_ladder_scope` on the card |
| `txt_ladder_irreversible` | tier 3's banner | "there is no un-descend" is asserted, not just coloured |
| `txt_ladder_limits_unavailable` | the missing-bundle fallback | a limit the app cannot state is its own fact; it is tagged as such wherever a limit block falls back |
| `txt_ladder_catalogue_fallback` | the compiled ladder standing in | a compiled ladder shown as the node's is the drift `/v1/operations` exists to end |
| `chip_ladder_delegation_{i}` / `txt_ladder_delegations_*` | the owner's usable delegation ids, or why there are none | the id every act is taken under, from the node, not from a paste |

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25), route
literals in `src/`.

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
| tier 3 — descend | `POST /v1/admin/descend` | `src/admin_ops.rs:4275` | **live** — `AdminLadder.kt:515`; quorum 2, no inverse |
| tier 4 — de-admit | `POST /v1/admin/deadmit` | `src/admin_ops.rs:4276` | **live** — `AdminLadder.kt:525` |
| tier 4 — re-admit | `POST /v1/admin/re-admit` | `src/admin_ops.rs:4277` | **live** — `AdminLadder.kt:533` |
| tier 4 — refuse writes | `POST /v1/admin/refuse-writes` | `src/admin_ops.rs:4280` | **live** — `AdminLadder.kt:541` |
| tier 4 — accept writes | `POST /v1/admin/accept-writes` | `src/admin_ops.rs:4284` | **live** — `AdminLadder.kt:550` |
| **the ladder itself, as data** — op, route, tier, scope, quorum, inverse, `reaches_substrate` | `GET /v1/operations` | CIRISServer `src/operations_catalogue.rs:50` (ROUTE), rows `:82-182`; ungated | **live and READ** (CIRISClient#109 closed 2026-09-27). `AdminLadderViewModel.load()` → `CIRISApiClient.getOperations` → `LadderRung.fromCatalogue` (`models/NodeCatalogue.kt`): the chips, tier, scope, quorum floor, reversal pair and `reaches_substrate` are the served row's, joined to this app's labels and message ids by op token (`AdminLadderOp.wireOp`; `throttle_release`, `de_admission` are not derivable from the enum name). A served op with no compiled entry is still a rung, under its token; the commit posts to the SERVED route. A node without the route keeps the compiled ladder and says so (`txt_ladder_catalogue_fallback`, in the error tone); a failed read likewise, with its detail. `NodeCatalogueTest` + `ModerationCatalogueTest` are the acceptance tests the issue asked for, red on the old code |
| the scopes a delegation may carry | `GET /v1/vocabulary` → `delegation_scope.moderation` | CIRISServer `src/vocabulary_surface.rs:50`; ungated | **live and read by CSD-090** (`getVocabulary`, CIRISClient#108). On THIS card the duty radio (`ModerationDuty`, `models/safety/SafetyModels.kt:135`) is the request enum of `moderation.rs::Duty` and is correct to be fixed; the ladder's per-rung scope now comes from the `/v1/operations` row above, so `LADDER_SCOPE_*` survive only in the compiled fallback |
| **the owner's usable delegation ids** | `GET /v1/admin/self` → `owner_delegations[]` | CIRISServer `src/admin_ops.rs:3348` (read), `owner_serve_delegations` `:3470-3526`, on `integ/0.5.218` (CIRISServer#676) | **live in 0.5.218, read.** `OwnerDelegationDto` (`models/selfreader/SelfAndReaderOps.kt`); the picker is `chip_ladder_delegation_{i}`. **On 0.5.218 every returned row carries `infra:serve`** (`REQUIRED_SCOPE_SELF_DIRECTED`, `:286`): that is the tier S/R scope, and tiers 0–4 require the NAMED row to carry `review`/`moderate`/`slash` itself (`resolve_authority` `:1039`, `authority_scope_absent`). So on this ladder the node's list will, today, carry nothing usable, and the card says exactly that (`txt_ladder_delegations_none_usable`: "returned N delegation(s) carrying infra:serve; none carries slash") rather than offering an id the node would refuse. A node before 0.5.218 returns no field (`txt_ladder_delegations_not_returned`). Upstream ask, not yet filed: return the owner's `review`/`moderate`/`slash` rows too, or let an owner session resolve the delegation (CIRISServer#676 option 2) |
| the caller's key | `GET /v1/federation/self-key-record` | CIRISServer | live — `SafetyViewModel.probeIdentityAndStatus`; the `signer_key_id` on the report |
| the caller's protective posture | `GET /v1/safety/status/{key_id}` | CIRISServer `src/safety/age.rs` | live — read on open; rendered by CSD-066 |
| the proposer's age band (§2.2's minor gate) | `GET /v1/safety/age-assurance/{key_id}` | CIRISServer `src/safety/age.rs` | live — read by `ModerationViewModel` before a proposal; that view model is mounted on Interact, so the route checker files it under CSD-091's screen, not this one |

The admin rungs moved from 4264-4284 (0.5.217) to 4360-4380 on
`integ/0.5.218` (97900cf5) with no change to the set; `/v1/operations` and
`/v1/vocabulary` are byte-identical in both.
| the candidate's track record | **missing** — the verdict body carries `candidate_key_id`, not `moderation_track_record` | CIRISServer | blocks `row_named_moderator_candidate` |
| **anyone-may-propose** (CC 4.5.13) | `POST /v1/safety/reports` — **missing** | CIRISServer | `blocked_by: CIRISServer#665`. The client calls it (`CIRISApiClient.proposeModeration`, on the NODE url since 2026-09-27); `src/safety/report.rs` is specified in CIRISServer `FSD/MODERATION_CHILD_SAFETY.md` §4.4 and does not exist on `origin/main` or `integ/0.5.218` (`src/safety/` is six files, no `report.rs`; no route literal). The sheet says so in words (§2.2) |

**The last row is the gap that matters.** The only moderation path CC opens to a
member who holds no duty has no route behind it. The control is built, the
endpoint constant is written on both sides, and the request 404s — and the
sheet now says that is what happened, instead of "Moderation proposal failed:
404".

**The filed report is not a CEG item on this screen.** `POST /v1/safety/moderation`
answers `{attestation_id, duty}` (`src/safety/moderation.rs:413`), no envelope,
so `txt_moderation_filed_id` is a line, not a hamburger (CSD-006 §1: a row
without the five facts is furniture). The row to ask for is the envelope on
the response; until then the card does not pretend.

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
them already have their own tag (`ModerationScreen.kt:661,670,677`). The
named-moderator half of the same screen collapses its four into one blank.

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
gate). All shown red against the previous behaviour on 2026-09-27.

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
