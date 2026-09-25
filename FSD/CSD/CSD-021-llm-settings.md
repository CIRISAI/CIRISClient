# CSD-021 — LLM (what the agent thinks with)

**CSD**: CSD-021 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see which models their agent reasons with, in what order, whose
key is paying, and turn the hosted option off — and the screen says plainly
when a provider is broken rather than silently routing around it.**

This is the most consequential setting in the app: it names the weights that
produce every `trace:complete:v1` the agent will ever emit. CC 3.1.5 makes a
trace "an agent's own reasoning trace carried envelope-native", and CC 3.4.5
makes it pro-self — only the agent may emit one about itself. The identity of
the model that produced that reasoning is therefore part of the agent's own
record, and nothing in the CEG registry names it. See §6.

## 2. Surface (what)

```yaml csd:surface
surface: llm-settings
screen: LLMSettings
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_llm_settings`. Correctly `agentOnly` (`CirclesNav.kt:154`) — a
bare node has no brain to give a model to.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:llm_provider_name
    use: read
    type: string
    example: "openai"
    renders: "OpenAI — the provider row (`GET /v1/system/llm/providers`)"
    tag: input_llm_provider
  - ceg: x_private:llm_model_id
    use: read
    type: string
    example: "gpt-4o-mini"
    renders: "gpt-4o-mini — the model this provider will be asked for"
    tag: input_llm_model
  - ceg: x_private:llm_base_url
    use: read
    type: string
    example: "http://192.168.1.40:11434/v1"
    renders: "Where it runs — a discovered local server, or the vendor default"
    tag: input_base_url
  - ceg: x_private:llm_api_key
    use: read
    type: string
    example: "sk-…redacted"
    renders: "Your key — masked; the app posts it to the node and does not keep it"
    tag: input_api_key
  - ceg: x_private:llm_provider_priority
    use: read
    type: int
    example: 1
    renders: "1st choice / 2nd choice — the order the bus tries providers in (`PUT /v1/system/llm/providers/{name}/priority`)"
    tag: "proposed:priority_openai"
  - ceg: x_private:llm_circuit_breaker_state
    use: display-only
    type: "enum[closed,open,half_open]"
    example: "open"
    renders: "Not being used right now — this provider failed and the bus stopped trying it. Today the bus status is fetched (`getLlmBusStatus`) but no row renders the breaker state."
    tag: "proposed:llm_breaker_openai"
  - ceg: x_private:ciris_services_enabled
    use: read
    type: bool
    example: true
    renders: "Use CIRIS's hosted models — a switch; turning it off is confirmed, because it is the only provider a person who has added none is using"
    tag: switch_ciris_services
  - ceg: "config:{scope}"
    bind: {scope: llm}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What this node is running, as an auditable record (CC 3.1.9: 'published as an auditable record rather than inferred from behaviour'). NOT EMITTED: no `config:llm` row exists, so the only answer to 'what is my agent thinking with' is this screen's own read-back."
    tag: "proposed:llm_config_receipt"
