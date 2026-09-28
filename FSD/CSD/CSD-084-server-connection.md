# CSD-084 — Server connection (which node this client is talking to)

**CSD**: CSD-084 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 · **Reads against**: `FSD/ONE_CLIENT_N_NODES.md`
**Flow**: unwritten — and today unwritable; see §5

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**One client, N nodes: this screen is where a person says which one, and it must
never let "I cannot reach it" look like "I am still trying".** Serves
**CC 3.2** — a node is an owned key with one responsible party, so "which node"
is a question about *whose* substrate is answering, not a preference. It is
reached from the Login status chip (CSD-081, `btn_server_status`) and from the
setup wizard's node-switch path, which makes it the one first-run surface a
person hits when the default backend is the wrong one.

Until the setup review this card's tags could not be driven: four of eight
were not drivable and its one text field had no input sink. §5 records what
changed; every value the screen shows now carries a tag a flow can read.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: ServerConnection
flow_only: true
entry: "Login's status chip (`btn_server_status` → `onServerSettings`, CIRISApp.kt:2324); or Setup's node-switch path (CIRISApp.kt:2949, 2967)"
exit: "Login when there is no session (the chip that opened it); otherwise homeTarget — Contacts on a node-only install, Interact with an agent (CIRISApp.kt, Screen.ServerConnection branch)"
```

No nav hop — `ServerConnection` is in `FLOW_ONLY` (`screen_atlas.py:42`) and the
sidebar is suppressed while it shows (`CIRISApp.kt:1792`). CSD-080 §2 records
why `flow_only:` is a checked key.

The `exit:` was the delta: back went to `Screen.Interact`, the agent home, even
on a node-only install and even before sign-in. It now returns to Login without
a session and to `homeTarget` with one (setup review). The legacy back map
(`Screen.ServerConnection, Screen.ClaimNode -> Screen.Interact`) still names
Interact for the shell's back arrow; ClaimNode is CSD-085's, so that line is
reported, not changed.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "peer_reachability:{network}"
    bind: {network: loopback}
    use: display-only
    type: "enum[CONNECTED_LOCAL,CONNECTED_REMOTE,CONNECTING,DISCONNECTED,ERROR]"
    example: "CONNECTED_LOCAL"
    renders: "a status row with a coloured dot: Connected / Connecting / Disconnected / Couldn't connect. ERROR has its own word (`mobile.server_status_error`); the tag carries the enum name"
    tag: txt_server_status
    assert:
      one_of: {txt_server_status: [CONNECTED_LOCAL, CONNECTED_REMOTE, CONNECTING, DISCONNECTED, ERROR]}
  - ceg: "health:liveness:{version}"
    bind: {version: v1}
    use: display-only
    type: string
    example: "ok"
    renders: "polled every few seconds while the screen is up (startPolling/stopPolling); a 200 means serving and there is no intermediate state (CIRISServer#548)"
    tag: "proposed:txt_server_health"
  - ceg: x_private:server_url
    use: emit
    type: string
    example: "http://192.168.50.8:8080"
    renders: "the URL field — 'Connect to a CIRIS agent running on a remote server'"
    tag: input_server_url
  - ceg: x_private:is_local_server
    use: display-only
    type: bool
    example: true
    renders: "decides which card is live: the local controls (Restart / Stop) enable only for a local backend, and Disconnect only renders for a remote one"
    tag: "proposed:txt_server_is_local"
  - ceg: x_private:recent_connections
    use: display-only
    type: "list[string]"
    example: ["http://192.168.50.8:8080", "http://127.0.0.1:8080"]
    renders: "a list of previously used URLs; the live one carries a 'Current' chip instead of a connect control. One tag per row (`row_`/`btn_connect_recent_`/`btn_remove_recent_` + the URL folded to [a-z0-9_]), so each row is addressable"
    tag: "row_server_recent_${tagSuffix}"
  - ceg: x_private:server_error
    use: display-only
    type: string
    example: "Connection refused"
    renders: "the reason the connect attempt failed, in the transport's own words"
    tag: txt_server_error
```

**What changed in the setup review.** The status word, the URL
(`txt_server_url`), the error and each recent row now carry tags; the list
controls are per row and drivable (`testableClickable`) where they were one
shared, undrivable tag. Two values stay `proposed:` — the health poll result
and the local/remote flag — because the screen renders them only as colour and
a badge, and a flow can read them through `txt_server_status` (`_LOCAL` /
`_REMOTE`) in the meantime.

```yaml csd:states
populated: {tag: "proposed:row_server_recent", renders: "status row, the local controls when the backend is local, the URL field, and the recent list"}
empty:     {tag: txt_server_no_recent, renders: "'No previous connections on this device.' — said, not implied by an absent section"}
loading:   {tag: txt_server_status, renders: "CONNECTING — the status word changes, and the loading overlay covers the controls"}
error:     {tag: txt_server_error, renders: "the transport's reason; the status word is 'Couldn't connect', never DISCONNECTED's (`serverStatusKey`, pinned by ServerConnectionStatusTest)"}
```

