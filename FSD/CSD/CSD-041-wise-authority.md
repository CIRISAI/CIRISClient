# CSD-041 — Wise Authority (My things › Someone I trust)

**CSD**: CSD-041 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person who has been seated as a Wise Authority can see what an agent has
stopped and deferred to them, read the deferral package, and send guidance back —
and a person who has *not* been seated can see who they defer to.**

Serves **CC 1.9**, whose deferral procedure is five steps and whose fourth is the
one this screen exists for: *"Await guidance; remain inactive on that issue."*
CC 4.3 states the consequence in the strongest terms available: *"a WA seat is an
authority-conferring relation, and an absent WA does not merely fail to answer,
it freezes the agent."* Everything on this card is downstream of that — a stuck
agent is more urgent than a health readout, which is why the pending-approvals
block is drawn above the status card.

The instrument is **Someone I trust**, and that framing is right and incomplete:
today the card only serves the *answering* side. The deferring side — who
answers for me, and are they live — has no rendering at all (§6).

## 2. Surface (what)

```yaml csd:surface
surface: wise-authority
screen: WiseAuthority
```

`nav_map` derives `btn_my_things -> nav_instrument_someone_i_trust ->
nav_epistemic_wise_authority`. It is the instrument's only card.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:deferral_id
    use: display-only
    type: string
    example: "7f2c91ab"
    renders: "one row per pending deferral, keyed on the first eight characters"
    tag: "item_deferral_{first8}"
  - ceg: x_private:deferral_question
    use: display-only
    type: string
    example: "Should I post the draft to #general before Ada reviews it?"
    renders: "the question, falling back to `reason` when the agent sent none"
    tag: "proposed:deferral_question"
  - ceg: x_private:deferral_priority
    use: display-only
    type: "enum[low,normal,high,critical]"
    example: "high"
    renders: "a HIGH badge, amber; critical is red"
    tag: "proposed:deferral_priority"
  - ceg: x_private:deferred_by
    use: display-only
    type: string
    example: "datum-v1"
    renders: "wa_from — 'from datum-v1'"
    tag: "proposed:deferral_from"
  - ceg: x_private:pending_deferrals
    use: display-only
    type: int
    example: 3
    renders: "Pending — 3 (amber when > 0)"
    tag: "proposed:wa_status_pending"
  - ceg: x_private:deferrals_24h
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Last 24h — 3. THE NUMBER IS NOT A 24-HOUR WINDOW: CIRISAgent returns `deferrals_24h = pending_deferrals` (routes/wa.py:295). The card renders it under a label that is false."
    tag: "proposed:wa_status_24h"
  - ceg: x_private:notifications_blocked
    use: display-only
    type: bool
    example: true
    renders: "mobile.approvals_alerts_off, in the error container — when the OS denied notifications this screen is the only way the operator learns the agent is blocked"
    tag: wa_alerts_blocked
  - ceg: x_private:tool_approval_capability
    use: display-only
    type: string
    example: "network.write"
    renders: "one row per capability flag the tool is asking for"
    tag: "row_tool_cap_{flag}"
  - ceg: "wa_adjudication:{state}"
    bind: {state: abandonment}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. The one CC-registered WA artifact — the quorum finding a `wa_adjudication_ref` resolves to (CC 3.1.9.4, used as the CC 3.2 node-recovery gate) — has no route and no rendering."
    tag: "proposed:wa_adjudication_row"
  - ceg: "hard_case:{kind}"
    bind: {kind: wa_window_expiry}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. CC 4.3 rule 1 says a deferral 'MUST NOT be left indefinitely open because its addressee is gone: on window expiry it re-routes to the live quorum.' `timeout_at` is on the wire and the card never draws it."
    tag: "proposed:deferral_timeout"
