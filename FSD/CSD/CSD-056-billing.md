# CSD-056 — Billing (a card CC keeps off the wire, filed under a circle's rules)

**CSD**: CSD-056 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see how much they have left to spend on the agent and buy more,
and can tell the difference between "nothing left" and "this install has no
billing".** Serves **Transparency** — and it is the card where the plainest
statement in the area is a constitutional one: **billing is not fabric.**
CC 3.3.10 says so twice. *"Value transfer itself is **not** a CEG primitive — it
rides external rails."* And, normatively: *"An operational envelope MUST NOT
carry Stripe-derived or any payment-processor-derived data … Substrate admission
MUST reject an operational envelope carrying recognizable payment-processor
identifiers … **Billing remains entirely Portal+Stripe, off-wire.**"*
(`part_3_the_namespace.md:1351`).

A thing CC forbids the wire to carry has no `cohort_scope`, and a card with no
`cohort_scope` has no circle. It is in one: **Communities and Businesses ›
Rules** (`Placement(NavSurface.Billing, Tab.RULES, setOf(GLOBAL_COMMUNITIES))`).

## 2. Surface (what)

```yaml csd:surface
surface: billing
screen: Billing
```

`nav_map` derives `circle_global_communities -> tab_rules ->
nav_epistemic_billing`.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:credit_balance
    use: display-only
    type: int
    example: 250
    renders: "250 credits — the balance, large, at the top"
    tag: "proposed:text_billing_balance"
  - ceg: x_private:free_uses_remaining
    use: display-only
    type: int
    example: 3
    renders: "3 free uses left today"
    tag: "proposed:text_billing_free_uses"
  - ceg: x_private:plan_name
    use: display-only
    type: string
    example: "Starter"
    renders: "Starter — or nothing at all when there is no plan"
    tag: "proposed:text_billing_plan"
  - ceg: x_private:credit_product
    use: display-only
    type: "list[string]"
    example: ["500 credits — $4.99"]
    renders: "one purchasable package per row. NOT from the store: the three rows and their prices are a compiled-in constant (BillingViewModel.kt:47-66), never replaced — see §3"
    tag: "proposed:row_billing_product"
  - ceg: x_private:billing_credential_stale
    use: display-only
    type: bool
    example: false
    renders: "Credits need a fresh Google sign-in. You are still signed in to this agent; only the token it uses to pay for AI use has expired. — with the Refresh Google sign-in button; NOT the session banner (CIRISClient#171, #172)"
    tag: "btn_billing_refresh_signin_text"
  - ceg: x_private:session_expired
    use: display-only
    type: bool
    example: false
    renders: "Your session is no longer valid. Sign in again to continue. — only when TokenManager's needsInteractiveLogin says the SESSION is dead, never for a billing 401 (CIRISClient#59, #171)"
    tag: "proposed:text_billing_session_expired"
