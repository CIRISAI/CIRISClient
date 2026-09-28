# FSD — Does the client need an AGENTS tier, a THIS NODE tier, or neither?

**Status:** decided, for review · 2026-09-25
**Author:** CIRISClient
**Answers:** the four options raised against the Locked Spec's five circles
**Verdict:** **neither.** Keep five circles. Split the `this-node` *instrument*
into **This agent** and **This node**, and move two device surfaces out of it.

---

## 1. The question

Today the person's agent is in four places and one of them is a 20-row list:

| where | what |
|---|---|
| Just me › Chats | `Interact` — the conversation (`CirclesNav.kt:87`) |
| Just me › Rules | `LayerAgent`; `Delegations` in all five circles (`CirclesNav.kt:108,116`) |
| My things › Devices & keys | `IdentityManagement` — the occurrence roster (`CirclesNav.kt:134`) |
| My things › **This node** | **twenty** surfaces (`CirclesNav.kt:142-157`) |

Those twenty are three different things in one flat list:

```
LLMSettings Adapters Services Telemetry Runtime     ← the brain
Sessions Tickets Scheduler Tools Skills                (what the agent runs on)
Nodes Transport NetworkOps Config Logs System       ← the substrate
Memory GraphMemory                                     (what the node runs on)
AgentSettings ClientInterface                       ← this device
```

Four options were raised:

- **(A)** an "Agents" tier in the rail, above Just me, below Family
- **(B)** an "Agents" tier below Just me, above a new "This node" tier
- **(C)** keep today's shape
- **(D)** whatever CC points to that is better

---

## 2. What CC says

### 2.1 A circle is `cohort_scope`, and `cohort_scope` is "who can SEE"

CC 2.3.3 `cohort-orthogonality` is the authoritative three-axis table
(`part_2_the_grammar.md:172-180`):

> | Axis | Field | Authority | Names |
> | **Visibility** | `cohort_scope` + (`family_id` or `community_id`) | Producer-side | **Who can SEE the data** |
> | **Revocability** | `subject_key_ids` | Subject-side | Who can REVOKE the data |
> | **Delivery** | `delivery_mode` + `listed` + `history_on_join` | Substrate / subscriber | Who actively RECEIVES it |

CC 3.1.9.7 (`part_3_the_namespace.md:363`) rules on what it is *not*:

> "`goal:{scale}` and `cohort_scope` are different axes (normative ruling). …
> `cohort_scope` ([CC 2.3.3]) is a **privacy/routing** axis that keys the
> encryption tier. They MUST NOT be reconciled into a single vocabulary."

And CC 4.4.3.2.1 (`part_4_composition_governance.md:364-370`, normative) is the
table that makes a circle mean something operationally:

> "The line is drawn at **'does it have a bounded membership roster?'** — yes →
> encrypt, no → plaintext."
>
> | Tier | `cohort_scope` | At-rest | Wire discovery | Reader |
> | self / family | `self`, `family` | encrypted, per-write DEK | **none** | occurrences / family members |
> | Community | `community`, `affiliations` | community DEK | `holds_bytes:*` + provenance | community members |
> | Commons | `species`, `biosphere`, `federation` | **plaintext** | `holds_bytes:*` | anyone |

This is why the product's own copy for a circle is a *visibility* rule and
nothing else — `nav.circle_rule.*` in `en.json`: "nobody else" · "hidden from
everyone outside" · "anyone can stop something…" · "public, and mostly one-way".

**A tier in the rail that is not a `cohort_scope` has no row in that table, so it
cannot answer the only question a circle exists to answer.**

### 2.2 An agent is a MEMBER of `self`, not a scope of its own

CC 3.2 (`part_3_the_namespace.md:599`, normative):

> "**`self` is the single-owner cohort.** The `self` cohort is the transitive set
> of nodes sharing **one** owner — a person's own devices, distinct keys unified
> by the owner-binding graph."

The worked example is explicit, CC 4.4.3.4.6 (`part_4:901`):

> "**Concrete**: Alice has admitted `alice_phone`, `alice_laptop`, `alice_agent`.
> Her self-collective is `{alice_root, alice_phone, alice_laptop, alice_agent}`.
> When Alice's phone publishes a `cohort_scope: self` Twitter scroll, the
> substrate wraps the content DEK under all four keys."

