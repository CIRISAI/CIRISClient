# CSD-003 — Constitutional card — is the halt authority armed, and who holds it

**CSD**: CSD-003 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/constitutional_card.yaml — **stale**, see §4

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: armed-source
  card: 'armed is read from the node: a genesis in force, N holders seated, and an acceptance edge for this node'
  code: 'armed and standing are two fields (KillSwitch.kt), but armed is always unknown: no node reports it yet'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/KillSwitch.kt
  open_while: 'val armed = HaltArmed\.UNKNOWN'
  blocked_on: 'O1, O13 (the 0.6 halt-status shape: armed vs standing)'
```

**Model.** What this card draws and offers, cited from CIRISConstitution's formal model (pinned in `packaging/model/`) and held to it by `packaging/check_csd_model.py`: a modelled state the card leaves out, or a signer, policy or confirm that differs, fails CI.

```yaml csd:model
- var: root.halt
  states: 'not_halted latched_lone confirmed'
  code: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/KillSwitch.kt
  code.not_halted: 'data object NotHalted'
  code.latched_lone: 'data class Lone\('
  code.confirmed: 'data object Confirmed'
- var: node.halt_latch
  states: 'clear latched'
  code: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/KillSwitch.kt
  code.clear: 'data object NotHalted'
  code.latched: 'data class Lone\(|data object Confirmed'
```

> Everyone › Safety carries `nav_epistemic_constitutional` (this card),
> `nav_epistemic_accord` (**CSD-067**) and `nav_epistemic_provision_accord_holder`
> (**CSD-068**). The accord's own roster, invocations and ceremonies are CSD-067's.

## 1. Mission (why)

**A person can see whether the halt authority is armed, learn that it is not
armed rather than that it is safe when the node will not say, and reach the two
flows that put a human behind it.** The killswitch card is the surface of
**CC 4.2** halt authority — "no objective can outvote it" — one of the three
claims CIRISServer#536 names as load-bearing on the public /safety page.

**On v62, "armed" and "halted" are two facts, and this card must not draw one
as the other** (§2.1a). **Armed** means a genesis bundle is in force on this node
(posture `entrenched`), it seats N holders, each of whom can fire a halt with
one signed row (and each sealed one at the ceremony, checked before
publication), **and** this node holds a live acceptance edge to the root, so a
halt reaches it. **Not armed** means pre-genesis, a fork, divergent, or no
acceptance edge. A node cannot observe the sealed halts (they stay offline until
published), so "every holder's halt was sealed" is a ceremony fact, not a node
read (CEREMONY step 3→4; persist ceremony header "A node's import requires no
halt"; CC 4.2.1 reach). Whether the server publishes the ceremony's coverage
evidence (the `check_trace` manifest, CC `7beaf20`) for this card to show is
**OPEN (O13): CIRISServer / CIRISConstitution.**

**A safety surface that silently fails to render is worse than one that is
absent, because absence is at least visible.** Serves **Core Identity** and
**Integrity**.

## 2. Surface (what)

```yaml csd:surface
surface: constitutional
screen: Constitutional
```

`nav_map` derives `circle_global_commons -> tab_safety ->
nav_epistemic_constitutional` — Everyone › Safety (`CirclesNav.kt:103`). Three
cards share that tab, so the chain ends on the row. Clicking that row before its
tab is on screen would fail as "element not found", which on a safety surface is
indistinguishable from a broken screen and is the worst possible confusion; the
runner walks the chain.

```yaml csd:shows
registry_sha256: 5f8ff71fd1415c88179b73a8b7d9a047613b22cb81f609909ca9ab9ccc2a8d69
fields:
  - ceg: "accord:halt_status"
    use: display-only
    type: "enum[halted,not_halted,lapsed,unknown]"
    example: "unknown"
    renders: "Kill-switch status UNKNOWN — the node did not answer. This is not a report that it is disarmed."
    tag: txt_killswitch_unknown
    assert:
      one_of: {card_accord_killswitch: [halted, not_halted, lapsed, unknown]}
  - ceg: "accord:family"
    use: display-only
    type: "enum[live,not_configured]"
    example: "live"
    renders: "the LIVE / NOT CONFIGURED chip beside the title"
    tag: chip_constitutional_family
  - ceg: "accord:holders"
    use: display-only
    type: int
    example: 3
    renders: "the holder count. On v62 it reads as holders SEATED by the genesis bundle in force (not 'registered'): '3 holders seated by the bundle in force (m = 2: a strict majority of 3)', N and m as the node reports them (constants.tsv rows 6, 8). Today's string still says 'registered'; see §2.1a"
    tag: card_accord_holders
  - ceg: x_private:holder_threshold
    use: display-only
    type: int
    example: 2
    renders: "the m of the m-of-N as the node states it, printed only beside a real roster, never a fixed number. On v62 m is a strict majority of the standing roster (2·yes > N; N from the bundle in force — 3 at the seed, so 2 today; constants.tsv rows 6, 8, CC 4.2.6)"
    tag: txt_constitutional_threshold
