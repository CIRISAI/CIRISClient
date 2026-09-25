# CSD-070 — Commons (1-of-N to protect, m-of-n to undo — and eight distinct zeroes)

**CSD**: CSD-070 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten

```yaml csd:stage
stage: sketched
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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "objection:{state}"
    use: display-only
    type: "enum[unreadable,action_unknown,cohort_unknown,not_governed,quiet,objected,stood,reversed]"
    example: "objected"
    renders: "OBJECTED — somebody raised a brake and the window is open"
    tag: "proposed:txt_commons_standing"
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
    tag: "proposed:txt_commons_ballot_tally"
  - ceg: x_private:objection_threshold
    use: display-only
    type: int
    example: 1
    renders: "It costs ONE member to raise this. That number is named on every response."
    tag: "proposed:txt_commons_raise_price"
  - ceg: x_private:escalation_respondent_floor
    use: display-only
    type: int
    example: 3
    renders: "and never fewer than 3 respondents — an absolute floor no policy string can lower"
    tag: "proposed:txt_commons_floor"
  - ceg: x_private:dismissal_required
    use: display-only
    type: int
    example: 4
    renders: "Lifting it costs 4 of this cohort's 7 — the cohort's own number, often simply not known here"
    tag: "proposed:txt_commons_lift_price"
  - ceg: x_private:dismissal_payload_sha256
    use: display-only
    type: string
    example: "c40a9f2c…"
    renders: "the dry run's canonical bytes — co-signers sign exactly this"
    tag: "proposed:txt_commons_dry_run_hash"
  - ceg: x_private:escalation_standing
    use: display-only
    type: "enum[not_adopted,nothing_to_escalate,awaiting,open]"
    example: "awaiting"
    renders: "Escalation · AWAITING — the appointed moderators have not answered yet"
    tag: "proposed:txt_commons_escalation"
  - ceg: x_private:commons_refusal
    use: display-only
    type: string
    example: "objection_roster_unresolved"
    renders: "the refusal token and the server's own sentence for it"
    tag: "proposed:txt_commons_refusal"
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
deliberately not a dimension.**

```yaml csd:states
populated: {tag: "proposed:txt_commons_standing", renders: "the standing chip, its sentence, and — only on the five non-absence arms — the counts"}
empty:     {tag: "proposed:txt_commons_quiet", renders: "Nobody objected. The plane was read and it is clear. (This is NOT an absence.)"}
loading:   {tag: "proposed:spinner_commons_read", renders: "the Read button carries the progress affordance; no standing chip is drawn"}
error:     {tag: "proposed:txt_commons_unreadable", renders: "The plane could not be read. No counts are printed, because none exist."}
```

**The model already draws this distinction and the screen already renders it.**
`ABSENCE_ARMS` (`CommonsScreen.kt:318`) is the four-arm set; `absent` gates the
counts (`:353`); `quiet` gets its own success-toned block (`:405`); the refusal
path returns before any of it (`:332`). This is the most carefully built
zero-discipline in the client.

**And none of it has a test tag.** All twenty tags on `CommonsScreen.kt` are
inputs and buttons — the three write doors. The read this surface exists to
render — eight arms, four of them count-free, one of them deliberately not an
absence — is entirely unassertable. A regression that collapsed
`unreadable` into `quiet` would pass every check this repo runs, and it is the
exact regression the server's module doc says cost 71 hours on 2026-08-05.

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
3318, 3357, 3398`). There is no substrate gap on this surface at all — which is
why its `sketched` blocker is entirely the tag list, and why moving it to
`building` is a client-side afternoon rather than an upstream ask.

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
  visible: [input_commons_cohort_key, input_commons_action, btn_commons_read]
```

*Cannot yet assert:* the asymmetry banner, which is drawn before any control and
is the first thing §1 claims.

Type a cohort key and an action id; press `btn_commons_read`.

```yaml
expect:
  visible: [input_commons_objection_grounds, btn_commons_object]
```

*Cannot yet assert:* the standing, its arm, whether counts were printed, or
whether they should have been. Eight arms, one assertion, and the assertion is
"a text field appeared".

Open the lift disclosure and run the dry run:

```yaml
expect:
  visible: [btn_commons_dismiss_dry_run, input_commons_cosigner_key,
            input_commons_cosigner_classical, btn_commons_add_cosigner]
```

*Cannot yet assert:* the payload hash, or that submit stayed shut until the dry
run produced it.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. `unreadable` and `quiet` are visibly different, and only one of them prints
   counts.
2. The raise price and the lift price are never rendered as peers.
3. The dismissal submit is unreachable before a dry run.
4. A refusal shows the substrate's own token, not a paraphrase.

**Not tested here.**
* **The thresholds.** `m`, the respondent floor, the window and the escalation
  are decided by `resolve_reverse_quorum` in persist and rendered verbatim.
  Asserting a number here would be re-implementing the rule this screen was
  built to not re-implement — and a second answer that can disagree with the
  first is the defect class the server's module doc calls this repo's dominant
  one.
* **Every state** — all four are `proposed:` (§2).
* **Acceptances 1, 2 and 3**, entirely.
* **The non-2xx-carries-a-body contract** (§3), on either side.