The agent holds the Self DEK. It sees exactly what "Just me" sees, by
construction. So an **Agents** circle's visibility rule would be *character for
character* Just me's rule — a second source for a thing that already has one,
which is the drift this repo exists to close (`AGENTS.md`, Security & Config).

`CohortScope.AGENT` is already spelled for this: `cegScope = "self"`
(`ui/nav/CohortScope.kt:33`). Just me **is** the agent's circle.

### 2.3 An agent IS its own identity — in four layers, and CC separates them

An agent is not *only* a delegate. It is federation entity kind 2 with its own
`key_id` and FedCode (CC 2.6.8, `part_2:596-599`: `kind(1): 1=user 2=agent
3=node 4=family 5=community`). But CC 4.4.3.4.3 (`part_4:740-745`, normative)
splits what that buys into two independently revocable layers:

> | Layer | Mechanism | Buys | Revoked by |
> | **Co-self** (visibility) | occurrence + Policy-L Self DEK | the agent can *read/manage the user's Self* | `withdraws` the occurrence |
> | **Agency** (act-on-behalf) | `consent:partnered` + scoped `delegates_to` | the app may *act AS the user on the network* | `withdraws` the delegation |
>
> "So a user MAY grant a device co-self … while revoking its network agency — or
> vice-versa — without disturbing the other."

and CC 3.3.6 (`part_3:1119-1163`) makes the agent an **occurrence** with
`device_class: agent` — "An AI agent acting on the identity's behalf".

That is four distinct questions, and **the client already answers each one in a
different place, correctly**:

| CC layer | the question | today's home | right? |
|---|---|---|---|
| co-self (CC 4.4.3.4.3) | what can it see / say to me | Just me › Chats — `Interact` | yes |
| agency (`delegates_to`, CC 2.4.1) | what may it do for me | Just me › Rules — `LayerAgent`, `Delegations` | yes |
| occurrence (CC 3.3.6) | which of my things is it | My things › Devices & keys — `IdentityManagement` | yes |
| operation | what does the brain run on | My things › This node — 15 rows, mixed with the node's 8 | **no** |

**The agent is not "scattered". Three of the four placements are the three
layers CC names.** Only the fourth is wrong, and it is wrong in one specific
way: it is mixed with the substrate.

### 2.4 A node is substrate, and CC forbids the fusion a node tier would make

CC 3.4.7.3 `actor-substrate` (`part_3:1708-1712`, normative) — CC calls this
"the canonical statement of 'infrastructure must not have agency'":

> "**Clause A — `node` is exclusive (normative).** A `federation_keys` key whose
> `identity_type` set contains `node` MUST NOT also contain `agent` or `user`.
> **A key is substrate or actor, never both.** … The defect it prevents is
> **axis fusion** — one key answering both *which node is this* and *which agent
> is this* … and **no trace can say whether the substrate or the actor acted**."

CC 4.4.3.4.5 (`part_4:784`) on what a node may hold:

> "**Membership is standing, not stewardship.** … a `node`-only key may carry
> *only* `infra:*` scopes, therefore **it cannot hold a judgment duty at all**."

A node can be a *recipient* (family rosters hold bare device keys — `part_4:871`,
the Roku and the thermostat "receive directly"), but it is never a *cohort*. A
tier in the audience rail named "This node" promotes infrastructure to the axis
CC says it must be kept off.

### 2.5 CC hands over the split line for free

CC 4.4.3.4.3 (`part_4:775-778`, normative) reserves two scope prefixes:

> | `infra:*` | server-class (allowed for a `node`-role delegate) | `infra:serve`, `infra:store`, `infra:transport`, `infra:attest`, `infra:network_presence`, `infra:hold_community_membership`, `infra:hold_family_membership` |
> | `agency:*` | brain-only (forbidden for a pure `node`-role delegate) | `agency:act_on_behalf`, `agency:message_io`, `agency:reason`, `agency:decide` |

and `part_4:788` names the exact thing the client is looking at:

> "**Cohabitation (`agent = node + brain`):** when both compose in one process,
> the node holds **partnership-without-agency** (`infra:*`) and the brain layers
> **Self-at-login partnership-with-agency** (`agency:*`) as a *separate*
> `delegates_to`. **Two delegations, two scope classes, independently
> revocable.**"

Two delegations, two scope classes → **two instruments**. That is option (D).

### 2.6 Multi-agent: CC permits it, nothing serves it

