# CSD-081 — Login (the one door, and which one it is)

**CSD**: CSD-081 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
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
  - ceg: x_private:signin_outcome
    use: display-only
    type: "enum[claims_this_node,admitted_as_observer,refused]"
    example: "refused"
    renders: "what an account this node has never seen would get if it signed in NOW, with the node's reason_id and remedy when it is `refused` — read from `GET /v1/auth/signin-state` `new_identity`, never inferred from the provider list and the owner hint"
    tag: "proposed:txt_login_signin_outcome"
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
| Google / Apple handoff | platform OAuth + the node's OAuth link routes (`src/auth/oauth.rs`, `src/auth/oauth_link.rs`) | CIRISServer | live — the concrete legs are the rows below |
| which providers to offer (drives `txt_google_unavailable`) | `GET /v1/auth/oauth/providers` | CIRISServer `src/auth/oauth.rs:2932` (handler `:1598`) | **live** — `CIRISApiClient.getOAuthProviders` (`CIRISApiClient.kt:2126`), called once from `CIRISApp.kt:1839` |
| configure a provider | `POST /v1/auth/oauth/providers` | CIRISServer `src/auth/oauth.rs:2933` | live, **not called** — operator configuration, not a sign-in act; no card owns it |
| desktop: open the browser leg | `GET /v1/auth/oauth/{provider}/login?app_nonce=…` | CIRISServer `src/auth/oauth.rs:2936` (callback `:2940`) | **live** — the client builds the URL (`CIRISApiClient.kt:2106`) and hands it to the system browser; the callback is the provider's, never ours |
| desktop: collect the session | `GET /v1/auth/oauth/handoff?app_nonce=…&allow_unbound=…` | CIRISServer `src/auth/oauth.rs:2981` (handler `:2505`) — **LOOPBACK-ONLY**, a layer not a comment (`:2982`) | **live** — `CIRISApiClient.pollOAuthHandoff` (`CIRISApiClient.kt:2080`), polled from `CIRISApp.kt:2131`; 204 = pending. The whole desktop login rests on this row |
| Android: native Google token exchange | `POST /v1/auth/native/google` | CIRISServer `src/auth/oauth.rs:2963` | **live** — `AuthManager.android.kt:226` |
| iOS: native Apple token exchange | `POST /v1/auth/native/apple` | CIRISServer `src/auth/oauth.rs:2964` | **live** — `AuthManager.ios.kt:138`, and `CIRISApiClient.appleAuth` (`CIRISApiClient.kt:6369`) via `:6421` |
| **what signing in would do, right now** | `GET /v1/auth/signin-state` | CIRISServer `src/auth/oauth.rs:2972` (handler `:2326`, CIRISServer#439); unauthenticated | **live and never read.** It answers `claimed`, `managed`, `providers`, `session_delivery` (`loopback_handoff` \| `exchange_code`, per caller) and `new_identity.{outcome,reason_id,remedy}` from the same predicates `resolve_oauth_user` gates on. The screen instead infers the outcome from the provider list and the owner hint, and so cannot say "a new account would be refused here" until after someone is refused. The card SHOULD read it (`proposed:txt_login_signin_outcome` above) — a CIRISClient issue is drafted, not yet filed |
| link an OAuth identity onto the owner's existing certificate | `POST /v1/self/oauth-link` | CIRISServer `src/auth/oauth_link.rs:49` (ROUTE), registered `:466` | live, **wired and unreached** — two client methods post it, `preprovisionOAuthEmail` (`CIRISApiClient.kt:913`) and `linkOAuthIdentity` (`:2291`), and neither has a caller. This is the remedy `signin-state` withholds ("a locally-claimed node cannot have an OAuth identity linked", #432): the node side now exists, the client side is unreached. The route-coverage report marked it CALLED |
| sign in AS a federation identity | `POST /v1/self/login` | CIRISServer `src/auth/self_login.rs:221` | live, **wired and unreached** — `selfLogin` (`CIRISApiClient.kt:1945`) has no caller, which is consistent with "there is deliberately no federation sign-in option" (§2, CIRISClient#23); the method is dead code. Also marked CALLED in the report |
| a web page redeems `?ciris_code=` | `POST /v1/auth/oauth/exchange` | CIRISServer `src/auth/oauth.rs:2969` | live, not a KMP leg — browser-only, deliberately not loopback-gated |
| is this a first run | `GET /v1/setup/status` | CIRISAgent `routes/setup/status.py:45`; CIRISServer `src/auth/bootstrap.rs` (**loopback-only**) | live, with CIRISAgent#1195 open |
| reset the device | local wipe + node reset | CIRISClient | live (CIRISClient#55/#56/#61 closed) |

Routes verified against CIRISServer `origin/main` 046e1b39 (0.5.217). On
`integ/0.5.218` (97900cf5) the OAuth set is unchanged and every line above moved
+49 (`providers` `:2981`, `signin-state` `:3021`, `handoff` `:3030`).

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

*Cannot yet assert:* that the refusal was stated BEFORE the attempt. On an owned
personal node `GET /v1/auth/signin-state` already answers
`new_identity.outcome: refused` with `auth.oauth.no_local_identity`; the step
that belongs in front of this one is "land on Login, see
`txt_login_signin_outcome` say a new account will be refused", and it waits on
the card reading that route.

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

**Not tested here.** The OAuth round trip (the browser handoff leaves the app —
`btn_google_signin` / `btn_apple_signin` can be pressed, and nothing after that
is drivable); the reset-device confirmation (`btn_login_reset_device` →
`btn_reset_device_confirm`) is destructive and is not run on a developer
install; the language selector; the privacy-policy link.

**One tag to watch.** `input_username` exists on BOTH this screen and the setup
wizard, and a gate that waits for it has matched the wizard's field before
(`testing/driver.py:82`). A flow that starts here must assert the screen, not
the tag.
