# CSD-003 — Constitutional screen and the Accord cards

**CSD**: CSD-003 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/constitutional_card.yaml

> Profile demonstration; must go upstream and be deleted here.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A user can now see whether the halt authority is armed, who holds the Accord,
and reach the ceremony and provisioning entry points — on one screen.** The
killswitch card is the reason this CSD is not optional: it is the surface of
**CC 4.2** halt authority ("no objective can outvote it"), one of the three
claims CIRISServer#536 names as load-bearing on the public /safety page. **A
safety surface that silently fails to render is worse than one that is absent,
because absence is at least visible.** Serves **Core Identity** and **Integrity**.

## 2. Surface (what)

```yaml csd:surface
surface: constitutional
screen: Constitutional
```

A **child** surface: `nav_map` derives `nav_group_commons-layers ->
nav_epistemic_layer_global_commons -> nav_epistemic_constitutional`. Clicking it
directly would fail as "element not found" on a sidebar whose parent had not
expanded — indistinguishable from a broken screen, which on a safety surface is
the worst possible confusion.

```yaml csd:shows
registry_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5
fields:
  - ceg: "accord:halt_status"
    use: display-only
    type: "enum[armed,disarmed]"
    example: "armed"
    renders: "Halt authority — ARMED"
    tag: "proposed:card_accord_killswitch"
    assert:
      one_of: {card_accord_killswitch: ["armed", "disarmed"]}
  - ceg: "accord:holders"
    use: display-only
    type: "list[string]"
    example: ["wa-holder-1c4f", "wa-holder-9b02", "wa-holder-33da"]
    renders: "3 holders"
    tag: "proposed:card_accord_holders"
  - ceg: "trust:{job}:{version}"
    bind: {job: root, version: v1}
    use: display-only
    type: string
    example: "wa-root-7c19"
    renders: "Trust root — wa-root-7c19"
    tag: "proposed:card_constitutional_overview"
```

```yaml csd:states
populated: {tag: screen_constitutional}
empty:     {tag: "proposed:text_accord_empty", renders: "No Accord is provisioned on this node."}
loading:   {renders: "the card frames with progress affordances"}
error:     {tag: "proposed:text_accord_error", renders: "Could not read the Accord."}
```

**The error state is mandatory here in the strongest sense.** §1 says a safety
surface that fails silently is worse than an absent one; rendering a failed
halt-status read as "disarmed" — or as nothing — is precisely that. `one_of`
exists on the killswitch so a third value cannot be shown as either.

`accord:*` is **reserved**, `accord_holder-only` per **CC 3.4.1**, owned by
`registry`/CIRISRegistry. `display-only` is therefore enforced: this client
cannot mint Accord state, and an `emit` here fails the load. The two entry
points open ceremonies that write — through the holder's own authority, not the
client's.

## 3. Contracts (who)

Confirmed against ciris-server 0.5.199's route table (the pinned wheel).

| value | endpoint | owner |
|---|---|---|
| trust root | `/v1/trust-root` | CIRISServer |
| this node's holder record | `/v1/accord/holder` | CIRISServer |
| halt status | `/v1/accord/halt-status` | CIRISServer |
| holder set | `/v1/accord/holders` | CIRISServer |
| ceremony entry | `/v1/accord/provision` | CIRISServer |
| provision-holder entry | `/v1/accord/provision-holder` | CIRISServer |

**These are confirmed** — unlike CSD-001/002/004, this CSD's §3 is answered, so
its blocker to `building` is the `proposed:` tags rather than the substrate.

## 4. Flow (how)

Four steps upstream. Unchanged.

## 5. QA plan

**Platforms.** All five.

**Untested and must be established**
* Every state but populated.
* That halt status renders its actual value rather than a default — the
  `one_of` above is written and not yet assertable, because the tag is proposed.
* That the two entry points open ceremonies rather than merely existing.
