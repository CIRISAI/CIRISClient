# CSD-045 — This node's own standing (shed load · stop accepting · legal compulsion)

**CSD**: CSD-045 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route-coverage pass for #90
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: envisioned
owner: CIRISClient
```

## 1. Mission (why)

**The owner of a node can say, on the record, what this node has done to itself:
it is shedding load, it has stopped accepting new work, or it is under legal
compulsion. They can lift each of these, and read all three standings side by
side, never folded into one.**

The node already has all of this: CIRISServer#345, "Tier S", `src/admin_ops.rs`.
No card has it. It is the one rung of the admin ladder that works while the node
is **partitioned**, because every act on it touches only this node's own database.

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

**No `csd:surface` block yet, deliberately.** No `NavSurface` exists for this card,
so any `surface:` would fail the checker, and it is not `flow_only` either: it
belongs *in* the shell. Proposed: a `node-self` surface in the **This node**
instrument (`btn_my_things -> nav_instrument_this_node -> nav_epistemic_node_self`,
after #93 lands), shown on every build, because the routes are the node's and do
not need an agent.

**It must call the node's address**, not `$baseUrl`. `/v1/admin/*` is not forwarded
by the agent's `node_proxy.py`, so on a with-AI install it 404s on `:8080`
(CIRISAgent#1213). Use the node URL, as the accord calls already do
(`CIRISApiClient.kt`, `$localNodeUrl/v1/accord/...`).

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:self_standing_load_shed
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "never_declared"
    renders: "Shedding load — Not declared / In force since {since} / Lifted {since}; could not be read is its own row in the error tone"
    tag: "proposed:node_self_axis_load_shed"
  - ceg: x_private:self_standing_accepting
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "in_force"
    renders: "Stopped accepting new work — In force since 2026-09-25 14:02 · reason: planned migration"
    tag: "proposed:node_self_axis_accepting"
  - ceg: x_private:self_standing_legal_compulsion
    use: display-only
    type: "enum[in_force,lifted,never_declared,unreadable]"
    example: "never_declared"
    renders: "Under legal compulsion — Not declared. When in force: since, reason, and 'by {compelled_by}' or 'authority not named'"
    tag: "proposed:node_self_axis_legal_compulsion"
  - ceg: x_private:compelled_by
    use: display-only
    type: string
    example: "not named"
    renders: "shown only on the compulsion axis; its absence is rendered as 'authority not named', never as blank and never as a defect"
    tag: "proposed:node_self_compelled_by"
  - ceg: x_private:self_count_declarations
    use: display-only
    type: int
    example: 2
    renders: "Declared 2 times — with the lifts count below, 'declared and lifted' is history, not a blank"
    tag: "proposed:node_self_count_declarations"
  - ceg: x_private:self_count_lifts
    use: display-only
    type: int
    example: 1
    renders: "lifted once"
    tag: "proposed:node_self_count_lifts"
```

**The three zeroes are three different facts, and the node says so**
(`admin.self.distinct_zeroes`): never declared, declared and lifted, and could not
be read. They MUST render differently. `unreadable` is the error treatment, never
"not declared".

**The display strings come from the node.** Every standing carries
`message: {id, text}` (`admin.self.*`), and the response carries `partition` and
`distinct_zeroes` notes. The card renders the localized `id` and falls back to
`text`. It never writes its own sentence for a standing.

```yaml csd:states
populated: {tag: "proposed:node_self_list", renders: "all three axes, side by side, each with its own standing, since, reason and counts"}
empty:     {tag: "proposed:node_self_never", renders: "not used as a whole-card state: an axis that has never been declared is a row saying so, and the card always shows all three"}
loading:   {tag: "proposed:node_self_loading", renders: "the frame with a progress affordance and no sentence"}
error:     {tag: "proposed:node_self_error", renders: "503 with unreadable_axes: every other axis still renders, and the unreadable axis says 'could not be read', never 'not declared'. 403 node_unowned or not_owner: the node's refusal by id. 404 with no id (node too old): 'this node can't record its own standing yet'"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the three standings | `GET /v1/admin/self` (`admin_ops.rs:4288`, `self_standing`) — **503** when any axis is unreadable, with that axis still in the body | CIRISServer | live since CIRISServer#345 |
| shed / resume | `POST /v1/admin/self/shed` · `/resume-load` | CIRISServer | live |
| stop / resume accepting | `POST /v1/admin/self/stop-accepting` · `/resume-accepting` | CIRISServer | live |
| declare / lift compulsion | `POST /v1/admin/self/compelled` · `/compulsion-lifted` (`self_compelled`, `self_compulsion_lifted`) | CIRISServer | live |
| request body, every act | `{delegation_id, reason, compelled_by?}`: `delegation_id` is the owner's own `delegates_to` id and is required; `reason` is required (`admin.refusal.reason_absent`); `compelled_by` is read only by the compulsion act | CIRISServer | live |
| where `delegation_id` comes from | the owner's own delegation to this node. The client has no read that returns it today (`GET /v1/auth/device/grants` lists grants, but not the owner's `delegates_to` to the node) | CIRISServer | **unconfirmed**: blocks `sketched`. CIRISServer#676 |
| reach from a with-AI install | `/v1/admin/*` through the agent | CIRISAgent | **missing**: CIRISAgent#1213. Until then the card calls the node URL directly |
| a declaration that anyone else can see | the act writes a `hard_case:admin_action:{op}` row into persist's local `hard_case_events` table: unsigned, no `cohort_scope`, not an attestation, so nothing replicates it. The route's own doc says it is "the one a peer reads", but no peer can | CIRISServer / CIRISPersist | **missing**: CIRISServer#675 |

**Registry gap.** The row kind is `admin_action:{op}` (persist `hard_case.rs`,
`ADMIN_ACTION_PREFIX`), so the full dimension is `hard_case:admin_action:self_compelled`.
The registry's `hard_case:{kind}` has one vocabulary segment and cannot name it,
which is the same variadic-segment problem as `accord:*` (CIRISConstitution#108).
The standings are therefore bound as `x_private:` members here.

## 4. Flow (how)

Sign in as the node's owner; open My things › This node › (proposed) Own standing.

```yaml
expect:
  visible: ["proposed:node_self_axis_load_shed", "proposed:node_self_axis_accepting", "proposed:node_self_axis_legal_compulsion"]
```

Declare compulsion without naming the authority: tap `proposed:btn_node_self_compelled`.
A ConfirmSheet shows three facts: what is recorded (a declaration of legal
compulsion), what it does (nothing to the node's behaviour; a separate act from
stopping), and who signs (the owner). Type a reason into `proposed:input_node_self_reason`,
leave `proposed:input_node_self_compelled_by` blank, and confirm.

```yaml
expect:
  text: {"proposed:node_self_axis_legal_compulsion": "In force", "proposed:node_self_compelled_by": "not named"}
```

Lift it with `proposed:btn_node_self_compulsion_lifted`. The axis now reads **Lifted**,
and its counts read declared 1 · lifted 1. It does not revert to "Not declared":
"a compulsion that ENDED is not a compulsion that never happened"
(`admin.self.lift.compelled`).

A reason left empty is refused before sending, and the node's
`admin.refusal.reason_absent` is rendered if it arrives anyway.

## 5. QA plan

**Platforms.** All five, against a claimed node with an owner session. The
with-AI legs exercise the node URL, not the agent port.

**Negative paths.** An unowned node (`node_unowned`), a session that isn't the
owner's (`not_owner`, `authority_not_the_owner`), a missing reason, and a node
older than CIRISServer#345 (404 with no id).

**Not tested here.** Behaviour under a real partition. Whether a peer can see the
declaration: it cannot today, and that is the upstream gap in §3, not a client test.

## 6. Delta — card vs API vs CC

- **Card vs API.** The API is complete for the owner's own view: six acts, one
  read, localized strings, and a 503 that refuses to let an unreadable axis look
  like a zero. The client has none of it: no surface, no call, no tag.
- **API vs CC.** CC 3.2 makes this the owner's alone, and the node enforces that
  (`resolve_owner_authority` plus `is_steward_bound`). CC 3.1.9.4's attributed
  audit is honoured locally. What CC's purpose for the act implies, and the route
  doc promises, is that a *peer* can read it. That fails: the row is a local,
  unsigned table entry.
- **Reach.** Node-only today (CIRISAgent#1213); the card must use the node address.
- **Registry.** `hard_case:{kind}` cannot name the two-segment `admin_action:{op}`
  kind (see §3).
