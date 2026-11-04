# CSD-088 — First run (the wizard's order, and what every choice gives and costs)

**CSD**: CSD-088 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: steward 2026-11-04, "make sure the first run wizard choices are clear - with benefit and downside"
**Spec**: CIRISEdge `FSD/PRIVACY_MODES.md` (locked at 0f3a33da; not yet on its main)
**Pairs with**: CSD-082 and CSD-083 (the with-AI and without-AI passes, which keep their own exits), CSD-093 (add this device), CSD-032 (changing a choice later), CSD-095 (held changes)
**Flow**: unwritten; nothing on the PRIVACY step can be driven until server S2 and client C1 exist

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**Every choice in the first run says what it gives and what it costs, before it is
made, and no default is silent about its cost.** CC 4.5.13 "The choice as data" makes
that normative for every item the node puts before a person; CC 5.4.3 keeps shaping's
own obligations. A consent is not a separate kind: it is a choice whose writes are
consent rows, and nothing is `required` except what the node needs to run.

Items follow `manifests/choice_item.cddl`: kinds `choice`, `act`, `entry`, `notice`,
with context, facts, policy, and options whose impact is derived from what they write.
The wizard is the first run of the one queue the node keeps of what awaits this
person's hand (CIRISServer#851, one surface with #855). The queue never blocks.

This card owns the order and the list of choices. CSD-082 and CSD-083 keep what is
different about each pass: how it ends, on which backend. CSD-093 keeps the
add-this-device path.