CC allows one person many agents — `nodes_owned_by(U)` is a set (CC 2.6.8,
`part_2:596`, with `node_count(1) = 0..=16` in the v3 FedCode payload), and an
identity "MAY admit **unbounded occurrences**" (CC 3.3.6, `part_3:1170`).

But nothing in the stack can enumerate them today:

- **No plural-agents route in either service.** `git grep '"/[^"]*agents[^"]*"'`
  over `CIRISServer origin/main -- 'src/*.rs'` → zero hits. `~/CIRISAgent` (HEAD
  `2e1eccf`, 2026-08-15 — 41 days stale) has `APIRouter(prefix="/agent")`,
  singular (`routes/agent.py:44`). `"/v1/agent"` on the node is a reverse-proxy
  prefix for the **one** folded brain (`src/proxy.rs:154`).
- **`NodeProfile` carries no agent.** `models/NodeProfile.kt:21-46` is
  `{id, name, baseUrl, sessionToken?, lastUsedEpochMs, pinnedKeyId?,
  pinnedPubkeyBase64?, isLocal}` — no `agentId`, no capabilities, no role.
- **`ONE_CLIENT_N_NODES.md` is about nodes, not agents.** A2 (`:59-62`):
  "**A node can be upgraded to include a brain.** Agent-ness is a runtime,
  per-attachment, *mutable* property." A1 (`:53`): "**Agent is a strict superset
  of node.**" The word "agent" never appears as a pluralisable thing.
- **Capability is one bit.** `ClientMode` ∈ `{NODE, AGENT}`
  (`models/ClientMode.kt:34-43`), derived from one `/v1/system/health` probe
  (`clientModeFrom`, `:144-189`); the build flag is gone (`CIRISBuild.kt:6`,
  "`HAS_AGENT` is gone — the gate is the PROBE now (CIRISServer#479)").

**A tier named "Agents" (plural) would promise a list no service can return.**
When multi-agent arrives, the right shape is a *list inside the instrument* —
exactly what `Nodes` already is inside This node — not a rail tier, because the
rail is `cohort_scope` and an agent is not one.

---

## 3. Does the seven-tab frame even fit?

The frame is pinned: `theTabsAreTheSevenInTheLockedOrder`
(`CirclesNavTest.kt:48`), plus the rule a tab is named for what it holds
(`filesHoldsOnlyFiles`, `chatsHoldsOnlyConversations`).

**An "Agents" tier, tab by tab:**

| tab | what it would hold | verdict |
|---|---|---|
| Files | nothing — what the agent makes is mine at `cohort_scope: self` (CC 1.13.3.4 smallest-scope default, `part_1:225`) | empty by construction |
| Chats | `Interact` — **already** Just me › Chats | duplicate; breaks `everySurfaceIsPlacedExactlyOnceOrIsAFlow` |
| People | an agent is not people; the roster of my agents is `identity_occurrence` = Devices & keys | duplicate |
| Safety | the kill switch is Everyone › Safety *by explicit design* (`theAccordIsAllUnderEveryoneSafety`); stopping the agent is `btn_stop_everything`, in every circle's top bar | duplicate |
| Rules | `LayerAgent` + `Delegations` — **already** Just me › Rules | duplicate |
| Decisions | `nav.empty.decisions_agent`: "There is nobody here to decide with. Decisions start in Family." | empty by construction |
| Record | `Audit` — already in all five circles' Record | duplicate |

Seven tabs, one real card, and that card already has a home. But
`peopleSafetyRulesAndRecordAreNeverEmptyOnAnyBuild` (`CirclesNavTest.kt:54`)
**forces** People/Safety/Rules/Record to be non-empty in *every* circle — so a
sixth circle mandates inventing four cards that CC says belong elsewhere, or
deleting the test that keeps circles honest.

**A "This node" tier, tab by tab:** Files — no. Chats — a node does not talk.
People — CC 4.4.3.4.5 (`part_4:784`), judgment is "never node-holdable". Safety,
Rules — `config:{scope}` is "a node's declared operating configuration"
(registry, CC 3.1.9), not a rule about who can see. Decisions — CC 3.4.7.3,
infrastructure has no agency. Record — `Logs`/`Audit` exist. **0 of 7.**

---

## 4. The options, and the delta each creates

### Option A — an "Agents" tier above Just me, below Family

