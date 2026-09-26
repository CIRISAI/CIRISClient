# CSD-057 — Wallet (real money, in a circle, bound to a family that does not exist)

**CSD**: CSD-057 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see the address their federation key controls, what is in it,
what they have already spent against their limits, and send USDC to an address
they have checked — with the experimental status and any hardware-trust
advisory in front of them, not under them.** Serves **Autonomy** and, because
this one moves real value, **Integrity**.

CC recognises exactly this object. CC 3.3.10: *"Value transfer … rides external
rails (USDC on Base via x402, keyed to the federation signing key under the
**Identity = Wallet** principle)"*, and *"the same federation key that signs this
attestation controls the Base wallet … so 'I settled `tx` for action X' is
self-proving — no oracle needed"*. The wallet is not a bolt-on; it is the
signing key seen from the money side.

**But the records it would show cannot be named.** CC defines `settlement:*`
(CC 3.3.10) and `ledger:*` (CC 3.3.10.1) in prose, and **neither is in the
registry** — `client/ceg/README.md` lists `settlement:*` among the families that
are deliberately absent, and states the consequence: *"A family with no registry
row cannot be rendered because it cannot be named — that is the 'nothing renders
an unregistered family' gate (CC 3.1.7 R2)."* So the transaction history on this
screen is, constitutionally speaking, not showing CEG records. It is showing a
chain explorer's rows. Saying that in the CSD is better than binding
`x_private:` and letting a reader assume the gap is a client convention.

## 2. Surface (what)

```yaml csd:surface
surface: wallet
screen: Wallet
```

`nav_map` derives `circle_global_communities -> tab_rules ->
nav_epistemic_wallet` — Communities and Businesses › Rules, beside Billing.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:wallet_address
    use: display-only
    type: string
    example: "0x4a19…c2f0"
    renders: "the address in mono with a copy control — and it IS the federation signing key's address (Identity = Wallet, CC 3.3.10)"
    tag: txt_wallet_address
  - ceg: x_private:balance_usdc
    use: display-only
    type: string
    example: "42.50"
    renders: "42.50 USDC"
    tag: card_wallet_balance
  - ceg: x_private:spending_progress
    use: display-only
    type: "list[string]"
    example: ["session 12.00 of 500.00", "daily 30.00 of 1000.00"]
    renders: "two bars: this session, and today, each with what is left and when it resets"
    tag: card_spending_progress
  - ceg: x_private:settlement_ref
    use: display-only
    type: "list[string]"
    example: ["send 5.00 USDC confirmed 0x8f…"]
    renders: "recent transactions, each with its status and a link out to the explorer"
    tag: card_transaction_history
  - ceg: x_private:paymaster_status
    use: display-only
    type: string
    example: "sponsored"
    renders: "who pays the gas"
    tag: txt_paymaster_status
  - ceg: x_private:hardware_trust_advisory
    use: display-only
    type: bool
    example: false
    renders: "a security advisory affecting hardware trust, above the send form, not below it"
    tag: card_trust_warning