`error` collapsing into `empty`/`idle` is the failure CSD/3 §2.2 names; it was in
the source (`ConnectionStatus.DISCONNECTED` and `.ERROR` both rendered
`mobile.server_status_disconnected`) and is closed — `ServerConnectionStatusTest`
was red against it.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| is it answering, and what is it | `GET /v1/system/health` | CIRISServer (`src/health.rs`) / CIRISAgent | live — `ServerConnectionViewModel.kt:135` |
| node-only liveness | `GET :4243/health` | CIRISServer (`src/health.rs:584`) | live — `BackendEndpoint.kt:72` |
| is a node's read API up | `GET /v1/identity` — unauthenticated | CIRISServer `src/compose.rs:3132`; CIRISAgent `routes/node_identity.py:50` proxies the folded node's (`:59`, 5 s timeout) so the client has one address | **live** — `CIRISApiClient.isLocalNodeUp` (`CIRISApiClient.kt:2176`, from `NodeSwitcherViewModel.kt:839` and `SetupViewModel.kt:636`), and the desktop runtime's readiness and existing-backend probes (`PythonRuntime.desktop.kt:320, 673`) |
| attach this install to a Portal-registered record | `POST /v1/setup/connect-node` · `GET /v1/setup/connect-node/status` · `POST /v1/setup/reset-device-auth` | CIRISServer `src/auth/device_auth.rs:371-377` | **not this card's.** Listed here while they were wired and unreached; they now live in the setup wizard's AI step (CSD-082 §3, `PortalConnectSection`), because what they return — the template, adapters and stewardship tier an organisation approved — configures the agent at completion. They do not change which node this client talks to |
| restart / stop the local backend | platform runtime control, not HTTP | CIRISClient (`PythonRuntime.*`) | live on desktop/mobile; absent on wasm |
| what a remote node's setup state is | `GET /v1/setup/status`, `GET /v1/setup/owned-nodes` | CIRISServer | **wrong-host by construction** — both are loopback-only and 403 off-host (`docs/FSD-remote-first-run-claim.md` §3.1, measured on 0.5.190). This client cannot ask a remote node whether it is set up or whether it has an owner |
| recent connections | local storage | CIRISClient | live |

**The card-vs-CC delta.** The Locked Spec has no "server connection" concept:
which node is answering is a property of the attachment, and
`FSD/ONE_CLIENT_N_NODES.md` §1 is explicit that capability is one bit today
(`ClientMode`) and that `NodeProfile` carries no role, version or capability
set. This screen shows a URL and a dot where the spec wants the node's identity
— its key, its owner, its version, what it can serve. Until then it is a
transport dialog wearing a node's name.

### 3.1 Every route this screen calls (generated)

<!-- generated: python3 packaging/check_csd_routes.py --print CSD-084 (screen ServerConnection; heuristic) -->
| value | endpoint | owner | state |
|---|---|---|---|
| `getSystemStatus` | `GET /v1/system/health` | CIRISAgent (front door) | called — `viewmodels/ServerConnectionViewModel.kt:157` |

**Why CSD-080 and this card overlap 100% on routes, and stay two cards.** Both
cite `/v1/system/health` and `/v1/setup/status` because both ask "is a backend
answering, and does it need setup". They ask it of different things for
different acts: Startup asks the LOCAL runtime it just launched, once, on cold
start, and has no input; this screen asks a URL the person chose, repeatedly,
and exists to change that choice. A merged card would put a URL field on the
boot screen or a boot phase on the connection dialog. The overlap is the
probe, not the act.

## 4. Flow (how)

Open it from Login's status chip.

```yaml
expect:
  state: populated
  visible: [input_server_url, btn_connect, btn_server_back]
```

Connect to a URL nothing is listening on:

```yaml
expect:
  state: error
  visible: ["proposed:txt_server_error"]
  absent: ["proposed:txt_server_no_recent"]
```

Connect to a live node, then leave:

```yaml
expect:
  state: populated
  matches: {"proposed:txt_server_status": "^CONNECTED_(LOCAL|REMOTE)$"}
```

All three steps are drivable since the setup review: `input_server_url`
declares its sink (`rememberTextInputDriver`) and the status and error tags
exist.

## 5. QA plan

**Platforms.** Four. wasm has no local runtime (`PythonRuntime.wasmJs.kt`), so
the local-controls card has nothing to control and the screen must say so rather
than render two dead buttons.

**What the review closed (all four asks were in this repo).** (1)
`input_server_url` declares its input sink (`rememberTextInputDriver`, the
same driver `CirisTextField` uses); `btn_server_back` is `testableClickable`.
(2) Tags for status, URL, error, recent rows and the empty list. (3) Per-row
tags on the recent list. (4) ERROR has its own word. Still open: the health and
local/remote values are not tagged on their own (see §2), and the card-vs-CC
delta in §3 — a URL and a dot where the spec wants the node's identity — is
unchanged.
