# CSD/4 — topology-declared fixtures: can the client execute it?

**Date**: 2026-09-29 · **Server side read**: CIRISServer `chore/adopt-edge-v33`
@ `7c13f96f` (`FSD/TOPOLOGY.md`, `harness/native/{__main__,mesh,scenarios,topology}.py`,
`harness/native/topologies/csd-09{1,2,3}-*.yaml`) · **Client side read**: `main`
@ `40f5b20e` (`testing/gate/{flow_spec,run_flows,run_platform,two_node}.py`,
`testing/flows/README.md`, `CSD.md`, `packaging/check_csd_v3.py`,
`.github/workflows/five-platform-live-qa.yml`, the drafts) · **Evidence run**:
five-platform live QA 36588619656 (branch `flows/matrix-0.5.225`, 2026-09-29).

CSD/4 = CSD/3 + a `topology:` block typed by `FSD/TOPOLOGY.md` (roots →
canonicals → nodes → persons → relations → actor → negatives, with realizability
rules and derivations) + per-layer `checks:`. Server line numbers below are at
`7c13f96f`; client line numbers at `40f5b20e`.

## 0. Summary

1. **Executable as-is: partly.** The builder's `values.json` is already the
   shape our `${NAME}` substitution eats (`topology.py:313,359-365` ↔
   `two_node.py:437-452`, `flow_spec.py:73,424-435`), so the **client UI layer
   runs today** once a `server_harness` fixture wraps `python -m harness.native
   build`. **roots, canonicals/persons (rooting), nodes (dials) and relations are
   executable in the harness but not on the matrix**: every leg downloads the
   *released* `ciris-server` (workflow `:161-179,405-416,808-819`) and the
   synthetic root exists only in a `--features test-anchor` build
   (`mesh.py:5-9`, `__main__.py:5-7`). **An `--attach url,token` mode does not
   exist** (`Mesh.add` always spawns and `claim`s: `mesh.py:431-446,233-265`;
   `build` claims every person's first node: `topology.py:166-171`) and, once it
   does, an attached released node cannot `accepts: R`, so realizability rule 2
   (`TOPOLOGY.md:108`, `topology.py:83-84`) makes the actor's person unrootable —
   the declaration must say `accepts: none` and `rooted_with … require: false`.
   **The agent layer has no vocabulary at all.**
2. **Yes, gate `testable` on topology coverage.** Today `fixture: two_node` is
   the whole declaration (`flow_spec.py:64-69,313-316`) and `check_csd_v3`
   knows nothing of fixtures (`check_csd_v3.py:42-49,207-213`). Proposal in §2:
   a `csd:topology` block, required at `testable`, compared layer by layer
   against the fixture's own declaration (`testing/gate/topologies/<fixture>.yaml`),
   with the failure message naming the layer and the row.
3. **A native test-anchor `ciris-server` per host target is the right fixture**
   (three targets, not five: Android and iOS legs run it on their host, exactly
   as `two_node.py` does today — `two_node.py:10-18,56-66`). It does not fit on
   iOS for the *actor's* node (the app embeds a released node on 4242/4243), on
   Windows until `mesh.py` gets the process-group/`.exe`/work-dir handling
   `two_node.py` already has (`mesh.py:181,195,400-402`, `__main__.py:27` vs
   `two_node.py:132-141,316-320,355-379`), and nowhere until the test-anchor
   asset is published. Run 36588619656 reproduced #699's window
   (`reachable_nodes=0` on both contacts) and #698's outcome (the room never
   keyed in 150 s) on the released 0.5.225 binary.
4. **Communities/households, moderation, accord/duty conferral and trust-root
   cards all need layers the vocabulary lacks** — chiefly `community`/
   `household` with a held `quorum_change`, `delegation` chains, `holder` and
   `duty_conferral`, an unclaimed node, a list-valued `accepts`, and a way to
   declare a *transition* (`pre:`/`post:`), since those cards are the screens
   that *create* the state the proving set assumes pre-exists. §4 drafts each
   block.
5. **A red should read** `[fail] CSD-091 csd_091_user_chat › a_room_with_history
   (expect) layer=relations/message cc=CC 5.4.6 ceg=… tag=… — <means>; evidence:
   B: <log line>`. That needs `cc:` on `expect:` steps (and on `shows:` rows so
   the ceg→clause map is one source), `layer:` on every `checks:` row, and the
   builder's `means`/`evidence` carried into `StepResult`/`FlowOutcome`
   (`flow_spec.py:517-527`, `run_flows.py:68-75`).

Concrete asks of the server are in §6; the client's plan, by file, in §7.

## 1. Can the runner execute CSD/4 as-is?

### 1.1 `values.json` as the fixture — yes, with two shape notes

The builder writes `result["values"]` as a flat upper-case dict
(`__main__.py:72-75`; filled at `topology.py:258-259,313,359-365`):
`PEER_URL`, `PEER_KEY_ID`, `PEER_NODE_KEY_ID`, `PEER_OWNER_KEY_ID`,
`LOCAL_OWNER_KEY_ID`, `LOCAL_NODE_KEY_ID`, `ROOM_ID`, `MESSAGE_TEXT`,
`MESSAGE_ATTESTATION_ID`, `MESSAGE_ARRIVED`, `PEER_CONTACT_CODE`. That is the
key set `FixtureValues.as_vars()` produces (`two_node.py:437-452`), which is
what `FlowRunner.variables` consumes (`flow_spec.py:543-546`) through
`resolve_step` (`flow_spec.py:438-465`). Every draft that names a variable
(`csd-091:44-46,82-87`, `csd-006:36-39,49`, `csd-005:55-56`, `csd-047:42-52`)
would resolve unchanged.

