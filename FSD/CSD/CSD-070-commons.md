# CSD-070 — Commons (1-of-N to protect, m-of-n to undo — and eight distinct zeroes)

**CSD**: CSD-070 · **Standard**: CSD/3 (`CSD.md`), CSD/5 (`CSD5.md`, §2.3) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**One member of a cohort can raise a brake on a pending commons act, with one
mandatory sentence and no gathering — and lifting that brake costs the cohort's
own m-of-n, disclosed in three steps whose submit stays shut until a dry run has
produced the exact bytes co-signers must sign.** Serves **Justice** and
**Autonomy**.

The asymmetry is the mechanism, not a policy the client applies. CC 4.5.13 is
the general shape — *presence is authority, absence forfeits it, a timer
decides* — and `reverse_quorum` in persist is what prices it: one objection
raises, `m` distinct roster members dismiss, silence is its own arm, and after
the steward deadline the escalated threshold counts **respondents, not the
roster**. The server's module doc states plainly that none of that arithmetic
lives above the substrate, and this screen holds none of it either; it draws two
controls whose *shapes* are their costs.

**The second half of the mission is the eight zeroes.** `CommonsStanding`
separates eight facts that all read as "nothing is stopping this action": the
plane could not be read, the action is not a row this node holds, the cohort
does not resolve here, the cohort declares no policy, nobody objected, somebody
objected and the window is open, the window closed under the threshold, and the
action is reversed. Four of those carry **no counts at all** (`fold: null`), and
`quiet` — the plane was read and nobody spoke — is deliberately not among them.
*A `0` because nobody spoke and a `0` because nothing could be read must not
render the same.*

## 2. Surface (what)

```yaml csd:surface
surface: commons
screen: Commons
```

`nav_map` derives `circle_global_commons -> tab_decisions ->
nav_epistemic_commons` — Everyone › Decisions (`CirclesNav.kt:126`), beside
Health & Reputation and the environment snapshot: *how the circle is doing and
what it is weighing.* Not under Rules and not under any settings surface,
because `/v1/admin` and `/v1/mesh-config` are authority acting **on** a node and
this is the commons acting on itself; one member is enough to raise a brake, and
no owner privilege is exercised anywhere on the screen.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "objection:{state}"
    use: display-only
    type: "enum[unreadable,action_unknown,cohort_unknown,not_governed,quiet,objected,stood,reversed]"
    example: "objected"
    renders: "OBJECTED — somebody raised a brake and the window is open"
    tag: txt_commons_standing
    assert:
      one_of: {txt_commons_standing: [unreadable, action_unknown, cohort_unknown,
                                      not_governed, quiet, objected, stood, reversed]}
  - ceg: "vote:{contribution_id}"
    bind: {contribution_id: act-8812ab4f}
    use: emit
    type: bool
    example: true
    renders: "Uphold / Overrule — one governing ballot per respondent"
    tag: btn_commons_uphold
  - ceg: "weighted_aggregate:{contribution_id}"
    bind: {contribution_id: act-8812ab4f}
    use: display-only
    type: int
    example: 3
    renders: "3 upholds · 1 overrule, of 5 respondents"
    tag: txt_commons_ballot_tally
  - ceg: x_private:objection_threshold
    use: display-only
    type: int
    example: 1
    renders: "It costs ONE member to raise this. That number is named on every response."
    tag: txt_commons_raise_price
  - ceg: x_private:escalation_respondent_floor
    use: display-only
    type: int
    example: 3
    renders: "and never fewer than 3 respondents — an absolute floor no policy string can lower"
    tag: txt_commons_floor
  - ceg: x_private:dismissal_required
    use: display-only
    type: int
    example: 4
    renders: "Lifting it costs 4 of this cohort's 7 — the cohort's own number, often simply not known here"
    tag: txt_commons_lift_price
  - ceg: x_private:dismissal_payload_sha256
    use: display-only
    type: string
    example: "c40a9f2c…"
    renders: "the dry run's canonical bytes — co-signers sign exactly this"
    tag: txt_commons_dry_run_hash
  - ceg: x_private:escalation_standing
    use: display-only
    type: "enum[not_adopted,nothing_to_escalate,awaiting,open]"
    example: "awaiting"
    renders: "Escalation · AWAITING — the appointed moderators have not answered yet"
    tag: txt_commons_escalation
  - ceg: x_private:commons_refusal
    use: display-only
    type: string
    example: "objection_roster_unresolved"
    renders: "the refusal token and the server's own sentence for it"
    tag: txt_commons_refusal
