# CSD-023 — Config (what this node is running, and what a root asks of it)

**CSD**: CSD-023 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can read every setting their node is running, change one, and see
separately what a trust root is asking of it — and the two planes never look
like one list.**

CC 3.1.9 makes a node's configuration "an auditable record rather than
inferred from behaviour", and CC 3.4.5 makes `config:{scope}` **self-or-owner**:
"a third-party assertion of what you are running is a rumour." This screen is
where the owner half of that rule is exercised. The mesh-config plane is the
opposite direction — a subscribed trust root's `mesh_config:{key}` rows
(CC 3.1.9.2, relieve-never-expand, most-restrictive-across-roots) — and the
screen reads it without being able to author it.

## 2. Surface (what)

```yaml csd:surface
surface: config
screen: Config
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_config`. Not `agentOnly`, and that is **correct**: `/v1/config`
is served by both the node and the agent, so a bare node has real config to
show. `ConfigScreen`'s own header comment already states the two-plane rule.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "config:{scope}"
    bind: {scope: transport}
    use: read
    type: string
    example: "net.radio.frequency_hz = 915000000"
    renders: "net.radio.frequency_hz — 915000000. The dotted key, its value, and when and by whom it changed."
    tag: "proposed:row_config_net_radio_frequency_hz"
  - ceg: x_private:config_updated_by
    use: display-only
    type: string
    example: "wa-owner-4a19c2"
    renders: "Changed by you, 3 days ago — `updated_by` / `updated_at` on the list route. CC 3.4.5 makes the emitter the whole claim, so this row is not decoration."
    tag: "proposed:row_config_attester"
  - ceg: x_private:config_is_sensitive
    use: display-only
    type: bool
    example: true
    renders: "******** with a `mobile.config_sensitive` chip in the warning tone (ConfigScreen.kt:448, :488)"
    tag: "proposed:chip_config_sensitive"
  - ceg: x_private:config_cohort_scope
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who can see this setting — 'Just you' for an admission or transport leaf, 'the peers that route to you' for a load leaf. NOT SENT: `GET /v1/config` returns key/value/updated_at/updated_by and no envelope, so the screen cannot tell a self-scoped secret from a federated one. CC 3.4.5.1 note 1 makes this a per-row fact and REQUIRES `admission` and `transport` to be emitted at `self`."
    tag: "proposed:row_config_scope"
  - ceg: "mesh_config:{key}"
    bind: {key: baseline.moderation}
    use: display-only
    type: string
    example: "mesh_config:baseline.moderation"
    renders: "What the root asks — one mesh-config row, its value, the root that signed it, and whether it is durable or emergency (chip_mesh_config_durable / chip_mesh_config_emergency; emergency TTL ≤ 72 h per CC 3.1.9.2)"
    tag: item_mesh_config_header
  - ceg: x_private:mesh_config_root
    use: read
    type: string
    example: "wa-root-9f3e11"
    renders: "Asked by — the trust root this row came from; the field the owner types when submitting"
    tag: input_mesh_config_root
  - ceg: x_private:mesh_config_delegation_id
    use: read
    type: string
    example: "del-7c2a"
    renders: "Under which delegation — the delegation a mesh-config write rides"
    tag: input_mesh_config_delegation
