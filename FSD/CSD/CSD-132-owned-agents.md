# CSD-132 — Agents you own (My things › Devices & keys)

**CSD**: CSD-132 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the steward's ask for a browsable roster of the agents a person owns; CIRISServer#840 (owned-nodes fields, rename of any owned node)
**Reads with**: CSD-035 (Nodes — every node, agents or not), CSD-037 (release, held for the roster's quorum), CSD-085 (claim), CSD-087 (verify a build), CSD-131 (owner requests, which confirm a managed agent)
**Flow**: none yet — the fields are unreleased (§3)
**Built**: `ui/screens/OwnedAgentsScreen.kt`, `NavSurface.OwnedAgents`, `ownedAgents()` (`models/federation/NodeCode.kt`); tested by `OwnedAgentsTest`

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every agent they own in one list, and from each row rename
it, make it known to the federation, check its build, or release it. Nothing
the owner's node does not know is shown as known.**

Agents ran on owned nodes, but the Nodes list did not say which, so "which of
my things are agents" had no answer on screen. The owner's node can say which
nodes have an agent paired under the owner, whether each is announced, and
what the owner named it. It cannot say an agent's version or whether it is up:
no node carries a signed status row yet. Serves **Transparency** (unknown is
said, never filled in) and **Integrity** (release is the roster's decision,
CSD-037).

## 2. Surface (what)

```yaml csd:surface
surface: owned-agents
screen: OwnedAgents
```

**Its own row under My things › Devices & keys,** beside My Identity. One card
per agent node, agents first:

| row | when | what it says |
|---|---|---|
| **Runs an agent** | the owned-nodes row's `agents` names at least one occurrence | the name (or "Unnamed"), the node key, whether it is known to the federation |
| **May run an agent** | `agents` is absent (unknown), and the node is not one of your own roster devices | the same, plus that its agent is not confirmed from one of your devices yet (CSD-131) |

A roster device with no agent is a device, not an agent, and stays on My
Identity. Each card offers **Rename** (a dialog), **Make known to the
federation** (only while not announced), **Check its build** (CSD-087) and
**Release** (CSD-037's confirm; held for the roster's quorum, after which the
device erases itself). **Claim a node** (CSD-085) sits above the list.
Stopping or retiring an agent is the agent's own ladder (T16, CIRISAgent) and
is said, not offered.

**Release and Rename are one action with two doors.** They are the same view
model, confirm and route as on My Identity (CSD-037), reached from where a
person looks for the agent; the route checker's baseline records the pair.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:owned_agent
    use: display-only
    type: string
    example: "Echo · 3f9a…c21e · Runs an agent · Known to the federation"
    renders: "one card per owned agent node: label or 'Unnamed', the node key, 'Runs an agent' or 'May run an agent', and the announced state (or that it is not recorded)"
    tag: "row_owned_agent_*"
  - ceg: x_private:owned_agent_rename
    use: emit
    type: string
    example: "Echo"
    renders: "Rename: a dialog with the current name; saves through the label route, which names any owned node"
    tag: "btn_owned_agent_rename_*"
  - ceg: x_private:owned_agent_announce
    use: emit
    type: string
    example: "3f9a…c21e"
    renders: "Make known to the federation, only while not announced; a refusal renders by id"
    tag: "btn_owned_agent_announce_*"
  - ceg: x_private:owned_agent_release
    use: emit
    type: string
    example: "3f9a…c21e"
    renders: "Release, behind CSD-037's three-fact confirm; held for the roster's quorum"
    tag: "btn_owned_agent_release_*"
```

```yaml csd:states
populated: {tag: screen_owned_agents, renders: "the intro, Claim a node, and one card per agent node"}
empty:     {tag: owned_agents_empty, renders: "'You don't own a node that runs an agent yet. Claim one to see it here.'"}
loading:   {tag: screen_owned_agents, renders: "the intro and Claim; the cards fill in when the owned nodes are read"}
error:     {tag: owned_agents_announce_error, renders: "an announce refusal by id; release and rename refusals render through their own confirm and dialog"}
```

## 3. Contracts (who)

All at the local node, owner session.

| value | endpoint | owner | state |
|---|---|---|---|
| the owned nodes | `GET /v1/setup/owned-nodes` → `{owner, nodes: [{key_id, is_self, roster_member, announced?, label?, agents?}]}` | CIRISServer#840 (0.5.242) | **unreleased.** `agents` absent = unknown, never none; it appears on a managed agent once the owner signs its `community_trust` request (CSD-131). `roster_member: false` can mean "not known to be" (CIRISPersist#1158). Older nodes send `{key_id, is_self}` and every row is "may run an agent" unless it is this device |
| rename | `POST /v1/self/occurrence/label {occurrence_key_id: <node key>, label}` | CIRISServer#840 | **unreleased** for a node key (live for roster devices since 0.5.216) |
| announce | `POST /v1/self/nodes/{node_key_id}/announce` | CIRISServer | live |
| release | `POST /v1/self/nodes/{node_key_id}/release` | CIRISServer | live; held for the roster's quorum on the release branch (CSD-037) |
| shared reads of the view model it shares with My Identity (CSD-037) | `GET /v1/federation/self-key-record`, `GET /v1/self/occurrences`, `GET /v1/system/health` | CIRISServer | live — read once on entry by `IdentityManagementViewModel.load()`; this screen draws none of them |
| version, status, last seen | none | CIRISServer · CC | **not carried**: needs a signed node-status row (a new family), not yet scoped. Rendered as nothing, never as a guess |

## 4. Flow (how)

1. My things › Devices & keys › Agents you own: `screen_owned_agents`.
2. A node with `agents` shows "Runs an agent"; rename it (`btn_owned_agent_rename_{key}` → `input_owned_agent_name` → `btn_owned_agent_rename_save`); the card shows the new name on the re-read.
3. On a node not yet announced, `btn_owned_agent_announce_{key}`; the card reads "Known to the federation" on the re-read.
4. `btn_owned_agent_release_{key}` opens CSD-037's release confirm.

## 5. QA plan

`OwnedAgentsTest`: the new fields decode and an older row leaves them unknown;
agents come first, nodes that may run one next, and the owner's own plain
devices never appear. The nav tests cover the placement and the screen's
mapping.

## 6. Delta — card vs API vs CC

* **No status.** An agent's version, health and last-seen need a signed
  node-status row; until one exists the card shows none of them.
* **Retirement** is the agent's (T16); the card links the fact, not the act.
