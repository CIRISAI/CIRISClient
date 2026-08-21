# FSD: One client, N nodes — the tree of record, the capability lattice, and the pipelines that feed it

**Status**: DRAFT — for discussion, not locked.
**Date**: 2026-08-20.
**Repo**: CIRISClient. MDD: this FSD names *what* we build; [`../MISSION.md`](../MISSION.md) names *why*.
**Reads against**: CIRISClient `eb064d7` (PR #1), CIRISServer `a2433ba` (0.5.185, `feat/revendor-contacts-chat`), CIRISAgent `6083bdf` (2.9.28, main), CIRISConformance#86 (the parent FSD; this document is its client-side §2/§4 made concrete).

Every claim is tagged **[today]** or **[proposed]**. A **[proposed]** with no named
producer, consumer, and check is not a plan; it is a wish, and it does not
belong in §9's acceptance list.

---

## §0 The one-sentence problem

The same ~200k-line client exists in three trees in three states; its only
runtime notion of a node's capability is one bit; and every new substrate
feature costs a hand-written API layer plus a hand-carried localization pass.
This FSD makes this repo the tree of record, makes capability a first-class
per-attachment fact with an explanation for every absence, and makes "new
feature, here is the API" a structured intake instead of a conversation.

## §1 Findings this FSD stands on **[today]**

1. **Three trees.** CIRISServer/client @ `a2433ba` (0.5.185: Chat, Contacts,
   `NodeRefusal`, 141 translation repairs, 22 numbered `NODE VENDOR DRIFT`
   markers) is ahead. CIRISAgent/client @ `6083bdf` (2.9.28, `CLIENT_VERSION`
   0.5.181) is the vendor source of PR #1 — which is therefore stale at birth:
   it lacks the chat work it will immediately need.
2. **Capability is 1 bit.** `ClientMode` (NODE/AGENT) from one
   `/v1/system/health` probe. `SubstrateGate` is compile-time and keyed on
   upstream GitHub issues — switching nodes changes zero gates; two of its
   eight entries have no consumer. Roles never reach the UI: the session role
   is a flat string, and `NodeProfile` carries no role, version, or capability
   set.
3. **Degradation is inverted.** Loud at nav (SOON chips with a "why it's
   gated" explainer), silent at data (seven `nodeSkip()` sites return empty
   collections with no UI signal). Chat, Contacts, and Commons ballots shipped
   with no gate at all: against an older node they 404 through the generic
   error path.
4. **The API layer is the velocity bottleneck.** `generated-api/` is built
   from the *agent's* OpenAPI (159 paths; no chat, contacts, or commons) with
   no regeneration task in the build graph, while 186 node paths are
   hand-written in `CIRISApiClient.kt`. The node serves no OpenAPI at all
   (`evidence/blocked_upstream.tsv` rows 1–2).
5. **The substrate half of the handshake exists.** `/health` serves the
   build-level ceiling (`build_profiles`, wire-vocabulary hash, contract
   hashes); `/v1/federation/conformance` serves the state-level declared claim
   (narrow-only, fail-closed); every federation op names `required_profiles`
   and refuses. The client consumes none of it — not even
   `/v1/auth/signin-state`, the node's own capability-description endpoint.

## §2 Axioms

- **A1 — Agent is a strict superset of node.** One source tree, one registry;
  the flavor (`-PhasAgent`) is a ceiling, and dead-code elimination is an
  optimization of the runtime rule, never a separate system.
- **A2 — A node can be upgraded to include a brain.** Agent-ness is a runtime,
  per-attachment, *mutable* property. Discovery must be refreshable; the
  interesting staleness is the node getting *better* and the UI not noticing.
- **A3 — N nodes × N roles.** What a user sees is a property of an
  *attachment* (this client, this node, this role), never of the app alone.
- **A4 — 29 locales are non-negotiable.** Gating localization quality is part
  of this repo's product, not overhead. (The recurring "30" is 29 locale files
  plus `manifest.json`; this FSD says 29 and means languages.)
- **A5 — An absence is explained, never silent.** Inherited from the gate
  rules: `unimplemented` is not a pass, and an empty state is not "nothing in
  force".

## §3 The tree of record **[proposed → executed by this branch]**

A true three-way merge, then this repo's `client/` leads.

- **Base**: commit `dc17f56` — the byte-identical vendor of CIRISAgent
  `6083bdf` `client/` with the §2 exclusions of
  [`../client/VENDORING.md`](../client/VENDORING.md) applied.