```

**Two credentials, two states, and the card says which is which.** The agent's
`GET /v1/api/billing/credits` answers 401 `billing_auth_expired` when the
Google ID token it spends at the hosted proxy has lapsed (`routes/billing.py`,
`_AUTH_FAILURE_CATEGORIES`). That token lasts an hour, and on desktop setup
writes it only once (CIRISClient#172). It is not the session, so it never
signs the person out of an agent that is answering them (CIRISClient#171):
`TokenManager.billingCredentialStale` is its own flag, `BillingViewModel.billingCredentialStale` its own state, and the
banner above (`BillingCredentialBanner`) is non-blocking and renews the token
in place: the native picker on Android/iOS; on desktop the browser hand-off
(`BrowserSignIn.kt`), whose `id_token` is written through `EnvFileUpdater` to
`CIRIS_BILLING_GOOGLE_ID_TOKEN` with `.config_reload` tripped
(`BillingCredentialRefresh.kt`, `BillingCredentialRefreshTest`). The session
token the hand-off also returns is deliberately ignored. A hand-off without an
`id_token` is the named `mobile.billing_refresh_node_too_old` state
(ciris-server < 0.5.177), not a generic error.

**Every row is `x_private:` and that is correct, not a shortcut.** There is no
billing, payment, wallet or currency family in the registry — none among the
158 prefixes at rc6 (CIRISClient#150). `bond_posted:{currency}` is a forfeitable Sybil-resistance stake, not a
balance.

**And `credits:*` is a trap this card must never fall into.** The registry has
`credits:{domain}:{language}:{subject}` — *"Commons Credits (P2).
**Non-transferable governance weight**; accrues via truth-grounding loop"*
(CC 3.1.9.6), glossed for users as *"Earned by helping. **They only go up, and
they cannot be sent or sold.**"* This screen's credits are bought with money and
spent to zero. **Two things called credits, opposite in every property that
matters, one app.** Binding this card to `credits:*` would be a category error
the checker would happily pass, so the prohibition is written here instead: the
balance is `x_private:credit_balance` and the word on screen should change, not
the binding. Naming, not machinery.

**SEVEN tags exist on the screen**: `btn_billing_back`, `btn_billing_refresh`,
`txt_billing_version`, — per product — `item_product_${productId}`
(`BillingScreen.kt:251`) and `btn_buy_${productId}` (`:289`), and the credits
banner's `btn_billing_refresh_signin` / `btn_billing_refresh_signin_text`
(`BillingCredentialBanner.kt`; `_waiting` while the browser is open). So a
`count: {of: "item_product_*", min: 1}` is assertable today with no tagging PR;
the other VALUE rows above are `proposed:`.

```yaml csd:states
populated: {tag: "proposed:text_billing_balance", renders: "the balance and the purchasable packages"}
empty:     {tag: "proposed:text_billing_no_products", renders: "Nothing to buy right now. — the store returned no packages"}
loading:   {tag: "proposed:billing_loading", renders: "the frame with a progress affordance (the real isLoading branch)"}
error:     {tag: "proposed:billing_unsupported", renders: "This install has no billing. Credits belong to an agent, and this is a node running {version}. — the danger tone, NOT a zero balance"}
```

**On a node the card shows an error, never a balance.** A synthesised "free /
no billing" response (a real-looking balance of zero with `hasCredit = true`)
would be a manufactured fact presented as the host's answer, so `getCredits`
raises on a node and the card renders its read failure (§3). The same repo
rules on this shape: the wheels job stages a placeholder that **raises** on every lookup
rather than returning a path, *because a thing that installs and silently
contains nothing is the failure this arrangement exists to prevent*
(`AGENTS.md`, CI). The billing equivalent of raising is the `error` state above.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| balance, free uses, plan | `GET /v1/api/billing/credits` | **CIRISAgent** (`routes/billing.py:30, 643` on `main`) | live on the agent — generated SDK `getCreditsV1ApiBillingCreditsGet` (`CIRISApiClient.kt:7780`). The doubled `api` segment is real: the router's prefix is `/api/billing`, mounted at `/v1`. |
| redeem a Play purchase | `POST /v1/api/billing/google-play/verify` | CIRISAgent (`routes/billing.py:983`) | **live** — `CIRISApiClient.verifyGooglePlayPurchase` (`CIRISApiClient.kt:7824`, generated `verifyGooglePlayPurchaseV1ApiBillingGooglePlayVerifyPost`), called from `androidApp/.../MainActivity.kt:277` after `BillingManager` completes |
| the credit history | `GET /v1/api/billing/transactions?limit&offset` | CIRISAgent (`routes/billing.py:878`) | live, **not called** — a generated stub exists (`BillingApi.kt:130`) and nothing in `shared/` uses it. The card shows a balance and no account of how it got there |
| start a non-Play purchase | `POST /v1/api/billing/purchase/initiate` | CIRISAgent (`routes/billing.py:727`) | live, **not called** (stub `BillingApi.kt:167`) — the Portal/Stripe leg; nothing in `shared/` or `androidApp/` reaches it |
| poll that purchase | `GET /v1/api/billing/purchase/status/{payment_id}` | CIRISAgent (`routes/billing.py:800`) | live, **not called** (stub `BillingApi.kt:94`) |
| purchasable packages | **a compiled-in constant, not the store** | CIRISClient | live, and **two lists that never meet** — see below |
| anything on a node | none | — | The node serves no billing route. `getCredits` RAISES `RouteNotOnThisHost` on a node (`CIRISApiClient.kt:7776`) and the card renders `ReadFailureBlock` (`BillingScreen.kt:123-132`: `billing_not_on_this_node` / `billing_error`), never a synthesised balance |
| a 503 from the credit service | `GET /v1/api/billing/credits` → 503 `billing_unavailable` (`routes/billing.py:615-623`) | CIRISAgent | **an outage, never a sign-in:** no token refresh and no "session expired" for an outage that is not the person's (`BillingViewModel.kt:211-213, 330-332`); only a 401 is auth (`isAuthFailure`, `BillingViewModelTest.loadBalance_503_isAnOutageNotAnExpiredSignIn`) |
| a 401 from the credit service | `GET /v1/api/billing/credits` → 401 `billing_auth_expired` (`routes/billing.py`, `_AUTH_FAILURE_CATEGORIES`); the agent also writes `<CIRIS_HOME>/.token_refresh_needed` (`token_handshake.py`) | CIRISAgent | **the billing credential, not the session (CIRISClient#171):** the Google ID token at the hosted proxy. `TokenManager.onBillingCredentialRejected` → `billingCredentialStale` → `BillingViewModel.onBillingCredentialStale` → the credits banner (`BillingViewModelTest.loadBalance_401Error_retryAlsoFails_isABillingStateNotASessionOne`, `TokenManagerCredentialSplitTest`). The session's `on401Error` / `needsInteractiveLogin` path is untouched |
| renew the billing credential (desktop) | `GET /v1/auth/oauth/{provider}/login?app_nonce=` in the browser, then `GET /v1/auth/oauth/handoff?app_nonce=&allow_unbound=` on the **node** (CSD-081 row), then `.env` + `.config_reload` | CIRISServer (hand-off) · CIRISAgent (`.env` reader) · CIRISClient (the write) | **live (CIRISClient#172):** `refreshBillingCredentialFromBrowser` (`BillingCredentialRefresh.kt`) takes the hand-off's `id_token` (ciris-server 0.5.177+, CIRISServer#434) and writes it to `CIRIS_BILLING_GOOGLE_ID_TOKEN` through `EnvFileUpdater` (`BillingCredentialRefreshTest`). The same write happens on every existing-user desktop Google sign-in. **No silent path exists:** the node asks Google for `access_type=offline` and discards the `refresh_token` it gets back (`src/auth/oauth.rs` `exchange_google` reads only `access_token` + `id_token`) — `evidence/blocked_upstream.tsv` |

**The prices on screen are not the store's.** `BillingViewModel.DEFAULT_PRODUCTS`
(`viewmodels/BillingViewModel.kt:47-66` — `credits_100` $9.99/99, `credits_250`
$24.99/249, `credits_600` $59.99/599) is assigned once at `:95`; the only other
write is `onNoBillingHere` (`BillingViewModel.kt:244`), which empties the list
on a node. Google Play IS queried, but by a
different object outside `shared/src`:
`androidApp/src/main/kotlin/ai/ciris/mobile/billing/BillingManager.kt:114-120`
(`queryProductDetailsAsync`) holds its own `_products` with the real
`formattedPrice` (`:49-50`), and nothing in `androidApp` references
`BillingViewModel`. Two consequences: the prices drawn are the constant's, not
the store's, and `proposed:text_billing_no_products` is reachable only on a
node — anywhere else the list is never empty, because it is never replaced.
Until the two lists are joined, the `empty:` row in `csd:states` can be
asserted on a node and nowhere else.

**Still open on the client.** `-1` means three things — not loaded, failed, and
BYOK/unlimited (`BillingViewModel.kt:185, 256`) — and all three render
`login_signin_provider` with the provider stripped, a broken "Sign in with "
(`BillingScreen.kt:154`); the actual error only appears in a transient
snackbar. `purchase_options` (`billing.py:135`) is not in `CreditStatusData`
(`CIRISApiClientProtocol.kt:275`); the displayed balance adds paid, free and
daily-free together (`BillingViewModel.kt:189`); "credits" / "CIRIS Credits"
are English literals (`BillingScreen.kt:154, 278`). `verifyGooglePlayPurchase`
runs on a client pinned to `http://localhost:8080`
(`androidApp/.../MainActivity.kt:268-277`), so a purchase is credited to the
local agent whichever host the person is signed into.

