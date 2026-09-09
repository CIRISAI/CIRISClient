# CSD-001 — Delegation card and screen (Family layer)

**CSD**: CSD-001 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/delegation_card.yaml

> Profile demonstration. The canonical CSD-001 is CIRISAgent's; this shows CSD/3
> filled in and must go upstream and be deleted here.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A user can now see who they have authorised to act on their behalf, and reach
the grant management flow, from the Family layer.** A delegation the owner cannot
see is an authority they did not knowingly give. Serves **Core Identity**.

## 2. Surface (what)

```yaml csd:surface
surface: layer-family
screen: LayerFamily
```

`nav_map` derives `nav_group_commons-layers -> nav_epistemic_layer_family`. The
screen the card OPENS (`Delegation`) is a child surface and the flow drives the
click; only the entry point is named here.

```yaml csd:shows
registry_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5
fields:
  - ceg: "consent:{kind}"
    bind: {kind: delegation}
    use: display-only
    type: "list[string]"
    example: ["wa-peer-4a19c2"]
    renders: "Delegated to wa-peer-4a19c2"
    tag: "proposed:card_delegation_outbound"
  - ceg: identity_continuity:relational_anchor
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "Acting for wa-self-88b1"
    tag: "proposed:card_delegation_inbound"
```

**Outbound and inbound are different claims and must not share a tag.** #45's
own history says so: an earlier cut reported outbound grants as inbound
authority, which tells an owner someone has power over them when the truth is
the reverse.

```yaml csd:states
populated: {tag: card_family_delegations}
empty:     {tag: "proposed:text_delegation_empty", renders: "You have not delegated to anyone."}
loading:   {renders: "the card frame with a progress affordance"}
error:     {tag: "proposed:text_delegation_error", renders: "Could not read delegations."}
```

`consent:{kind}` is **reserved** (CC 3.1.5, `accord-agent`), so `display-only`
is checkable: this surface shows grants and does not mint them — the ceremony
does.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| delegation grants | `/v1/self/delegations` — **unconfirmed** shape | CIRISServer | blocks `building` |

## 4. Flow (how)

Five steps upstream: the card is on the hub, it opens, the screen composes its
three panels, refresh is live, back returns. Unchanged — every CSD/3 addition
here rests on `proposed:` tags, which may not enter a flow.

## 5. QA plan

**Platforms.** All five.

**Untested and must be established**
* The grant payload shape (§3), and therefore every `shows:` type.
* All four states — no tag for empty or error is confirmed.
* That inbound and outbound are rendered from different reads rather than one
  list filtered in the UI, which is what made the earlier defect possible.