Nothing here decides a person's posture for them. The node states facts; the
person answers. A choice a person can change later says so.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Setup
flow_only: true
entry: "As CSD-082: Login `btn_local_login` on a first-run node, Startup under the HA addon, or ServerConnection after a node switch"
exit: "As the pass: Login for CSD-082 and CSD-083; the claimed session for CSD-093"
```

### 2.1 The order

| Path | Steps |
|---|---|
| New identity, with AI | `YOU → JOIN_FEDERATION → PRIVACY → AI → COMPLETE` |
| New identity, without AI | `YOU → JOIN_FEDERATION → PRIVACY → COMPLETE` |
| Add this device | `YOU (existing) → the join → PRIVACY after the join` |

The step rail counts only the steps this path will show.

### 2.2 Every choice

Each row states both sides on the option itself, before it is chosen. Copy goes
through `docs/five-year-old-test.md` before translation. The wording here is the
meaning, not the final string.

| Step | Choice | Preselected | Benefit | Downside | Later? |
|---|---|---|---|---|---|
| YOU | New identity or add this device | None; Next waits | A new identity starts clean; adding keeps one person across devices | A new identity is a new person to everyone who knew the old one | No |
| YOU | With or without an AI assistant | As CSD-082 / CSD-083 | Without: no model, no provider. With: an assistant that acts for you | Without: no assistant. With: a provider or local model to set up | Yes |
| YOU | Age band | None; required | The band protects the right people | Declining means the protective `minor` default | CSD-082 |
| YOU | Anonymous identity (held) | Not selectable | Shown so nobody is surprised it exists | "Not ready yet"; first run or never, and it can't be undone | No |
| YOU | Name, password, fed-ID label | As CSD-082 | Yours to sign in with and be known by | The label is seen by people you connect with | CSD-082 |
| YOU | Device name (an `entry`, no options) | Empty | You can tell your devices apart | The approver sees it on add-device | CSD-093 |
| YOU | An existing fed-ID (`btn_federation_associate_existing`, `btn_federation_import_usb`) | Not chosen | Keeps an identity you already have | Needs the key to hand; a new identity is the other path | No |
| YOU | Hardware key (`toggle_secure_2fa`) | Off | The key is held by hardware, not by this device's software | Needs the hardware each time it signs | No: fixed for this key in this client |
| YOU | Minor path (`btn_request_steward`) | Follows the age band | A minor has an adult steward (CC 3.2) | It stops at a steward request; setup does not finish on its own | No |
| JOIN | Announce (`toggle_announce_ownership`) | On, with its cost on screen (CC 4.5.13) | A short ciris:// name instead of a node code, public groups, and geographic communities with a rough-location opt-in | Your binding and delegations go to the roots you trust on the active roster, including ones added later, so you can be found. Never content. Traces are a separate consent. Off: reached only by a contact code or QR you hand over yourself, and you can't take part in public groups. Private rooms, contacts and traces still work | Not yet: no way to undo it until the route exists |
| JOIN | Send traces (`trace_consent_yes`, `trace_consent_no`) | Unanswered; deferred if left, nothing leaves, and it waits in the queue | As `GET /v1/setup/consent-disclosure` (CSD-082 §2) | As the disclosure | Yes, CSD-039 |
| JOIN | Be scored (`toggle_trace_analyze`), only with traces on | Unanswered | As the disclosure | As the disclosure | Yes |
| JOIN | Rough location (`toggle_share_location`), its own item | Unanswered | As the disclosure | The disclosure's `declining.costs` | Yes |
| PRIVACY | Everything on (rung 1) | Home, phone | A message arrives in under a second | A watcher can see when you are active | Yes |
| PRIVACY | Hide when I talk (rung 2) | Server | A watcher can't see when or how much you send to people who also chose this | Each message waits about {gap_s}. Uses data whether you send or not. Calls and files can still be seen | Yes |
| PRIVACY | Always hide when I talk (rung 3) | Listed, disabled: "This needs more of the network to take part first." | No call or file shows a watcher what you are doing | No calls or streams. A 1 MB file takes {file_1mb_s}. People who didn't choose this can't be reached | Yes |
| PRIVACY | Help others connect | On at rung 2, off at rung 3 | Others can reach people through you | Uses your data; routes already handed out can't be recalled. Off: "You can connect through others, but others can't connect through you." | Yes |
| PRIVACY, phone | Wi-Fi and mobile data, under "Hide when I talk" | Selecting that option turns Wi-Fi on and mobile data off; both off returns to "Everything on" | Each network has its own figures, so a contradictory combination cannot be chosen | Hiding on mobile data costs more; Wi-Fi only shows a watcher the phone moved | Yes |
| PRIVACY, phone | "Always hide when I talk", once offered | One setting for the device: both networks on, no network controls | Hidden on every network, so "always" stays true | The option states the mobile data figures | Yes; leaving it for rung 2 keeps both on until changed |
| AI | Agent template (`btn_setup_advanced`) | Ally | As CSD-082 | Chosen once, at setup | No |
| AI | Free AI (`btn_use_free_ai`), or provider and model | As CSD-082 | As CSD-082 | As CSD-082 | Yes, CSD-021 |
| AI | Portal (`btn_portal_connect`) | As CSD-082 | As CSD-082 | As CSD-082 | Not verified |

The PRIVACY step also shows three lists per option, from the node: features,
performance, and privacy (what it hides and what it does not). Numbers are the
node's, for this device's class. The client highlights what differs from the
selected option and computes nothing else.

**Not shown:** helping on a client-only node, on rung 1, or with announce off.
Traces and scoring appear when the node serves them; a disclosure with no `replication` grant has none to ask (`setup_consent_empty`, CSD-083).
**Absent, not greyed:** the anonymous line and helping for a minor or an undeclared
age. The anonymous line is also absent on add-device, an existing install, a remote
client and a serve seat.

Words that never reach the screen: "shaped", "shaped only", "link", "relay", "rung".
Options state numbers, never "fastest" or "a bit slower".

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:privacy_rung
    use: emit
    type: unconfirmed
    example: "standard"
    renders: "one radio per option, each with its benefit, its downside (never empty) and its three lists. An option with `available: false` is disabled and shows its reason line. Selecting previews and writes nothing; Next writes once"
    tag: "proposed:opt_setup_privacy_*"
    blocked_by: "CIRISServer#853"
  - ceg: x_private:privacy_helping
    use: emit
    type: unconfirmed
    example: "true"
    renders: "'Help others connect', with what it gives and costs, only where §2.2 shows it"
    tag: "proposed:toggle_setup_helping"
    blocked_by: "CIRISServer#853"
  - ceg: x_private:privacy_phone_network
    use: emit
    type: unconfirmed
    example: "wifi"
    renders: "one control per network, each with its own figures"
    tag: "proposed:opt_setup_privacy_network_*"
    blocked_by: "CIRISEdge#942"
  - ceg: x_private:anonymous_identity_listing
    use: display-only
    type: unconfirmed
    example: "Not ready yet. Helpers on the network today: 12."
    renders: "a held line on YOU after the age band: title, reason, and 'You can only choose this when you first set up a device, and it can't be undone.' Without a count it shows the line without one. Selecting it is not possible"
    tag: "proposed:txt_setup_anonymous_identity"
    blocked_by: "CIRISServer#854"
```