- **Ours**: PR #1 HEAD — base plus the eight declared deltas
  (flavor generation, `VERSION`-derived `CLIENT_VERSION`, destructive-`Sync`
  removal, vendored localization checker).
- **Theirs**: CIRISServer `a2433ba` `client/`, with the same exclusion set
  **extended**: `**/.ciris_keys/`, `**/__pycache__/`, `*.pyc` (the server tree
  tracks two `secrets_master.key` files and compiled Python inside `client/`;
  they do not cross).

**Conflict policy**: localization bundles — theirs (the repairs and the 67 new
keys are server-side); build files — ours' flavor mechanics composed with
theirs' artifact gating; `CIRISBuild.kt` — stays deleted (generated per
flavor); `ClientMode.kt` — theirs' drift-#27 three-state content, minus the
`CLIENT_VERSION` const (generated). Every other conflict is resolved for
content currency (theirs) unless it undoes a declared delta.

**After the merge**: `VERSION` = 0.5.185; the post-merge digest and a
per-delta hash table replace the single post-delta digest in `VENDORING.md`;
and the direction of travel flips — upstream changes reach this tree by
cherry-pick, and *this* tree is where client work happens ("updating manually
until we roll out our wheel"). VENDORING.md §8's wipe-and-reapply procedure
retires in favor of merge-based pulls, recorded there.

- Producer: this branch. Consumer: CIRISServer and CIRISAgent (as future wheel
  consumers), every client PR. Check: `packaging/check_vendoring.py`
  (reworked: tracked-files-only digest, per-delta hashes so a declared file
  cannot drift silently — the two Codex P1s against it).

## §4 The capability lattice **[proposed]**

**The rule**: `visible(surface, attachment) = min(ceiling, exposure, role)` —
and the UI names whichever term is the minimum.

| Term | Source | When it changes |
|---|---|---|
| ceiling | flavor + `CLIENT_VERSION` (build-time) | new install |
| exposure | the node's attested capability set (runtime) | node upgrade, brain fold-in — **can rise mid-attachment** |
| role | this user's role on this node (runtime) | grant/revoke |

**4.1 One registry.** `SubstrateGate` grows into a `Capability` registry in
shared code, keyed by stable capability id (`chat`, `contacts`,
`commons.ballots`, `video`, `groups.private`, …), each entry carrying: the
existing provenance fields (repo, issue, prefix family, FSD section); the API
operations it requires; the minimum node version / build profile; the flavor
ceiling; the role floor; and its strings namespace. Every `NavSurface` and
every API-client method points at an entry. An entry with no consumer is a
CI failure, not a curiosity (two such entries exist today).

**4.2 Six states, each with a sentence.** Every surface resolves per
attachment to exactly one of: `AVAILABLE`, `NODE_LACKS` (with the version that
adds it, and which attached node has it), `ROLE_LACKS` (the role floor, this
node), `BUILD_LACKS` (compiled out; the node has it — link to the full
client), `PENDING` (probe unresolved; a retry state, never latched — the
`ModeProbe.undetermined` rule generalized), `UNSHIPPED` (today's SOON chip,
kept as is). Every sentence is a localization key in all 29 locales, riding
the existing `NodeRefusal.reason_id` channel. The seven `nodeSkip()`
silent-empty sites and the seven throw-on-call federation methods migrate to
these states. `ComingSoonPlaceholder` generalizes to `CapabilityPlaceholder`
parameterized by state.

**4.3 The document.** The node serves its attested capability set
(`ciris-capability/v1`, CIRISConformance#86 §4/heading-7: per-capability
surface status, node version, build profiles, locale Merkle root, wire
vocabulary — signed). The client caches it per `NodeProfile`
(`{version, role, capabilities, hash, fetchedAt}` — all new fields), re-checks
by hash on reconnect, node switch, and push. The version-mismatch banner
becomes a capability diff. Until the node serves the document, the client
derives a degraded exposure set from what it can already read (`/health`,
`/v1/federation/conformance`, `/v1/auth/signin-state`, probe results latched
per the `BudgetCapability` pattern) — and says it is guessing.

- Producer: node (document), this repo (registry, states, cache). Consumer:
  every screen; the compat matrix (§6); CIRISConformance's client lane.
  Check: `nav-gate-registry` extended (no dead entries, no ungated surface);
  a new `capability-coverage` gate — every registry entry's operations appear
  in the spec the node serves, **[unimplemented]** until §5 lands.

**Why this is the rapid-release mechanism**: with degradation as every
surface's default state, client cadence decouples from node rollout — a
surface ships gated this week and lights up per-node as nodes upgrade,
explained in the meantime. Activation is server-driven; rendering stays
native and verifiable, which a decentralized mesh requires.

## §5 OpenAPI per release — the upstream requirement **[proposed]**

"A spec the client owns cannot be the contract the client is judged against"
(#86). The requirement, per releasing repo:

- **CIRISServer**: serve the spec from the node (`GET /v1/openapi.json`,
  versioned with the release) **and** attach `openapi.json` to every GitHub
  release. The served spec is the normative one; the asset is the archival
  copy CI can pin.
- **CIRISAgent**: attach its FastAPI-generated `openapi.json` to every release
  (the app already generates it; publishing is the missing step).
- Both: the spec carries `info.version` equal to the release version, and CI
  runs `oasdiff` against the previous release's asset, labeling the release
  notes with breaking/non-breaking.

- Producer: CIRISServer, CIRISAgent (tracked as upstream issues; the issue
  numbers land in `evidence/blocked_upstream.tsv` when filed — the rows exist
  today as `absence`). Consumer: `spec-drift` and `generated-api-drift` gates,
  the §4.3 `capability-coverage` gate, the §6 matrix, `generated-api`
  regeneration. Check: `spec-drift` stops being `unimplemented` the day the
  node serves a spec.

## §6 The compatibility matrix **[proposed → seeded by this branch]**

One machine-readable file, `compat/matrix.json`, schema
`ciris-client-compat/v1`. One row per client release:

```
{ client_version, flavor, node_min, node_max_tested, agent_min_tested,
  capabilities: [ids], spec_source: {repo, release, sha256|null},
  locale_bundle: {languages: 29, keys: N}, notes }
```

Rules: rows are append-only (a released row never mutates; corrections append
a superseding row naming what it corrects); `node_min` is the oldest node the
release degrades *gracefully* against — meaning every absent capability
renders a §4.2 state, not an error; `capabilities` lists registry ids present
at that release's ceiling. The matrix is the *published* answer to "which
client works with which node, and what does it do when they mismatch" — the
question the version banner currently answers with a string compare.

- Producer: this repo's release process (a release PR without its matrix row
  does not merge). Consumer: operators and support; the docs; the client
  itself (the `NODE_LACKS` sentence's "available from v…" comes from the
  registry, cross-checked against the matrix); CIRISConformance's client lane
  (which node versions to run against). Check: a new **`compat-matrix`** gate
  — the file parses, rows are append-only, exactly one row matches `VERSION`,
  and every capability id it names exists in the registry.

## §7 The localization pipeline **[today → hardened]**

Current: 3,827 keys × 29 languages, four in-tree mirrors, byte-identity
guarded. Recent history shows the limit of byte-identity: 141
placeholder-corrupt values entered upstream and were repaired by hand
(CIRISAgent#1086); the sync checker compares key *sets*, not values.

Hardened, in the order pieces retire each other:

1. **Structural validation as a merge gate**: placeholder integrity against
   the English source, plural forms, no raw keys, **value-level** cross-mirror
   comparison, and a non-empty language manifest checked against
   `SUPPORTED_LANGUAGES`. Makes the #1086 class unmergeable. (Absorbs three
   Codex findings against `check_localization_sync.py`.)
2. **Fail-closed intake**: a key entering via §8 without all 29 translations
   fails `locale-parity`. Pre-translation may be machine-generated against the
   glossary; *quality review* is threshold-triggered — but *presence* is
   binary and gated.
3. **Pseudo-locale in CI** to catch hard-coded strings and layout breakage
   before any translator sees a key.
4. **The Merkle root retires the mirrors**: when the client bundle is checked
   against the substrate's signed locale root, byte-identity across mirrors
   stops being the gate (#86; blocked on the `ciris.locale_manifest` `v2`
   /`locale` vs shipped `v1`/`lang_code` domain drift — the `untestable`
   evidence row, which needs an upstream issue filed).

- Producer: this repo (checker, gates), substrate (root). Consumer: every PR;
  the release attestation. Check: `locale-parity` (extended), the vendored
  checker in CI.

## §8 The intake contract — "new feature, here is the API" **[proposed]**

The handover artifact from a server/agent team is a **spec delta plus a
registry entry**, delivered as an issue/PR here carrying `capability.yaml`:

```
id:            commons.ballots
owner:         CIRISServer#451        # repo + tracking issue
fsd:           §…                     # section in the owning FSD
prefix_family: …                      # FSD-002 attestation prefixes, if any
operations:    [POST /v1/commons/ballots, …]
profiles:      […]                    # required build profiles
role_floor:    OBSERVER|ADMIN|AUTHORITY|OWNER
flavor:        node|agent             # ceiling (A1: agent ⊇ node)
strings:       commons.ballots.*      # localization namespace
```

Pipeline, per feature: **(1)** intake lands the registry entry as `UNSHIPPED`
— the surface ships in the next client release, gated, visible, explained,
*before* the node work lands; **(2)** `oasdiff` classifies the spec delta
against the node-served spec (§5); **(3)** the API layer regenerates from the
spec — a build-graph task, ending the hand-written-Ktor era one module at a
time; **(4)** strings enter §7 at intake, so translation runs concurrent with
implementation; **(5)** the flip is CI-forced by #86 §3's strict-xfail
machine: declared-not-built → `xfail(strict)` in the conformance client lane;
the node change lands → xpass turns the build red → the gate flips and the
issue closes. There is no state in which the change exists and nobody has
acknowledged it. **(6)** The release carries the signed `ciris-capability/v1`
— the same document §4.3's runtime diff consumes. One artifact, two consumers.

- Producer: server/agent teams (the yaml + spec delta), this repo (UI, gates,
  strings). Consumer: the registry, the matrix, the conformance lane. Check:
  CI validates `capability.yaml` against the registry schema; the
  `capability-coverage` gate ties operations to the served spec.

## §9 Phasing and acceptance

- **Phase 0 — converge (this branch)**: the §3 merge; `VERSION` 0.5.185;
  extended exclusions; reworked vendoring check; `compat/matrix.json` seeded
  with the 0.5.185 row; `compat-matrix` gate; registry placeholders reserved
  for `video`, `voting`, `groups.private` (CIRISServer#451) so the three
  unrepresented features get the established SOON surface; upstream issues
  filed (server spec-serving, agent spec-publishing, locale-manifest domain
  drift) and their numbers recorded in the evidence registry.
  *Accept when*: the merged tree builds both flavors; the gate board runs;
  every Codex finding on PR #1 is fixed or carries an evidence row saying why
  not.
- **Phase 1 — consumers flip**: Server and Agent depend on
  `ciris-client[node|agent]`, delete their trees; drift markers, sync
  scripts, and the byte-identity guard retire on #86 §7's conditions.
  *Accept when*: `ciris-client` appears in both repos' requirements (the
  evidence predicate PR #1 already names).
- **Phase 2 — the lattice**: §4 registry, states, `NodeProfile` cache,
  capability diff banner; nav/gating tests exist (today: zero).
  *Accept when*: no surface renders an unexplained empty; switching nodes
  changes what the sidebar offers and says why.
- **Phase 3 — the pipeline**: §5 specs per release, codegen in the build
  graph, `oasdiff` gate, conformance client lane, signed release capability
  document. *Accept when*: a new capability reaches a gated, localized,
  released UI without a hand-written API layer.

Ordering rule, inherited from #86 §8.1: each phase produces the fact the next
one needs. Do not invert it.

## §10 Risks

- **The merged tree is a fork until Phase 1 completes.** Mitigation: the
  drift that accumulates here is exactly the drift the consumers inherit when
  they flip — visible in one place, guarded by the reworked vendoring check,
  instead of three.
- **Server's client tree keeps moving while we merge** (six files were dirty
  in its working tree at the time of writing). Mitigation: merge from
  committed HEAD only; pulls are merges now, not wipes, so a follow-up pull
  is cheap.
- **Machine pre-translation can pass presence while failing quality.**
  Mitigation: §7 separates the two on purpose — presence is a binary gate,
  quality is a scored, threshold-reviewed lane; neither substitutes for the
  other.
- **The capability document does not exist yet.** Mitigation: §4.3's
  derived-exposure fallback ships value before the node half lands, and says
  it is guessing rather than pretending to know.

## §11 References

- CIRISConformance#86 — the parent FSD (requisite classes, client lane,
  strict-xfail machine, `ciris-capability/v1`).
- CIRISClient PR #1 — the extraction and packaging model; its Codex review
  findings are §3/§7 inputs.
- CIRISServer#451 (edge-v18 umbrella: video, voting substrate, private
  groups), #464 (contacts + user chat + the 2.9.28 re-vendor).
- Prior art the lattice is checked against: Matrix `/versions` +
  capabilities; Nostr NIP-11; XMPP XEP-0030/0115 (capability hashes for cheap
  re-discovery); Kubernetes aggregated discovery + client-side feature gates;
  AT Protocol lexicon codegen; OpenFeature evaluation context; `oasdiff`.
  Where CIRIS exceeds them: the capability advertisement is attested and
  checked, and refusal reasons are localization keys.