| | delta |
|---|---|
| **CC** | **Fails.** No `cohort_scope` value to back it (CC 2.5, `part_2:333`). It would sit between `self` and `family`, implying an audience wider than self and narrower than family — CC has no token there. The agent already reads `self` (CC 4.4.3.4.6, `part_4:901`), so the new circle's rule is a verbatim copy of Just me's. |
| **The receipt contradicts the rail** | `ReceiptSheet.kt:142-155` renders the wire's `cohort_scope` as the "who can see it" fact, via `cohortScopeOf(wire)` (`ceg/Formatters.kt:30`) → `ScopePill` → `CirisTokens.circle(scope)`. A card in an Agents circle carries a receipt whose scope pill names a **different** circle than the rail it sits in. The receipt is `Wire`; the rail would be a guess. This is the hard stop. |
| **Cards moved** | 0 moved, 4+ **invented** (People/Safety/Rules/Record must be non-empty), 1 duplicated (`Interact`). |
| **Code** | `CohortScope` gains a 6th entry with no `cegScope` (the field is non-null `String`; there is no CEG token to give it). Two exhaustive `when`s stop compiling: `CirisTokens.circle` (`CirisTokens.kt:69`) and `ScopePill.circleName` (`ScopePill.kt:50`). `CirclesNav.circles = CohortScope.entries` means every `ALL` placement (Contacts, ChildSafety, Trust, ManageConsent, Consent, Delegations, Audit — 7 surfaces) silently gains an Agents home unless each is rewritten. A 6th circle colour token + its `CirisTokensContrastTest` row. |
| **Locale** | 3 new keys (`nav.circle.*`, `nav.circle_sub.*`, `nav.circle_rule.*`) + up to 7 `nav.empty.*` ≈ **10 keys × 29 bundles = 290 values**, through `localize.py`. |
| **Tags / hops** | new `circle_agents`; `circleFor` and every `circle_<slug>` assertion learns a 6th value. |
| **API** | none — there is nothing to call. |
| **Gate churn** | `CirclesNavTest`: 4 tests rewritten. CIRISAgent's gate: no leaf tag changes. |

### Option B — "Agents" below Just me, above a new "This node" tier

Everything in A, twice, plus:

| | delta |
|---|---|
| **CC** | **Fails harder.** A node tier in the audience rail is precisely the **axis fusion** CC 3.4.7.3 Clause A (`part_3:1708-1712`) exists to prevent: one surface answering both "which node is this" and "which agent is this". |
| **Tabs** | 0 of 7 fit a node (§3). The seven are pinned; holding the substrate needs an 8th tab or a tab whose name lies — and "a tab is named for what it holds" is the rule the spine pass was *for*. |
| **Cards moved** | 8 substrate surfaces leave My things for the rail, giving infrastructure equal billing with Everyone. |
| **Code** | 7 rail items; the phone bottom bar already scrolls at 5. Two more colour tokens, two more `when` arms, ~20 locale keys × 29 = **~580 values**. |
| **Gate churn** | 8 surfaces change hop *prefix* (`btn_my_things → …` becomes `circle_this_node → tab_? → …`), and there is no `tab_?` to name. |

### Option C — keep today's shape

| | delta |
|---|---|
| **Cost** | zero |
| **Fixes** | nothing. The 20-row list stays. So do the defects in §5. |

### Option D — split the instrument, not the rail ← **recommended**

`this-node` (20) becomes **This agent** (10, all agent-only), **This node** (8),
and 2 surfaces move to **Devices & keys**. `Account` is retired.

