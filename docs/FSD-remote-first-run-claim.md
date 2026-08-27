# FSD — Remote first-run claim

**Status:** draft for review
**Author:** CIRISClient
**Covers:** claiming a remote CIRIS node from this client — both the
*configured-but-unclaimed* case and the *configure-and-claim* case.

---

## 1. The gap

There are CIRIS nodes running as research agents that are **fully configured and
unclaimed**. A node in that state works, serves its API, and has no responsible
party bound to it. Today an operator's route to claiming one from this client is
narrow and undiscovered, and for some client deployments it does not exist.

Two cases, which are not the same problem:

| | node state | what the operator needs |
|---|---|---|
| **A** | configured, unclaimed | claim it: bind an owner |
| **B** | bare, unconfigured | run first-run config against it, *then* claim |

Case A is the one in front of us. Case B is the more ambitious one, and §3
establishes that it is **blocked server-side today** — it is not a client
omission, and no amount of client work reaches it.

---

## 2. What exists today

**`ClaimNodeScreen`** (`ui/screens/ClaimNodeScreen.kt`) already takes a NodeCode,
a claim PIN and a display name, and drives `claimRemote`. It is reached from
`ManageNodesScreen` → "claim ownership" (`CIRISApp.kt:3583`).

**`CIRISApiClient.claimRemote`** (`api/CIRISApiClient.kt:2264`) POSTs
`{node_code, claim_pin, cohort_scope}` to **the local node's**
`/v1/setup/claim-remote`. The app performs no crypto: the local node decodes the
NodeCode, builds and hybrid-signs the owner-binding
`delegates_to(user → target, infra:*)` in its own substrate, and POSTs the signed
artifact to **the target's** `/v1/setup/root`.

**The first-run wizard** self-claims by the same path with the target set to
itself (`SetupViewModel.claimLocalNodeOwnership`, `viewmodels/SetupViewModel.kt:1052`),
taking the PIN from `claimPinProvider` — which reads the local node's
`<home>/claim_pin` file. That provider returns null for any node this device did
not start, and the wizard then surfaces "claim PIN not captured … you can claim
ownership later from the Network surface".

So the machinery for a remote claim exists. What is missing is above it.

---

## 3. The contract that constrains the design

Verified against CIRISServer 0.5.190/0.5.191 source and the released
`x86_64-unknown-linux-gnu` binary.

### 3.1 Only ONE setup route is reachable off-host

`src/auth/bootstrap.rs` builds two routers and merges them —
`claim.merge(loopback_reads)`, where `loopback_reads` carries
`require_loopback`:

| route | reachable remotely? |
|---|---|
| `POST /v1/setup/root` | **yes** |
| `GET /v1/setup/status` | no — loopback only |
| `GET /v1/setup/owned-nodes` | no — loopback only |
| `GET /v1/setup/consent-disclosure` | no — loopback only |

A non-loopback caller gets `403 "setup routes are localhost-only (run the wizard
on the node's own host)"` (`src/auth/loopback.rs:44`).

**Measured, not inferred.** Released `ciris-server v0.5.190`, binding `0.0.0.0`,
queried over loopback and over the host's own LAN address (192.168.50.8):

| route | via `127.0.0.1` | via LAN address |
|---|---|---|
| `GET /v1/setup/status` | 200 | **403** |
| `GET /v1/setup/owned-nodes` | 200 | **403** |
| `GET /v1/system/health` | 200 | 200 |
| `GET /v1/setup/root` | 405 | 405 |

The last row is the useful one: `/v1/setup/root` answers **405 Method Not Allowed
from the LAN**, not 403. A loopback-layered route rejects before it ever
considers the method, so a 405 off-host is positive proof that the claim route
sits outside the guard — the claim is reachable remotely, and only the reads are
not.

This is a deliberate trust boundary, not an oversight: `/v1/setup/root` can be
open because it authenticates on its own terms — the one-time claim PIN plus a
signed owner-binding — while the reads would otherwise leak a node's setup and
ownership posture to anyone who can reach it.

### 3.2 Consequences, stated plainly

1. **This client cannot ask a remote node whether it is set up, or whether it has
   an owner.** `getSetupStatus()` and the `owned-nodes` probe behind
   `nodeHasOwner()` both 403 off-host. Anything the UI wants to say about a
   remote node's claim state must come from the operator or from a route outside
   `/v1/setup/*`.

2. **Case B is blocked.** The first-run wizard reads `/v1/setup/status` to know
   there is a first run at all, and `/v1/setup/consent-disclosure` to render what
   joining grants in the substrate's own words. Both are loopback-only, so the
   wizard cannot run against a remote node. Closing Case B requires a CIRISServer
   change — see §7.

3. **Claiming runs through the local node, and every shipped platform has one.**
   `/v1/setup/claim-remote` is where the signing happens, and it is loopback-only
   *and* first-run-gated (`src/claim_remote.rs:391` — owner-gated once owned,
   open during first-run, "loopback-only via the setup-route guard"). The app is
   deliberately not allowed to do crypto itself, so the claim needs a substrate
   holding the operator's identity.

   That substrate is always present on the platforms we ship to the stores:
   desktop, **Android and iOS all run a local node** (`PythonRuntime.{desktop,
   android,ios}.kt`). The precondition is therefore not "do you have a node" but
   "is your node up and are you signed in to it" — a state the client can check
   before asking for anything.

   The one exception is the **wasm/web** build, which has no local runtime
   (`PythonRuntime.wasmJs.kt` — "Web mode - connecting to remote server"). Web is
   remote-only and cannot claim; it must say so rather than offer the flow.