```

**`config_cohort_scope` is the `unconfirmed` that matters.** CC 3.4.5.1
explicitly refuses to pin the family to one scope — scope is per row, and the
leaf's content decides: `admission` (admin key ids) and `transport` (bootstrap
peers) **MUST** be `self`; `load` may go wider. A screen that lets an owner
edit a config key without showing which of those it is cannot warn them.

```yaml csd:states
populated: {tag: "proposed:config_sections"}
empty:     {tag: "proposed:config_empty", renders: "ConfigScreen.kt:215 — the filtered-to-nothing branch. There is no unfiltered-empty case: a node always has config."}
loading:   {tag: "proposed:config_loading", renders: "ConfigScreen.kt:135 — a progress affordance while `configData.sections` is empty; no sentence"}
error:     {tag: "proposed:config_error", renders: "`ConfigViewModel._error` exists and is not rendered by `ConfigScreen`. A failed `GET /v1/config` therefore lands on the same 'no results' as a search that matched nothing."}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the config list | `GET /v1/config` | **both** | live on CIRISServer (`/v1/config`) and CIRISAgent (`routes/config.py`) |
| change one | `PUT /v1/config/{key}` | **both** | live |
| delete one | `DELETE /v1/config/{key}` | **both** | live |
| the mesh plane | `GET /v1/mesh-config` | CIRISServer | live |
| its history | `GET /v1/mesh-config/history` | CIRISServer | live |
| durable form | `/v1/mesh-config/durable` | CIRISServer | live |
| relief form | `/v1/mesh-config/relief` | CIRISServer | live |
| a row's `cohort_scope` | `GET /v1/config` — **unconfirmed** | CIRISServer | blocks `building` for `row_config_scope` |

This is one of only two cards in this area (with Logs) whose whole contract is
served by both hosts. Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → Config.

```yaml
expect:
  state: populated
  count: {of: "row_config_*", min: 1}
  visible: [input_config_search, btn_config_refresh]
```

Search for a key that exists.

```yaml
expect:
  state: populated
  visible: [row_config_net_radio_frequency_hz]
```

Search for one that does not → the empty state; clear → the list again.

Edit a value: the row → the dialog → save.

```yaml
expect:
  visible: [input_config_edit_value, btn_config_dialog_save, btn_config_dialog_cancel]
```

The mesh plane.

```yaml
expect:
  visible: [btn_mesh_config_refresh, btn_mesh_config_history]
```

Submit a mesh-config row with a dry run first.

```yaml
expect:
  visible: [btn_mesh_config_dry_run, btn_mesh_config_write, btn_mesh_config_write_cancel]
```

## 5. QA plan

**Platforms.** All five.

**Not tested here.** A real trust-root signature: the mesh plane is driven
against a node with one seeded root, and the "most-restrictive-across-roots"
fold (CC 3.1.9.2) needs two, which the gate does not stand up.

## 6. Card vs API vs CC — the delta

1. **Placement is right and the two-plane split is already documented in
   code** (`ConfigScreen.kt:52`–`60`). The mesh plane has genuinely good tags
   (twenty of them, including the dry-run and the confirm). The self plane has
   five. That asymmetry is backwards: the plane an owner edits is the one with
   no row tags.
2. **The self plane has no error state.** `ConfigViewModel._error` is
   modelled and never rendered. A failed read is indistinguishable from a
   search miss.
3. **CC conflict — the one substantive one.** This screen lets an owner change
   a `config:{scope}` value without showing that row's `cohort_scope`. CC
   3.4.5.1 makes that scope the difference between "your admin key ids stay on
   this device" and "your admin key ids are on the federation". **Ask
   (CIRISServer): return each config row's envelope — at minimum
   `cohort_scope` and the scope leaf the key belongs to — on `GET /v1/config`.**
   The client can then render "Just you" / "peers that route to you" per row
   and refuse to widen one.
4. **The categories are cosmetic.** `CONFIG_CATEGORIES` is a client-side
   list of seven labels (`adapters`, `services`, `security`, `database`,
   `limits`, `workflow`, `telemetry`) matched by key prefix, and only the
   first three render (`ConfigScreen.kt:319`, `.take(3)`). They are not CC
   `{scope}` values. Either map them onto the CC canonical scopes
   (`admission`, `replication`, `moderation`, `transport`, `load`) or stop
   calling them categories — a second vocabulary for the same thing is the
   drift this repo measures.
5. **Whose setting is it?** The NODE's, attested by the owner. That is exactly
   what CC 3.4.5 says, and it is the right reading of the card.