**One upstream ask (CIRISAgent, not yet filed).** With no credit provider configured the agent answers
`credits_remaining=999, free_uses_remaining=999, plan_name="unlimited"`
(`billing.py:374-384`): a made-up balance the client can only defend against by
string-matching "unlimited". A typed `mode: "unlimited"` with null counts is the
ask. Billing itself stays Portal+Stripe and off-wire; nothing for CIRISServer or
CIRISConstitution.

## 4. Flow (how)

Unwritten: the value rows are untagged. A purchase cannot be driven in a flow anyway
— it hands off to the platform store — so this card's flow will always stop at
the balance and the package list, which is precisely what is untagged.

## 5. QA plan

**Platforms.** Android and iOS carry the store integration; desktop and web show
the balance and no purchase path. **And a node build**, which is the run that
shows the card's not-on-this-node state (§2).

**Not tested here.**
* The purchase itself. It leaves the app.
* That the balance is correct. That is the agent's claim; the CSD asserts what is
  rendered.

**The recommendation, so it is on the record.** Move to **My things › Devices &
keys**, beside `Account` — which is already described as *"the account, not the
agent"* (`EpistemicNav.kt`, `Account`). Billing is the account. CC 3.3.10 puts
payment-processor data outside the envelope entirely, so there is no
`cohort_scope` that makes it a fact about Communities and Businesses rather than
about any other circle; it is in that circle because that circle's name sounds
commercial, which is the kind of placement the Card Atlas exists to stop. Keep
the not-on-this-node state rather than marking it `agentOnly`, for the reason
CSD-054 §5 gives.