```

**`objection:{state}` and `vote:{contribution_id}` are non-reserved**
(`node`/CIRISNodeCore, CC 3.1.9.2 and 3.1.9.3), so `emit` on the ballot loads.
The read is `display-only` because the standing is the substrate's resolution
and this client re-derives none of it.

**Six values are `x_private:` and that is a finding, not a convenience.** The
threshold, the respondent floor, the required-dismissal count, the dry-run
payload hash, the escalation arm and the refusal token are all on the wire, all
load-bearing, and none has a registry family. The `objection:{state}` row's own
registry description says "state lifecycle per the minting cut" and CC 3.1.7 R2
names `objection:{state}` as one of three families that shipped and admitted
before they were registered. The prices of the asymmetry deserve the same
treatment the states got. **Ask: CIRISConstitution — register the reverse-quorum
price vocabulary, or state that it rides `objection:{state}`'s payload and is
deliberately not a dimension.** Filed: CIRISConstitution#110.

```yaml csd:states
populated: {tag: txt_commons_standing, renders: "the standing chip (its value is the arm token), its sentence, and — only on the four counted arms — the counts"}
empty:     {tag: txt_commons_quiet, renders: "Nobody objected. The plane was read and it is clear. (This is NOT an absence.)"}
loading:   {tag: spinner_commons_read, renders: "a progress affordance under the form; no standing chip is drawn"}
error:     {tag: txt_commons_no_counts, renders: "the no-counts block, whose value is the arm: unreadable / action_unknown / cohort_unknown / not_governed. No counts are printed, because none exist. A read that never arrived (transport failure) is txt_commons_error, and a refusal is txt_commons_refusal with the token"}
```

**The model draws this distinction and the screen renders it.** `standingBody()`
(`CommonsScreen.kt`, pure, `CommonsStandingBodyTest`) decides REFUSED /
NO_COUNTS / QUIET / COUNTED: a refusal returns first, the four absence arms and
any arm without a fold print no counts, `quiet` is read-and-clear. This is the
most carefully built zero-discipline in the client.

**And it has tags.** The eight arms are assertable through
`txt_commons_standing` (value = the arm), `txt_commons_no_counts`,
`txt_commons_quiet`, `txt_commons_refusal`, `txt_commons_objectors`,
`txt_commons_raise_price`, `txt_commons_lift_price`, `txt_commons_floor`,
`txt_commons_dry_run_hash`, `txt_commons_ballot_tally` and
`txt_commons_escalation`. Every text field has an input sink and every
button's automation handler runs the same act as a tap.

**One tag says which state the block is in.** `state_commons_standing` wraps
whatever the standing draws and its value is the state token of §2.3: `idle`,
`loading`, `error`, `refused`, `unknown`, `not_on_this_node`, `empty` or
`populated` (`standingState()`, pure, `CommonsStandingStateTest`). A write or a
dry run that did not arrive is `txt_commons_write_error`, apart from
`txt_commons_error`, because the standing under a failed write is still the
answer and stays drawn. What the node said to a write is `card_commons_result`,
whose value is `admitted`, `dry_run` or the refusal token.

### 2.0.1 Every write is confirmed first

Every write on this screen is signed by this node's key on its owner's behalf
(`commons_surface.rs`, `sign_hybrid` over the stamped row) and replicates as a
row to every peer; none can be taken back by the one who made it. Raising a
brake is lifted only by the cohort's own m-of-n, a ballot is one per
respondent, and a dismissal is the m-of-n act itself. Each opens a three-fact
ConfirmSheet first, and the buttons the flow already drives (`btn_commons_object`,
`btn_commons_uphold` / `_overrule`, `btn_commons_dismiss_submit`) open it:

| sheet | which | what changes | who signs |
|---|---|---|---|
| `sheet_commons_raise` → `btn_commons_raise_confirm` | action · cohort key | a brake goes on; it stays until the cohort lifts it with its own threshold; you cannot take it back on your own | this node's key, on your behalf as its owner; the signed row goes to every peer |
| `sheet_commons_ballot` → `btn_commons_ballot_confirm` | action · cohort key | your uphold / overrule of objection *id* is recorded with your grounds; one per respondent, not taken back | the same |
| `sheet_commons_dismiss` → `btn_commons_dismiss_confirm` | action · cohort key | the brake from objection *id* is lifted if the signatures meet the threshold; the node counts, and short means nothing lifts | this node's key with the *n* co-signatures added |

The dry run (`btn_commons_dismiss_dry_run`) is not confirmed: it signs and
submits nothing. UI change, verified by compile; the facts are drawn by the
shared `ConfirmSheet`, whose three-fact rule `confirmFacts` enforces.

### 2.1 The asymmetry is drawn and can be broken silently

`AsymmetryBanner` (`CommonsScreen.kt:174`) renders the raise price as a solid
block with one number and the lift price as a thin outline whose number is often
unknown. The comment says it outright: *"They are never rendered as two buttons
side by side, because a vote count is exactly what this is not."*

That is a visual invariant with no test behind it. `PrimitiveRulesTest.errorNeverLooksLikeEmpty`
exists for the equivalent claim on `StateBlock` (CSD-005 §2); nothing equivalent
pins that raise and lift do not converge on the same treatment.

### 2.2 The three-step lift, and what is not asserted about it

`btn_commons_lift_disclose` → `btn_commons_dismiss_dry_run` →
`btn_commons_dismiss_submit`, with cosigner fields between
(`input_commons_cosigner_key` / `_classical` / `_pqc`,
`btn_commons_add_cosigner`). The dry run is not a convenience: the m-of-n is
unreachable without it, because the co-signers must sign the exact
`payload_sha256` the dry run hands back.

The screen has a tag for every button in that sequence and for the hash it
produces (`txt_commons_dry_run_hash`). **The submit sends the dry run.** The
view model keeps the request the dry run was taken over and the node's stamped
`envelope` (`DismissalDraft`), and `submitDismissal` sends that request with
`dry_run` off, the co-signatures added and the envelope back as
`stamped_envelope`; it takes no grounds from the screen. This is the
preview-then-ratify invariant CSD-065 §2 names on the enforcement ladder
(`txt_ladder_selection_hash`), and `CommonsViewModelTest` pins it.

Before this the submit rebuilt the request from the fields as they then stood,
so the grounds, cohort key or action could be changed under a hash already on
screen, and it never sent `stamped_envelope`. The node has refused a co-signed
dismissal without it since CIRISServer#402 (2026-08-13;
`stamped_envelope_required`, 400), so no lift that needed a second signature
could succeed from this client. Read from the server's source, not observed on
a node: no co-signed dismissal has been run from this client before or since.

**One voice, two hashes.** When nobody co-signs, the node ignores the echoed
envelope and stamps the dismissal again (`post_dismissal`: "ignored rather than
refused"). The row that lands then has another instant, id and
`payload_sha256` than the dry run showed. The screen sends nothing false and
the result card draws the hash the node answered with, which is the hash of
the row that landed; but the hash under step 1 and the hash in the result
differ on this path and agree on the co-signed one. The client echoes the
envelope on every path, so this closes on the node's side with no client
change: CIRISServer#872 asks it to honour the echo whenever it is present. The
dry-run hash is not hidden meanwhile, because it is the bytes a co-signer needs
and the person has not yet said whether there will be one.

What ends a held dry run, and only this:

| event | the dry run and its co-signatures |
|---|---|
| the dismissal grounds are edited to a different sentence | let go |
| the cohort, cohort key or action changes | let go, with the standing |
| a second dry run | replaced; the old signatures go, because each dry run is stamped anew |
| Cancel in step 3 | let go |
| a submission the node admits | let go, and the standing is read again |
| a submission the node refuses, or that never arrives | **kept**: a shortfall is answered by one more signature over the same bytes |
| a re-read that is in flight, fails or is refused | **kept**: none of those says the objection is gone |
| a re-read that answers and no longer names the objection | let go |

### 2.3 The screen as a function of its inputs (CSD/5)

The three blocks below are the whole screen: what it holds, what it draws from
that, and what the person can do. `packaging/check_csd5.py` derives every tag
from them, walks every state they allow, and checks the twelve laws of
`packaging/csd5/ui_primitives.json` in each one (`FSD/CSD5.md`). The same model
is `formal/csd5/CSD070.tla`, and TLC reaches the same 134,928 states on it and
finds no invariant violated. Each write says the row it puts on the node and
whether that row is confirmed; all three here attest an `objection:{state}` row,
which widens, so all three are behind a sheet (L6). `shows:` on a sheet is what
the sheet itself draws of what it sends (L12); the grounds are not on any sheet
and are drawn by the field under it. Where a tag is derived and the Kotlin still carries
an older one, `was:` records the old tag; the Kotlin is renamed card by card,
with the flows and the other repos that drive those tags.

```yaml csd:inputs
context: [window]
local:
  cohort:             {type: enum, values: [family, community, affiliations]}
  cohort_key:         {type: text}
  action:             {type: text}
  raise_grounds:      {type: text, lives_while: "standing = ok"}
  ballot_grounds:     {type: text, lives_while: has_objections}
  ballot:             {type: enum, values: [unset, uphold, overrule], lives_while: "sheet = ballot"}
  lifting:            {type: bool, lives_while: "has_objections or dry_run = taken"}
  dismiss_grounds:    {type: text, lives_while: "lifting or dry_run = taken"}
  cosigner_key:       {type: text, lives_while: "lifting and has_objections and dry_run = taken"}
  cosigner_classical: {type: text, lives_while: "lifting and has_objections and dry_run = taken"}
  cosigners:          {type: enum, values: [none, some], lives_while: "dry_run = taken"}
  result:             {type: enum, values: [none, answer, preview], lives_while: "result != preview or dry_run = taken"}
  write_error:        {type: bool, lives_while: "standing = ok"}
