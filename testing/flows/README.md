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

## Verdicts

| verdict | when | leg |
|---|---|---|
| `pass` | every step held | green |
| `refused` | the `client:` floor is above the client under test (`>=X`, `>X`, `unreleased`) | green — reported, not passed |
| `cannot-start` | floor met, but the first step's `requires` never held | **red** |
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

- **No navigation.** A flow's first step names its screen and the runner waits
  for it; it does not walk the sidebar there. Every flow today starts where
  sign-in lands (`Contacts`). A flow for another surface needs `nav_map` to drive
  the hop — the CSD names the surface, and the hop is derived, never written.
- **Only what a bare node can show.** The matrix stands up one node with no
  contacts, no agent and no peers, so CSD-005's populated list and receipt sheet
  are not driven here.
