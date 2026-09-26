# CSD-001 — Delegation (the read view, and the card that opens it)

**CSD**: CSD-001 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/delegation_card.yaml
**Rebound**: 2026-09-25, for the circles redesign. What this file said before is
in §6, because two of its claims were wrong and one has since been fixed in code.

> Profile demonstration. The canonical CSD-001 is CIRISAgent's; this shows CSD/3
> filled in and must go upstream and be deleted here.

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person standing in Family can see what authority their keys have handed to
other parties, and the inverse — and where the screen cannot see the inverse, it
says so instead of reporting zero.** A delegation the owner cannot see is an
authority they did not knowingly give. Serves **Core Identity**.

**This card and CSD-055's `Delegations` read the same list from the same view
model, and in the Family circle they are adjacent rows of the same tab.**
`Screen.Delegation` composes `federation.DelegationScreen(delegations =
delegationsViewModel.delegations, …)` (`CIRISApp.kt:4717-4727`), which is the
same `DelegationsViewModel` `Screen.Delegations` uses, and its one action —
`btn_delegation_manage_grants` — navigates to `Screen.Delegations`
(`:4725`). So Family › Rules offers "Delegation" and "Delegations", from one
source, and the first is a read-only preamble to the second.

One of them should go. The read view is the weaker of the two: it has eight tags
to CSD-055's twenty-six, no revoke, no approve, and its two headings are
hard-coded English (`"INBOUND DELEGATIONS"`, `"OUTBOUND DELEGATIONS"`, and the
sentences under them — `federation/DelegationScreen.kt:186, 193, 240, 247`),
which is a 29-bundle gap in a repo that keeps them at parity with a check.

## 2. Surface (what)

```yaml csd:surface
surface: delegation
screen: Delegation
```

`nav_map` derives `circle_family -> tab_rules -> nav_epistemic_delegation` — the
Family circle's Rules tab. The card that opens it from the layer hub
(`card_family_delegations`, `LayerHubScreen.kt:225`, with
`btn_open_delegations`) is a second entry point to the same screen and belongs
to CSD-050's screen; it is named here because the flow clicks it.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:delegated_scope
    use: display-only
    type: "list[string]"
    example: ["wa-agent-7c31"]
    renders: "OUTBOUND DELEGATIONS — scopes this agent has delegated to paired devices and occurrence instances"
    tag: card_delegation_outbound
  - ceg: x_private:inbound_delegations
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "INBOUND DELEGATIONS — and, under it, that the screen cannot see them. UNAVAILABLE, not empty."
    tag: txt_delegation_inbound_unavailable
    blocked_by: CIRISServer#663
  - ceg: x_private:delegation_count
    use: display-only
    type: int
    example: 2
    renders: "the overview count, outbound only"
    tag: card_delegation_overview