| | delta |
|---|---|
| **CC** | The split line is CC's own: `agency:*` vs `infra:*` (CC 4.4.3.4.3, `part_4:775-778`), under Clause A "a key is substrate or actor, never both" (`part_3:1708`), and cohabitation's "**Two delegations, two scope classes, independently revocable**" (`part_4:788`). Five circles untouched; seven tabs untouched. |
| **The instrument line already says it** | `nav.instrument_line` = *"your own things — not in a circle"*. An agent and a node are both your own things and neither is an audience. The instrument shelf is where the spec already puts what is not a circle — "My things (the avatar, top left — **not a sixth circle**)" (`MyThings.kt:37`). |
| **Cards moved** | 12 (10 + 2). 0 invented, 0 duplicated, 1 deleted (`Account`). |
| **API** | The split **lines up with the host split** (§5): all 10 This-agent surfaces are agent-owned; all 8 This-node surfaces are node-owned or served by both. It turns 7 live wrong-host defects from incidental into structural. |
| **Code** | `CirclesNav.instruments` — one data edit. Two render sites iterate `instruments` unfiltered (`CIRISApp.kt:4960`, `MyThings.kt:80`) and would show an empty "This agent" row on a node build: add `Instrument.isEmpty(hasAgent)` and filter. ~6 lines in 2 files. |
| **Locale** | **1 key** (`nav.instrument.this_agent`) × 29 bundles = 29 values, one `localize.py` run. |
| **Tags** | **+1** (`nav_instrument_this_agent`). `−1` (`nav_epistemic_account`, retired). **Every `nav_epistemic_<id>` leaf tag is unchanged.** |
| **Hops** | 12 surfaces change their **middle** hop only. 10 → `nav_instrument_this_agent`; 2 → `nav_instrument_devices_keys`. `nav_map.py` re-derives from `CirclesNav.kt`; nothing is hand-maintained. |
| **Gate churn (CIRISAgent)** | Three files reference nav tags. `federation_screen_tags.py:37-38` and `federation_walk_test.py:66,245` use `nav_epistemic_layer_global_commons` — a circle-tab surface, **unaffected**. `ios_physical_test_cases.py:988` clicks `nav_epistemic_interact` — **unaffected**. `:966` clicks `nav_epistemic_telemetry` — leaf unchanged, hop becomes `btn_my_things → nav_instrument_this_agent`. Note both iOS steps first click `btn_nav_drawer_open`, which **does not exist in the locked-spec shell** (the controls are `btn_my_things` and `btn_rail_toggle`): that test is already stale and must be rewritten whichever option is chosen. |
| **Client tests** | `CirclesNavTest`: `chatsHoldsOnlyConversations` (3 asserts `"this-node"` → `"this-agent"`), `settingsLivesUnderThisDeviceOnEveryBuild` (→ `"devices-keys"`), `aBareNodeCanStillReachAccountAndThereforeSignOut` (rewritten around `AgentSettings`). `theHomesTheGatesLeanOnAreWhereTheyWere` and `filesHoldsOnlyFiles` keep passing — `Nodes` and `Memory` stay in `this-node`. Add: every `this-agent` surface is agent-only. The subtractive rule still holds — a whole instrument vanishing is subtraction — and it is pinned by `theNodeBuildIsASubsetOfTheAgentBuild` (`CirclesNavTest.kt:71`), whose per-instrument arm (`:77`) already covers the new instrument. (`EpistemicNav.kt:135-136` calls that test `narrowingIsPurelySubtractive`; no test by that name exists — stale doc reference, worth a one-word fix while here.) |

---

## 5. What the API says — the split is already there

Owners verified against `CIRISServer origin/main` (`046e1b3`, 0.5.217, route
literals in `src/*.rs`; no `.nest(` exists, so every literal is the full path)
and `~/CIRISAgent` working tree (`2e1eccf`, **2026-08-15 — 41 days stale**;
agent claims are as of that date). Host vocabulary:
`platform/BackendEndpoint.kt:47,70,83-84` — `baseUrl` is the agent on `:8080`
normally, the bare node on `:4243` on a run-without-AI install.

The proposed **This agent** set is exactly the set of agent-owned surfaces:

| surface | endpoints | node has them? |
|---|---|---|
| LLMSettings | `/v1/system/llm/*` (`system/llm_routes.py:56`) | no — `git grep "system/llm"` → empty |
| Adapters | `/v1/system/adapters*` (`system/adapters.py:164`) | no |
| Services | `/v1/system/services` (`system/services.py:77`) | no |
| Telemetry | `/v1/telemetry/overview`, `/export/destinations` (`telemetry.py:681`, `telemetry_export.py:28`) | no — node serves only `/v1/telemetry/logs` (`src/telemetry_logs.rs:169`) |
| Runtime | `/v1/system/runtime/{action}` (`system/runtime.py:43`) | no |
| Sessions | `/v1/system/state/transition` (`system/runtime.py:94`) | no |
| Tickets | `/v1/tickets*` (`tickets.py:37`) | no |
| Scheduler | `/v1/scheduler/*` (`scheduler.py:22`) | no |
| Tools | `/v1/system/tools` (`system/tools.py:119`) | no |
| Skills | `/v1/system/adapters/import-skill*` (`system/skill_import.py:605`) | no |