```

**The `enum` is the halt's standing, and unknown is one of its values.** Not
knowing is not the same as being safe (`KillSwitch.read`): `haltStatus` is null
on first render and after any failed `getAccordHaltStatus()`, and a null drawn
as "not halted" would report a confirmed-safe state at the one moment the
client has no idea. "Halted" is drawn as lone (with its lapse time when the node
gives one) or confirmed. Whether a halt would reach this node at all, armed, is
a separate line (`txt_killswitch_armed_unknown` today, §2.1a).

**`accord:*` is reserved** — `accord_holder-only` at CC 3.4.1, owned by
`registry`/CIRISRegistry — so `display-only` is enforced: this client cannot
mint accord state and an `emit` here fails the load. The two buttons open
ceremonies that write, through the holder's own authority and not the client's.

**`trust:{job}:{version}` is not bound here.** `card_constitutional_overview`
renders a title, a description and the LIVE chip; there is no trust root on it
and the screen never calls `/v1/trust-root`.

```yaml csd:states
populated: {tag: screen_constitutional, renders: "the overview card, the kill-switch card and the holder-authority card"}
empty:     {tag: chip_constitutional_family, renders: "NOT CONFIGURED — no Accord is provisioned on this node."}
loading:   {tag: txt_killswitch_loading, renders: "Reading kill-switch status… — a sentence, and NOT the unknown one, on its own tag"}
error:     {tag: txt_killswitch_unknown, renders: "Kill-switch status UNKNOWN — the node did not answer. This is not a report that it is disarmed."}
```

`error` is mandatory here in the strongest sense: `txt_killswitch_unknown`
carries the sentence verbatim and cannot be reached by a successful read.
`one_of` on the killswitch exists so a third value cannot be drawn as either of
the other two.

`loading` has its own tag: `txt_killswitch_loading` and `txt_killswitch_unknown`
follow the branch, so a flow can tell a slow read from a failed one.

### 2.1 The state that matters most has its tag

`haltStatus?.halted == true` renders an `ACTIVE HALT` banner in `StatusWarn`
with the invocation id, tagged **`txt_killswitch_halted`** — the halted state is
the reason §1 calls this card not optional. (Before v62 this card called the
halted state "armed"; on v62 armed is a separate fact, §2.1a.) The `one_of` above is enforceable
across all three arms, and the same fact is assertable on both cards in the
tab (`accord_halt_banner`, CSD-067 §2.1).

### 2.1a On v62 (audit of 2026-11-21)

v62 is a total re-genesis: this edition begins a new mesh, and nothing of the
earlier mesh (its roots, bundles v1–v4, holders A1/B1/C1, canonical records,
family rows) gives standing here. What that changes on this card:

* **Armed and halted are two fields.** (a) **armed**: `armed` / `not_armed`
  (with the reason: pre-genesis, fork, divergent, no acceptance edge) /
  `unknown`, read from the trust-root posture and the acceptance edge.
  (b) **halt standing**: `not_halted` / `halted_lone` (lapses at T) /
  `halted_confirmed` / `lapsed` / `unknown`. "Disarmed" must never mean "not
  halted": an armed switch that has not fired is the safe, nominal state, and
  reads "armed, not fired". **Built:** the two are two fields
  (`models/federation/KillSwitch.kt`): the standing line is
  `txt_killswitch_not_halted` / `txt_killswitch_halted` / `txt_killswitch_lapsed`
  / `txt_killswitch_unknown`, and armed is its own line. **Still open:** armed
  is always drawn unknown (`txt_killswitch_armed_unknown`), because no node
  reports it: 0.5.x halt-status does not, and the v62 halt-status shape is
  0.6: pending (CIRISServer, O1, O13). It is never derived from "not halted"
  (the `armed-source` code gap above).
* **Both thresholds.** "3 holders seated by the bundle in force. Any one can
  fire. Confirming or resuming a halt takes a strict majority (2 of 3 today; m
  read from the roster)" — fire = 1, lifecycle = a strict majority (CC 4.2.6).
* **Reach.** A node with no live acceptance edge is not in a halt's reach, and
  un-trust stops the next halt, not one already latched (CC 4.2.1; CC 4.2.6
  second residual).
* **First drill.** The first drill is due within 7 days of the genesis's
  publication (CEREMONY step 11); the card should show the due date and whether
  it happened.
* **The two buttons.** `btn_open_accord_ceremony` opens the retired ceremony
  (CSD-069) and `btn_open_provision_holder` the stale CSD-068 flow. **Built:**
  both are offered only to an edition-1 (0.5.x) node, read from
  `/v1/federation/conformance` → `edition` (`AccordEditionGate`); on a v62 or an
  unknown node the card shows `note_constitutional_edition` instead (the v62
  genesis is the offline ceremony tool). Where they point on v62 is O2.

* **The fuse.** A halt is no longer simply "halted". A lone halt reads
  "halted — lapses at T unless a strict majority confirms" (`halt_fuse_secs`
  from the bundle, default 86 400 s, counted from receipt on this node); a
  confirmed halt reads "halted (confirmed) — stands until resumed"; a lapsed
  halt reads "lapsed". `unknown` stays, and still never reads as safe (CC
  4.2.1.1, 4.2.1.3; persist `edition2/halt.rs:38-60`; the server's
  `accord_halt.rs` `HaltStanding` already has `InEffect{lapses_at}` and
  `Lapsed`). **Built** over the 0.5.x read: `halt.confirmed` gives confirmed,
  `halt.lapses_at` the lone halt's time, `lapsed` the lapsed state
  (`KillSwitch.read`); the v62 shape is 0.6: pending (O1).
* **Five kinds, if this card shows kinds.** `constitutional`, `notify`,
  `drill`, `lifecycle:confirmed`, `lifecycle:active` (CC 4.2.1.2). The kinds
  are CSD-067's to draw, and `InvocationKind` has all five.
* **NOT CONFIGURED.** A v62 node before its genesis reads posture
  `PreGenesis{leg: anchor}` (persist `federation/genesis/posture.rs:233-250`);
  the empty state is that — "No genesis in force on this node (pre-genesis): no
  halt can reach it yet" — and an earlier-form bundle offered to it is refused
  as `earlier_form_object`. With a fork: "two geneses — this node roots on
  neither (re-anchor by the object id)" (posture `:410-433`; CEREMONY §5).
* **Holders are seated, not registered.** A holder is seated by the genesis or
  a later bundle that names its record; there is no registration (CEREMONY
  steps 1, 3).

### 2.2 Localized strings

`"HUMANITY_ACCORD KILL-SWITCH"`, both `ACTIVE HALT` sentences, `"Reading
kill-switch status…"`, the UNKNOWN sentence, the not-halted, lone, confirmed and
lapsed sentences, the three armed sentences, the two family sentences, `"RESERVED PREFIX AUTHORITY"`,
the two holder-count lines, the reserved-prefix paragraph and the `LIVE` /
`NOT CONFIGURED` chip are fourteen `mobile.constitutional_*` keys in `en.json`
and its three mirrors; per `AGENTS.md` (Working in `localization/`) the other
28 locales come from a `localize.py` run, not by hand. The title has no
screen-level fallback: `localizedString` already falls back through English to
the key, and a second fallback would be a second source.

