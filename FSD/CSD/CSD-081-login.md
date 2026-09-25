# CSD-081 — Login (the one door, and which one it is)

**CSD**: CSD-081 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person signs in to a node, and when they cannot, the screen names which
account the node is bound to instead of repeating a generic refusal.** Serves
**CC 3.2** — the owner-binding is single-valued and the owner's own signature is
the claim of ownership, so "this node already has an owner and it is not the
account you just used" is a fact the screen can state rather than a failure it
has to guess at. The owner hint is the whole difference between "wrong door" and
"broken lock", and CIRISClient#70 is what happens without it: every refusal
surfaced as `Token exchange failed` because the server's reason was dropped.

This screen is also the one the first-run wizard RETURNS to. Completing setup
restarts the node, which invalidates the session by design
(CIRISServer#393) — so landing back here is correct and looks exactly like a
failure. `banner_setup_complete_relogin` exists for that single sentence.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Login
flow_only: true
entry: "Startup routes here when `setup_required` is true, or when a stored token did not validate; and Setup routes here on completion, after the node restart kills the session"
exit: "homeTarget on success — Interact on an agent build, Contacts on a node; Setup on `btn_local_login` at first run"
```

No nav hop: `Login` is in `FLOW_ONLY` (`testing/gate/screen_atlas.py:42`) and the
sidebar is suppressed while it is showing (`CIRISApp.kt:1789-1792`). CSD-080 §2
records why `flow_only:` is a checked key rather than a spelling convention.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "Eric (eri***@gmail.com)"
    renders: "the owner hint above the sign-in buttons — name and masked email, or either alone; blank means no usable identity and the row is skipped"
    tag: txt_owner_hint
  - ceg: "session:{kind}"
    bind: {kind: owner}
    use: display-only
    type: bool
    example: true
    renders: "'Setup finished. Sign in with the same details you just created.' — the session died with the restart, not with you"
    tag: banner_setup_complete_relogin
  - ceg: x_private:username
    use: emit
    type: string
    example: "qaadmin"
    renders: "the username field; the local form is revealed by btn_local_login and is not shown until it is"
    tag: input_username
  - ceg: x_private:password
    use: emit
    type: string
    example: "••••••••"
    renders: "the password field"
    tag: input_password
  - ceg: x_private:oauth_available
    use: display-only
    type: bool
    example: false
    renders: "when false the provider button is greyed AND explained — 'Sign-in with Google isn't available on this install.' A dead control with no sentence was the defect"
    tag: txt_google_unavailable
  - ceg: x_private:login_error
    use: display-only
    type: string
    example: "This device is signed in as an observer."
    renders: "the refusal, in the server's own words — capped at 100 chars, never replaced by a generic one (CIRISClient#70)"
    tag: txt_oauth_error
  - ceg: x_private:observer_blocked
    use: display-only
    type: bool
    example: true
    renders: "the recovery card: who owns this node, then two ways out — sign in as someone else, or reset the device"
    tag: card_observer_blocked
  - ceg: x_private:connection_status
    use: display-only
    type: "enum[CONNECTED_LOCAL,CONNECTED_REMOTE,CONNECTING,DISCONNECTED,ERROR]"
    example: "CONNECTED_LOCAL"
    renders: "a pressable status chip — Connected / Connecting / Disconnected — that opens ServerConnection (CSD-084)"
    tag: btn_server_status
  - ceg: x_private:federation_identity_key_id
    use: display-only
    type: string
    example: "null"
    renders: "absent entirely once the device HAS one. When it is null: 'This device has no federation identity' plus Create — which routes to the catch-up screen on a configured node, never back through the wizard"
    tag: btn_federation_create
```

**There is deliberately no federation sign-in option.** A fed-ID is an identity,
not a credential; a "sign in as `<key_id>`" door was added and removed
(CIRISClient#23) because on a fresh install that key_id is only the bootstrap
keystore alias and the button could never do anything but refuse.

```yaml csd:states
populated: {tag: btn_local_login, renders: "the chooser: provider button (or its greyed self plus txt_google_unavailable), then 'Use a local account'"}
empty:     {tag: "proposed:txt_login_no_owner", renders: "a node with no owner yet: the local button reads 'Set up this device' and routes to the wizard instead of revealing a form. There is no owner hint to show, and the screen should say so rather than render nothing where a name goes"}
loading:   {tag: "proposed:login_submitting", renders: "the submit control carrying a progress affordance; the form stays legible and NO error sentence is shown"}
error:     {tag: txt_oauth_error, renders: "the server's refusal; and card_observer_blocked for the one refusal that has a remedy — wrong account on an owned node"}
```

`empty` is `proposed:` because it does not exist: on a fresh node the hint is
simply absent and nothing takes its place. That is the same shape as the defect
`screen_startup` was added to fix — nothing composed and nothing said look
identical from outside.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| owner hint | `GET /v1/auth/owner-hint` | CIRISServer (`src/auth/session.rs`, `src/auth/oauth.rs`) | live |
| sign in | `POST /v1/auth/login` | CIRISServer (`src/auth/session.rs`, `src/claim_remote.rs`); the agent serves its own | live |
| Google / Apple handoff | platform OAuth + the node's OAuth link routes (`src/auth/oauth.rs`, `src/auth/oauth_link.rs`) | CIRISServer | live |
| **post-login redirect: a contract the client must meet** | `GET /v1/auth/oauth/{provider}/login?redirect_uri=…&app_nonce=…` | CIRISServer | **behaviour change, built, unmerged** (0.5.218, CIRISServer#672, still open; not readable, since no branch or PR is pushed). The redirect is now **parsed**, and only a **same-origin path** (`/…`, not `//…` or `/\…`) or an **exact loopback host** (`localhost`, `127.0.0.1` or `[::1]`, any port, over `http`) is accepted. An absolute `https://` redirect, which `main` accepts for any host (`is_safe_redirect`, `src/auth/oauth.rs:1850-1864` @ `046e1b39`), is **refused**. The refusal id is `auth.oauth.unsafe_redirect` on `main` (`oauth.rs:1790-1799`, a browser refusal page); the brief says new ids land under `oauth.*`, so the 0.5.218 id is **unconfirmed** until CIRISClient#78. **The client complies today by omission:** the desktop browser flow sends only `app_nonce` (`CIRISApiClient.oauthBrowserLoginUrl`, `CIRISApiClient.kt:2102-2106`), so the node's default `/` applies; Android and iOS sign in natively and do not use this route; and the web build sends no `redirect_uri` (no match in `wasmJsMain`). **The rule for any future caller:** send a path, or `http://localhost:<port>/…` for a loopback listener. Never an `https://` URL, and never an app-scheme URL unless the server posts an allow-list for one |
| is this a first run | `GET /v1/setup/status` | CIRISAgent `routes/setup/status.py:45`; CIRISServer `src/auth/bootstrap.rs` (**loopback-only**) | live, with CIRISAgent#1195 open |
| reset the device | local wipe + node reset | CIRISClient | live (CIRISClient#55/#56/#61 closed) |

**Owner hint may be null on a bugged install** (no SYSTEM_ADMIN): the card still
renders, falling back to a generic body, because the user needs the two buttons
whether or not the node can name its owner.

## 4. Flow (how)

Land on Login on an owned node.

```yaml
expect:
  state: populated
  visible: [btn_local_login, btn_server_status]
```

Reveal the local form and sign in — the sequence `session_fixture.log_in` drives
(`testing/gate/session_fixture.py:130-141`): click `btn_local_login`, type into
`input_username` and `input_password`, click `btn_login_submit`.

```yaml
expect:
  visible: [input_username, input_password, btn_login_submit, btn_login_back]
```

Sign in as an account that is not the owner.

```yaml
expect:
  state: error
  visible: [card_observer_blocked, btn_choose_different_account, btn_reset_setup]
```

Arrive here straight from a completed wizard.

```yaml
expect:
  visible: [banner_setup_complete_relogin, txt_owner_hint]
```

## 5. QA plan

**Platforms.** All five. `btn_local_login` / `input_username` / `input_password`
/ `btn_login_submit` are exactly the tags CIRISAgent's five-platform gate sends
to this screen, and `session_fixture` cannot establish a session without them.
`input_username` and `input_password` declare their sinks at
`LoginScreen.kt:690`, so both are drivable by construction.

**The redirect contract (CIRISServer#672).** A `commonTest` pins
`oauthBrowserLoginUrl`: its query carries either no `redirect_uri`, or one that
is a same-origin path or an exact loopback host. It must be shown red first by
planting an `https://` redirect. That is the one part of the OAuth round trip
the client controls, so it is tested here even though the round trip is not.

**Not tested here.** The OAuth round trip (the browser handoff leaves the app —
`btn_google_signin` / `btn_apple_signin` can be pressed, and nothing after that
is drivable); the reset-device confirmation (`btn_login_reset_device` →
`btn_reset_device_confirm`) is destructive and is not run on a developer
install; the language selector; the privacy-policy link.

**One tag to watch.** `input_username` exists on BOTH this screen and the setup
wizard, and a gate that waits for it has matched the wizard's field before
(`testing/driver.py:82`). A flow that starts here must assert the screen, not
the tag.