and the proposed **This node** set is node-owned or dual-served: `Nodes`
(`/v1/setup/owned-nodes`, `src/auth/bootstrap.rs:1489`; `/v1/self/*`,
`src/auth/occurrence.rs:668`), `Transport` (`/v1/federation/identity`,
`src/federation_surface.rs:696`), `NetworkOps` (same), `Config` (`/v1/config`,
`src/config_api.rs:359,363` — both), `Logs` (both), `System`
(`/v1/node/state`, `src/operator_surface.rs:2446`), `Memory` + `GraphMemory`
(`src/memory_api.rs:1265-1267` — both).

**Seven live defects this split makes structural.** The nav gate hides 10
surfaces on a node (`CirclesNav.kt:87,108,153-156`) but the API gate covers only
7 methods (`nodeSkip`, `CIRISApiClient.kt:248-254`). Seven surfaces survive the
nav gate and then call agent-only endpoints with no gate at all — **Adapters,
NetworkOps, Services, Telemetry, System, Runtime, WiseAuthority** (plus
`Storage`, which calls `getAgentMode`). A node owner sees error cards, not
explained absences (`NetworkViewModel.kt:78-82` writes the 404 into `_error`).
Under (D), "is this agent-only?" is answered once per instrument instead of once
per surface.

**Three further host defects, out of scope here but worth the row:**

1. `Transport` reads the node at `:4243` and **writes `net.radio.*` to
   `$baseUrl`** (`TransportViewModel.kt:71` vs `:111-113`) — on any with-AI
   install the write lands in the agent's graph config and the node never sees
   it. CIRISClient bug.
2. `Nodes` and `Data` call `PUT /v1/my-data/accord-settings` at `$baseUrl`;
   only the agent serves it (`my_data.py:580,689`), node `/v1/my-data/` has
   only `lens-identifier` and `capacity` (`src/system_data.rs:387,390`).
3. `GET /v1/registry/lookup` (`CIRISApiClient.kt:9392`, called from
   `CIRISApp.kt:4007`) **matches no route in either repo**.

**And one CC conformance bug in the fold itself.** `ceg/Formatters.kt:35` folds
`"species", "planet", "federation" → GLOBAL_COMMONS`. CC 2.5
(`part_2_the_grammar.md:333,336`) says the seventh `cohort_scope` token is
**`biosphere`**, and that `planet` belongs to `goal:{scale}` only — CC 3.1.9.7
(`part_3:363`) forbids merging the two ladders. So the client accepts a token CC
does not define and **returns `null` for `biosphere`**, which means the receipt's
"who can see it" fact goes blank on a legitimate commons-tier row. One-line fix;
`CohortScope.kt`'s doc comment needs the same correction.

---

## 6. Recommendation

**Do not add a tier. Split the instrument.** Six instruments:

```
My things
├── Devices & keys   IdentityManagement · AgentSettings · ClientInterface
├── Everything I shared   Data · Storage
├── This agent  (agent-only, whole instrument)
│      LLMSettings Adapters Services Telemetry Runtime
│      Sessions Tickets Scheduler Tools Skills
├── This node
│      Nodes Transport NetworkOps Config Logs System Memory GraphMemory
├── Someone I trust  (agent-only)   WiseAuthority
└── Help   Help
```

Five circles unchanged. Seven tabs unchanged. `Interact` stays in Just me ›
Chats — CC says that is where it belongs: the agent is an occurrence in my
`self` cohort, so a conversation with it is `cohort_scope: self`, and
`CohortScope.AGENT.cegScope == "self"` already spells that.

**The reasoning, in one line each:**

1. A circle is a `cohort_scope` and a `cohort_scope` is "who can see" (CC 2.3.3,
   `part_2:172-180`). An agent is a *member* of `self`, not a scope (CC 3.2
   `part_3:599`; CC 4.4.3.4.6 `part_4:901`). Putting a member where a cohort goes
   is a category error, and the receipt catches it: `ReceiptSheet` renders the
   wire's scope pill, which would name a different circle than the rail.
2. A node is substrate. CC 3.4.7.3 Clause A (`part_3:1708`): "A key is substrate
   or actor, never both" — a node tier in the audience rail is the axis fusion
   that clause exists to prevent.