### 2.3 What moved to CSD-067

The holder roster as cards, pending invocations and their per-kind CC 4.2.1.2
treatment, canonical servers, co-scrubs, drills, announces and the halt sheet
are all on `Screen.Accord` and are specified in CSD-067 — including the
conformance defect where `accord:lifecycle:active` is rendered as a `notify`.
This card shows a **count** of holders and no roster, which is the right
division: it answers "is the authority constituted and armed", and the Accord
card answers "by whom, and what is pending".

## 3. Contracts (who)

Verified against ciris-server 0.5.217, route literals in `src/`. **On v62 every
accord route below is retired or re-pointed, and none has a 0.6 shape yet: 0.6:
pending (CIRISServer, O1).** The rows stay because the client still calls them.

| value | endpoint | owner | state |
|---|---|---|---|
| halt status | `GET /v1/accord/halt-status` | CIRISServer `src/accord.rs:2617` | **live** |
| the family (drives the LIVE chip) | `GET /v1/accord/family` | `src/accord.rs:2628` | **live** |
| holder set | ~~`/v1/accord/holders`~~ → `GET /v1/accord-holders` | `src/accord.rs:2601` | **re-pointed on v62 at the bundle's holders; 0.6: pending (CIRISServer).** On 0.5.x: **live at a different path.** `/v1/accord/holders` does not exist; the route is a sibling of `/v1/accord/*`, not a child. The client calls this one (`CIRISApiClient.kt:3473`) |
| this node's holder record | ~~`/v1/accord/holder`~~ | `src/accord.rs:2600` | **retired on v62** (no registration; a bundle seats a holder). On 0.5.x: **wrong verb.** `/v1/accord/holder` is **POST** `register_holder`. There is no GET, so "this node's holder record" has no read and the row was describing a write |
| provision-holder entry | `POST /v1/accord/provision-holder` | `src/accord_provision.rs:3697` | **live on 0.5.x** (CSD-068). Retired on v62; re-implemented as an on-device composite key + `holder_key_record_draft`; 0.6: pending (CIRISServer) |
| ceremony entry | ~~`/v1/accord/provision`~~ → `POST /v1/accord/genesis/{envelope,assemble}` + `/v1/accord/family/cosign` | `src/accord.rs:2621,2625`, `src/accord_provision.rs:3701` | **no such route as `/v1/accord/provision`.** The ceremony is three endpoints, not one (CSD-069). **All three retired on v62**; the v62 ceremony (CEREMONY §3) has no route yet: 0.6: pending (CIRISServer). Which card hosts it, CSD-067's sheet or CSD-069, is OPEN — Eric / CIRISClient (O2) |
| trust root | `GET /v1/trust-root` | `src/trust_root_api.rs:415` | **live and not called by this screen** — see §2 on why it is not bound here; it is CSD-105's, reached from the Accord card |
| the owner's nodes | `GET /v1/setup/owned-nodes` | CIRISServer, loopback-only | **called, not drawn** — this screen shares `AccordViewModel` with the Accord card (`CIRISApp.kt`, the `Screen.Constitutional` arm collects its flows and calls its `refresh()`), and `refresh()` reads owned-nodes for the Accord card's target pickers. The checker charges every init-time read of a touched view model to the screen (CSD.md §4.1), which is why this row and the next exist |
| the rest of the accord read | `GET /v1/accord/invocations` · `GET /v1/accord/events` · `GET /v1/accord/canonical/servers` · `GET /v1/accord/canonical/withdrawals` · `GET /v1/accord/canonical/pending` | CIRISServer (`src/accord.rs:2618,2658`, `src/accord_provision.rs:3711,3753,3766`) | **called by the shared `refresh()`, drawn on the Accord card (CSD-067 §3), not here.** On v62 `canonical/withdrawals` and `canonical/pending` are retired (the withdraw and supersede doors are deleted; only a later bundle un-seats a canonical), and `canonical/servers` is re-pointed at the bundle's seats: 0.6: pending (CIRISServer). This screen reads three of the flows it fills — family, holders + threshold, halt status. The cost is one screen's refresh fetching eight routes for a card that shows three; the alternative, a second view model for the same three reads, is a second source |