```

**Seventeen real tags, and six of them carry values.** This is the best-tagged
screen in the area — `card_wallet_balance`, `card_wallet_address`,
`txt_wallet_address`, `card_spending_progress`, `card_transaction_history`,
`card_wallet_limits`, `card_wallet_paymaster`, `txt_paymaster_status`,
`card_trust_warning`, `card_wallet_experimental`, `card_wallet_transfer`,
`input_recipient_address`, `input_transfer_amount`, `input_transfer_memo`,
`btn_send_transfer`, `btn_copy_address`, `btn_wallet_back`. That is why five
`shows:` rows above are already real, and why this CSD's blocker is upstream
rather than a tagging PR.

**`settlement_ref` is `x_private:` under protest.** It is the one field with a
CC-defined family — `settlement:{…}`, CC 3.3.10, with `rail` and
`settlement_ref` members spelled out at `part_3_the_namespace.md:1376` — and no
registry row. The ask is on CIRISConstitution (§3). Until it lands, a conformant
client cannot name what it is rendering.

```yaml csd:states
populated: {tag: card_wallet_balance, renders: "balance, limits, history, and the send form"}
empty:     {tag: "proposed:text_wallet_no_transactions", renders: "Nothing has moved yet. — a real wallet with no transactions"}
loading:   {tag: "proposed:wallet_loading", renders: "the frame with a progress affordance"}
error:     {tag: "proposed:wallet_unsupported", renders: "This install has no wallet. A wallet needs an agent, and this is a node running {version}."}
```

`getWalletStatus` short-circuits on a node and returns
`WalletStatusResponse()` — all defaults, i.e. "no wallet"
(`CIRISApiClient.kt:8559-8563`). That is **more honest than Billing's
synthesised balance** (CSD-056 §2), because the defaults say nothing rather than
saying zero-and-fine. It is still a manufactured answer standing in for a
missing `error` state, and a person on a node sees an empty wallet rather than
"there is no wallet here".

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| balance, limits, paymaster | `GET /v1/wallet/status` | **CIRISAgent** (`routes/wallet.py:570`) | live on the agent |
| send | `POST /v1/wallet/transfer` | CIRISAgent (`:674`) | live |
| check an address | `POST /v1/wallet/validate-address` | CIRISAgent (`:817`) | live |
| catch a repeat send | `POST /v1/wallet/check-duplicate` | CIRISAgent (`:976`) | live |
| anything on a node | none | CIRISServer | **wrong-host by placement.** `/v1/wallet/*` is agent-only; the card is not `agentOnly`, so it is offered in Communities › Rules on a node build. |
| a nameable settlement record | — | **CIRISConstitution** | **missing, and the ask already has a number this card did not cite: CIRISConstitution#105 OPEN** — "The namespace generator harvests only CC 3.1.x — 17 families declared in CC 3.3.8–3.3.12 are in no vendored registry, including every `ledger:*`". Its table names `3.3.10 settlement → settlement:*` and `3.3.10.1 ledger → ledger:head:{unit}, ledger:checkpoint:{unit}, ledger:promotion`, cites `part_3_the_namespace.md:1369`, and names this repo's CSD blocker. Consumer re-file: CIRISPersist#753 OPEN. (CIRISConstitution#92, the `ledger:*` standard, is CLOSED — which is why #105 exists.) `settlement_ref` therefore stays `x_private:`, and that is a registry gap with an owner, not an unknown. |

## 4. Flow (how)

Unwritten. It could be written today for everything up to the send — the
balance, the limits, the address copy and the address-validation error all run
on real tags. **The send step must not be flowed against a live rail.** A flow
that moves USDC to pass is not a test.

```yaml
# candidate — read-only, stops before btn_send_transfer
expect:
  state: populated
  visible: [card_wallet_balance, card_spending_progress, card_wallet_experimental, txt_wallet_address]
```

`card_wallet_experimental` is in that list deliberately: the screen already
renders an amber experimental warning, and a flow that asserts the balance while
letting the warning quietly disappear would be testing the wrong half.

## 5. QA plan

**Platforms.** All five, agent build. Plus a node build for the
`wallet_unsupported` state in §2 once it exists.

**Not tested here.**
* Sending. It moves real value on Base; the duplicate check and the address
  validation are the parts a flow can exercise safely.
* Whether the balance is right. That is the chain's claim, relayed by the agent.
* Gas estimates and the paymaster, which depend on live network conditions.

**The recommendation, so it is on the record.** Move to **My things › Devices &
keys**. The wallet IS the federation signing key (CC 3.3.10, Identity = Wallet),
and the key lives with `IdentityManagement`, which is already there. The
positive argument against the current placement is CC's own default for this
family: a settlement record is `cohort_scope: self` unless the producer opts in
— *"the federation log is **not** a public payment trail unless the producer
chooses it"* (CC 3.3.10). Communities and Businesses is `affiliations`. Putting
the self-scoped object in the affiliations circle inverts the default it ships
with, and a person reading the Rules tab of a circle reasonably concludes the
things in it are that circle's.
