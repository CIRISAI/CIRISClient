# CSD-066 — Child Safety (the protective posture, and the opt-in that must never be silent)

**CSD**: CSD-066 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person sees the protective posture their identity is under, and a
`moderate`-holder can turn a content watchlist on for ONE group they moderate —
with the three limits of that mechanism stated where they cannot be scrolled
past.** Serves **Non-maleficence** and **Transparency**.

The honest framing is the feature, not the packaging. CC 4.5.7 makes three
things normative and the card carries all three in a banner it renders first:
a watchlist is **opt-in, per-group, NEVER global**; detection runs only at the
publish/share seam and **cannot reach CC 5.2 self/family private content**; the
hashes are **operator-provisioned** and the NCMEC report is the operator's duty,
not the fabric's. A surface that implied otherwise would be claiming
bulk-scanning the framework refuses.

CC 4.5.7 also makes a fourth thing normative — **audit, never silent** — and
that one is not on this card at all (§2.2).

## 2. Surface (what)

```yaml csd:surface
surface: child-safety
screen: ChildSafety
```

`nav_map` derives `circle_agent -> tab_safety` — placed for `ALL` five circles
(`CirclesNav.kt:100`), and in Just me it is the tab's only card so the chain
ends on the tab. That placement is half right and half wrong; §2.3.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "age_assurance:{level}:{band}:{version}"
    bind: {level: provider, band: adult, version: v1}
    use: display-only
    type: "enum[adult,minor]"
    example: "adult"
    renders: "Adult posture — content gates are off for this identity"
    tag: "proposed:txt_posture_band"
  - ceg: "age_self_declared:{band}:{version}"
    bind: {band: adult, version: v1}
    use: display-only
    type: bool
    example: true
    renders: "Self-declared and unfalsifiable. A misdeclaration routes to adjudication, never to slashing."
    tag: "proposed:txt_posture_self_unfalsifiable"
  - ceg: "watchlist:{id}"
    bind: {id: iwf-2026q3}
    use: emit
    type: string
    example: "iwf-2026q3"
    renders: "Watchlist id — iwf-2026q3"
    tag: input_watchlist_id
  - ceg: "cw_class:{class}"
    bind: {class: csam}
    use: emit
    type: "enum[csam,other_content]"
    example: "csam"
    renders: "the class radio — CSAM / Other content. CSAM additionally requires the takedown duty."
    tag: class_csam
  - ceg: x_private:watchlist_mode
    use: emit
    type: "enum[alert_only,enforce]"
    example: "alert_only"
    renders: "the mode radio — Alert only / Enforce"
    tag: mode_alert
  - ceg: x_private:watchlist_group_key_id
    use: emit
    type: string
    example: "wa-grp-4f19c2"
    renders: "The group this applies to. One group. There is no all-groups."
    tag: input_watchlist_group
  - ceg: x_private:watchlist_enables
    use: display-only
    type: "list[string]"
    example: ["iwf-2026q3 (CSAM, ALERT_ONLY)"]
    renders: "Currently on for this group — one line per enable"
    tag: "proposed:list_watchlist_enables"
  - ceg: "hard_case:{kind}"
    bind: {kind: watchlist_enabled}
    use: display-only
    type: string
    example: "wa-mod-9b02 turned iwf-2026q3 on, 2026-09-04T11:02:19Z"
    renders: "Who turned this on, and when — the CC 4.5.7 enablement record"
    tag: "proposed:row_watchlist_enablement_audit"
  - ceg: "hard_case:{kind}"
    bind: {kind: watchlist_match}
    use: display-only
    type: int
    example: 3
    renders: "3 matches since this was enabled"
    tag: "proposed:txt_watchlist_match_count"
