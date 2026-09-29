# CSD flows

A CSD's §4 says what its surface must do. A flow here is that §4 made
executable, and the five-platform gate (`five-platform-live-qa.yml`) runs every
flow in this directory on every leg — Linux, macOS and Windows desktops, the
Android emulator, the iOS simulator — against a real `ciris-server`.

That is what `testable` means in `CSD.md` §1: *"floor flips off unreleased; flow
runs on the matrix"*.

## The file

```yaml
flow: people                  # the flow's id; unique across this directory
csd: CSD-005                  # the CSD it tests — REQUIRED here
title: People on a node with no contacts yet
description: >-               # optional; say what is NOT driven and why
  …
client: ">=0.5.224"           # the client that carries every tag it names
cleanup:                      # optional; run AFTER the flow, pass or fail
  - click: btn_contact_code_close

steps:
  - step_id: landing
    title: Signing in on a bare node lands on People
    requires:                 # checked BEFORE the step; the first step's is the entry
      screen: Contacts
    do:                       # click / input / scroll_to / wait (one per entry)
      - wait: card_contacts_add
        wait_ms: 5000         # a `wait` waits wait_ms × 4
    expect:                   # checked AFTER
      visible: [card_contacts_add]
      absent: [contacts_empty]

  - step_id: search_no_match
    title: A search nothing matches is the empty state
    do:
      - input: {input_contacts_search: "zz-no-such-contact"}
    expect:
      state: empty            # read from the CSD's `states:` block
```

The language is `testing/gate/flow_spec.py`'s — vendored from CIRISAgent, with
the CSD/3 §3 predicates (`count`, `number`, `matches`, `one_of`, `each`,
`relation`, `state`) and one local addition, `csd:` (see
`testing/gate/VENDORED.md`). Unknown keys anywhere are a load error.

### How a flow is tied to its CSD

`csd: CSD-NNN` resolves to the one `FSD/CSD/CSD-NNN-*.md`, and the runner reads
two of its typed blocks (`testing/gate/csd_doc.py`):

- **`csd:shows`** → field id → tag. A `relation:` names `ceg:` field ids
  (`capacity:composite`), not tags, and this is how they resolve.
- **`csd:states`** → state → tag. `state: empty` holds when the `empty` tag is on
  screen **and no other state's tag is** — so an error cannot pass for "nothing
  here".

Each of these is a **load error**, found before any app is started:

- the CSD does not exist, is ambiguous, or a typed block does not parse;
- the flow names a tag the CSD still marks `proposed:` — in `requires`,
  `expect` or `do`. No client carries it, and it would fail as "element not
  found", which looks exactly like a broken app;
- `state: X` where the CSD gives X no tag, or a proposed one;
- a `relation:` operand that is not one of the CSD's `shows:` fields.

`testing/test_flows.py` also checks that every literal tag a flow here names is
a string in the client's `commonMain` source.

## What is here

Every file in this directory runs on every leg. Promoted from
`testing/flows/drafts/` on the 0.5.225 run (2026-09-29); what still waits
there, and why, is in `drafts/README.md`.