```

```yaml csd:states
populated: {tag: "proposed:wa_deferrals_list"}
empty:     {tag: "proposed:wa_deferrals_empty", renders: "the surfaceVariant card at WiseAuthorityScreen.kt:242-247 — 'nothing is waiting on you'. Untagged."}
loading:   {tag: "proposed:wa_loading", renders: "a 20dp progress affordance beside the 'Pending' heading (:230-235); the list keeps its previous contents, which is right for a 10-second auto-refresh"}
error:     {tag: "proposed:wa_error", renders: "This node does not have a Wise Authority surface. — DOES NOT EXIST. A node with no /v1/wa routes renders the empty card, so 'nobody needs you' and 'this node cannot answer the question' are the same picture."}
```

That last line is the CSD-003 principle applied here: a safety surface that
silently fails to render is worse than one that is absent, because absence is at
least visible. On a bare node **every** route this screen calls is missing (§3),
and the screen draws the calm empty state.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| pending deferrals | `GET /v1/wa/deferrals` | **CIRISAgent** — `routes/wa.py:130` | live on the brain; **absent from the node** |
| send guidance | `POST /v1/wa/deferrals/{id}/resolve` | **CIRISAgent** — `routes/wa.py:156` | live on the brain; **absent from the node** |
| WA status | `GET /v1/wa/status` | **CIRISAgent** — `routes/wa.py:245` | live, and two of its five numbers are fabricated (§6) |
| proposals / ticket budget | `fetchProposals`, `fetchTicketBudget`, `grantBudget`, `updateTicketStatus` | CIRISAgent | live |
| any WA surface at all | — | CIRISServer | **missing** — zero `/v1/wa` route literals in `src/*.rs` on 0.5.217; `src/compose.rs:5166` names the "WBD `route_deferral` / Wise-Authority surface" and marks it **SCAFFOLD** |
| `wa_adjudication:{state}` findings | — | CIRISServer | **missing** — the family is registered to `node` / CIRISNodeCore and nothing serves it |

CIRISAgent's tree is dated 2026-08-15 and may be stale; the `/v1/wa` rows should
be re-read before this CSD advances.

## 4. Flow (how)

Sign in on a node **with a brain**, as a seated WA; open My things › Someone I
trust.

```yaml
expect:
  state: populated
  count: {of: "item_deferral_*", min: 1}
  visible: ["proposed:wa_status_pending", "proposed:deferral_question"]
  number: {"proposed:wa_status_pending": {min: 1}}
```

Open one and send guidance: click `item_deferral_<first8>`, type into
`input_wisdom_guidance`, click `btn_resolution_approve`.

```yaml
expect:
  count: {of: "item_deferral_*", eq: 0}
  state: empty
```

On a **node-only** build, where no `/v1/wa` route exists:

```yaml
expect:
  state: error
  visible: ["proposed:wa_error"]
  absent: ["proposed:wa_deferrals_empty"]
```

The third block fails today — it is the assertion the fix owes, and it is the
most important one in this file.

## 5. QA plan

**Platforms.** All five with a brain, and all five **without** — the node build
is where the honest-error requirement bites, and it is the build the Locked Spec
treats as normal.

**Not tested here.** Whether guidance reached the agent (that is CIRISAgent's
matrix, not this client's); the 10-second auto-refresh, which a flow would have
to race; the notification path behind `wa_alerts_blocked`, which needs an OS
permission denial.

## 6. Delta — card vs API vs CC

* **The verbs are not CC's, and one of them is a lie in transit.** CC 1.9 says
  *"await guidance … integrate the received guidance"*. The card offers
  **approve / reject / modify**, and CIRISAgent maps anything that is not
  `approve` to `approved=False` (`routes/wa.py:179`) — so **"modify" is persisted
  as a rejection**. A WA who chose "modify" and typed a nuanced instruction has
  their instruction stored and their verdict inverted. **Ask (CIRISAgent):**
  either give `modify` its own persisted outcome or remove the button. Removing
  it is the honest interim fix and is a one-line client change.
* **Two fabricated metrics on screen.** `deferrals_24h` is `pending_deferrals`
  under a 24-hour label (`routes/wa.py:295`) and the card renders it;
  `average_resolution_time_minutes` is hardcoded `0.0` (`:291`) and the card only
  escapes it because it guards on `> 0`. `active_was` is hardcoded `1`. **Ask
  (CIRISAgent):** compute them or remove them. **Ask (CIRISClient):** stop
  rendering `deferrals_24h` until it is real — a number under a wrong label is
  worse than a blank, which is the metric-dishonesty failure CSD-004 was written
  about.
* **CC 4.3's three symmetry rules have no rendering.** Rule 1 — liveness is
  signalled, deferrals re-route on window expiry — is on the wire as `timeout_at`
  and unused. Rule 2 — conduct is published, composing "relative and positional,
  never a single global score" — has no surface. Rule 3 — *"Who audits the wise:
  last-deferral-answered and published-ruling history are readable by the parties
  who defer"* — is the missing half of this instrument: a person who defers has
  no way here to see whether their WA is live or what they have ruled. **Ask
  (CIRISAgent):** serve `timeout_at` semantics and a per-WA conduct record;
  **ask (CIRISClient):** draw the deferring side of "Someone I trust".
* **The node has no WA surface at all.** Everything here is the brain's. That is
  constitutionally odd: `wa_adjudication:{state}` is registered to **node /
  CIRISNodeCore** (CC 3.1.9.4) and is the gate CC 3.2 requires before a live
  owner-binding may be withdrawn — so the node needs WA findings whether or not
  a brain is attached. **Ask (CIRISServer):** land the scaffold at
  `compose.rs:5166`, at minimum a read route for `wa_adjudication:*` findings
  against this node.
* **Placement.** Correct, and the name is better than "Wise Authority": *Someone
  I trust* is what CC 4.3 describes — a seat a person holds, read by the people
  who defer to it.