```

`watchlist:{id}`, `cw_class:{class}` and `hard_case:{kind}` are **non-reserved**
(`node`/CIRISNodeCore, CC 3.1.9.4 and 3.1.9.2), so `emit` loads. The two
`age_*` families **are** reserved — `witness-reserved, subject-not-self` at
CC 3.4.11 — which makes `display-only` checkable rather than promised: this card
shows a band and cannot mint one, and an `emit` on either would fail the load.
That is the right shape: the person whose age it is cannot be the witness.

**`x_private:watchlist_mode` is private-use on purpose.** Alert-only versus
enforce decides whether a match fires a `takedown_notice`, and no registry family
carries it — it rides the watchlist config. Naming it `x_private:` says so
rather than borrowing a prefix that means something else.

```yaml csd:states
populated: {tag: "proposed:list_watchlist_enables", renders: "the enables for this group, each with its class, mode and who turned it on"}
empty:     {tag: "proposed:txt_watchlist_none", renders: "Nothing is watched in this group. Default is off, and it stays off until someone turns it on."}
loading:   {tag: "proposed:spinner_watchlist", renders: "the frame with a progress affordance; the honesty banner stays up"}
error:     {tag: "proposed:txt_watchlist_error", renders: "Could not read this group's watchlists. This is NOT a report that nothing is watched."}
```

**All four are `proposed:` and three of them do not exist as pixels either.**
`ChildSafetyScreen.kt:252` renders the enables list only
`if (state.watchlistEnables.isNotEmpty())` — so an empty list, a failed read and
a group that was never asked about all render as *nothing at all*, below a
banner that says detection is off by default. A person reading that page cannot
tell "off" from "we could not ask", and on this particular mechanism the wrong
one of those is a serious thing to believe.

### 2.1 The one state that is drawn honestly

The posture block does separate its zero: `ChildSafetyScreen.kt:129` shows a
progress indicator while `statusLoading`, and `AgeBand` null renders
`mobile.child_safety_posture_unknown` rather than defaulting to adult. That is
the ConstitutionalScreen killswitch fix (CSD-003 §2) applied here, and it is why
the age half of this card needs tags rather than a rewrite.

### 2.2 The CC clause this card does not implement

> **Audit — never silent (normative).** Enabling a watchlist emits
> `hard_case:watchlist_enabled:{group}` — who turned it on + which list; **every
> match** emits `hard_case:watchlist_match:{group}`. Enablement and matches are
> on the record, always. — CC 4.5.7

Neither is rendered. The enables list prints `id (class, mode)` and nothing
about who enabled it, when, or whether anything has matched. A watchlist that
is on and has never been attributed to a person is the posture the separation-
of-powers table exists to prevent: the authority's opt-in is the only thing
standing between the operator's hash database and a group's content, and the
card does not say whose opt-in it was.

The **CSAM-disable non-silent floor** is the sharper half. CC 4.5.7 bars silent
removal of a CSAM list — disabling must be an audited `withdraws` that emits the
disable variant. `btn_watchlist_disable` (`:243`) is one `OutlinedButton` that
sends `enabled=false` for whichever class the radio happens to be on. There is
no confirmation, no different treatment, and no record shown afterwards. The
node may well emit the attestation; the *person* gets no more friction and no
more evidence for turning off CSAM detection than for turning off anything else,
and "a predator-operator cannot turn it off without leaving a trace" is a claim
about a trace nobody is shown.

### 2.3 The placement is right for half the card and wrong for the other half

The card is two cards. `ALL` five circles is correct for the **protective
posture** — age assurance is a property of *this identity*, it is what gates
content anywhere, and Just me is exactly where a person should find it.

`ALL` is wrong for the **watchlist**. CC 4.5.7 scopes a watchlist to a group its
`moderate`-holder opts in for, and states in the same clause that detection
"cannot reach CC 5.2 self/family private content". So in Just me and in Family
the control is offered where, by construction, it can never apply — and it is
offered under a banner whose second line says so. A person can type their own
key into `input_watchlist_group` and press Enable; the node will refuse, and the
refusal will be the first thing that tells them.

**Recommended:** split the surface. Posture stays at `Tab.SAFETY, ALL`; the
watchlist moves to `Tab.SAFETY, NEIGHBOURS_OUT` — the same set Moderation
already carries (`CirclesNav.kt:99`), for the same reason: a duty is held over a
community, and there is no community in the first two circles.

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25).

| value | endpoint | owner | state |
|---|---|---|---|
| protective posture | `GET /v1/safety/status/{key_id}` | CIRISServer `src/safety/age.rs:549` | **live** |
| a group's enables | `GET /v1/safety/watchlist/{group_key_id}` | CIRISServer `src/safety/watchlist.rs:511` | **live** |
| enable / disable | `POST /v1/safety/watchlist` | CIRISServer `src/safety/watchlist.rs:509` | **live** |
| who enabled it, and when | **missing** — `WatchlistListResponse` carries `enables[]` + `honesty{}` and no attester or timestamp | CIRISServer | blocks `row_watchlist_enablement_audit` |
| match count for a group | **missing** — no `hard_case:watchlist_match` read | CIRISServer | blocks `txt_watchlist_match_count` |

The two missing rows are one ask: **CC 4.5.7's "never silent" audit has no read
route.** The attestations are emitted by the node and there is nothing that
serves them back — the same shape as the capacity read before CIRISServer#580
(CSD-004 §3), and with the same consequence: a client that can only render the
config renders a mechanism with no history.

## 4. Flow (how)

Real tags only; the screen has ten and every one of them is an input or a
button, so the flow below drives the card and asserts almost nothing about it.

Land on `ChildSafety` (Just me › Safety, derived).

```yaml
expect:
  visible: [input_watchlist_group, input_watchlist_id,
            class_other, class_csam, mode_alert, mode_enforce,
            btn_watchlist_enable, btn_watchlist_disable, btn_watchlist_refresh]
```

*Cannot yet assert:* the honesty banner is on screen, the posture band, or the
self-declared caveat — the three things §1 calls the feature.

Type a group key; press `btn_watchlist_refresh`.

*Cannot yet assert:* anything. Populated, empty, loading and error are one
rendering.

## 5. QA plan

**Platforms.** All five.

**Acceptance — functional**
1. The three honesty lines are on screen before any control is reachable.
2. "Nothing is watched" and "we could not ask" are visibly different.
3. Turning a CSAM list off shows the person the record it left.
4. A non-holder's 403 is rendered as a refusal, not as an empty list.

**Not tested here.**
* **The matcher.** Detection runs at the node's publish/share seam; nothing on
  this screen observes it, and a client fixture asserting a match would be
  asserting against the wrong machine.
* **That private content is not scanned.** This is a CC 5.2 property of the
  substrate. The card states it; the card cannot prove it, and no test here
  should be read as proof.
* Acceptance 3, entirely — there is no route to read the record (§3).
* All four states (§2).