**A row is confirmed only against route literals.** A route table written from
a design document and labelled *confirmed* is worse than one labelled
`unconfirmed`, because the stage machine trusts the label: at `building` an
`unconfirmed` row stops the advance and a wrong row does not.

## 4. Flow (how)

`tools/qa_runner/flows/constitutional_card.yaml` is **stale**: four steps
through a federation hub the shell does not have. What replaces it, using real
tags:

Land on `Constitutional` (Everyone › Safety, derived).

```yaml
expect:
  state: populated
  visible: [screen_constitutional, card_constitutional_overview,
            card_accord_killswitch, card_accord_holders,
            btn_constitutional_refresh]
```

Against a node that does not answer `halt-status`:

```yaml
expect:
  state: error
  visible: [txt_killswitch_unknown]
  absent:  [txt_killswitch_not_halted]
```

Against a node that answers "not halted":

```yaml
expect:
  visible: [txt_killswitch_not_halted, txt_killswitch_armed_unknown, chip_constitutional_family, txt_constitutional_threshold]
  absent:  [txt_killswitch_unknown, txt_killswitch_halted, txt_killswitch_loading]
```

Against a node with a latched halt:

```yaml
expect:
  visible: [txt_killswitch_halted]
  absent:  [txt_killswitch_not_halted, txt_killswitch_unknown]
```

