# CSD-013 — Tickets (the duties other people's requests put on this node)

**CSD**: CSD-013 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Someone outside this node asked it to do something the operator is obliged to
do — see the ask, see the deadline, see whether it was met.** Every other card
in This node is about what the operator chose; this one is about what was asked
of them. The screen's own title is "Tickets & Privacy"
(`mobile.screen_tickets`), and the honest reading of that is: this is where a
data-subject request lands. Serves **`duty:{kind}`** (CC 3.1.1) — *an
obligation attached to a permission, the thing a permission-and-prohibition
grammar cannot express* — and composes with the CC 3.3.1 deletion ladder, where
`consent:deletion_sla:{days}` is the producer's commitment and
`consent:deletion_complete` is the producer's attestation that it was kept.

The falsifiable claim, and it fails today: **"completed" on this screen means
the duty was discharged.** `status == "completed"` is a row in the agent's own
ticket table (`routes/tickets.py`), written by the operator's own process. CC
3.3.1 D1 rules that *proof of deletion is the absence, not a new artifact* —
the row withdrawn, superseded or hard-deleted — and that an affirmative
`consent:deletion_complete` is *evidence toward* it and never a substitute.
This screen shows neither. It shows a word.

## 2. Surface (what)

```yaml csd:surface
surface: tickets
screen: Tickets
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_tickets`. `agentOnly` (`CirclesNav.kt:154`) — **and that is the
placement this CSD disputes**, see §3.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: duty:{kind}
    bind: {kind: dsar_access}
    use: display-only
    type: string
    example: "DSAR_ACCESS"
    renders: "the SOP chip on the row — which standard operating procedure this request rides"
    tag: "proposed:tickets_row_sop"
  - ceg: x_private:subject_email
    use: display-only
    type: string
    example: "someone@example.org"
    renders: "WHO ASKED — another living person's contact address, in a list"
    tag: "proposed:tickets_row_email"
  - ceg: x_private:ticket_status
    use: display-only
    type: "enum[pending,in_progress,completed,failed,cancelled]"
    example: "pending"
    renders: "a coloured status chip (TicketsScreen.kt:571)"
    tag: "proposed:tickets_row_status"
  - ceg: x_private:ticket_deadline
    use: display-only
    type: timestamp
    example: "2026-10-25T00:00:00Z"
    renders: "Deadline — the date the duty is owed by"
    tag: "proposed:tickets_row_deadline"
  - ceg: x_private:ticket_priority
    use: display-only
    type: int
    example: 9
    renders: "an URGENT badge at 8 and above (TicketsScreen.kt:448)"
    tag: "proposed:tickets_row_priority"
  - ceg: consent:{kind}
    bind: {kind: deletion_complete}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WAS IT ACTUALLY DONE — not on this screen. CC 3.3.1 makes this the producer's attestation that revoked content was evicted; the wire carries a local status word instead."
    tag: "proposed:tickets_row_deletion_complete"
    blocked_by: [CIRISAgent#1207, CIRISServer#671]
  - ceg: x_private:ticket_automated
    use: display-only
    type: bool
    example: true
    renders: "Handled automatically — whether a person or the agent processed it"
    tag: "proposed:tickets_row_automated"
```

**`consent:deletion_sla:{days}` cannot be written as a `ceg:` id against this
registry, and that is a registry defect rather than an omission here.** CC
3.3.1:690 catalogues the leaf with three segments; the registry declares
`consent:{kind}` with two (`literal consent` + `vocab {kind}`), so
`consent:deletion_sla:30` resolves to no family at all — as does
`consent:scope:analyze`, which CC 3.4.5 makes the precondition for every
`capacity:*` row. The deadline field above is therefore `x_private:` under
protest.

**The stat tiles cost a second full fetch of every ticket.**
`getTicketStats()` does not call a stats route; it calls
`listTickets(limit = 1000)` and counts in the client
(`CIRISApiClient.kt:11951`). So rendering four numbers pulls up to a thousand
other people's email addresses across the wire on every refresh, and the
`urgent` tile is computed from `priority >= 8` locally
(`CIRISApiClient.kt:11959`). A count is not worth a thousand identities.

```yaml csd:states
populated: {tag: "proposed:tickets_list", renders: "the four stat tiles, the filter row, then one expandable card per ticket"}
empty:     {tag: "proposed:tickets_empty", renders: "No tickets found (TicketsScreen.kt:207)"}
loading:   {tag: "proposed:tickets_loading", renders: "a progress indicator instead of the list on first load; a small inline one over an existing list (TicketsScreen.kt:226)"}
error:     {tag: "proposed:tickets_error", renders: "the error card at TicketsScreen.kt:164 — REACHABLE, because fetchTickets sets state.error (TicketsViewModel.kt:205)"}
```

This is the only one of the seven agent-work surfaces whose error state can
actually be reached from its load path. The message it carries is raw English —
`"Failed to load tickets: ${e.message}"` (`TicketsViewModel.kt:211`) — so a
person on any of the other 28 locales reads an English sentence with a stack
message in it.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the ticket list | `GET /v1/tickets` | CIRISAgent (`routes/tickets.py:385`, prefix `:37`) | live — but the client calls `"/v1/tickets/"` **with a trailing slash** (`TicketsApi.kt:210`) against a route registered as `@router.get("")`; it works only through FastAPI's `redirect_slashes` 307 |
| one ticket | `GET /v1/tickets/{ticket_id}` | CIRISAgent (`routes/tickets.py:356`) | live |
| create | `POST /v1/tickets` | CIRISAgent (`routes/tickets.py:264`) | live — same trailing-slash shape |
| the SOP list | `GET /v1/tickets/sops` | CIRISAgent (`routes/tickets.py:189`) | live |
| one SOP's metadata | `GET /v1/tickets/sops/{sop}` | CIRISAgent (`routes/tickets.py:218`) | live |
| the stat tiles | **no route** — computed client-side over `limit=1000` | — | **missing**; ask below |
| proof the duty was discharged | **no route** — `consent:deletion_complete`, CC 3.3.1 | CIRISAgent as producer | **missing**; blocks `building` for `proposed:tickets_row_deletion_complete` |

**Wrong circle, and the reason is legal rather than aesthetic.** A data-subject
request is an obligation on the **controller** — the person who runs the node —
and it does not become optional because they chose to run without AI.
`CIRISServer origin/main` serves `/v1/auth/erasure` and `/v1/system/data*` but
has no `/v1/tickets` and no `/v1/dsar` literal anywhere in `src/`, and the card
is `agentOnly`, so **a run-without-AI install has no inbound-request surface at
all**. Two consistent fixes exist and one must be chosen: give the node the
ticket routes and drop `agentOnly`, or state plainly that a node without an
agent accepts no requests. Leaving it as it is means the obligation is invisible
on exactly the install that cannot automate it.

**CIRISAgent working tree is dated 2026-08-15**, five weeks behind today; the
trailing-slash observation is against that checkout.

## 4. Flow (how)

My things → This node → Tickets, on an agent that has handled a DSAR.

```yaml
expect:
  state: populated
  visible: [btn_tickets_refresh, btn_tickets_toggle_filters]
  count: {of: "proposed:tickets_row_*", min: 1}
```

Open the filters and select a status no ticket has.

```yaml
expect:
  state: empty
  visible: [proposed:tickets_empty]
```

Point the client at an agent whose ticket store is unavailable and refresh.

```yaml
expect:
  state: error
  visible: [proposed:tickets_error]
  absent: [proposed:tickets_empty]
```

## 5. QA plan

**Platforms.** All five. The expandable card and the two dropdown filters are
plain Compose.

**Not tested here.** Whether a DSAR was actually answered — nothing on the wire
reports it (see the `consent:deletion_complete` row). The budget-envelope keys
in `TicketData.metadata` (`CIRISApiClient.kt:14347`), which belong to the HITL
approval seam and are read by `BudgetApprovalSeam`, not drawn here.

**Upstream asks.**
CIRISAgent — add `GET /v1/tickets/stats` so four counters stop costing a
thousand rows of other people's PII; and emit `consent:deletion_complete` (or
say the agent does not) when a deletion SOP closes.
CIRISConstitution — the registry's `consent:{kind}` is two segments and CC
3.3.1 catalogues three-segment leaves (`consent:deletion_sla:{days}`,
`consent:scope:*`, `consent:state:expired`); no CSD can name them today.
