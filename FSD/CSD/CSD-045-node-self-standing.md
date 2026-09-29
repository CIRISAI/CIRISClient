# CSD-045 — This node's own standing (shed load · stop accepting · legal compulsion)

**CSD**: CSD-045 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route-coverage pass for #90
**Flow**: `testing/flows/drafts/csd-045-node-self-standing.yaml`
**Reads with**: CSD-036 (This node › Network, which hosts tier R and used to host this card as a section)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Why `building` now.** This card was `envisioned` because the standing was a
section at the bottom of Network and not a surface, so there was no hop to
derive. It now has one: `NavSurface.NodeSelf` under This node, `Screen.NodeSelfStanding`,
`NodeSelfStandingScreen.kt`. Every route is confirmed against CIRISServer source.
The one field no route served — the owner's `delegation_id` — is served by
0.5.218 (`GET /v1/admin/self` → `owner_delegations`, CIRISServer#676), and the
client reads it; on an older node the field is typed, and the screen says why.
Not `testable`: the floor is still `unreleased` for the 0.5.218 half, and the
flow has not run on the matrix.

## 1. Mission (why)

**The owner of a node can say, on the record, what this node has done to itself:
it is shedding load, it has stopped accepting new work, or it is under legal
compulsion. They can lift each of these, and read all three standings side by
side, never folded into one.**

The node has all of this: CIRISServer#345, "Tier S", `src/admin_ops.rs`. It is
the one rung of the admin ladder that works while the node is **partitioned**,
because every act on it touches only this node's own database.

The compulsion declaration is the product's warrant-canary act, in its honest
form. It is not a standing "we have not been served" statement that goes silent
when a warrant arrives. It is an **affirmative, attributed declaration** that
force has been applied from outside the mesh. A reason is required, and naming
the compelling authority (`compelled_by`) is deliberately optional: an operator
under a gag order may be unable to name it, and the most constrained operator
must still be able to leave a trace. The act changes nothing the node does. It
is kept apart from "stopped accepting" so that no one downstream can mistake a
compulsion for a choice.

Serves **CC 3.1.9.4** (`hard_case:{kind}`, the attributed audit plane) and the
CC 3.2 owner-authority rule: only the node's responsible party may act here, in
person. A delegated bearer token cannot run any rung of this ladder.

## 2. Surface (what)

```yaml csd:surface
surface: node-self
screen: NodeSelfStanding
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_node_self`. Shown on every build: the routes are the node's and
need no agent. **It calls the node's address**, not `$baseUrl` — every method
defaults `nodeUrl = LOCAL_NODE_URL` (`CIRISApiClient.kt`, `getSelfStanding` and
the six `self*` acts). The agent forwards `/v1/admin/*` to the node from agent
2.12.1 (CIRISAgent#1213, closed by #1215) and 404s it before that; the direct
node address works on every agent version, so the card keeps using it.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:self_standing_load_shed
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "never_declared"
    renders: "Shed load — Never declared / In force / Declared, then lifted / Standing unknown — could not read; four chips, four colours, four meaning lines"
    tag: chip_self_standing_load_shed
  - ceg: x_private:self_standing_accepting
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "in_force"
    renders: "Stop accepting — In force · Since 2026-09-25T14:02:00+00:00 · Reason recorded: planned migration"
    tag: chip_self_standing_accepting
  - ceg: x_private:self_standing_legal_compulsion
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "never_declared"
    renders: "Legal compulsion — Never declared. When in force: since, reason, and the confirm named 'authority not named' if none was given"
    tag: chip_self_standing_legal_compulsion
  - ceg: x_private:self_count_declarations
    use: display-only
    type: int
    example: 2
    renders: "Declared 2× · lifted 1× — history, not state: 'declared and lifted' is a different fact from 'never declared'"
    tag: text_self_counts_load_shed
  - ceg: x_private:owner_delegation_id
    use: display-only
    type: string
    example: "att-owner-serve-1"
    renders: "Owner delegation id — Named by this node: att-owner-serve-1 (mono). Read, never typed, on a 0.5.218 node"
    tag: row_self_owner_delegation
  - ceg: x_private:node_key_id
    use: display-only
    type: string
    example: "ciris-node-4a19c2"
    renders: "This node — ciris-node-4a19c2 (mono)"
    tag: row_self_node_key
```

**The three zeroes are three different facts, and the node says so**
(`admin.self.distinct_zeroes`, rendered at `text_self_distinct_zeroes`): never
declared, declared and lifted, and could not be read. They render differently
(`SelfStandingChip`: colour, label AND meaning line). `unreadable` is the error
treatment, never "not declared".

**The display strings come from the node.** Every standing carries
`message: {id, text}` (`admin.self.*`) and the response carries `partition` and
`distinct_zeroes` notes; the card resolves the `id` through the bundle and falls
back to `text` (`serverMessage`). It never writes its own sentence for a standing.

**Where the delegation comes from (CIRISServer#676).** Every act must name the
owner's own `infra:serve` delegation. A 0.5.218 node returns
`owner_delegations` beside the standings; the client's `OwnerAuthority` keeps four
facts apart and renders each differently:

| the node said | renders | tag |
|---|---|---|
| one delegation | the id, read-only | `row_self_owner_delegation` |
| several | a radio list, first selected | `opt_self_owner_delegation_{i}` |
| an empty list | "holds no delegation … cannot record this act"; the act cannot be sent, because the server re-derives the same set | `text_self_no_owner_delegation` |
| null with `owner_delegations_error` | a typed field, "could not read your delegations just now" | `input_self_delegation_id` + `text_self_delegation_fallback` |
| nothing (a node before 0.5.218) | a typed field, "this node can't name your delegation (older than 0.5.218)" | `input_self_delegation_id` + `text_self_delegation_fallback` |

**The act confirms.** A standing is an attributed, permanent record: a lift
supersedes it and never erases it. So every act goes `btn_self_declare_{axis}` /
`btn_self_lift_{axis}` → the dialog (`input_self_reason`, and
`input_self_compelled_by` on the compulsion declaration only) → `btn_self_review`
→ a `ConfirmSheet` (`sheet_self_act`) with exactly three facts: what is recorded
(`self_act_fact_1`: axis, declared/lifted, the reason, and for compulsion the
authority or "authority not named"), what it changes (`self_act_fact_2`: nothing
the node does; it stays in the history after it is lifted), who signs
(`self_act_fact_3`: you, as this node's owner, under the delegation). Confirm is
`btn_self_act_confirm`.

```yaml csd:states
populated: {tag: card_self_directed, renders: "all three axes, side by side (card_self_axis_{axis}), each with its own standing, since, reason and counts"}
empty:     {tag: chip_self_standing_load_shed, renders: "not a whole-card state: an axis that has never been declared is a chip saying 'Never declared', and the card always shows all three"}
loading:   {tag: progress_self_standing, renders: "the card frame with a progress affordance and no chip"}
error:     {tag: banner_self_unreachable, renders: "'This node could not be reached — the three standings are unknown, which is not nothing in force.' A refused read is banner_self_refused; a 503 with unreadable_axes is banner_self_unreadable_axes and every other axis still renders, with the unreadable one saying 'could not read', never 'never declared'"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the three standings | `GET /v1/admin/self` (`src/admin_ops.rs:4288` on 0.5.217; `:4384` on `integ/0.5.218`) — **503** when any axis is unreadable, with that axis still in the body | CIRISServer | live — `getSelfStanding` (`CIRISApiClient.kt:5263`) from `SelfReaderOpsSection.kt` `reloadStandings()` |
| the owner's delegations | `GET /v1/admin/self` → `owner_delegations[] {delegation_id, issuer_key_id, subject_key_id, scope, owner_binding, cohort_scope, asserted_at}` and `owner_delegations_error` (`owner_serve_delegations`, `src/admin_ops.rs:3470-3526` on `integ/0.5.218`) | CIRISServer | live on 0.5.218 (unreleased) — `OwnerDelegationDto`, `SelfStandingResponse.ownerAuthority()`; **absent on ≤0.5.217**, rendered as the typed fallback, never as "none held" |
| shed / resume | `POST /v1/admin/self/shed` · `/resume-load` (`:4289`, `:4291`) | CIRISServer | live — `selfShedLoad` / `selfResumeLoad` |
| stop / resume accepting | `POST /v1/admin/self/stop-accepting` · `/resume-accepting` (`:4295`, `:4299`) | CIRISServer | live — `selfStopAccepting` / `selfResumeAccepting` |
| declare / lift compulsion | `POST /v1/admin/self/compelled` · `/compulsion-lifted` (`:4303`, `:4307`) | CIRISServer | live — `selfDeclareCompelled` / `selfCompulsionLifted` |
| request body, every act | `SelfCommit {delegation_id, reason, compelled_by?}` (`src/admin_ops.rs:3372-3384`): both required, `reason` refused by name when empty (`admin.refusal.reason_absent`); `compelled_by` read only by the compulsion declaration | CIRISServer | live — `SelfCommitRequest` |
| reach from a with-AI install | `/v1/admin/*` through the agent | CIRISAgent | live from agent 2.12.1 (CIRISAgent#1213, closed by #1215); older agents 404. The card calls the node URL directly, which works on every agent version, so it does not depend on it |
| a declaration that anyone else can see | the act writes a `hard_case:admin_action:{op}` row into persist's local `hard_case_events`: unsigned, no `cohort_scope`, not an attestation, so nothing replicates it | CIRISServer / CIRISPersist | **missing**: CIRISServer#675 |

**Registry gap.** The row kind is `admin_action:{op}` (persist `hard_case.rs`),
so the full dimension is `hard_case:admin_action:self_compelled`. The registry's
`hard_case:{kind}` has one vocabulary segment and cannot name it (the same
variadic-segment problem as `accord:*`, CIRISConstitution#108), so the standings
are bound as `x_private:` members here.

## 4. Flow (how)

Sign in as the node's owner; open My things › This node › Own standing.

```yaml
expect:
  state: populated
  visible: [screen_node_self, card_self_directed, row_self_node_key]
  count: {of: "card_self_axis_*", eq: 3}
  one_of: {chip_self_standing_load_shed: ["Never declared", "In force", "Declared, then lifted", "Standing unknown — could not read"]}
```

Declare compulsion without naming the authority: tap
`btn_self_declare_legal_compulsion`, type a reason into `input_self_reason`,
leave `input_self_compelled_by` blank, tap `btn_self_review`.

```yaml
expect:
  visible: [sheet_self_act, self_act_fact_1, self_act_fact_2, self_act_fact_3, btn_self_act_confirm]
  matches: {self_act_fact_1: ".*authority not named.*"}
```

Confirm. The axis reads **In force**; lift it with
`btn_self_lift_legal_compulsion`. The axis now reads **Declared, then lifted**,
and its counts read declared 1 · lifted 1. It does not revert to "Never declared":
"a compulsion that ENDED is not a compulsion that never happened"
(`admin.self.lift.compelled`).

A reason left empty keeps `btn_self_review` disabled, and the node's
`admin.refusal.reason_absent` is rendered (`banner_self_act_refused`) if it
arrives anyway.

## 5. QA plan

**Platforms.** All five, against a claimed node with an owner session. The
with-AI legs exercise the node URL, not the agent port. The delegation-supplied
path needs a 0.5.218 node; the typed fallback needs a 0.5.217 one, and the
matrix should carry both until 0.5.218 is the floor.

**Negative paths.** An unowned node (`node_unowned`), a session that isn't the
owner's (`not_owner`, `authority_not_the_owner`), a missing reason, a node older
than CIRISServer#345 (404 with no id → `banner_self_refused` with the status),
and a node that reads no owner delegation (`text_self_no_owner_delegation`, the
act unsendable).

**Not tested here.** Behaviour under a real partition. Whether a peer can see the
declaration: it cannot today, and that is the upstream gap in §3, not a client test.

## 6. Delta — card vs API vs CC

- **Card vs API.** The API is complete for the owner's own view: six acts, one
  read, localized strings, a 503 that refuses to let an unreadable axis look
  like a zero, and (0.5.218) the delegation the act is taken under. The client
  calls all of it. What was wrong before this revision: the card was a section
  at the bottom of the Network card, under the disk budget, with no surface of
  its own; the delegation id had to be typed from nowhere; and an act went
  straight from a dialog to the wire with no confirm.
- **API vs CC.** CC 3.2 makes this the owner's alone, and the node enforces that
  (`resolve_owner_authority` plus `is_steward_bound`). CC 3.1.9.4's attributed
  audit is honoured locally. What CC's purpose for the act implies, and the route
  doc promises, is that a *peer* can read it. That fails: the row is a local,
  unsigned table entry (CIRISServer#675).
- **Reach.** Through the agent from agent 2.12.1 (CIRISAgent#1213, closed by
  #1215); node-only before that. The card uses the node address, which works on
  every agent version.
- **Registry.** `hard_case:{kind}` cannot name the two-segment `admin_action:{op}`
  kind (see §3).
