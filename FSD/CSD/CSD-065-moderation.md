# CSD-065 — Moderation (the duty, the existence invariant, and the brake)

**CSD**: CSD-065 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1 (S2/G1)
**Flow**: unwritten — §2.3 is the tag list it needs before it can be written

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

The reason this CSD is written before its UI is changed is stated in the plan as
G1: **this screen already carries a brake and cannot prove it drew one.** The
ten `/v1/admin` rungs are wired, the preview→confirm-with-hash pattern is
implemented, and the four things a person needs to see before signing — the
verdict, the blast radius, the "what this does NOT reach" sentence, and the
refusal when the node says no — have **no test tag between them**. A safety
surface that silently fails to render is worse than one that is absent
(CSD-003 §1); a *brake* that silently fails to render its limits is worse
still, because the operator signs anyway.

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
    renders: "This community has no moderator and must not operate at moderated capability."
    tag: "proposed:txt_named_moderator_verdict"
    assert:
      one_of: {txt_named_moderator_verdict: [operate, auto_promote, quiesce]}
  - ceg: x_private:named_moderator_fails_secure
    use: display-only
    type: bool
    example: true
    renders: "Better no group than an unmoderated one — this verdict fails secure. (CC 4.5.4 rule 3)"
    tag: "proposed:txt_named_moderator_fails_secure"
  - ceg: "moderation_track_record:{community_key_id}"
    bind: {community_key_id: wa-comm-4f19c2}
    use: display-only
    type: float
    range: unconfirmed
    example: 0.82
    renders: "Auto-promoting wa-mem-9b02 — highest moderation track record in this community (0.82)"
    tag: "proposed:row_named_moderator_candidate"
    blocked_by: CIRISServer#665
  - ceg: x_private:moderation_attestation_id
    use: display-only
    type: string
    example: "att-6f21c40a"
    renders: "Filed — att-6f21c40a"
    tag: "proposed:txt_moderation_filed_id"
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
    tag: "proposed:txt_ladder_preview_counts"
  - ceg: x_private:enforcement_limits
    use: display-only
    type: string
    example: "Quarantine marks; it deletes nothing and does not evict held bytes."
    renders: "What this reaches / What it does NOT reach — the node's own sentences, before the signature"
    tag: "proposed:txt_ladder_not_reached"
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
populated: {tag: "proposed:txt_named_moderator_verdict", renders: "the community's verdict, the fail-secure line under it, and the report form below"}
empty:     {tag: "proposed:txt_named_moderator_none", renders: "No community asked about yet — type a community key and press Check."}
loading:   {tag: "proposed:spinner_named_moderator", renders: "the Check button carries the progress affordance and the verdict area stays BLANK — never the empty sentence"}
error:     {tag: "proposed:txt_moderation_error", renders: "Could not ask this node about that community. This is NOT a report that it has a moderator."}
```

**`error` and `empty` are the same pixels today.** `state.error` renders as an
untagged red line at the bottom of a long scroll
(`ModerationScreen.kt:291`), while a failed `loadNamedModerator()` leaves
`namedModeratorVerdict` null — which draws nothing at all, exactly like a
community that was never asked about. The mission's own distinction — "this
community has no moderator" versus "we could not ask" — is not expressible on
this surface, and on a fail-secure invariant those two are opposites.

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

### 2.3 The tags G1 needs, and why each one

G1 is "moderation exposure contract: test tags before any brake UI ships". This
is that list. Every row is a value the screen already renders; none is a new
feature. The `code` column is where the untagged element is today.

| tag | what it must carry | code | why the flow cannot go without it |
|---|---|---|---|
| `screen_moderation` | the screen root | `ModerationScreen.kt:129` (the scroll column) | there is no tag that means "the Moderation card composed", so a blank screen and a working one differ only in what a flow fails to find — which is the "element not found ≡ broken screen" confusion CSD-003 §2 names |
| `txt_named_moderator_verdict` | `operate` \| `auto_promote` \| `quiesce` | `:183` (the label Text) | the CC 4.5.4 verdict. Untagged, so `one_of` cannot be enforced and a fourth value would render as itself |
| `txt_named_moderator_fails_secure` | the fail-secure sentence | `:188` | CC 4.5.4 rule 3 is the reason `quiesce` is not a failure report. If it silently stops rendering, `quiesce` reads as an outage |
| `txt_named_moderator_none` | the never-asked state | **absent** — nothing renders | distinguishes "no community named" from "no moderator" |
| `txt_moderation_error` | the read/POST failure | `:291` | today identical to the never-asked state |
| `txt_moderation_filed_id` | the returned attestation id | `:260` | the only evidence the report landed |
| `row_named_moderator_candidate` | candidate + track record | `:178` names the candidate only | CC 4.5.4 rule 2 makes `moderation_track_record` the deterministic selector; naming who without why is an unexplained appointment |
| `txt_ladder_preview_counts` | rows + targets + per-key | inside `block_ladder_preview` (`:687`) | `block_ladder_preview` is one element holding a paragraph; `number:` cannot read a blast radius out of it |
| `txt_ladder_not_reached` | the "does NOT reach" sentence | `LadderLimitBlock` (`:1045`), called four times at `:901-922`, no tag on any of them | **the single most important tag on this screen.** The server states each op's limits and the client resolves them from the bundle before the act; nothing asserts the operator could see them |
| `txt_ladder_irreversible` | tier 3's banner | `:840` | "there is no un-descend" is drawn in an untagged `errorContainer` |
| `txt_ladder_limits_unavailable` | the missing-bundle fallback | `:1060` | a limit the app cannot state is its own fact; it currently looks like a limit |

Nine of the eleven are `proposed:`, which is why this CSD is `sketched` and not
`building`, and why **no flow may be written against them yet** (CSD/3 §1: a
proposed tag may never enter a flow).

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
| **the ladder itself, as data** — op, route, tier, scope, quorum, inverse, `reaches_substrate` | `GET /v1/operations` | CIRISServer `src/operations_catalogue.rs:50` (ROUTE), rows `:82-182`; ungated | **live and never read.** `AdminLadderOp` (`AdminLadder.kt:457-557`) re-derives every column by hand. Today the two agree row for row; nothing fails when they stop agreeing, and the catalogue's `reaches_substrate` ("we noted it" vs "the door is shut") has no client field at all. The card SHOULD render the ladder from this read — a CIRISClient issue is drafted, not yet filed |
| the scopes a delegation may carry | `GET /v1/vocabulary` → `delegation_scope.moderation` | CIRISServer `src/vocabulary_surface.rs:50`; ungated | **live and never read.** The duty radio (`ModerationDuty`, `models/safety/SafetyModels.kt:135`) is the request enum of `moderation.rs::Duty` and is correct to be fixed; the ladder's `requiredScope` constants (`AdminLadder.kt:443-445`) are a picker vocabulary and should come from here. See CSD-090 for the picker this bites hardest |

The admin rungs moved from 4264-4284 (0.5.217) to 4360-4380 on
`integ/0.5.218` (97900cf5) with no change to the set; `/v1/operations` and
`/v1/vocabulary` are byte-identical in both.
| the candidate's track record | **missing** — the verdict body carries `candidate_key_id`, not `moderation_track_record` | CIRISServer | blocks `row_named_moderator_candidate` |
| **anyone-may-propose** (CC 4.5.13) | `POST /v1/safety/reports` — **missing** | CIRISServer | the client calls it (`CIRISApiClient.kt:1778`); `src/safety/report.rs` is specified in CIRISServer `FSD/MODERATION_CHILD_SAFETY.md` §4.4 and does not exist. `git ls-tree origin/main src/safety/` has six files and no `report.rs` |

**The last row is the gap that matters.** The only moderation path CC opens to a
member who holds no duty has no route behind it. The control is built, the
endpoint constant is written on both sides, and the request 404s.

## 4. Flow (how)

Real tags only. Every step below is drivable against today's build; the steps
this screen most needs are the ones that cannot be written, and they are named
under each block rather than invented.

Land on `Moderation` (the runner walks the derived hop).

```yaml
expect:
  visible: [input_moderation_community, btn_load_named_moderator,
            duty_moderate, duty_takedown, duty_review,
            input_allegation_type, btn_file_moderation, card_enforcement_ladder]
```

*Cannot yet assert:* that the screen composed at all (`screen_moderation`).

Type a community key; press `btn_load_named_moderator`.

*Cannot yet assert:* the verdict, its closed set, or the fail-secure line — the
whole point of the step.

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
  visible: [sheet_ladder_confirm, btn_ladder_commit, btn_ladder_cancel]
```

*Cannot yet assert:* that the limits and the not-reached sentence are on that
sheet — which is the assertion the sheet exists to make.

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

**Not tested here.**
* The chain walk. Whether the signer holds the duty is decided by the node
  (CC 4.5.5 admit-(a)/(b)); no client assertion can stand in for it, and a
  fixture that admitted a non-holder would be testing the wrong machine.
* The 48-hour window (CC 4.5.13) — it is server-side governance and this
  screen renders none of it.
* **Every state but the ladder preview's** — nine of the eleven §2.3 tags are
  `proposed:`.
* The proposal path, which is not on this surface (§2.2).