Two differences that matter:

* **No `notes`.** `substitute()` quotes the fixture's *reason* when a value is
  absent (`flow_spec.py:426-433`; README `:186-188`). The builder has something
  better — each failed step carries `means` and `evidence` (`scenarios.py:38-45`)
  in `report.json` (`__main__.py:69-71`) — but it is not in `values.json`. The
  `server_harness` fixture must read `report.json` and hand the failing step's
  `means` + evidence lines to the runner as `notes`.
* **Values are positional, not declared.** `PEER_*` is "the first person who is
  not the actor" (`topology.py:360-362`). In csd-091 that is bob by list order;
  a declaration that lists eve before bob would hand the flow the outsider's
  key with no error. `${NAME}` is `[A-Z][A-Z0-9_]*` (`flow_spec.py:73`), so
  `PERSON_BOB_OWNER_KEY_ID`, `NODE_B_URL`, `ROOM_PAIR_ID`,
  `MESSAGE_1_ATTESTATION_ID` already lex; the builder should emit those (keyed
  by the declared ids, upper-cased) and keep `PEER_*`/`ROOM_ID` as actor-view
  aliases. `PEER_KEY_ID` in the harness is always the owner key
  (`topology.py:363`) — the `contact_via: node` demotion in `two_node.py:524-540`
  has no counterpart, which is correct: a contact held under the node key is a
  different fact and should fail rule-visibly, not be renamed.

### 1.2 `--attach url,token` — does not exist; what it needs; what it cannot do

Nothing in `harness/native` takes an existing node. `Mesh.add` configures a
home, sets `net.bootstrap_peers` offline and spawns (`mesh.py:431-446,168-190`);
`Node.claim` waits for `<home>/claim_pin`, mints an identity and runs the
console claim (`mesh.py:233-265`); `build` does that for every person's first
node (`topology.py:166-171`). The leg's node is claimed by the *client's* wizard
(`run_flows.sign_in` → `session_fixture`, `run_flows.py:240-248`) and the
current fixture only signs in to it (`two_node.py:52-54,462-467,680`).

An attach mode needs a `Node` with `proc=None`, `configure/start/stop/claim`
as no-ops, `token` given, `owner_key_id` read the way `two_node.owner_of` does
(the announce bundle's `owner_key` row, else `root:<fed-ID>` from
`/v1/auth/me`: `two_node.py:485-501`), and a `log_path` for evidence — the leg's
node writes `node.log` (`.github/actions/ciris-node/action.yml:59-62,94`).
`rooted_with()` greps `peer=<key_id>` where `key_id` is the harness's own
`native-<name>` alias (`mesh.py:309-316,416`); an attached node's alias is
whatever the action started it with, so the pattern must take the peer's
*node key id*, not the alias.

Three things attach cannot make true, and the declaration must say so:

* **The attached node's dial set is fixed at boot** (`net.bootstrap_peers` is
  boot-structural: `two_node.py:22-25`). So a harness node must dial *it*
  (`B: {dials: [C, A]}`, as csd-091 already writes at `:10`), never the
  reverse; and a released node dials the production canonical by default
  (#698, last paragraph) — under a live test anchor "a node dials only what it
  is told" (`mesh.py:14-15`), but the attached node is not under one.
* **The attached node cannot `accepts: R`.** The synthetic root is honoured
  only by a test-anchor build. Rule 2 (`TOPOLOGY.md:108`; `topology.py:83-84`)
  then refuses `alice: {accepts: R}`; rule 3 refuses `rooted_with(alice, bob)`.
  The declaration for a leg-attached actor is `A: {accepts: none}`,
  `alice: {accepts: none}`, `rooted_with … require: false` — which is what all
  three proving declarations do anyway (`csd-091:18`, "the room keys without it
  today"). The persist layer is then *observed and recorded*
  (`topology.py:216-218`), not proven, for every matrix flow.
* **On iOS there is no log file to grep** (§3).

### 1.3 What the flow YAML and `flow_spec` need

```yaml
csd: CSD-091
fixture: server_harness          # FIXTURES += "server_harness" (flow_spec.py:69)
topology: CSD-091                # _FLOW_KEYS += "topology" (flow_spec.py:64): the CSD's
                                 # `csd:topology` block, not a path into another repo
```

The `topology:` block lives in the CSD as ```` ```yaml csd:topology ```` — the
`BLOCK` regex already accepts any section name (`check_csd_v3.py:36`;
`csd_doc.py:49-58` reuses it) and `TOPOLOGY.md:3-4` names "CIRISClient's CSD/4
`topology:` block" as a consumer. The server's `harness/native/topologies/*.yaml`
are then the proving set the *server's* CI runs, and must be byte-identical to
the block bodies (the flow byte-identity rule of `FSD/CSD_STANDARD.md` §4,
applied to the topology) — checked here the way `check_localization_sync.py
--server-src` is (`packaging/gates.sh:57-58`), or, better, the builder reads
the CSD file directly (ask §6.6). Two hand-kept copies is the drift this repo
measures.

`checks:` per layer — **pass-through, not re-asserted.** The builder already
asserts each layer and names the first that does not hold (`topology.py:147,
164,180,186,198,210,242,256,280,293,311,336,347,355`; `step.fail` at
`212,236,278,290,308,330,334`). The runner's job is: refuse to start the flow
unless the builder's verdict is `PASS` (`__main__.py:66-67`), and report the
first failing layer with its evidence as the flow's `cannot-start` reason
(`run_flows.py:313-321` already turns a fixture exception into `cannot-start`
naming why). The runner asserts only the `client_ui` layer — the steps — plus
one cross-check: a `${NAME}` produced by a relation the declaration marked
`require: false` (observed, `topology.py:216-218`) is reported as `observed`,
so a green step over an unproven layer is visible in the report.

```yaml
checks:                          # in csd:topology, one row per layer the CSD leans on
  - {layer: roots,     cc: [CC 3.2],   proves: "one software key root, active"}
  - {layer: nodes,     cc: [CC 5.4.6], proves: "B dials A: bodies are one hop"}
  - {layer: relations, rel: reachable, cc: [CC 3.3.7], proves: "bob's binding is held on A (CIRISServer#699)"}
  - {layer: relations, rel: room,      proves: "the pair room keyed on both sides"}
  - {layer: relations, rel: message,   cc: [CC 5.4.6], exports: [MESSAGE_ATTESTATION_ID, MESSAGE_TEXT]}
  - {layer: client_ui, steps: [on_the_chat, a_room_with_history]}
```

### 1.4 Per layer

| layer (stack) | topology layer | executable in `harness/native` | executable on the matrix | gap |
|---|---|---|---|---|
| **verify** (trust-root verdict) | `roots` | **yes**, for `{kind: key, holders: 1, custody: software_test, lifecycle: active}` only (`topology.py:26-28,67-69`; `TOPOLOGY.md:50-54`); evidence `TEST-ANCHOR ceremony: trust_root_valid GREEN` (`topology.py:149`) | **no** — released binary, no synthetic root | no published test-anchor `ciris-server` per host target; `family`/`hardware`/`stalled`/`halted` refused by name |
| **persist** (rooting, owner bindings) | `canonicals`, `persons.accepts`, `rooted_with`, `reachable` | **yes** — `rooted_with` from edge's log verdict (`mesh.py:309-316`), `reachable` gate (`topology.py:219-243`) | `reachable`: **yes** (HTTP only); `rooted_with`: **observed only** — the attached node cannot accept R (§1.2) | attach mode; every proving declaration already has `require: false` (`csd-091:18`), so the layer is never gated |
| **edge** (transport, one-hop scoped content) | `nodes.dials` | **yes** — `dials` → `net.bootstrap_peers` (`mesh.py:168-173,442`); rule 4 refuses non-neighbour `message`/`file` (`topology.py:92-100`); evidence `_BODY` (`scenarios.py:173`) | **yes**, if the harness node dials the attached one | attached node's dials fixed at boot; multi-fragment frames stall on a direct link (CIRISEdge#716, `TOPOLOGY.md:69-70`) |
| **server** (routes) | `relations`, `negatives` | **yes** — `peered`, `rooted_with`, `reachable`, `contact`, `room(pair\|self)`, `message`, `file`, `cannot_list_room`, `holds_no_row` (`topology.py:194-355`); `member`/`quorum_change` raise "declared, no builder yet" (`:337-338`; `TOPOLOGY.md:92`) | `room keyed: true` **no** on the released line (#698); everything before it **yes** | #698/#699 |
| **agent** | — | **no vocabulary** | **no** — the matrix runs a bare node (`run_platform.py:98-115,175`; workflow `:43-44`) and CIRISAgent publishes no per-platform artifact (workflow `:27-30`) | an `agents:` layer (which node a brain is folded on; `ClientMode` is probed, never declared) |
| **client UI** | `actor` + steps | n/a | **yes** — `${NAME}` (`flow_spec.py:73,424-465,776-785`), fixture lifecycle (`run_flows.py:295-349`) | report format (§5); `server_harness` wrapper (§7) |

## 2. Gating `testable` on "fixture topology ≥ CSD topology"

**Today.** `fixture: two_node` is the entire fixture declaration
(`flow_spec.py:64-69,313-316`); what it actually builds is prose in
`two_node.py:1-81` and README `:162-169`. `check_csd_v3.py` never reads a flow
or a fixture: `REQUIRED_AT["testable"]` is `stage/shows/states`
(`check_csd_v3.py:42-49`) and the `testable` rule is "no `proposed:` tag"
(`:207-213`). `TOPOLOGY.md:120-122` states the rule this section implements:
*"A flow may not advance to `testable` while its fixture's topology is smaller
than its CSD's on any layer."*

**Proposal.**

1. Every CSD carries a ```` ```yaml csd:topology ```` block; `REQUIRED_AT`
   gains `"topology"` at `testable` and above. The bare node is a declaration
   too (`roots: []`, one node `{accepts: none, announced: false}`, one person,
   no relations, actor) — README `:140-143`'s "one node with no contacts, no
   agent and no peers" becomes checkable instead of remembered.
2. Each fixture declares what it builds, in the same vocabulary, checked in:
   `testing/gate/topologies/bare.yaml`, `testing/gate/topologies/two_node.yaml`
   (honestly: two nodes, no common root, one-way dial, contacts both ways,
   `room … keyed: false`, no `message` — that is what #698 measured and what
   run 36588619656 produced), and `server_harness` = the CSD's own block
   (trivially ≥).
3. `check_csd_v3` resolves the CSD's flow (`**Flow**:` line, or the flow that
   names `csd: CSD-NNN`) → its `fixture:` → that fixture's declaration, and
   requires the CSD's block to embed in it **on every layer**: `roots` by
   `(kind, custody, lifecycle)` multiset; `canonicals` count; `nodes` — an
   injective map by id preserving `accepts`, `announced`, and `dials ⊆`;
   `persons` — same, with `|owns| ≤`; `relations` and `negatives` — every CSD
   row present in the fixture (rows compared as dicts with `wait`/comments
   dropped; `keyed`, `min`, `require` must be ≥ the CSD's). Ids are the
   fixture's ids — a CSD names `A`, `B`, `alice`, `bob`, `pair`, so no
   isomorphism search is needed. Realizability itself (`TOPOLOGY.md` §3) stays
   the server's (`topology.py:54-110`); our check is coverage, stdlib, no
   build.
4. It is shown to FAIL on a planted defect before it is believed (AGENTS.md):
   `testing/test_csd_topology.py` plants `keyed: true` against `two_node.yaml`.

**Failure message** (one per layer, first layer first, in dependency order):

```
CSD-091: stage 'testable' needs a fixture whose topology covers the CSD's on every
layer, and fixture 'two_node' (testing/gate/topologies/two_node.yaml) is smaller:
  roots:     the CSD declares R {kind: key, custody: software_test} and the fixture
             provides none — the leg's binary is the released build (no test anchor)
  relations: the CSD declares {rel: room, kind: pair, members: [alice, bob], keyed: true}
             and the fixture provides keyed: false (CIRISServer#698)
  relations: the CSD declares {rel: message, from: bob, to: alice, room: pair} and the
             fixture provides no message
A flow may not advance while its fixture's topology is smaller than its CSD's on any
layer (FSD/TOPOLOGY.md §4). Either declare `fixture: server_harness`, or keep the
stage at 'building'.
```

## 3. The five-platform matrix

**A native test-anchor `ciris-server` per host target is acceptable, and is the
only shape that fits.** `two_node.py:10-18` already records why Docker cannot:
macOS runners have none, Windows runners run Windows containers, and neither
the emulator nor the simulator sees a compose network. `harness/native` is the
same shape (`mesh.py:1-10`: "N `ciris-server` processes on 127.0.0.1 … no
Docker"), so it needs **three** binaries — `x86_64-unknown-linux-gnu`,
`aarch64-apple-darwin`, `x86_64-pc-windows-msvc` — not five: the Android and
iOS legs run the fixture on their host and the client only ever talks to its
own node (workflow `:27-30`, `:716-719`; `two_node.py:56-66`).

Where it does not fit:

* **iOS simulator.** The app embeds its own node, binding 0.0.0.0:4242/4243 on
  the loopback it shares with the runner (workflow `:667-690`); that node is a
  release artifact (`client/VENDORING.md` §2 never-vendor classes —
  `iosApp/app_packages_native/`), not a test-anchor build. So the *actor's*
  node cannot be under R (§1.2), and it must be attached, not started. Its log
  is os_log (workflow `:729-741`, `log show --style syslog`) and its home is
  the simulator's data container (`:749`, `simctl get_app_container … data`):
  `Node.grep` has no file, so `rooted_with` and every `step.fail` evidence for
  the actor's node is empty unless the attach mode accepts a *command* as the
  log source. Harness nodes on 7000+ (`mesh.py:411`, `free_port_pair`
  `:77-92`) stay clear of 4242/4243; the macOS leg's peers must be down before
  the simulator boots (`:714-715`), which generalises to `harness.native down
  --work`.
* **Android emulator.** The client reaches the *host's* :4243 through `adb
  reverse` (workflow `:27-30`; `two_node.py:61-63`), so the actor's node is a
  host process and attach is the desktop case. The one wrinkle is shared state:
  the Linux and Android legs share a runner and the workflow restarts a fresh
  node when the fixture seeded the first (`:258-284`). With a topology fixture
  the actor's node is *harness-started under R* and torn down with the mesh
  (`mesh.py:474-481`, `down` by pidfile `:453-464`), so "was the node seeded?"
  becomes "each leg gets its own mesh" — simpler, not harder. 10.0.2.2 is never
  needed.
* **Windows.** `mesh.py` is POSIX-only: `start_new_session=True`
  (`:181`), `os.killpg` (`:195,199,459`); no `CREATE_NEW_PROCESS_GROUP` /
  `taskkill /T` branch (`two_node.py:316-320,355-379`). `Mesh.__init__`
  requires `binary.is_file()` on `ciris-server` (`:400-402`) but the leg
  extracts `ciris-server.exe` (workflow `:818-819`; `two_node.resolve_binary`
  `:132-141`). `--work` defaults to `/tmp/ciris-native-mesh` (`__main__.py:27`)
  — must be `$RUNNER_TEMP`. A home whose SQLite a leftover process still holds
  cannot be `rmtree`'d (`mesh.py:474-476`) — `down` before enter, as the
  workflow does for `two_node` (`:839-842`).
* **macOS runner.** No Docker; native fits with nothing further.

**What #130 and run 36588619656 showed.** On the released 0.5.225 binary both
contacts came back `reachable_nodes=0` (fixture log 15:43:12 on macOS,
15:37:29 on Linux) — #699's window exactly — and the room never keyed within
150 s (`chat.state.awaiting_peer`, "the other side's KeyPackage did not
cross") — #698. `two_node.py` waits for the owner *key* to be known
(`:518-524`, `knows()`), which is earlier than the binding; the harness's
`reachable` gate re-POSTs until `reachable_nodes >= 1` (`topology.py:219-243`)
and then does **not** re-add the contact (`:247-253`, "three builds that did so
never keyed the room afterwards"). Both belong in `two_node.py` meanwhile, but
neither closes #698: the released line still cannot key, so on the matrix
`room … keyed: true` is realizable only under the test-anchor binary. The same
run also reddened `people`/`csd_005` on `contacts_list` / `card_contacts_add`
never appearing and `csd_092` on an error sentence — client-UI-layer reds that a
report line naming the layer would have separated from the fixture's at a
glance (§5).

## 4. CSDs that need layers the vocabulary lacks

The vocabulary is a snapshot of state the flow *reads*. Four families of cards
are the screens that *create* it, so their topology is a transition: what must
hold before the flow (`pre:`), and what the flow's `do:` writes (`post:`). That
is the first lack, and it is common to every block below. The second is
rule 4: scoped content is one hop (`TOPOLOGY.md:109-110`, `topology.py:92-100`),
so an N-member room whose bodies must open on every member needs an N-clique
of `dials` — the rule's parenthesis says no relay-routable scoped address
exists; the vocabulary should say whether that is the design.

### 4.1 Communities (CSD-102, CSD-103, CSD-110) and households (CSD-100, CSD-101)

What the card must SEE: a room row with `member_count` and `my_role`
(CSD-102 `:64`), the rule `quorum:2/3` (`:75-76`), the moderators line read
from the founder-rooted chain (`:95`), a **held** change with "Bo · signed,
Cy · not yet" (CSD-100 `:132`), a member added after founding flagged late
(CSD-103 `:93`, CIRISPersist#907), and a non-member's `community.not_found`.

```yaml
topology:
  roots:      [{id: R, kind: key, holders: 1, custody: software_test, lifecycle: active}]
  canonicals: [{id: C, holds: R, serves: [infra:serve, infra:attest]}]
  nodes:
    - {id: A, dials: [C],       accepts: R, announced: true}
    - {id: B, dials: [C, A],    accepts: R, announced: true}
    - {id: D, dials: [C, A, B], accepts: R, announced: true}   # N-clique: rule 4
    - {id: X, dials: [C],       accepts: R, announced: false}
  persons:
    - {id: alice, owns: [A], accepts: R}
    - {id: bob,   owns: [B], accepts: R}
    - {id: cy,    owns: [D], accepts: R}
    - {id: eve,   owns: [X], accepts: R}
  relations:
    - {rel: peered, between: [A, B]}
    - {rel: peered, between: [A, D]}
    - {rel: peered, between: [B, D]}
    - {rel: reachable, node: A, person: bob, min: 1}
    - {rel: reachable, node: A, person: cy,  min: 1}
    - {rel: contact, from: alice, to: bob, via: owner}          # a member must be a contact
    - {rel: contact, from: alice, to: cy,  via: owner}          # (community.not_a_contact)
    # LACKING: community(...) — kind room|family, tier, founder, consensus_protocol
    - {rel: community, id: allotment, kind: room, tier: community, founder: alice,
       consensus_protocol: "quorum:2/3", members: [alice, bob, cy]}
    # LACKING: quorum_change with a STATE — the card shows a held change, not an applied one
    - {rel: quorum_change, community: allotment, op: role, subject: bob, role: founder,
       proposer: alice, signers: [alice], state: held}
    # LACKING: member(..., joined: late) — CSD-103's late flag (CIRISPersist#907)
    - {rel: member, person: cy, community: allotment, role: member, joined: late}
    - {rel: room, id: allotment, kind: community, members: [alice, bob, cy], keyed: true}
    - {rel: message, from: bob, to: alice, room: allotment}
  actor: {person: alice, device: A}
  negatives:
    - {check: cannot_list_room, person: eve, room: allotment}
    - {check: cannot_read, person: cy, room: allotment}        # LACKING: a widened member is sealed to but cannot read
```

A household is the same block with `kind: family`, `cohort_scope: family`,
`consensus_protocol: founder_only | quorum:M/N`, and one more check the card
leans on: CC 4.4.3.4.1 wraps every extant family DEK to a new member, so
`can_read(member, room, since: founded)` is the fact "I added Carol and she can
read". **Lacking**: `community`/`household` as a relation; `quorum_change` with
`state: held|applied`; `member … joined: late`; `room kind: community`
(`topology.py:295` refuses any kind but `pair`/`self`); `cannot_read`.
`member`/`quorum_change` are named at `TOPOLOGY.md:92` and refused at
`topology.py:337-338`.

### 4.2 Moderation (CSD-065): delegation chains

What the card must SEE: the owner's usable delegation ids from `GET
/v1/admin/self → owner_delegations[]` filtered to the rung's served scope
(CSD-065 `:152-157,291`), the named-moderator verdict for a community
(`:277`), a rung the node refuses `authority_scope_absent`, and a member with
no duty who can only propose (`:301-304`, blocked on CIRISServer#665).

```yaml
topology:
  roots:      [{id: R, kind: key, holders: 1, custody: software_test, lifecycle: active}]
  canonicals: [{id: C, holds: R, serves: [infra:serve, infra:attest]}]
  nodes:
    - {id: A, dials: [C],    accepts: R, announced: true}   # the founder
    - {id: M, dials: [C, A], accepts: R, announced: true}   # the appointed moderator
    - {id: S, dials: [C, M], accepts: R, announced: true}   # the sub-delegate (depth 1)
    - {id: P, dials: [C, A], accepts: R, announced: true}   # a plain member
  persons:
    - {id: founder, owns: [A], accepts: R}
    - {id: mod,     owns: [M], accepts: R}
    - {id: sub,     owns: [S], accepts: R}
    - {id: member,  owns: [P], accepts: R}
  relations:
    - {rel: peered, between: [A, M]}
    - {rel: peered, between: [M, S]}
    - {rel: peered, between: [A, P]}
    - {rel: community, id: allotment, kind: room, founder: founder,
       consensus_protocol: founder_only, members: [founder, mod, sub, member]}
    # LACKING: delegation — a delegates_to row with scopes, depth and dimension
    - {rel: delegation, from: founder, to: mod, scopes: [moderate, throttle], depth: 1,
       dimension: "trust:confers:v1", over: allotment}
    - {rel: delegation, from: mod, to: sub, scopes: [moderate], depth: 0, over: allotment}
  actor: {person: mod, device: M}
  negatives:
    # LACKING: cannot_act — a rung the node refuses by reason id
    - {check: cannot_act, person: member, op: "admin:throttle", reason: authority_scope_absent}
    - {check: cannot_act, person: sub, op: "admin:slash", reason: authority_scope_absent}
```

**Lacking**: `delegation` (chain of `delegates_to` with `scopes`, `depth`,
`dimension`, `over`); `cannot_act(person, op, reason)`; and, for the
anyone-may-propose half, a `report(from: member, …)` relation once
CIRISServer#665 lands.

### 4.3 Accord and duty conferral (CSD-067, CSD-068, CSD-069, CSD-090)

What the card must SEE: the family card "consensus_protocol quorum:2/3"
(CSD-067 `:66`), one holder card per seat with hardware custody (`:71-73,93`),
a pending invocation "1 of 2 signatures" (CSD-090 `:147`), the adopted
`trust:confers:v1` edge in the node's own words (`:153-155`), and — for
CSD-069 — a ceremony that *mints* the root in six slots.

```yaml
topology:
  roots:
    # Everything on this row is declared and REFUSED today (topology.py:26-28, 67-69):
    # kind family, two seated holders, hardware custody.
    - {id: HA, kind: family, founders: {n: 3, human: true, node_bearing: true}, quorum: 2,
       custody: hardware, lifecycle: active}
  canonicals: [{id: C, holds: HA, serves: [infra:serve, infra:attest]}]
  nodes:
    - {id: H1, dials: [C],     accepts: HA, announced: true}
    - {id: H2, dials: [C, H1], accepts: HA, announced: true}
    - {id: S,  dials: [C, H1], accepts: HA, announced: true}   # the subject's node
  persons:
    - {id: holder1, owns: [H1], accepts: HA}
    - {id: holder2, owns: [H2], accepts: HA}
    - {id: subject, owns: [S],  accepts: HA}
  relations:
    # LACKING: holder — a person seated in a family root, with custody class
    - {rel: holder, person: holder1, root: HA, seat: SEAT,  custody: fips_yubikey}
    - {rel: holder, person: holder2, root: HA, seat: VAULT, custody: fips_yubikey}
    # LACKING: duty_conferral with a state — the card renders the PARTIAL ("1 of 2")
    - {rel: duty_conferral, root: HA, subject: subject, scopes: [moderate], depth: 2,
       signers: [holder1], state: partial}
    # LACKING: invocation / drill / halt events (CSD-067's history and kill switch)
    - {rel: invocation, root: HA, kind: drill, signers: [holder1], state: pending}
  actor: {person: holder2, device: H2}
  negatives:
    - {check: cannot_act, person: subject, op: "accord:duty/propose", reason: accord.not_a_holder}
```

CSD-069 (the genesis ceremony) is the transition case in its purest form: the
flow *starts* with `roots: []` and its `do:` steps mint HA. The vocabulary has
no way to declare that except `pre:`/`post:` (or `roots[].minted_by: actor`).
**Lacking**: `holder`, `duty_conferral … state`, `invocation`/`drill`/`halt`,
`cannot_act`, a transition form; and — a mint gap, not a vocabulary gap —
`kind: family`, `custody: hardware`, `quorum: 2` are refused until the
ceremony can produce them (`TOPOLOGY.md:52-54`).

### 4.4 Trust-root cards (CSD-105)

What the card must SEE, per root: accepted, `trust_root_valid`, the charter,
"2 of 2 distinct holders (roster 3)" (CSD-105 `:80-82`), one row per seated
holder with Layer A / Layer B verdicts (`:88-89`), drill, halt, bounded, and
(proposed, no route) the witnessed head "3 of 3 witnesses" (`:130-132`). Then
the two acts: adopt a seed, un-trust.

```yaml
topology:
  roots:
    - {id: HA, kind: family, founders: {n: 3, human: true, node_bearing: true}, quorum: 2,
       witnesses: {n: 3, k: 2, independent_custody: true}, custody: hardware, lifecycle: active}
    - {id: R2, kind: key, holders: 1, custody: software_test, lifecycle: stalled}   # T7: attached stay, new refused
  canonicals: [{id: C, holds: HA, serves: [infra:serve, infra:attest]}]
  nodes:
    # LACKING: accepts as a LIST — the node lists humanity-accord first and its own second
    - {id: A, dials: [C], accepts: [HA, R2], announced: true}
  persons:
    - {id: me, owns: [A], accepts: [HA, R2]}
  relations:
    - {rel: holder, person: h1, root: HA, seat: SEAT, custody: fips_yubikey}   # ×3 seated, 2 checked
    - {rel: invocation, root: HA, kind: drill, state: completed}
  actor: {person: me, device: A}
  post:                                                       # LACKING: the flow's own writes
    - {rel: accepts, person: me, root: R3, via: "POST /v1/trust-root/import"}
    - {check: not_accepts, person: me, root: R2, via: "DELETE /v1/trust-root/{id}"}
```

Every field on the `roots` rows exists in the vocabulary (`TOPOLOGY.md:39-48`)
and none but the first line of §2.1's "buildable today" can be minted. What is
genuinely **lacking**: list-valued `accepts` on nodes and persons (rule 2 and
`topology.py:83-84` compare a scalar); `lifecycle: stalled|halted` as a
*state the harness can put a root into* (T7/T8); `post:`; and any route for the
witnessed head (the CSD marks it `proposed:`).

### 4.5 Second device (CSD-093, CSD-094) — a mapping note

The server's `csd-093-second-device.yaml` is the *outcome* of our CSD-094 (a
second device claimed under the same owner), built by carrying key material
"standing in for `POST /v1/self/associate`" (`mesh.py:267-283`). Our CSD-093
(show the approval code) and CSD-094 (approve the new device) drive the
`claim-remote` ceremony itself (CSD-094 `:115`). Their topology starts with an
**unclaimed** node — `nodes[].claimed: false`, owned by nobody in `pre:` and by
`one` in `post:` — which the vocabulary cannot say (`persons[].owns` is the
claim: `TOPOLOGY.md:74-76`). The server's file should be renamed to what it
proves (our CSD-037/CSD-007's self-room file, and CSD-094's post-state) so the
numbering does not collide.

## 5. Traceability: what a red should carry

**Today** a red reads (run_flows.py `:352`; `StepResult` `flow_spec.py:517-527`):

```
[    fail    ] csd_006_receipt (CSD-006): step 'the_contact_row_carries_a_hamburger' (do): 'contacts_row_ciris-gate-peer-…' never appeared
```

Flow, CSD, step, phase, tag. Not: which *layer* produced it (the fixture's
note — "the room never keyed on the peer within 150s" — was printed forty lines
earlier at `run_flows.py:310-311` and is not in the outcome), which CC clause
the assertion stands on, or which `shows:` row (ceg) the tag draws. The
builder's step records already carry `layer`, `proves`, `means` and `evidence`
(`scenarios.py:22-45`; `topology.py:147-150`); none of it reaches our report.

**Proposed report line** (console; the JSON carries the same fields):

```
[fail] CSD-091 csd_091_user_chat › a_room_with_history (expect) layer=relations/message cc=CC 5.4.6 ceg=chat:message:v1 tag=chat_msg_${MESSAGE_ATTESTATION_ID} — the row may be here but its BODY did not open on the recipient; evidence: B: "blob_swarm::pull … outcome=FetchFailed" (harness step body_NOT_arrived, t=41.2s, report: …/report.json)
[cannot-start] CSD-091 csd_091_user_chat layer=relations/reachable cc=CC 3.3.7 — bob's owner→node binding is not held on A at federation scope (CIRISServer#699); evidence: A: "handshake cannot complete …"
[fail] CSD-005 csd_005_people › on_people (do) layer=client_ui cc=CC 2.1 ceg=consent:replication:v1 tag=contacts_list — 'contacts_list' never appeared; on screen: [card_contacts_add, …]
```

`[<verdict>] <CSD> <flow> › <step> (<phase>) layer=<layer>[/<rel>] cc=<clause[,…]>
ceg=<field> tag=<tag> — <means>; evidence: <node>: <line>`. A red without a
`layer=` is a bug in the reporter, not a permitted shape.

**What `flow_spec`/the CSD must carry for it:**

* **`cc:` on steps** — `_STEP_KEYS += "cc"` (`flow_spec.py:52`), a list of
  clause ids validated as `^CC \d+(\.\d+)+$` (the form every CSD already uses:
  `CC 3.4.5` ×52, `CC 3.1.9` ×36, `CC 3.2` ×34 across `FSD/CSD/*.md`). Default
  when absent: the union of the `cc:` of the `shows:` rows whose tags the
  step's `expect:` names (reverse of `field_tags`, `csd_doc.py:68`), so the
  ceg→clause map has one source.
* **`cc:` on `shows:` rows** — a new optional key in CSD/4 §2.1.1, checked by
  `check_csd_v3` for format; required at `testable` on every row a flow
  asserts.
* **`layer:` on every `checks:` row** (§1.3), one of `LAYERS`
  (`topology.py:30`) plus `client_ui`, with `rel:` for relations; the fixture
  maps the builder's failing step name (`NOT_reachable:…`, `room_NOT_keyed`,
  `body_NOT_arrived`, `file_NOT_open:…`, `topology.py:212,236,278,290,308,330`)
  to the row by `layer`+`rel`.
* **`StepResult` gains `layer`, `cc`, `ceg`, `evidence`; `FlowOutcome` gains
  `layer`, `fixture_report`** (`flow_spec.py:517-527`, `run_flows.py:68-75`);
  `write_report` (`flow_spec.py:847-870`) and the gallery emit them. A
  `cannot-start` from a fixture carries the builder's `means` verbatim.
* **From the builder**: `step.fail(...)` should carry `layer`, `rel` and `cc`
  as fields (it cites CC 5.4.6 in prose at `topology.py:99-100`,
  `mesh.py:433-436`), and the `VERDICT` JSON (`__main__.py:68`) a
  `first_failing_layer`.

## 6. What the client needs from the server

1. **A `test-anchor` `ciris-server` per host target with each release**
   (`x86_64-unknown-linux-gnu`, `aarch64-apple-darwin`,
   `x86_64-pc-windows-msvc`), or the runtime knob #698 asks for. Without it
   the `roots` layer is unrealizable on every leg and `room … keyed: true`
   cannot be declared for the matrix.
2. **`harness.native` installable outside the repo.** `anchor_env()` reads
   `harness/mesh-repro/docker-compose.yml` by relative path (`mesh.py:47-48,
   55-74`); accept the anchor env from the environment or an `--anchor-env`
   file, and ship a `pyproject` (or a subtree we vendor under a digest, as
   `client/` is).
3. **`build --attach <id>=<url>,<token>[,log=<path>|log-cmd=<cmd>]`** — a
   `Node` with no process, no claim, `owner_key_id` from the announce bundle,
   `dials` marked fixed, `rooted_with` evidence "no log" rather than `None`.
4. **Windows**: `CREATE_NEW_PROCESS_GROUP` + `taskkill /T`, `.exe`
   resolution, `--work` default under the runner temp (`mesh.py:181,195,
   400-402,459`; `__main__.py:27`).
5. **`values.json` keyed by declared ids** (`PERSON_BOB_OWNER_KEY_ID`,
   `NODE_B_URL`, `ROOM_PAIR_ID`, `MESSAGE_1_ATTESTATION_ID`) with the
   actor-view aliases kept, and a `notes`/`means` entry for every value a
   declared relation should have produced and did not.
6. **`build --topology FSD/CSD/CSD-091-user-chat.md`** reads the fenced
   `csd:topology` block, so the CSD is the one source and
   `harness/native/topologies/*.yaml` are either generated or checked
   byte-identical.
7. **Vocabulary** (§4): `community`/`household`, `quorum_change … state:
   held|applied`, `member … joined: late`, `room kind: community`,
   `delegation`, `holder`, `duty_conferral … state`, `invocation|drill|halt`,
   `nodes[].claimed: false`, list-valued `accepts`, `pre:`/`post:` (or
   `minted_by: actor`), negatives `cannot_act`, `cannot_read`, `not_accepts`;
   and an `agents:` layer agreed with CIRISAgent.
8. **Rule 4 for N-member rooms**: say whether an N-room needs an N-clique of
   `dials` or a relay-routable scoped address is planned.
9. **`step.fail` carries `layer`/`rel`/`cc` as fields; the VERDICT names
   `first_failing_layer`.**
10. **#699** (refuse-by-name or re-grant on binding arrival) and **#698** (the
    released line): until #698 closes, every matrix `room` is `keyed: false`
    and every `message` unrealizable on the binary users run.

## 7. What the client would change

* `testing/gate/server_harness.py` (new) — `ServerHarnessFixture(binary, work,
  topology, attach)`: `up()` runs `python -m harness.native build --topology …
  --binary … --work … [--attach …]`, reads `values.json` + `report.json`,
  returns vars; `values.notes` = the failing step's `means` + evidence;
  `down()` = `harness.native down --work`. Same contract `run_all` holds a
  fixture to (`run_flows.py:270-274,297-322,340-349`).
* `testing/gate/flow_spec.py` — `FIXTURES += {"server_harness"}` (`:69`);
  `_FLOW_KEYS += {"topology"}` (`:64`); `_STEP_KEYS += {"cc"}` (`:52`);
  `StepResult` += `layer, cc, ceg, evidence` (`:517-527`); `write_report`
  (`:847-870`).
* `testing/gate/run_flows.py` — `fixture_factory` builds by name (`:370-386`);
  `--harness-binary` (the test-anchor one) beside `--node-binary`; `FlowOutcome`
  += `layer, fixture_report` (`:68-75`); the report line of §5 (`:352`).
* `testing/gate/csd_doc.py` — parse `csd:topology` (`checks:`, `exports:`) and
  `cc:` on `shows:` rows.
* `packaging/check_csd_v3.py` — `csd:topology` required at `testable`
  (`:42-49`); the coverage check of §2 against
  `testing/gate/topologies/{bare,two_node}.yaml`; `cc:` format; negative test
  in `testing/test_csd_topology.py` (planted `keyed: true`).
* `packaging/check_csd_topology_sync.py` (new, until ask 6 lands) — the CSD
  block vs the server's `harness/native/topologies/` file, byte-identical, the
  way `check_localization_sync.py --server-src` works (`gates.sh:57-58`).
* `testing/gate/two_node.py` — stays the released-line fixture, declared
  honestly in `topologies/two_node.yaml`; gains the `reachable` gate and the
  post-once rule from `topology.py:219-253` (#699); retires into
  `server_harness` when ask 1 ships.
* `.github/workflows/five-platform-live-qa.yml` — download the test-anchor
  asset per leg beside the released one; `harness.native down --work` under
  `always()` (as `:350-354,774-778,839-842` do for `two_node`); the Android
  "seeded?" step (`:258-284`) becomes "own mesh per leg"; Windows work dir.
* `CSD.md` — a §7 for CSD/4: `csd:topology`, `checks:`, `cc:`, the coverage
  rule; the README table and `MISSION.md` §3 in the same commit (AGENTS.md).
* `FSD/CSD/CSD-091-user-chat.md` — the first `csd:topology` block (the body of
  the server's `csd-091-user-chat.yaml`, with `A: {accepts: none}` for the
  matrix variant); `testing/flows/drafts/csd-091-user-chat.yaml` →
  `fixture: server_harness`, `topology: CSD-091`, `cc:` on
  `a_room_with_history` (`CC 5.4.6`).
* `evidence/blocked_upstream.tsv` — one row per ask in §6 with a scannable
  predicate (e.g. `harness/native/mesh.py` contains `CREATE_NEW_PROCESS_GROUP`;
  the release's asset list contains `test-anchor`).