reads:
  standing:
    of: [cohort, cohort_key, action]
    carries:
      refused:    {type: bool}
      arm:        {type: enum, values: [unreadable, action_unknown, cohort_unknown, not_governed, quiet, objected, stood, reversed]}
      fold:       {type: bool}
      escalation: {type: bool}
      objections: {type: bool}
      prices:     {type: bool}
    where:
      # A refusal carries its token and sentence and nothing else (`err()` in commons_surface.rs).
      - "not standing.refused or (standing.arm = unreadable and not standing.fold and not standing.escalation and not standing.objections and not standing.prices)"
      # The four absences send `fold: null` and `escalation: null`.
      - "standing.arm in [quiet, objected, stood, reversed] or (not standing.fold and not standing.escalation)"
      # An objection is named by the fold or by an escalation record.
      - "not standing.objections or standing.fold or standing.escalation"
previews:
  dry_run:
    covers: [cohort, cohort_key, action, dismiss_grounds]
    # A re-read in flight, failed or refused says nothing about the objection; only an answer that no longer names it ends the draft.
    lives_while: "standing != ok or standing.refused or has_objections"
writes:
  # Each lands one new signed row (`objection:raised:v1`, `objection:upheld:v1` or
  # `objection:overruled:v1`, `objection:dismissed:v1`): an attest, which widens, so it is confirmed.
  raise:   {sends: [cohort, cohort_key, action, raise_grounds], row: {family: "objection:{state}", verb: attest}, confirm: true}
  ballot:  {sends: [cohort, cohort_key, action, ballot_grounds, ballot], row: {family: "objection:{state}", verb: attest}, confirm: true}
  dismiss: {sends: [cohort, cohort_key, action, dismiss_grounds], adds: [cosigners], previewed_by: dry_run, row: {family: "objection:{state}", verb: attest}, confirm: true}