| file | CSD | first screen | fixture | floor |
|---|---|---|---|---|
| `people.yaml` | CSD-005 | Contacts (bare node: the add card instead of an empty block) | — | `>=0.5.224` |
| `csd-005-people.yaml` | CSD-005 | Contacts (a seeded contact: row, chip, hamburger, receipt) | `two_node` | `>=0.5.225` |
| `csd-006-receipt.yaml` | CSD-006 | Contacts (the five facts, off the wire) | `two_node` | `>=0.5.225` |
| `csd-008-notes-to-self.yaml` | CSD-008 | Notes | — | `>=0.5.225` |
| `csd-047-network-content.yaml` | CSD-047 | LayerGlobalCommons → `tile_federation_content` | `two_node` | `>=0.5.225` |
| `csd-057-wallet.yaml` | CSD-057 | Wallet (read-only; never presses send) | — | `>=0.5.224` |
| `csd-068-provision-accord-holder.yaml` | CSD-068 | ProvisionAccordHolder (the no-token refusal) | — | `>=0.5.224` |
| `csd-092-share-contact-code.yaml` | CSD-092 | Contacts → the contact-code card | — | `>=0.5.225` |
| `csd-101-household-members.yaml` | CSD-101 | HouseholdMembers (the bare node's empty shape; the roster is gated) | — | `>=0.5.225` |

A flow's floor is the client that carries every tag it names — checked at the
keyboard by `testing/test_flows.py`. `csd-005` and `csd-006` were run locally on
the Linux desktop leg before promotion (their CSDs' §5 say what passed); the
other six have not run anywhere yet, which is what their CSDs' `stage:`
(`building`) says.

## Verdicts

| verdict | when | leg |
|---|---|---|
| `pass` | every step held | green |
| `refused` | the `client:` floor is above the client under test (`>=X`, `>X`, `unreleased`) | green — reported, not passed |
| `cannot-start` | floor met, but the flow never reached its first screen (no hop, a hop tag missing, or the first `requires` never held) | **red** |
| `fail` | it started and a step broke | **red** |

`cannot-start` is red on purpose. The floor is how a flow waits for a surface
that has not shipped; once the floor is met, a flow that never reached its first
screen is a flow that silently never ran.

Each leg's report (`reports/<leg>.json`) carries a `flows` list with every
outcome and its step-level detail; screenshots and per-flow JSON land under
`shots/flows-<leg>/`. The client version the floor is checked against is this
tree's `VERSION` — on the matrix the artifact is asserted to be this tree's.

## From `building` to `testable`

1. Every tag the flow needs is real: no `proposed:` left on the rows it drives,
   and the PR that adds them has shipped.
2. Write `testing/flows/<surface>.yaml` with `csd:` pointing at the CSD and
   `client: ">=<the release that carries it>"`. Until a release carries it, use
   `client: unreleased` — the flow loads, is checked, and is refused on the
   matrix instead of failing.
3. Run it locally (below), then let the nightly matrix run it.
4. When it is **green on the platforms the CSD's §5 declares**, the pen-holder
   moves `stage:` to `testable`. Nothing advances the stage automatically — a
   green run is evidence for the edit, not the edit.

## Running one flow locally, against a desktop client

The runner drives whatever client answers on the test-automation port. Keep it
off your own install: the node takes `--home`, the client reads `CIRIS_HOME`
(`testing/gate/session_fixture.py` explains why they differ).

```bash
# 1. a throwaway node
python3 -m testing.gate.node_fixture --version v0.5.224 --home /tmp/flows-node \
    --platform x86_64-unknown-linux-gnu        # or aarch64-apple-darwin

# 2. the desktop client in test mode, with its own home
( cd client && ./gradlew :desktopApp:packageUberJarForCurrentOS )
CIRIS_TEST_MODE=true CIRIS_HOME=/tmp/flows-client \
    java -jar "$(python3 -m testing.gate.candidate_artifacts --kind desktop | tail -1)" &

# 3. the flow — it signs in (running first-run setup if the node has no owner)
python3 -m testing.gate.run_flows --platform desktop \
    --flows testing/flows/people.yaml --report /tmp/flows.json
```

`--client-version` checks floors against another version (default: `VERSION`);
`--no-sign-in` drives whatever screen the client is already on; `--url` points at
a test server other than `http://127.0.0.1:9091`. Exit status is 0 only when
every flow passed or was refused.

On the matrix the same code runs inside `run_platform` (`--flows testing/flows`)
after the smoke walk, in the app the walk just brought up, so there is one
bring-up per leg and one session.

## What this does not do yet

- **Navigation is to the first screen only.** Before step one the runner walks
  the hop `testing/gate/nav_map.py` derives for the flow's first
  `requires: screen:` on this build (node or agent tree, from `/state`),
  waiting for each tag before clicking it. A missing hop tag is `cannot-start`
  naming the tag and listing what was on screen; a screen with no hop that is
  not flow-only is `cannot-start` with "no nav hop for Screen.X"; a flow-only
  screen (pre-login, wizards, leaves) is waited for, not walked to. Hops
  between later steps are the flow's own `do:` clicks.
- **The hop is always walked, and every circle and tab hop is verified.** Being
  on the screen already says nothing about which circle it is shown in
  (Contacts sits in every circle's People tab), and the last flow left the
  shell wherever it left it, so the runner re-selects the hop's circle and tab
  every time. After each `circle_*` / `tab_*` click it reads `/state` (`circle`,
  `tab`, from client 0.5.226) until the shell says it stands there; a click
  that succeeded is not a hop that took — `CIRISApp.openTab` runs with the
  circle the last composition captured, and a tab clicked in the same frame
  as the circle opens the OLD circle's tab. That race cost the 2026-09-29 run
  four flows on every desktop leg, each reported as a row that "never
  appeared". On a client without those `/state` fields the hop is walked
  unverified.
- **A flow closes what it opened, whatever its verdict.** `cleanup:` is a list
  of actions run after the flow — pass, fail or crash. A flow stops at its
  first failed step, and a card that step left open (the contact-code card
  replaces People's body and its open state lives in the view model) is the
  next flow's failure. A cleanup that fails is reported in the outcome's
  detail and the per-flow JSON; it never changes the verdict.
- **A second node only when a flow asks.** The matrix stands up one node with
  no contacts, no agent and no peers. A flow that needs more says
  `fixture: two_node` — see below. Three flows here ask for it
  (`csd-005-people`, `csd-006-receipt`, `csd-047-network-content`); the rest
  run on the bare node.
- **No cross-node message on released nodes.** The two-node fixture seeds a
  contact each way and opens the room, but between two fresh, unconferred
  nodes of the released line (0.5.217) the room never keys, so no message
  crosses; see "Two-node flows".

## Two-node flows: `fixture: two_node` and `${NAME}`

```yaml
fixture: two_node
steps:
  - step_id: open_the_receipt
    title: The seeded contact's receipt opens
    do:
      - click: "btn_receipt_${PEER_KEY_ID}"     # QUOTED: `${` is YAML flow syntax
    expect:
      visible: ["contacts_row_${PEER_KEY_ID}", sheet_receipt]
```

`testing/gate/two_node.py` is CIRISServer's `harness/mesh-repro/scenarios/chat.sh`
without Docker: a second `ciris-server` from the binary the leg downloaded, run
natively on 5242/5243 with its own `--home` and a unique `--key-id`, claimed on
its console, announced, peered both ways with the leg's node (which the client
claimed; the fixture signs in to it as `qaadmin`), each owner added as the
other's contact **and waited for until the other is reachable from it**, the
pair room opened on both sides, and one message sent by the peer once the room
is keyed. It runs on every leg because it runs on the leg's HOST — the client
only ever talks to its own node.

The reachability wait is CIRISServer `FSD/TOPOLOGY.md` §2.5's `reachable(A, q)`
relation: `POST /v1/contacts` on A for q reporting `reachable_nodes >= 1`,
which means q's owner→node BINDING is held on A at federation scope — a later
fact than q's owner KEY being known, which is all the fixture used to wait for.
A contact added while it is 0 keys a pair room whose bodies read `not_granted`
for good (CIRISServer#699); the 2026-09-29 run logged `reachable_nodes=0` on
both sides and then `awaiting_peer` for the whole wait. The POST is the
predicate (no read route answers it), so the fixture re-asks it every 5 s, up
to `reachable_wait` (120 s), and writes what it waited on and for how long into
`values.notes`.

The values a flow may name:

| name | what |
|---|---|
| `PEER_KEY_ID` | the key the leg's node holds the contact under (the peer's owner; the peer NODE if the owner key never crossed) |
| `PEER_NODE_KEY_ID` / `PEER_OWNER_KEY_ID` | the peer's node and owner fed-IDs |
| `PEER_CONTACT_CODE` | the peer's contact code — only from a node that serves `GET /v1/self/contact-code` (0.5.218+) |
| `LOCAL_NODE_KEY_ID` / `LOCAL_OWNER_KEY_ID` | the leg's node and its owner |
| `ROOM_ID` | the pair room's community id |
| `MESSAGE_ATTESTATION_ID` / `MESSAGE_TEXT` | the peer's message — only if it ARRIVED on the leg's node |
| `PEER_URL` | the peer's read API, from the host |

The rules, each enforced by `testing/test_flow_fixtures.py`:

- A `${NAME}` in a flow with no `fixture:` is a load error.
- A value the fixture did not produce fails the step — optional or not — and
  the failure quotes the fixture's own note for why. A message that did not
  cross is not handed over, so a local-only row cannot pass for a conversation.
- Flows with no fixture run first, whatever the file order: the fixture changes
  the leg's node, and `people.yaml` asserts the bare one.
- The fixture stands up once per leg, only before the first runnable flow that
  asks for it, and is torn down in a `finally`; the workflow also runs
  `python3 -m testing.gate.two_node down --work <dir>` under `always()`.
- `run_platform` / `run_flows` need `--node-binary` (the workflow passes the
  leg's `node/ciris-server`); without it a fixture flow is `cannot-start`.

Locally, against the throwaway node above:

```bash
python3 -m testing.gate.run_flows --platform desktop \
    --flows testing/flows/csd-006-receipt.yaml --client-version 0.5.225 \
    --node-binary /tmp/node/ciris-server \
    --node-url http://127.0.0.1:4243 --peer-work /tmp/flows-peer
```

**What it cannot do on the released line.** Measured against v0.5.217 on
2026-09-28: peering, the announce, the owner keys and the contacts all cross,
but the room never keys — the two nodes admit each other `ADVISORY — not
conferred`, frames fail the "SignedTransportDestination binds this (peer,
dest)" check, and the joiner's KeyPackage never replicates, in 480 s of
waiting. CIRISServer's own chat ladder is green only on a `test-anchor` build
with a test trust root, which a leg's released binary is not. A flow naming
`${MESSAGE_ATTESTATION_ID}` therefore fails on the matrix today, saying so.
