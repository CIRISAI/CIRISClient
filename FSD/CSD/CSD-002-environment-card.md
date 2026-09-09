# CSD-002 — Environment page (Local Community layer)

**CSD**: CSD-002 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/environment_card.yaml

> Profile demonstration; must go upstream and be deleted here.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A user can now reach the environment page from the Local Community hub, and
get back.** Both directions: an entry that cannot be left is a trap, and it is
the half that usually ships broken because nobody drives it twice.

## 2. Surface (what)

```yaml csd:surface
surface: layer-local-community
screen: LayerLocalCommunity
```

```yaml csd:shows
registry_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5
fields:
  - ceg: "mesh_config:{key}"
    bind: {key: environment}
    use: display-only
    type: string
    example: "local-community"
    renders: "Environment — local community"
    tag: "proposed:card_local_community_environment"
```

```yaml csd:states
populated: {tag: card_local_community_environment}
empty:     {tag: "proposed:text_environment_empty", renders: "No environment recorded for this community."}
loading:   {renders: "the card frame with a progress affordance"}
error:     {tag: "proposed:text_environment_error", renders: "Could not read the environment."}
```

`mesh_config:{key}` is CC 3.1.9.2, `node`-owned and **not** reserved — so
`display-only` here is a choice this CSD makes rather than one the registry
forces, and it is the right one: the page reports configuration, it does not set
it.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| environment | **unconfirmed** | CIRISServer | blocks `building` |

## 4. Flow (how)

Two steps upstream, and the second is the one that matters: back returns to the
hub. Unchanged.

## 5. QA plan

**Platforms.** All five.

**Untested and must be established**
* The read behind the card (§3).
* Three of four states.
* That the back destination is the hub and not `homeTarget` — on a node install
  those differ, which CIRISClient#48 established the hard way.