defs:
  answered:        "standing = ok and not standing.refused"
  has_objections:  "standing = ok and not standing.refused and standing.objections"
  counted:         "standing = ok and not standing.refused and standing.fold and standing.arm in [quiet, objected, stood, reversed]"
  question_filled: "cohort_key != blank and action != blank"
```

`standing.arm` under a refusal is pinned to one value only so the model does not
count eight copies of one screen; a refusal sends no arm. `standing.prices` is
whether `objection_threshold` and `escalation_respondent_floor` arrived: the node
names them on every answer that is not a refusal, and the model lets them be
absent anyway, because the client must not print a `0` for a number it was not
sent. `quiet` without a fold is allowed for the same reason.

```yaml csd:view
kotlin:
  - client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ui/screens/commons/CommonsScreen.kt
instances:
  - id: standing
    primitive: StateBlock
    of: standing
    states:
      idle:             "standing = idle"
      loading:          "standing = pending"
      error:            "standing = failed"
      refused:          "standing = ok and standing.refused"
      unknown:          "answered and (standing.arm = unreadable or (standing.arm in [quiet, objected, stood, reversed] and not standing.fold))"
      not_on_this_node: "answered and standing.arm in [action_unknown, cohort_unknown]"
      empty:            "answered and standing.arm = quiet and standing.fold"
      populated:        "answered and (standing.arm = not_governed or (standing.arm in [objected, stood, reversed] and standing.fold))"
    was: {loading: spinner_commons_read, error: txt_commons_error, refused: txt_commons_refusal, empty: txt_commons_quiet}
  - {id: arm, primitive: Chip, of: standing, present: answered, was: txt_commons_standing}
  - {id: no_counts, primitive: FieldRow, of: standing, present: "answered and not counted"}
  - {id: objectors, primitive: FieldRow, of: standing, present: counted}
  - {id: escalation, primitive: FieldRow, of: standing, present: "answered and standing.escalation"}
  - {id: ballot_tally, primitive: FieldRow, of: standing, per: objection, present: "has_objections and standing.escalation", was: txt_commons_ballot_tally}
  - {id: dry_run_hash, primitive: FieldRow, of: dry_run, per: objection, present: "lifting and has_objections and dry_run = taken", was: txt_commons_dry_run_hash}
  - {id: write_error, primitive: FieldRow, present: write_error}
  - {id: result, primitive: CardShell, present: "result != none"}

  - {id: raise_price, primitive: Metric, of: standing, shown: "standing = ok and standing.prices", sent: standing.prices, was: txt_commons_raise_price}
  - {id: lift_price, primitive: Metric, of: standing, shown: "answered and standing.fold", sent: standing.fold, was: txt_commons_lift_price}
  - {id: floor, primitive: Metric, of: standing, present: "standing = ok and standing.prices", shown: "standing = ok and standing.prices", sent: standing.prices, was: txt_commons_floor}

  - id: cohort
    primitive: Choice
    var: cohort
    on_change: [{drop: [standing, dry_run]}]
    was: "chip_commons_cohort_$c"
  - {id: cohort_key, primitive: Field, var: cohort_key, on_change: [{drop: [standing, dry_run]}]}
  - {id: action, primitive: Field, var: action, on_change: [{drop: [standing, dry_run]}]}
  - {id: objection_grounds, primitive: Field, var: raise_grounds, present: "standing = ok"}
  - {id: ballot_grounds, primitive: Field, var: ballot_grounds, per: objection, present: has_objections, was: input_commons_ballot_grounds}
  - {id: dismiss_grounds, primitive: Field, var: dismiss_grounds, per: objection, present: "lifting and has_objections", on_change: [{drop: [dry_run]}], was: input_commons_dismiss_grounds}
  - {id: cosigner_key, primitive: Field, var: cosigner_key, per: objection, present: "lifting and has_objections", enabled: "dry_run = taken", was: input_commons_cosigner_key}
  - {id: cosigner_classical, primitive: Field, var: cosigner_classical, per: objection, present: "lifting and has_objections", enabled: "dry_run = taken", was: input_commons_cosigner_classical}
  - {id: cosigner_pqc, primitive: Field, untracked: true, per: objection, present: "lifting and has_objections", enabled: "dry_run = taken", was: input_commons_cosigner_pqc}

  - id: raise
    primitive: ConfirmSheet
    while: "standing = ok and raise_grounds != blank"
    shows: [action, cohort_key]
    confirm:
      - issue: raise
        on_ok: [{set: {raise_grounds: blank, result: answer}}, {ask: standing}]
        on_refused: [{set: {result: answer, write_error: false}}]
        on_fail: [{set: {write_error: true}}]
    was: {fact_1: commons_raise_fact_1, fact_2: commons_raise_fact_2, fact_3: commons_raise_fact_3, note: commons_raise_note, confirm: btn_commons_raise_confirm, cancel: btn_commons_raise_cancel}
  - id: ballot
    primitive: ConfirmSheet
    while: "has_objections and ballot_grounds != blank"
    shows: [action, cohort_key, ballot]
    confirm:
      - issue: ballot
        on_ok: [{set: {result: answer}}, {ask: standing}]
        on_refused: [{set: {result: answer, write_error: false}}]
        on_fail: [{set: {write_error: true}}]
    was: {fact_1: commons_ballot_fact_1, fact_2: commons_ballot_fact_2, fact_3: commons_ballot_fact_3, note: commons_ballot_note, confirm: btn_commons_ballot_confirm, cancel: btn_commons_ballot_cancel}
  - id: dismiss
    primitive: ConfirmSheet
    while: has_objections
    shows: [action, cohort_key, cosigners]
    confirm:
      - issue: dismiss
        on_ok: [{drop: [dry_run]}, {set: {result: answer}}, {ask: standing}]
        on_refused: [{set: {result: answer, write_error: false}}]
        on_fail: [{set: {write_error: true}}]
    was: {fact_1: commons_dismiss_fact_1, fact_2: commons_dismiss_fact_2, fact_3: commons_dismiss_fact_3, note: commons_dismiss_note, confirm: btn_commons_dismiss_confirm, cancel: btn_commons_dismiss_cancel}
