# Staged CSD flows — complete, load-clean, and waiting on one thing

Every file here is a finished flow for a CSD at `building`. Each one:

- names its CSD with `csd: CSD-NNN` and loads clean against the binder in
  `testing/gate/flow_spec.py` — no `proposed:` tag in any `requires`, `expect` or
  `do`, and no `state:` the CSD gives a proposed tag;
- carries `client: ">=0.5.224"`, because every literal tag in it was grepped out
  of the `v0.5.224` tree rather than assumed;
- follows its CSD's §4, with `requires` stated rather than assumed.

**They are here and not one directory up for exactly one reason: the runner does
not navigate.** `testing/flows/README.md` says so plainly — *"Every flow today
starts where sign-in lands (`Contacts`). A flow for another surface needs
`nav_map` to drive the hop."* — and `cannot-start` is **red on purpose**, so that
a flow which never reached its first screen cannot be mistaken for one that
passed.

Sign-in lands on `Contacts`. Every flow here starts somewhere else. Putting them
in `testing/flows/` would turn all five matrix legs red for a reason that has
nothing to do with the client.

`flow_spec.discover` globs `p.glob("*.yaml")` — **not** `rglob` — so this
subdirectory is staged and never run. That is the whole mechanism.

## Promoting one

When the runner can walk a hop, a flow here is promoted by `git mv` and nothing
else. The hop itself is never written into the flow: the CSD names the surface
and `testing/gate/nav_map.py` derives the chain (`CSD.md` §2.0).

Check first, in this order:

1. `python3 -m testing.gate.run_flows --flows testing/flows/drafts/<file>` loads it;
2. its CSD's §5 declares the platforms it should be green on;
3. the CSD's `stage:` moves to `testable` only after a green run — a green run is
   evidence for that edit, never the edit itself.

## What is here

| file | CSD | screen it needs | beyond nav, what else it waits on |
|---|---|---|---|
| `csd-006-receipt.yaml` | CSD-006 | Contacts + a contact | a second node to be a contact of; the matrix stands up one |
| `csd-025-system.yaml` | CSD-025 | System | nothing |
| `csd-036-network-ops.yaml` | CSD-036 | NetworkOps | nothing |
| `csd-057-wallet.yaml` | CSD-057 | Wallet | nothing |
| `csd-066-child-safety.yaml` | CSD-066 | ChildSafety | nothing |
| `csd-068-provision-accord-holder.yaml` | CSD-068 | ProvisionAccordHolder | nothing |
| `csd-069-accord-ceremony.yaml` | CSD-069 | AccordCeremony | its last step needs six FIPS tokens; it is `optional_step` |
| `csd-081-login.yaml` | CSD-081 | Login | the observer step needs a second, non-owner account |
| `csd-082-setup-with-ai.yaml` | CSD-082 | Setup | a node with no owner — the fixture claims one during sign-in |
| `csd-083-setup-without-ai.yaml` | CSD-083 | Setup | same |
| `csd-085-claim-node.yaml` | CSD-085 | ClaimNode | the no-signer step needs the local node stopped mid-flow |
| `csd-087-verify-agent.yaml` | CSD-087 | VerifyAgent | nothing — the refusal is the only state any node can produce |
| `csd-090-duty-conferral.yaml` | CSD-090 | DutyConferral | a node that knows an accord family |
| `csd-091-user-chat.yaml` | CSD-091 | UserChat | a peered contact to have a room with |

Six of the fourteen need **only** navigation. They are the ones to promote first.