```yaml csd:states
loading: {tag: "proposed:setup_privacy_loading", renders: "'Loading…', with no preview and no invented figure"}
empty: {tag: "proposed:setup_privacy_only_option", renders: "the node served one available option: it is stated with its cost, and the disabled options keep their reason lines"}
error: {tag: "proposed:setup_privacy_error", renders: "'Your privacy settings couldn't be loaded; your device stays at its default.' No fallback copy, no figures. Next works, and the unanswered choice waits for later"}
populated: {tag: "proposed:card_setup_privacy", renders: "the options, their lists, and helping where shown"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the items (`choice-item`), the same object as the queue's | `GET /v1/setup/privacy-choices` with the Join answer as a parameter, `{"data": …}` as `consent-disclosure`, evaluated for class, locale and that answer, so "Everything on" does not overclaim with announce off | CIRISServer | **not built** (CIRISServer#853, S2, with client 0.5.239) |
| the same, with AI | a thin read route on the agent over `ciris_server.privacy_choices()`, as `routes/setup/providers.py:85` | CIRISAgent | **not built**. The agent has no write route |
| the choice | `POST /v1/setup/privacy-choices` at the **node URL**: loopback only before the claim, owner session after | CIRISServer | **not built** (S2) |
| the choice surviving the no-AI replacement, a crash or a phone kill | a hold file under the home, read at compose; an owner-signed `config:*` row supersedes it after the claim | CIRISServer | **not built** (S3) |
| the settings | `net.shaping_opt_in`, `net.shaping_opt_in_cellular`, `net.shaped_only`, `net.relay_offering`. The rung is derived, never stored: 1 both opt-ins off; 2 at least one on, `shaped_only` off; 3 `shaped_only` and both on. A write of `shaped_only` with an opt-in off is refused | CIRISServer | **not built** (S1, 0.5.245) |
| a phone's facts | `shaping_set_device_state`, before the table is read, or a phone sees the wrong class | CIRISClient · CIRISEdge | **not built** (C2, E8) |
| the anonymous listing and its count | the `choice` item with one option, `available: false` | CIRISServer | **not built** (CIRISServer#854, S6) |
| an unanswered choice, later | the `self` queue (CIRISServer#851, not built); until then a notice on CSD-032's surface | CIRISServer | **not built** (S4) |

**The client computes nothing.** Numbers are raw values from the node, formatted
for locale. A word it does not know renders as the option's disabled state.

## 4. Flow (how)

1. **PRIVACY opens:** the client reports device facts (phones), then reads the
   table, passing the Join answer. The preselected rung is the class default, with its cost on screen.
2. **Selecting** previews from the served table and writes nothing.
3. **Next** works on the preselected rung without a touch, writes once, and applies
   at once: an item that writes nothing outward applies at once, and the owner's
   signed row supersedes it after the claim (CC 4.5.13). The screen says links opened earlier are not undone.
4. **Back** re-reads.
5. **Remote client before the claim:** read-only, the node's figures labelled as the
   node's. The choice arrives as a pending item after the claim.
6. **Add this device:** PRIVACY comes after the join, because the device cannot know
   the age before it. Until then it stays at its class default. The other device's
   rung is shown only as information.
7. **On a phone,** Wi-Fi and cellular are separate choices, each with its own figures.
   A phone pays only while the app is awake.

**Existing installs never see this step.** Nothing flips on upgrade. An operator
notice states the cost and the choice. The canonical stays off until measured, on
the steward's go, and nothing may claim that it shapes.

## 5. QA plan

Nothing is drivable yet. When S2 and C1 exist:

1. Every option on the table carries a non-empty benefit and downside, the default
   included. A check on the served table, not a screenshot.
2. Selecting writes nothing; Next writes once; a crash before the claim keeps the
   rung (the no-AI replacement is the case that matters).
3. A LAN peer's write before the claim is refused.
4. A minor sees no anonymous line and no helping, absent from the tree.
5. Each of the §6.9 states in `FSD/PRIVACY_MODES.md` has a test.
6. No internal word appears in `en.json`; other languages follow the glossary.

## 6. Delta — card vs API vs CC

| gap | who | note |
|---|---|---|
| Option names are proposed | CIRISClient C6 | settled before translation, or 29 languages inherit a rename |
| Portal pairing later | CIRISClient | it runs over `/v1/setup/connect-node`; a later path is not verified |
| A room of more than eight direct members | CIRISEdge Q16 | open; "Connected at once" may need a rooms line |
| Rooms over the onion | CIRISConstitution#202 | nothing user-facing mentions them until ruled and working |

**Open and not blocking:** rooms over the onion (CIRISConstitution#202), whether
scoped links count within the eight, and stable homing. No screen mentions any of
them.
