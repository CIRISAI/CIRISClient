# CSD-043 — Users (Communities and Businesses › People)

**CSD**: CSD-043 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**An operator can see who has an account on this node, what each of them may do,
and when they last signed in.**

That sentence is the mission of the screen that exists. It is **not** the mission
of the card the Locked Spec placed in Communities › People, which would be *the
people in the affiliations I belong to*. Those are two different objects and §6
says so plainly, because a roster is the single place CC constrains hardest:
**CC 2.1**, on the `listed` envelope field — *"Default absent (roster is producer-
+ self-queryable, **NEVER globally enumerable**)"*, and *"opting into roster
visibility is a one-way disclosure the member chooses; substrate does NOT
solicit."*

Serves **CC 4.4.3.4.3**, the membership ladder — observer → member →
server/attester → steward/moderator/founder-authority — against which the chips
on this screen do not line up (§6).

## 2. Surface (what)

```yaml csd:surface
surface: users
screen: Users
```

`nav_map` derives `circle_global_communities -> tab_people ->
nav_epistemic_users`. The tab holds two cards in that circle — Contacts (which
IS the People tab everywhere) and this — so the chain ends on the row rather than
the tab.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:user_id
    use: display-only
    type: string
    example: "wa-2026-08-14-A3F2B1"
    renders: "one row per account; the id is shown only in the detail sheet"
    tag: "item_user_{userId}"
  - ceg: x_private:username
    use: display-only
    type: string
    example: "ada"
    renders: "ada — the row's headline, with an avatar from `oauth_picture`"
    tag: "proposed:user_row_username"
  - ceg: x_private:oauth_email
    use: display-only
    type: string
    example: "ada@example.org"
    renders: "ada@example.org — rendered on the ROW, not only in the detail sheet"
    tag: "proposed:user_row_email"
  - ceg: "partner_role:{role}"
    bind: {role: community}
    use: display-only
    type: "enum[observer,admin,authority,system_admin,service_account]"
    example: "observer"
    renders: "an API-role chip. THE VOCABULARY IS NOT CC's: CC 4.4.3.4.3's ladder is observer → member → server/attester → steward, and CC 4.1.3 rejected `observer` as a grantable capability outright."
    tag: "chip_api_role_observer"
  - ceg: "duty:{kind}"
    bind: {kind: moderate}
    use: display-only
    type: "enum[root,authority,observer]"
    example: "authority"
    renders: "a WA-role chip (lowercase on the wire, uppercase in the chip)"
    tag: "proposed:user_row_wa_role"
  - ceg: x_private:auth_type
    use: display-only
    type: "enum[password,oauth,api_key]"
    example: "oauth"
    renders: "an auth-type chip; also a filter (chip_auth_type_oauth)"
    tag: "chip_auth_type_oauth"
  - ceg: x_private:is_active
    use: display-only
    type: bool
    example: true
    renders: "Active / Inactive, and the status filter chips"
    tag: "chip_status_active"
  - ceg: x_private:last_login
    use: display-only
    type: timestamp
    example: "2026-09-24T19:03:00Z"
    renders: "Last login — 2026-09-24T19:03:00Z (detail sheet only)"
    tag: "proposed:user_detail_last_login"
  - ceg: x_private:effective_permissions
    use: display-only
    type: "list[string]"
    example: ["send_messages", "resolve_deferrals"]
    renders: "the effective permission list, WA-role inheritance folded in (detail sheet only)"
    tag: "proposed:user_detail_permissions"
  - ceg: x_private:listed_optin
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT ON THE WIRE AND NOT RENDERED. CC 2.1's `listed` flag is what makes a person enumerable; `UserSummary` has no such field, so this screen enumerates everyone with no opt-in to check."
    tag: "proposed:user_row_listed"
    blocked_by: [CIRISAgent#1202, CIRISPersist#912]
```

`partner_role:{role}` and `duty:{kind}` are bound here **as the nearest
registered families**, and the binds are deliberately not the values on screen:
the screen's `api_role` vocabulary (`OBSERVER / ADMIN / AUTHORITY /
SYSTEM_ADMIN / SERVICE_ACCOUNT`) is CIRISAgent's, and CC has no such enum. Naming
the family anyway is what forces the mismatch into view rather than letting an
`x_private:` row hide it — which is §2.1's whole argument for making the CEG
family the field identifier.

```yaml csd:states
populated: {tag: "proposed:users_list"}
empty:     {tag: "proposed:users_empty", renders: "the no-results card at UsersScreen.kt:203 — reached both by an empty roster and by a search that matched nothing, and the two are not distinguished"}
loading:   {tag: "proposed:users_loading", renders: "the block at :152 — a progress affordance and no sentence"}
error:     {tag: "proposed:users_error", renders: "the errorContainer card at :159-171 — real, visible, and untagged"}
```

Error and empty are already visually distinct (error gets the error container,
empty gets a plain card), so the fix here is tags, not behaviour.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the roster | `GET /v1/users` (paged, filterable) | **CIRISAgent** — `routes/users.py:312` | live on the brain; **absent from the node** — zero `v1/users` route literals in CIRISServer `src/*.rs` on 0.5.217 |
| one account's detail | `GET /v1/users/{user_id}` | CIRISAgent — `routes/users.py:660` | live on the brain |
| change a role / deactivate | `PUT /v1/users/{id}`, `DELETE /v1/users/{id}`, `POST /v1/users/{id}/mint-wa` | CIRISAgent — `:679`, `:958`, `:797` | live and **not driven from this card** (read-only today) |
| the nearest thing the node has | `GET /v1/auth/me`, `GET|POST /v1/auth/api-keys` | CIRISServer — `src/auth/session.rs:1147`, `src/auth/api_keys.rs:319` | live — self-service only, no roster |
| revoke one API key | `DELETE /v1/auth/api-keys/{wa_id}` | CIRISServer `src/auth/api_keys.rs:323` (handler `revoke_api_key`, `:221`) | live, **not called** — only a generated stub (`deleteApiKeyV1AuthApiKeysKeyIdDelete`, `generated-api/.../AuthenticationApi.kt:134`). The card lists no keys, so it has nothing to revoke from; if it ever lists them, this is the row's action |
| revoke a service token | `POST /v1/auth/service-token/revoke` — `{token (≥ 8 chars, hashed before storage), reason, revoked_by}` | CIRISServer `src/auth/api_keys.rs:327` (handler `:276`) | live, **not called and no stub** — an operator act with no surface anywhere in the client |
| **an affiliation's roster** | — | — | **missing everywhere** — this is the card the placement asks for and no repo serves it |
| `listed` opt-in per member | — | CIRISAgent | **missing** — blocks `building` for `user_row_listed` |

CIRISAgent's routes also return **bare** Pydantic models on `/v1/users*` while
`/v1/wa/*`, `/v1/system/*` and `/v1/my-data/*` return `SuccessResponse[T]`. The
generated client unwraps per-operation, so this is not a live defect here; it is
a trap for the next hand-written call. Its tree is dated 2026-08-15 and may be
stale.

## 4. Flow (how)

Sign in on a node with a brain, as an account with roster access; open
Communities and Businesses › People › Users.

```yaml
expect:
  state: populated
  count: {of: "item_user_*", min: 1}
  visible: [input_users_search, "proposed:users_list"]
```

Filter to OAuth accounts: click `btn_users_filters`, then `chip_auth_type_oauth`.

```yaml
expect:
  each: {of: "item_user_*", visible: [chip_auth_type_oauth]}
```

Search for a string nobody matches:

```yaml
expect:
  state: empty
  visible: ["proposed:users_empty"]
```

On a node-only build, where `/v1/users` has no host:

```yaml
expect:
  state: error
  visible: ["proposed:users_error"]
  absent: ["proposed:users_empty"]
```

## 5. QA plan

**Platforms.** All five with a brain. The node build reaches only the fourth
block, and that block is the one that fails today.

**Not tested here.** Role mutation (`PUT /v1/users/{id}`, `mint-wa`) — not driven
from this card, and minting a WA in CI would seat an authority; pagination beyond
page 2; the OAuth avatars, which are third-party fetches.

## 6. Delta — card vs API vs CC

* **The placement is wrong, and CC 2.1 is the reason.** Communities › People
  should hold *the people in an affiliation I belong to*. This card holds *every
  account on this node*, paged, searchable, with email addresses and last-login
  times — which is CC 2.1's "globally enumerable" roster, the thing the `listed`
  default exists to prevent, with no `listed` field on the wire to check.
  **Recommended placement: My things › This node**, beside Account and
  Adapters — it is an operator surface about one node's auth database, and CC
  4.4.3.2.8's distinction (a community gathers by interest, an affiliation by
  necessity) fits neither. Moving it also makes the People tab mean one thing in
  every circle, which is what the Locked Spec's seven-identical-tabs rule buys.
* **And then the real card is missing.** Once Users moves, Communities › People
  has only Contacts, and the affiliation roster has no server anywhere. **Ask
  (CIRISServer):** a per-community roster route honouring CC 2.1 — members who
  have set `listed: public`, never an enumeration — so the tab can hold the card
  its placement promises.
* **A CC-rejected concept rendered as a chip.** CC 4.1.3 rejected `infra:observe`
  and "any `observer:*` capability" with the finding that observation as a
  *granted* capability inverts consent: *"**No scope, deliberately.** Observation
  is the zero state."* This screen draws `OBSERVER` as a role chip beside ADMIN
  and AUTHORITY, and offers `chip_api_role_observer` as a filter — reifying the
  zero state as a grant. **Ask (CIRISClient):** render the absence of a role as
  an absence, not as a chip; **ask (CIRISAgent):** reconcile `APIRole` with CC
  4.4.3.4.3's ladder, or document in one place that `APIRole` is a transport-auth
  concept and not a constitutional standing, so the UI stops presenting it as
  one.
* **Emails on the row.** `oauth_email` is rendered on the list row
  (`UsersScreen.kt:445`), not only in the detail sheet. Under CC 2.1 a roster is
  self- and producer-queryable by default; a searchable list of every account's
  email is the disclosure `listed` is meant to gate. **Ask (CIRISClient):** move
  the email into the detail sheet at minimum, pending the `listed` work above.