```

**Outbound and inbound are different claims and must not share a tag.** They do
not, and the code now says why at the point of the defect: *"It first reported
those outbound grants as inbound authority; the fix for that replaced the count
with a flat 'no active inbound delegations', which is the same unsupported claim
with the sign flipped. The screen cannot see this, so it says so."*
(`federation/DelegationScreen.kt:197-206`). Both the original defect and its
first, wrong fix are recorded there. **This is the state CSD/3 wants** — a third
thing that is neither populated nor empty, named `Fact.NotSent` in CSD-006 — and
it is why the inbound row above is `unconfirmed` rather than a count.

`delegates_to` is a CC 2.4.1 structural primitive and an envelope relation, not
a registry family (`client/ceg/README.md`), so every row is `x_private:`. See
CSD-055 §2 for the same reasoning at more length.

```yaml csd:states
populated: {tag: card_delegation_outbound, renders: "the outbound card with at least one grant"}
empty:     {tag: "proposed:text_delegation_empty", renders: "You have not delegated to anyone. — outbound, genuinely none"}
loading:   {tag: "proposed:delegation_loading", renders: "the card frame with a progress affordance (isLoading, DelegationScreen.kt)"}
error:     {tag: "proposed:text_delegation_error", renders: "Could not read delegations. — distinct from both the empty outbound card and the unavailable inbound one"}
```

Three states short a tag; `populated` and the not-available third state are
real. `screen_delegation`, `btn_delegation_back`, `btn_delegation_refresh` and
`btn_delegation_manage_grants` are the other real tags.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| outbound grants | `GET /v1/auth/device/grants` | CIRISServer | **live** (`src/auth/device_grant.rs:1356`) |
| inbound delegations | none — the screen makes no inbound query | CIRISServer | **missing.** The ask: a route returning `delegates_to` rows where this node's key is the *delegate*, not the delegator. Until then `txt_delegation_inbound_unavailable` is the correct render and blocks `building`. |

**`/v1/self/delegations` does not exist and this file used to claim it did.**
Zero hits in CIRISServer `src/*.rs` and zero in CIRISAgent; the node's `/v1/self/*`
routes are `nodes/{node_key_id}/release` and `occurrence/label`
(`src/self_devices.rs:469, 473`). The row above is the real one, and it is the
same route CSD-055 documents — further evidence the two cards are one card.

## 4. Flow (how)

Five steps upstream: the card is on the hub, it opens, the screen composes its
panels, refresh is live, back returns. Unchanged, and now partly assertable:
`card_delegation_outbound`, `card_delegation_inbound`, `card_delegation_overview`
and `txt_delegation_inbound_unavailable` are real tags, so the step that matters
can be written:

```yaml
expect:
  visible: [screen_delegation, card_delegation_outbound, card_delegation_inbound,
            txt_delegation_inbound_unavailable]
```

That last tag is the assertion: it pins that the screen keeps saying it cannot
see inbound authority, so the day someone "fixes" the blank by filling it from
the outbound list — which has already happened once — the flow goes red.

## 5. QA plan

**Platforms.** All five.

**Untested and must be established**
* The inbound read. There is no route (§3).
* Three of the four states — no tag for empty, loading or error is confirmed.
* That the two headings and their descriptions are localized. They are not.

**No longer untested.** The 2026-09 version of this file listed "that inbound and
outbound are rendered from different reads rather than one list filtered in the
UI" as open. It is closed, and in the strongest way available: they are not
rendered from different reads, there is only one read, and the screen says so
rather than pretending otherwise.

## 6. What this file said before the rebind

Three corrections, kept visible because a CSD that quietly changes its claims is
the drift this standard exists to measure.

1. **`/v1/self/delegations` — **unconfirmed** shape.** Wrong. The route does not
   exist on either host and never did. Replaced in §3.
2. **`ceg: "consent:{kind}" bind: {kind: delegation}`.** There is no
   `consent:delegation` leaf. CC 3.3.1's catalogue is closed at `state`,
   `stream`, `deletion_sla`, `deletion_complete`, `decay`, `partnership_grant`,
   `partnership_accept`, `scope`, `replication`. Delegation is a *structural
   primitive* (`delegates_to`, CC 2.4.1), not a consent leaf, and CC 2.4.1.2.1
   rules the two apart by name: *"Only delegation composes … A licence does not
   chain. A grant does not chain."* Replaced with `x_private:` rows.
3. **`ceg: identity_continuity:relational_anchor` for the inbound card.** Wrong
   family. That is CIRISPersist's substrate self-report of long-term key
   continuity (CC 3.1.3, reserved, substrate-self-report at CC 3.4.3) — a health
   signal about the node, not a statement about who has delegated to it. It also
   invited exactly the read the screen's own comment warns against, by giving the
   inbound card a family that resolves and a value that could be rendered.
4. **The surface was named `layer-family` / `LayerFamily`.** That was the card's
   entry point, which is CSD-050's screen; the hop now names the screen the card
   opens, which is what `check_csd_v3.py` resolves and what the flow drives.