### 3.3 The claim body

`POST /v1/setup/root` takes `{node_code, cohort_scope, claim_pin, owner_binding}`
where `owner_binding` is the user-signed `delegates_to`
(`CIRISServer tests/ownership.rs:376`). `201` on success, echoing
`identity_key_id`, `cohort_scope`, `role: SYSTEM_ADMIN` and
`owner_binding_attestation_id`.

---

## 4. Design — Case A: claim a configured, unclaimed remote node

**Shape:** operator supplies the target's NodeCode and its one-time claim PIN
(read from that node's console — the PIN never travels over HTTP by design); the
**local** node signs and delivers the claim.

```
  operator ──NodeCode + PIN──▶ client
                                 │  POST /v1/setup/claim-remote   (loopback)
                                 ▼
                            local node ──signs owner-binding──┐
                                                              │ POST /v1/setup/root
                                                              ▼
                                                         target node
```

### 4.1 What to build

**A1 — An entry point that matches the task.** Claiming several research agents
in a sitting is the actual workload, and today the flow is one node at a time
buried under ManageNodes. `ClaimNodeScreen` already offers "claim another"
(`ClaimNodeScreen.kt:292`); the work is to make the entry point reachable and
named for the job, and to keep the entered display name with the claimed node.

**A2 — Check the precondition before asking for secrets.** The claim is signed
by the operator's own node, so it needs that node up and a live session on it.
Both are knowable *before* the operator types a NodeCode and a PIN, and the
screen should say which one is missing up front rather than collecting both and
failing at the POST. On web, where there is no local runtime at all, the flow is
not offered.

**A3 — Distinguish the PIN failures.** A wrong PIN, an already-claimed node, and
an unreachable target are three different situations with three different next
actions. `NodeSwitcherViewModel.claimRemoteNode` (`viewmodels/NodeSwitcherViewModel.kt:722`)
already inspects the body for `invalid_claim_pin`; the screen should render that
distinction rather than one generic failure.

**A4 — Do not claim to know remote setup state.** Since §3.2(1) makes it
unknowable, the UI must not imply it. No "this node is unclaimed" badge for a
remote node; the operator is the source of that fact.

### 4.2 Explicitly NOT in Case A

Auto-discovery of unclaimed nodes on a network. The client cannot probe claim
state off-host, and a scan that inferred it from other signals would be guessing
about ownership — the one thing this flow exists to establish precisely.

---

## 5. Design — Case B: configure and claim a remote node

Blocked today (§3.2(2)). Two honest options:

**B1 — Configure on the node's own host, claim from here.** No server change.
The operator runs first-run on the remote host (console/SSH — which they already
need for the PIN), and the node then falls into Case A. This is what the current
trust boundary is telling us to do, and it is the recommendation for now.

**B2 — Make remote first-run possible.** Requires CIRISServer to expose, off-host
and safely, what the wizard needs: setup status and the consent disclosure. That
is a security decision about a deliberate boundary, and it belongs to the server
team, not to this client. §7 states the ask.

---

## 6. UX — where the PIN is entered

One rule, in both cases: **the claim PIN is typed by a human who read it from the
target node's console.** It is written `0600` to `<home>/claim_pin`, served by no
route, and `/v1/setup/status` carries only its *path*, never its value
(`CIRISServer tests/claim_pin_file_is_declared.rs`).

The local first-run wizard auto-fills it because the client is on the same host
and can read the file. That convenience does not generalise, and the code should
stop implying it does: `claimPinProvider` returning null is the normal case for
any node this device did not start, not an error condition.

---

## 7. Server dependency (Case B only)

For CIRISServer, if remote first-run is wanted:

> The first-run wizard needs `GET /v1/setup/status` and
> `GET /v1/setup/consent-disclosure` off-host. Both are behind
> `require_loopback` today. What would make them safe to expose — the claim PIN
> as a bearer, a short-lived setup token minted on the console, an explicit
> `--allow-remote-setup` flag, or nothing at all?

Until that is answered, Case B is B1.

---

## 8. Testing

The walk-test matrix (`testing/README.md`) grows a corner:

| corner | node | asserts |
|---|---|---|
| `remote-unclaimed` | configured, no owner | the claim flow reaches `/v1/setup/root` and binds an owner |

**A LIMITATION IN THE CURRENT HARNESS MUST BE FIXED FIRST, AND IT IS MINE.** The
existing "remote" corners put a facade on `127.0.0.1`, so the node sees a
**loopback** peer and the loopback-only routes answer normally. Those corners
therefore exercise the client's remote *configuration* path but not remote
*reachability* — a genuinely off-host client gets 403s that the harness never
sees. Testing any of §3 honestly requires the node to observe a non-loopback
source address (bind a second interface, or reach the host by its LAN address).
Until that is done, no walk-test result should be read as evidence about remote
behaviour.

---

## 9. Summary of decisions

1. Case A is buildable now and is the priority; the signing path already exists.
2. Case B is server-blocked; recommend B1 and raise §7 with the server team.
3. The client must never present remote setup/claim state it cannot observe.
   Desktop, Android and iOS all carry a local node, so a signer always exists;
   web is remote-only and cannot claim.
4. The claim PIN stays human-entered off-host. That is the design, not a gap.
5. The walk-test harness must see a non-loopback peer before it can test any of
   this.