```

**`config:llm` is the field that should exist and does not.** Everything else
here is `x_private:` because the CEG registry has exactly one model-named
family — `judge_model:verdict:{model_id}` (CC 3.1.9.4), which is the benchmark
judge's verdict and not the agent's own reasoning substrate. `config:{scope}`
is the right family and `llm` is a legitimate open-vocabulary scope
(CC 3.1.9 canonical scopes are `admission`, `replication`, `moderation`,
`transport`, `load`; the vocabulary is open per CC 4.5.1.1).

```yaml csd:states
populated: {tag: "proposed:llm_providers_list"}
empty:     {tag: "proposed:llm_providers_empty", renders: "LLMSettingsScreen.kt:851 — the no-providers branch; today it renders the add card rather than a sentence, which is the right move (one useful next action) but carries no tag"}
loading:   {tag: "proposed:llm_loading", renders: "a progress affordance and no sentence"}
error:     {tag: "proposed:llm_error", renders: "`LLMSettingsViewModel._errorMessage` exists and is surfaced as a snackbar, which disappears. A list that failed to load needs a persistent, distinguishable banner."}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| bus status | `GET /v1/system/llm/status` | CIRISAgent | live (`system/llm_routes.py:327`) |
| providers | `GET /v1/system/llm/providers` | CIRISAgent | live (`llm_routes.py:399`) |
| add one | `POST /v1/system/llm/providers` | CIRISAgent | live (`llm_routes.py:809`) |
| remove one | `DELETE /v1/system/llm/providers/{name}` | CIRISAgent | live (`llm_routes.py:745`) |
| reorder | `PUT /v1/system/llm/providers/{name}/priority` | CIRISAgent | live (`llm_routes.py:665`) |
| distribution strategy | `PUT /v1/system/llm/distribution` | CIRISAgent | live (`llm_routes.py:483`) |
| reset a breaker | `POST /v1/system/llm/providers/{name}/circuit-breaker/reset` | CIRISAgent | live (`llm_routes.py:527`) |
| breaker config | `PUT /v1/system/llm/providers/{name}/circuit-breaker/config` | CIRISAgent | live (`llm_routes.py:592`) |
| hosted-services status | `GET /v1/system/llm/ciris-services/status` | CIRISAgent | live (`llm_routes.py:1065`) |
| turn hosted off | `POST /v1/system/llm/ciris-services/disable` | CIRISAgent | live (`llm_routes.py:973`) |
| turn hosted on | `POST /v1/system/llm/ciris-services/enable` | CIRISAgent | live (`llm_routes.py:1025`) — **not called by this client** |
| list a provider's models | `POST /v1/setup/list-models` | CIRISAgent | live (`setup/llm_routes.py`) |
| find a local server | `POST /v1/setup/discover-local-llm` | CIRISAgent | live (`setup/llm_routes.py`) |
| `config:llm` as a record | — **unconfirmed** | CIRISAgent | blocks `building` for `llm_config_receipt` |

Agent tree last commit **2026-08-15**; "live" is as of that tree. Nothing under
`/v1/system/llm` exists on `CIRISServer`, which is correct — this is the
brain's configuration, not the node's.

## 4. Flow (how)

Open My things → This node → LLM.

```yaml
expect:
  state: populated
  visible: [card_ciris_services, switch_ciris_services]
```

Add a provider: `card_add_provider` → type, key, fetch models, submit.

```yaml
expect:
  visible: [input_add_provider_type, input_add_provider_api_key, btn_fetch_models]
```

```yaml
expect:
  visible: [input_add_provider_model, btn_add_provider_submit]
```

Turn hosted services off: `switch_ciris_services` → the confirm.

```yaml
expect:
  visible: [btn_confirm_disable_ciris, btn_cancel_disable_ciris]
```

## 5. QA plan

**Platforms.** All five. `discoverLocalLlmServers` uses mDNS and a 12-second
probe; it is driven only on desktop, where a stub server can be stood up.

**Not tested here.** A real provider key (CI has none); `btn_fetch_models` is
driven against a stub. The circuit breaker cannot be opened on demand without
a failing provider, so `llm_breaker_*` is asserted only in a unit test over the
bus-status decode.

## 6. Card vs API vs CC — the delta

1. **Placement is right.** `agentOnly`, under This node, is exactly where a
   brain's substrate belongs. No change.
2. **Whose setting is it?** The AGENT's — but paid for by the PERSON. The key
   in `input_api_key` is the person's, and the screen says nothing about where
   it goes or whether it is stored. `AdapterConfigData` at least documents
   "sanitized — no secrets"; this screen has no equivalent note.
3. **The breaker is invisible.** `getLlmBusStatus` is called and the per-
   provider circuit-breaker state is in the response, but no row shows it. A
   person whose first-choice provider is failing sees a list that looks fine
   and answers that are quietly coming from somewhere else. This is the
   "silently routing around it" the mission forbids.
4. **`enable` is unreachable.** The client calls `disable` and never `enable`
   (`CIRISApiClient.kt:10470` vs the route at `llm_routes.py:1025`). Turning
   CIRIS services off is a one-way door in the UI.
5. **CC gap — ask (CIRISAgent).** Publish the running LLM configuration as a
   `config:llm` row under CC 3.1.9 / CC 3.4.5 (self-or-owner). Today "what is
   my agent thinking with" is answerable only by reading this screen, which is
   the definition of inferring configuration from behaviour rather than from a
   record — the thing CC 3.1.9 exists to replace. Emit it at `cohort_scope:
   self` by the CC 3.4.5.1 smallest-scope default; the provider name and model
   id are not secrets but the base URL of a home LAN server is.
