# CSD-003 — Constitutional card — is the halt authority armed, and who holds it

**CSD**: CSD-003 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/constitutional_card.yaml — **stale**, see §4

```yaml csd:stage
stage: building
owner: CIRISClient
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
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "accord:halt_status"
    use: display-only
    type: "enum[halted,disarmed,unknown]"
    example: "unknown"
    renders: "Kill-switch status UNKNOWN — the node did not answer. This is not a report that it is disarmed."
    tag: txt_killswitch_unknown
    assert:
      one_of: {card_accord_killswitch: [halted, disarmed, unknown]}
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
    renders: "3 registered accord holder(s) (2-of-3 threshold)"
    tag: card_accord_holders
  - ceg: x_private:holder_threshold
    use: display-only
    type: int
    example: 2
    renders: "'3 registered accord holder(s) (2-of-3 threshold)' — the m of the m-of-n as GET /v1/accord-holders states it, printed only beside a real roster, never a fixed 2-of-3"
    tag: txt_constitutional_threshold
```

**The `enum` has three values, not two.** Not knowing is not the same as being
safe (`ConstitutionalScreen.kt:220`): `haltStatus` is null on first render and
after any failed `getAccordHaltStatus()`, and a null drawn as "disarmed" would
report a confirmed-safe state at the one moment the client has no idea.

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
with the invocation id, tagged **`txt_killswitch_halted`** — the armed state is
the reason §1 calls this card not optional. The `one_of` above is enforceable
across all three arms, and the same fact is assertable on both cards in the
tab (`accord_halt_banner`, CSD-067 §2.1).

### 2.2 Localized strings

`"HUMANITY_ACCORD KILL-SWITCH"`, both `ACTIVE HALT` sentences, `"Reading
kill-switch status…"`, the UNKNOWN sentence, `"Killswitch disarmed — All
systems nominal"`, the two family sentences, `"RESERVED PREFIX AUTHORITY"`,
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

Verified against ciris-server 0.5.217, route literals in `src/`.

| value | endpoint | owner | state |
|---|---|---|---|
| halt status | `GET /v1/accord/halt-status` | CIRISServer `src/accord.rs:2617` | **live** |
| the family (drives the LIVE chip) | `GET /v1/accord/family` | `src/accord.rs:2628` | **live** |
| holder set | ~~`/v1/accord/holders`~~ → `GET /v1/accord-holders` | `src/accord.rs:2601` | **live at a different path.** `/v1/accord/holders` does not exist; the route is a sibling of `/v1/accord/*`, not a child. The client calls this one (`CIRISApiClient.kt:3473`) |
| this node's holder record | ~~`/v1/accord/holder`~~ | `src/accord.rs:2600` | **wrong verb.** `/v1/accord/holder` is **POST** `register_holder`. There is no GET, so "this node's holder record" has no read and the row was describing a write |
| provision-holder entry | `POST /v1/accord/provision-holder` | `src/accord_provision.rs:3697` | **live** (CSD-068) |
| ceremony entry | ~~`/v1/accord/provision`~~ → `POST /v1/accord/genesis/{envelope,assemble}` + `/v1/accord/family/cosign` | `src/accord.rs:2621,2625`, `src/accord_provision.rs:3701` | **no such route as `/v1/accord/provision`.** The ceremony is three endpoints, not one (CSD-069) |
| trust root | `GET /v1/trust-root` | `src/trust_root_api.rs:415` | **live and not called by this screen** — see §2 on why it is not bound here; it is CSD-105's, reached from the Accord card |
| the owner's nodes | `GET /v1/setup/owned-nodes` | CIRISServer, loopback-only | **called, not drawn** — this screen shares `AccordViewModel` with the Accord card (`CIRISApp.kt`, the `Screen.Constitutional` arm collects its flows and calls its `refresh()`), and `refresh()` reads owned-nodes for the Accord card's target pickers. The checker charges every init-time read of a touched view model to the screen (CSD.md §4.1), which is why this row and the next exist |
| the rest of the accord read | `GET /v1/accord/invocations` · `GET /v1/accord/events` · `GET /v1/accord/canonical/servers` · `GET /v1/accord/canonical/withdrawals` · `GET /v1/accord/canonical/pending` | CIRISServer (`src/accord.rs:2618,2658`, `src/accord_provision.rs:3711,3753,3766`) | **called by the shared `refresh()`, drawn on the Accord card (CSD-067 §3), not here.** This screen reads three of the flows it fills — family, holders + threshold, halt status. The cost is one screen's refresh fetching eight routes for a card that shows three; the alternative, a second view model for the same three reads, is a second source |

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
  absent:  [txt_killswitch_disarmed]
```

Against a node that answers "not halted":

```yaml
expect:
  visible: [txt_killswitch_disarmed, chip_constitutional_family, txt_constitutional_threshold]
  absent:  [txt_killswitch_unknown, txt_killswitch_halted, txt_killswitch_loading]
```

Against a node with a latched halt:

```yaml
expect:
  visible: [txt_killswitch_halted]
  absent:  [txt_killswitch_disarmed, txt_killswitch_unknown]
```

The two entry points:

```yaml
expect:
  visible: [btn_open_accord_ceremony, btn_open_provision_holder]
```

*Cannot yet assert on a runner:* the loading sentence — the read is faster than
a step; the tag exists (`txt_killswitch_loading`) for a throttled fixture.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. Armed, disarmed and unknown are three renderings, and unknown never reads as
   safe.
2. The threshold shown is the node's, never a printed `2-of-3`.
3. Both entry points open their ceremonies rather than merely existing.

**Not tested here.**
* **Acceptance 1 against a real latch** — the tag exists (§2.1) and the flow
  asserts it against a fixture; a latched production node is not a fixture
  anyone should make.
* **Acceptance 2.** `holderThreshold` defaults to `2` in the composable's
  signature and the roster line only prints a threshold when
  `holders.isNotEmpty()`; `txt_constitutional_threshold` makes the printed
  value assertable, and nothing asserts which source a given render used.
* **Acceptance 3.** The buttons are tagged; what they open is asserted in
  CSD-068 and CSD-069, not here.
* **That the reserved-prefix sentence is true.** "All attempts to forge
  constitutional claims are refused fail-closed by node verification" (`:351`)
  is a claim about the node's admission gate, drawn here as prose. It is CC
  3.4.1 and it is the node's to prove.
