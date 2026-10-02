# CSD-070 — Commons (1-of-N to protect, m-of-n to undo — and eight distinct zeroes)

**CSD**: CSD-070 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
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

**And it now has tags.** The eight arms are assertable through
`txt_commons_standing` (value = the arm), `txt_commons_no_counts`,
`txt_commons_quiet`, `txt_commons_refusal`, `txt_commons_objectors`,
`txt_commons_raise_price`, `txt_commons_lift_price`, `txt_commons_floor`,
`txt_commons_dry_run_hash`, `txt_commons_ballot_tally` and
`txt_commons_escalation`. Before this revision all twenty tags on the screen
were inputs and buttons, and the write buttons' automation handlers were `{}`:
a flow could click `btn_commons_object` and nothing happened. Every text field
now has an input sink and every button's handler runs the same act as a tap.

### 2.0.1 Every write is confirmed first (review, 2026-09-28)

Every write on this screen is signed by this node's key on its owner's behalf
(`commons_surface.rs`, `sign_hybrid` over the stamped row) and replicates as a
row to every peer; none can be taken back by the one who made it. Raising a
brake is lifted only by the cohort's own m-of-n, a ballot is one per
respondent, and a dismissal is the m-of-n act itself. Before the review all
three went straight out on a tap. Each now opens a three-fact ConfirmSheet
first, and the buttons the flow already drives (`btn_commons_object`,
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

The screen has a tag for every button in that sequence and none for the hash it
produces. So "the submit sends the bytes the dry run disclosed" — the same
preview-then-ratify invariant CSD-065 §2 names on the enforcement ladder, where
it **is** tagged (`txt_ladder_selection_hash`) — is unassertable here.

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25), route
constants in `src/commons_surface.rs:120-126`.

| value | endpoint | owner | state |
|---|---|---|---|
| the fold's standing on one action | `GET /v1/commons/standing` | CIRISServer `src/commons_surface.rs:1311` | **live** |
| raise a brake (1-of-N) | `POST /v1/commons/objections` | `:1312` | **live** |
| ballot on what was left open | `POST /v1/commons/ballots` | `:1313` | **live** |
| lift a brake (m-of-n) | `POST /v1/commons/dismissals` | `:1314` | **live** |

**Every route is live and the client calls all four** (`CIRISApiClient.kt:3281,
3318, 3357, 3398`). There is no substrate gap on this surface at all. The tag
list, which was the whole blocker, is now real (§2); what keeps this at
`building` is the floor (`unreleased`) and a flow that has not run on the matrix.

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

**Review (2026-09-28).** Card vs CSD: agrees (tags, eight arms). CSD vs API:
all four routes live at the node URL; the non-2xx-with-body contract is
honoured. CSD vs CC: **closed** — irreversible writes now go behind a
three-fact ConfirmSheet (§2.0.1). Open: the six `x_private:` prices have no
registry family (the ask in §2 is CIRISConstitution#110, filed and open);
the raise/lift visual asymmetry and the submit-sends-the-
dry-run-bytes invariant have no test. Stage: building → building (the floor is
`unreleased` and no flow has run on the matrix).

**Not tested here.**
* **The thresholds.** `m`, the respondent floor, the window and the escalation
  are decided by `resolve_reverse_quorum` in persist and rendered verbatim.
  Asserting a number here would be re-implementing the rule this screen was
  built to not re-implement — and a second answer that can disagree with the
  first is the defect class the server's module doc calls this repo's dominant
  one.
* **Acceptances 1, 2 and 3**, entirely.
* **The non-2xx-carries-a-body contract** (§3), on either side.