3. The real problem is real, and it is *inside* the instrument: twenty rows
   conflating the brain, the substrate and this device. CC hands over the split
   line — `agency:*` vs `infra:*` (`part_4:775-778`) — and names the very case:
   "Cohabitation (`agent = node + brain`) … **Two delegations, two scope classes,
   independently revocable**" (`part_4:788`).
4. The split is also the host split. All 10 This-agent surfaces are agent-owned;
   all 8 This-node surfaces are node-owned or dual. Seven mis-gated surfaces stop
   being seven separate oversights.
5. It costs **1 locale key, 1 test tag, 12 middle hops, ~6 lines of shell code**,
   and **deletes** a surface. A tier costs a 6th `CohortScope` with no CEG token,
   two broken exhaustive `when`s, 290–580 translated values, four invented cards,
   and a receipt that disagrees with the rail.
6. "Agents" plural promises a list nothing can return: no plural-agents route in
   either service, `NodeProfile` carries no agent, `ClientMode` is one bit. When
   multi-agent arrives it is a list *inside* This agent — as `Nodes` already is
   inside This node.

**Two supporting moves.**

- **Retire `Account`.** `AgentSettings` and `Account` route to the same
  `Screen.Settings` (`CIRISApp.kt:5980,5983`). `Account` exists only because
  `AgentSettings` was dropped on node builds (`EpistemicNav.kt:118-148`,
  CIRISClient#51). Move `AgentSettings` to Devices & keys, leave it un-gated, and
  the reason for `Account` is gone. This also fixes `nav_map.py:155-160`, whose
  `setdefault` collision means the derived map **cannot currently emit a hop for
  `Account` at all** — the one surface that exists for the node build.
- **Un-gate `ClientInterface` and `GraphMemory`/`Memory`; gate the seven.**
  `ClientInterface` (VizSettings) writes to secure storage and calls nothing
  (`SettingsViewModel.kt:966-978`) — a node owner cannot change their own
  interface today, for no reason. `Memory` is marked agent-only while
  `GraphMemory` is not, and they call the same dual-served endpoints; both belong
  in This node, neither agent-only. Conversely the seven in §5 must become
  agent-only. Every one of these keeps `node ⊆ agent`.

**What this does not do.** It does not give the node its own audience, does not
add a way to talk to a second agent, and does not fix the three host defects in
§5 — those are code fixes with their own issues. It also does not decide the
copy: "This node" may read to a person as "this computer" now that "This agent"
sits above it. That is a wording pass, not a structure one.

---

## 7. Per-surface table — today → recommended

`agentOnly?` is the recommended gate. **bold** = a change.

| surface | today | recommended | agentOnly? | API owner | why (CC / host) |
|---|---|---|---|---|---|
| `Interact` | Just me › Chats | **unchanged** | yes (is) | AGENT `/v1/agent/interact` (`agent.py:841`) | the agent is an occurrence of my `self` (CC 3.3.6 `part_3:1119`); a chat with it is `cohort_scope: self` |
| `LayerAgent` | Just me › Rules | **unchanged** | yes (is) | — | the agency layer is a rule of Just me (CC 4.4.3.4.3 `part_4:740`) |
| `Delegations` | all circles › Rules | **unchanged** | no | NODE | `delegates_to` — "agency to act *for* someone" (CC 2.4.1.2.1 `part_2:270`) |
| `IdentityManagement` | Devices & keys | **unchanged** | no | NODE `/v1/self/occurrences` (`src/auth/occurrence.rs:668`) | the occurrence roster, `device_class: agent` included (CC 3.3.6 `part_3:1154-1163`) |
| `Account` | Devices & keys | **RETIRED** | — | BOTH `/v1/auth/logout` | same `Screen.Settings` as `AgentSettings`; exists only because that was node-dropped |
| `AgentSettings` | This node | **Devices & keys** | **no** | BOTH (split by route) | it is this device's — language, ground, sign out (`CirclesNavTest.kt:156-163`) |
| `ClientInterface` | This node (agent-only) | **Devices & keys** | **no** | LOCAL-ONLY (`SettingsViewModel.kt:973`) | writes secure storage, calls nothing; gating it behind a brain is simply wrong |
| `LLMSettings` | This node (agent-only) | **This agent** | yes | AGENT `/v1/system/llm/*` | `agency:reason` (CC `part_4:778`) |
| `Adapters` | This node | **This agent** | **yes** | AGENT `system/adapters.py:164` | `agency:message_io`; today ungated on node → writes 404 |
| `Services` | This node | **This agent** | **yes** | AGENT `system/services.py:77` | the 22 cognitive service lights; today ungated → 404 |
| `Telemetry` | This node | **This agent** | **yes** | AGENT `telemetry.py:681` | node serves only `/v1/telemetry/logs`; today ungated → 404 |
| `Runtime` | This node | **This agent** | **yes** | AGENT `system/runtime.py:43` | pause/resume/step the brain — `agency:decide`; today ungated → 404 |
| `Sessions` | This node (agent-only) | **This agent** | yes | AGENT `system/runtime.py:94` | the brain's cognitive state. **Name collision:** CC's `session:{kind}` is persist-owned "which occurrence is handling an exchange" (CC 3.1.3.1 `part_3:122`) — a different thing |
| `Tickets` | This node (agent-only) | **This agent** | yes | AGENT `tickets.py:37` | the agent's work queue |
| `Scheduler` | This node (agent-only) | **This agent** | yes | AGENT `scheduler.py:22` | the agent's deferred tasks |
| `Tools` | This node (agent-only) | **This agent** | yes | AGENT `system/tools.py:119` | `agency:act_on_behalf` |
| `Skills` | This node (agent-only) | **This agent** | yes | AGENT `system/skill_import.py:605` | same. Gap: the agent also serves `/v1/system/skills/*` (`skill_builder.py:43`) the client never calls |
| `Nodes` | This node | **unchanged** | no | NODE `src/auth/bootstrap.rs:1489` | `infra:*`; the N-nodes list (`ONE_CLIENT_N_NODES.md:63`). Fix the `accord-settings` wrong-host call |
| `Transport` | This node | **unchanged** | no | NODE `src/federation_surface.rs:696` | `infra:transport`. Fix the `net.radio.*` write going to `$baseUrl` |
| `NetworkOps` | This node | **unchanged** | no | NODE `federation/identity` + AGENT `agent-mode` | "THIS node's local edge facts" (`EpistemicNav.kt:171`); `nodeSkip` the agent-mode read |
| `Config` | This node | **unchanged** | no | BOTH `src/config_api.rs:359,363` | `config:{scope}` — "only ever about the emitting node", self-or-owner (registry, CC 3.4.5) |
| `Logs` | This node | **unchanged** | no | BOTH `src/telemetry_logs.rs:169` | substrate self-report |
| `System` | This node | **unchanged** | no | NODE `/v1/node/state` + AGENT calls | `infra:serve`; `nodeSkip` the channels/runtime/telemetry reads |
| `Memory` | This node (agent-only) | **unchanged, un-gate** | **no** | BOTH `src/memory_api.rs:1265-1267` | the node serves it; the gate is stricter than the API requires |
| `GraphMemory` | This node | **unchanged** | no | BOTH `src/memory_api.rs:1266` | same store as `Memory` — the two must be gated alike |
| `WiseAuthority` | Someone I trust | **unchanged, gate** | **yes** | AGENT `/v1/wa/*` (`wa.py:33`) | deferral needs a brain to defer; instrument has no gate today → `getDeferrals` 404s on a node |
| `Data` | Everything I shared | **unchanged** | no | mixed | `nodeSkip` `accord-settings` / `lens-traces` |
| `Storage` | Everything I shared | **unchanged** | no | BOTH `/v1/memory/stats` | `nodeSkip` `getAgentMode` |

Totals: **12 surfaces move**, 1 retired, 51 in the inventory
(`EpistemicNav.kt`), 49 screens reachable (`nav_map.py`), 5 flows
(`FLOW_ONLY_SURFACES`).

---

## 8. Not decided here

- The **copy** for "This node" now that "This agent" sits above it.
- The **three host defects** in §5 (Transport write, `accord-settings`,
  `/v1/registry/lookup`) — client fixes, own issues.
- The **`planet`/`biosphere` fold bug** (`ceg/Formatters.kt:35`) — one line, but
  it changes what a receipt shows, so it wants its own PR and a test.
- **Multi-agent.** CC permits it (CC 2.6.8 `part_2:596`; CC 3.3.6 `part_3:1170`);
  no service serves it. When it lands it is a list inside This agent.
- Whether `Memory` and `GraphMemory` should be **one** surface. They read the
  same three endpoints and differ only in rendering.