The two entry points, on an edition-1 node:

```yaml
expect:
  visible: [btn_open_accord_ceremony, btn_open_provision_holder]
```

On a v62 (or an unknown-edition) node:

```yaml
expect:
  visible: [note_constitutional_edition]
  absent:  [btn_open_accord_ceremony, btn_open_provision_holder]
```

*Cannot yet assert on a runner:* the loading sentence — the read is faster than
a step; the tag exists (`txt_killswitch_loading`) for a throttled fixture.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. The halt's standing (not halted, halted lone or confirmed, lapsed, unknown)
   and armed are two lines; unknown never reads as safe, and not halted never
   reads as disarmed.
2. The threshold shown is the node's, never a printed number: on v62 a strict
   majority of the standing roster, with N and m from the node.
3. Both entry points open their ceremonies rather than merely existing.

**Not tested here.**
* **Acceptance 1 against a real latch** — the tag exists (§2.1) and the flow
  asserts it against a fixture; a latched production node is not a fixture
  anyone should make.
* **Acceptance 2.** `holderThreshold` is `Int?`: null until the node serves a
  threshold (0 on the wire is unknown), never a default 2 (audit of
  2026-11-21, §6(a)). With holders and no threshold the line says the node did
  not report how many must agree (`constitutional_holders_count_m_unknown`).
  `txt_constitutional_threshold` makes the printed value assertable; nothing
  asserts which source a given render used.
* **Acceptance 3.** The buttons are tagged; what they open is asserted in
  CSD-068 and CSD-069, not here.
* **That the reserved-prefix sentence is true.** "All attempts to forge
  constitutional claims are refused fail-closed by node verification" (`:351`)
  is a claim about the node's admission gate, drawn here as prose. It is CC
  3.4.1 and it is the node's to prove.