```

```yaml csd:acts
acts:
  - {id: back, present: "window = wide", effect: [{leave: true}]}
  - {id: read, enabled: "question_filled and standing != pending", reason: null, effect: [{ask: standing}]}
  - {id: object, present: "standing = ok", enabled: "raise_grounds != blank", reason: null, effect: [{open: raise}]}
  - {id: uphold, per: objection, present: has_objections, enabled: "ballot_grounds != blank", reason: null, effect: [{set: {ballot: uphold}}, {open: ballot}], was: btn_commons_uphold}
  - {id: overrule, per: objection, present: has_objections, enabled: "ballot_grounds != blank", reason: null, effect: [{set: {ballot: overrule}}, {open: ballot}], was: btn_commons_overrule}
  - {id: lift_disclose, per: objection, present: has_objections, effect: [{toggle: lifting}], was: btn_commons_lift_disclose}
  - id: dismiss_dry_run
    per: objection
    present: "lifting and has_objections"
    enabled: "dismiss_grounds != blank"
    reason: null
    effect:
      - set: {cosigners: none, cosigner_key: blank, cosigner_classical: blank}
      - call: dry_run
        on_ok: [{set: {result: preview, write_error: false}}]
        on_refused: [{set: {result: answer, write_error: false}}]
        on_fail: [{set: {write_error: true}}]
    was: btn_commons_dismiss_dry_run
  - {id: add_cosigner, per: objection, present: "lifting and has_objections", enabled: "dry_run = taken and cosigner_key != blank and cosigner_classical != blank", reason: null, effect: [{set: {cosigners: some, cosigner_key: blank, cosigner_classical: blank}}], was: btn_commons_add_cosigner}
  - {id: remove_cosigner, per: objection, present: "lifting and has_objections and cosigners = some", effect: [{set: {cosigners: none}}], was: btn_commons_remove_cosigner}
  - {id: dismiss_submit, per: objection, present: "lifting and has_objections", enabled: "dry_run = taken", reason: surfaces.commons.dismiss_gate, effect: [{open: dismiss}], was: btn_commons_dismiss_submit}
  - {id: dismiss_abandon, per: objection, present: "lifting and has_objections", effect: [{drop: [dry_run]}], was: btn_commons_dismiss_abandon}
  - {id: clear_result, present: "result != none", effect: [{set: {result: none}}]}
