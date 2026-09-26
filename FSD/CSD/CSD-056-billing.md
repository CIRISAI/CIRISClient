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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
  - ceg: x_private:auth_expired
    use: display-only
    type: bool
    example: false
    renders: "Your billing sign-in has expired — sign in again. (CIRISClient#59)"
    tag: "proposed:text_billing_auth_expired"
```

**Every row is `x_private:` and that is correct, not a shortcut.** There is no
billing, payment, wallet or currency family in the registry — I checked all 116
prefixes. `bond_posted:{currency}` is a forfeitable Sybil-resistance stake, not a
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

**FIVE tags exist on the screen**: `btn_billing_back`, `btn_billing_refresh`,
`txt_billing_version`, and — per product — `item_product_${productId}`
(`BillingScreen.kt:251`) and `btn_buy_${productId}` (`:289`). So a
`count: {of: "item_product_*", min: 1}` is assertable today with no tagging PR;
only the VALUE rows above are `proposed:`.

```yaml csd:states
populated: {tag: "proposed:text_billing_balance", renders: "the balance and the purchasable packages"}
empty:     {tag: "proposed:text_billing_no_products", renders: "Nothing to buy right now. — the store returned no packages"}
loading:   {tag: "proposed:billing_loading", renders: "the frame with a progress affordance (the real isLoading branch)"}
error:     {tag: "proposed:billing_unsupported", renders: "This install has no billing. Credits belong to an agent, and this is a node running {version}. — the danger tone, NOT a zero balance"}
```

**The `error` state does not exist and a false `populated` stands in for it.**
`getCredits` short-circuits on a node and **synthesises a response**:
`hasCredit = true, creditsRemaining = 0, freeUsesRemaining = 0,
purchaseRequired = false, planName = null` (`CIRISApiClient.kt:7653-7662`). The
comment says the intent — *"Report 'free / no billing' so the node UI shows no
purchase prompts"* — and the intent is sound; the value is not. A node build
renders a real-looking balance of zero with `hasCredit = true`, which is a
manufactured fact presented as the host's answer. The same repo already rules on
this shape: the wheels job stages a placeholder that **raises** on every lookup
rather than returning a path, *because a thing that installs and silently
contains nothing is the failure this arrangement exists to prevent*
(`AGENTS.md`, CI). The billing equivalent of raising is the `billing_unsupported`
state above.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| balance, free uses, plan | `GET /v1/api/billing/credits` | **CIRISAgent** (`routes/billing.py:30, 580`) | live on the agent. The doubled `api` segment is real: the router's prefix is `/api/billing`, mounted at `/v1`. |
| purchasable packages | **a compiled-in constant, not the store** | CIRISClient | live, and **two lists that never meet** — see below |
| anything on a node | none | — | **wrong-host by placement.** The node serves no billing route. The card is not `agentOnly`, so on a node build it is offered in Communities › Rules and answers from a synthesised value. |

**The prices on screen are not the store's.** `BillingViewModel.DEFAULT_PRODUCTS`
(`viewmodels/BillingViewModel.kt:47-66` — `credits_100` $9.99/99, `credits_250`
$24.99/249, `credits_600` $59.99/599) is assigned once at `:95` and there is no
`_products.value =` anywhere else in that file. Google Play IS queried, but by a
different object outside `shared/src`:
`androidApp/src/main/kotlin/ai/ciris/mobile/billing/BillingManager.kt:114-120`
(`queryProductDetailsAsync`) holds its own `_products` with the real
`formattedPrice` (`:49-50`), and nothing in `androidApp` references
`BillingViewModel`. Two consequences: the `renders` text above was false, and
`proposed:text_billing_no_products` names an **unreachable** state — the list is
never empty because it is never replaced. The `empty:` row in `csd:states` cannot
be asserted by any flow until the two lists are joined, and §5 says so.

No upstream ask. CC settles this one: billing is Portal+Stripe and off-wire by
design, so there is nothing for CIRISServer to add and nothing for
CIRISConstitution to register. The work is entirely in this repo.

## 4. Flow (how)

Unwritten. Three tags, all chrome. A purchase cannot be driven in a flow anyway
— it hands off to the platform store — so this card's flow will always stop at
the balance and the package list, which is precisely what is untagged.

## 5. QA plan

**Platforms.** Android and iOS carry the store integration; desktop and web show
the balance and no purchase path. **And a node build**, which is the run that
exposes §2's synthesised balance.

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
commercial, which is the kind of placement the Card Atlas exists to stop. Mark
it `agentOnly` in the same change, or give it the `billing_unsupported` state —
and prefer the state, for the reason CSD-054 §5 gives.
