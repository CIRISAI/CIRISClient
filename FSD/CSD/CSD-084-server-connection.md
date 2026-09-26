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

The card is `envisioned`, not `sketched`, and the reason is in §5: four of its
eight tags are not drivable and its one text field has no input sink, so the
contract below cannot be asserted by anything. A CSD whose tags cannot be driven
is not sketched, it is described.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: ServerConnection
flow_only: true
entry: "Login's status chip (`btn_server_status` → `onServerSettings`, CIRISApp.kt:2324); or Setup's node-switch path (CIRISApp.kt:2949, 2967)"
exit: "Screen.Interact, from the legacy back map (CIRISApp.kt:4921) — the agent home, which is the wrong destination on a node-only install"
```

No nav hop — `ServerConnection` is in `FLOW_ONLY` (`screen_atlas.py:42`) and the
sidebar is suppressed while it shows (`CIRISApp.kt:1792`). CSD-080 §2 records
why `flow_only:` is a checked key.

The `exit:` above is the delta: `Screen.Interact` is the agent home, and on a
node-only install the landing surface is Contacts (CSD-083). Sending a
node-only operator to Interact drops them on a screen their node cannot serve —
the same defect CIRISApp.kt:4011 fixed for VerifyAgent by sending it to
ManageNodes instead.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "peer_reachability:{network}"
    bind: {network: loopback}
    use: display-only
    type: "enum[CONNECTED_LOCAL,CONNECTED_REMOTE,CONNECTING,DISCONNECTED,ERROR]"
    example: "CONNECTED_LOCAL"
    renders: "a status row with a coloured dot: Connected / Connecting / Disconnected. ERROR and DISCONNECTED render the same word today, which is the defect this row exists to name"
    tag: "proposed:txt_server_status"
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
    renders: "a list of previously used URLs; the live one carries a 'Current' chip instead of a connect control"
    tag: "proposed:row_server_recent"
  - ceg: x_private:server_error
    use: display-only
    type: string
    example: "Connection refused"
    renders: "the reason the connect attempt failed, in the transport's own words"
    tag: "proposed:txt_server_error"
```

**Every value this screen exists to show is `proposed:`.** The eight real tags
(`ServerConnectionScreen.kt`) are all controls — `btn_server_back`,
`btn_restart_server`, `btn_stop_server`, `input_server_url`, `btn_connect`,
`btn_disconnect`, `btn_connect_recent`, `btn_remove_recent` — and not one of
them carries the status, the health, the error or the list. A harness can press
Connect and cannot read what happened.

**The two list controls share one tag each across every row.**
`btn_connect_recent` and `btn_remove_recent` are applied inside the recent-list
item (`ServerConnectionScreen.kt:614, 626`), so N rows produce N elements with
the same `testTag`. CSD-005's `btn_receipt_<keyId>` is the shape this needs: a
per-row tag, or the row cannot be addressed at all.

```yaml csd:states
populated: {tag: "proposed:row_server_recent", renders: "status row, the local controls when the backend is local, the URL field, and the recent list"}
empty:     {tag: "proposed:txt_server_no_recent", renders: "no previous connections — the list section is simply absent today, which is the same 'nothing composed vs nothing to say' ambiguity screen_startup was added to close"}
loading:   {tag: "proposed:txt_server_status", renders: "CONNECTING — the status word changes and nothing else does; the Connect button does not carry a progress affordance"}
error:     {tag: "proposed:txt_server_error", renders: "the transport's reason. It MUST NOT reuse the DISCONNECTED word: ERROR and DISCONNECTED map to the same string today (`getStatusText`, ServerConnectionScreen.kt:661-662), so a refused connection and a deliberate disconnect are the same sentence"}
```

`error` collapsing into `empty`/`idle` is the exact failure CSD/3 §2.2 names, and
here it is in the source: `ConnectionStatus.DISCONNECTED` and
`ConnectionStatus.ERROR` both render `mobile.server_status_disconnected`.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| is it answering, and what is it | `GET /v1/system/health` | CIRISServer (`src/health.rs`) / CIRISAgent | live — `ServerConnectionViewModel.kt:135` |
| node-only liveness | `GET :4243/health` | CIRISServer | live |
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

**None of these three steps runs today.** The first needs
`input_server_url` to accept `/input`; the second and third need the status and
error tags to exist.

## 5. QA plan

**Platforms.** Four. wasm has no local runtime (`PythonRuntime.wasmJs.kt`), so
the local-controls card has nothing to control and the screen must say so rather
than render two dead buttons.

**Not tested here — and this is the whole of §1's `envisioned` stage.**
`client/tools/check_ui_drivable.py --list` reports `input_server_url`,
`btn_server_back`, `btn_connect_recent` and `btn_remove_recent` as tagged but
not drivable. `input_server_url` is the load-bearing one: it is
`Modifier.testable("input_server_url")` with **no** `rememberInputSinks` call
anywhere in the file (contrast `LoginScreen.kt:690` and `SetupScreen.kt:201`),
so `/input` has nothing listening and a gate cannot type a URL into the screen
whose only job is taking a URL. That is CIRISClient#30's defect class, on a
different screen.

**The ask, in order.** (1) `CirisTextField` for the URL — it declares its own
sink and reports what it holds, so the field becomes drivable by construction
(`ui/primitives/Controls.kt:91`). (2) Tags for the status, health, error and
recent list. (3) Per-row tags on the recent list. (4) Split ERROR from
DISCONNECTED in the status string. None of these is an upstream ask; all four
are in this repo.