```

## 3. Contracts (who)

Verified against ciris-server 0.5.217, route constants in
`src/commons_surface.rs:120-126`.

| value | endpoint | owner | state |
|---|---|---|---|
| the fold's standing on one action | `GET /v1/commons/standing` | CIRISServer `src/commons_surface.rs:1311` | **live** |
| raise a brake (1-of-N) | `POST /v1/commons/objections` | `:1312` | **live** |
| ballot on what was left open | `POST /v1/commons/ballots` | `:1313` | **live** |
| lift a brake (m-of-n) | `POST /v1/commons/dismissals` | `:1314` | **live** |

**Every route is live and the client calls all four** (`CIRISApiClient.kt:3281,
3318, 3357, 3398`). There is no substrate gap on this surface at all, and the
tags are real (§2); what keeps this at `building` is the floor (`unreleased`) and a flow that has not run on the matrix.

**One wire behaviour the client must keep honouring.** `unreadable` (503),
`action_unknown` (404) and `cohort_unknown` (404) arrive on **non-2xx statuses
carrying a full body** — they are answers, not transport failures
(`models/surfaces/CommonsSurface.kt:137-141`). A client that treated non-2xx as
an error would turn three distinct facts into one banner. That is a contract
between the two repos that no test on either side currently pins.

## 4. Flow (how)

Real tags only, which on this screen means the writes and nothing else.

Land on `Commons` (Everyone › Decisions, derived).

```yaml
expect:
  visible: [input_commons_cohort_key, input_commons_action, btn_commons_read, txt_commons_raise_price]
```

Type a cohort key and an action id; press `btn_commons_read`.

```yaml
do:
  - input: {input_commons_cohort_key: "cohort-key"}
  - input: {input_commons_action: "act-8812ab4f"}
  - click: btn_commons_read
expect:
  visible: [txt_commons_standing, input_commons_objection_grounds, btn_commons_object]
  one_of: {txt_commons_standing: [unreadable, action_unknown, cohort_unknown, not_governed, quiet, objected, stood, reversed]}
```

On an `unreadable` answer (a 503 with a body):

```yaml
expect:
  state: error
  visible: [txt_commons_no_counts]
  absent: [txt_commons_objectors, txt_commons_quiet]
```

On `quiet`:

```yaml
expect:
  state: empty
  visible: [txt_commons_quiet, txt_commons_objectors]
  absent: [txt_commons_no_counts]
```

Open the lift disclosure and run the dry run:

```yaml
expect:
  visible: [btn_commons_dismiss_dry_run, input_commons_cosigner_key,
            input_commons_cosigner_classical, btn_commons_add_cosigner]
```

After the dry run, `txt_commons_dry_run_hash` carries the payload hash the
submit sends.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. `unreadable` and `quiet` are visibly different, and only one of them prints
   counts.
2. The raise price and the lift price are never rendered as peers.
3. The dismissal submit is unreachable before a dry run.
4. A refusal shows the substrate's own token, not a paraphrase.

**Status.** Card, CSD and API agree: the tags and eight arms, all four routes
live at the node URL, the non-2xx-with-body contract honoured, and
irreversible writes behind a three-fact ConfirmSheet (§2.0.1). §2.3 is the
screen as a model: `packaging/check_csd5.py` walks 134,928 states of it and the
twelve laws hold in each, and TLC agrees on the generated module; `CommonsViewModelTest` (16) and
`CommonsStandingStateTest` (7) hold the Kotlin to the same rules. Open: the six
`x_private:` prices have no registry family (the ask in §2,
CIRISConstitution#110); the raise/lift visual asymmetry has no test; six
buttons can be disabled and draw no reason (`reason: null` in §2.3, counted in
`packaging/csd5/baseline.json`); the tags inside an objection's card are not
yet keyed by its id, so two objections on screen share them; a dismissal with
no co-signer lands a different hash than its dry run showed (CIRISServer#872);
and no co-signed dismissal has been run against a node from this client. Stage: `building` (the
floor is `unreleased` and no flow has run on the matrix).

**Not tested here.**
* **The thresholds.** `m`, the respondent floor, the window and the escalation
  are decided by `resolve_reverse_quorum` in persist and rendered verbatim.
  Asserting a number here would be re-implementing the rule this screen was
  built to not re-implement — and a second answer that can disagree with the
  first is the defect class the server's module doc calls this repo's dominant
  one.
* **Acceptances 1, 2 and 3**, entirely.
* **The non-2xx-carries-a-body contract** (§3), on either side.
