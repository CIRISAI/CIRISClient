# Staged CSD flows — written, load-clean, and each waiting on one named thing

Every file here names its CSD with `csd: CSD-NNN` and loads through
`testing.gate.run_flows.load_flows` — bound to its CSD's `shows:` and `states:`
blocks, no `proposed:` tag anywhere, `requires` stated rather than assumed.
`flow_spec.discover` globs `p.glob("*.yaml")`, not `rglob`, so nothing in this
directory runs on the matrix. That is the whole mechanism.

Until #97 the one reason for staging was that the runner did not navigate. It
does now — before a flow's first step it walks the hop `testing/gate/nav_map.py`
derives for the flow's first `requires: screen:` — so on the 0.5.225 run
(2026-09-29) eight drafts moved up to `testing/flows/`. What holds each
remaining file back is per-file, in the table at the end.

## The 0.5.225 run (2026-09-29)

**Moved to `testing/flows/` (8):** `csd-005-people`, `csd-006-receipt`,
`csd-008-notes-to-self`, `csd-047-network-content`, `csd-057-wallet`,
`csd-068-provision-accord-holder`, `csd-092-share-contact-code`,
`csd-101-household-members`. Each floor that was `unreleased` flipped to
`>=0.5.225` after every tag the flow drives was found in `client/shared/src` by
`testing/test_flows.py`'s rule; each first screen has a hop (or is Contacts,
where sign-in lands). Three drafts were edited on the way so an ordinary run
can go green: `csd-005`'s scan button is gated on there being a camera,
`csd-092`'s close step no longer expects `contacts_list` on a bare node, and
`csd-101`'s first step accepts the roster's empty shape.

**Not moved — floor left `unreleased` (5):** `csd-032`, `csd-036`, `csd-040`,
`csd-045`, `csd-100`. Each names a tag the client builds by interpolation with
an interpolated *head* — `"${tagPrefix}_not_on_this_node"` (`ReadFailureBlock`),
`"sheet_$tagPrefix"` / `"${tagPrefix}_fact_$i"` / `"btn_${tagPrefix}_confirm"`
(`ConfirmSheet`), `"input_${tagPrefix}_delegation_id"` (`OwnerDelegationPicker`).
They are real in 0.5.225 and the flow tag check cannot see them (below), so a
promoted flow naming them goes red at the keyboard. The CSD's §5 line names the
tags.

**Not moved — first screen is flow-only (7):** `csd-033`, `csd-046`, `csd-048`,
`csd-049` (the transport-hub leaves), `csd-069` (AccordCeremony), `csd-081`
(Login), `csd-090` (DutyConferral). `nav_map` derives no hop to these screens;
the runner only waits for one after sign-in lands on Contacts, so each would be
`cannot-start` — red — on every leg. The fix is in the flow's entry: start on a
screen that has a hop and tap into the leaf, as `csd-047` does from
LayerGlobalCommons. `csd-081` is different: the runner signs in before any flow,
so Login is gone; it needs `--no-sign-in` or a sign-out step of its own.

**Not moved — known red upstream (1):** `csd-091-user-chat`. Two released nodes
never key a pair room (CIRISServer#698, `evidence/blocked_upstream.tsv`), so
`a_room_with_history` fails on every leg for a defect the client does not have.

**Not on the list (16 files):** their CSDs are not yet "spec complete and flow
written" — a `proposed:` tag or an `unconfirmed` §3 field still holds the card
at `building` for the checker's reasons, or the flow is incomplete (`csd-082`).

## The blind spot in the flow tag check

`testing/test_flows.py::_client_tag_strings` reads `commonMain` for whole
literals (`"opt_run_with_ai"`) and for `$`-headed prefixes with two segments
(`"age_band_$token"` vouches for `age_band_adult`). It cannot see a tag whose
head is itself interpolated:

| how the client writes it | where | a draft that names the result |
|---|---|---|
| `"${tagPrefix}_not_on_this_node"` | `ReadFailureBlock` | `csd-032`, `csd-036`, `csd-040` |
| `"sheet_$tagPrefix"`, `"${tagPrefix}_fact_$i"`, `"btn_${tagPrefix}_confirm"` / `_cancel` | `ui/primitives/ConfirmSheet.kt` | `csd-045`, `csd-100` |
| `"input_${tagPrefix}_delegation_id"`, `"opt_${tagPrefix}_owner_delegation_$i"` | `SelfReaderOpsSection.kt` | `csd-045` |
| `"${tag}_status"` | `ui/primitives/QrScanAction.kt` | none — `csd-005` deliberately does not assert the desktop sentence |

`testing/test_csd_state_tags.py::source_tags` already resolves a `tagPrefix`
parameter slot to the callers' literals and sees all of these except
`${tagPrefix}_fact_$i`. The fix belongs in `test_flows.py`, not in the flows:
teach `_client_tag_strings` the same rule. Until then a flow naming one of these
tags waits here with its floor left `unreleased`, which is the convention this
directory has always used: a floor states what the grep can prove.

## Promoting one

A flow here is promoted by `git mv` and nothing else. The hop is never written
into the flow: the CSD names the surface and `nav_map` derives the chain
(`CSD.md` §2.0).

Check first, in this order:

1. `python3 -c "from testing.gate import run_flows; run_flows.load_flows(['testing/flows/drafts/<file>'])"`
   loads it, bound to its CSD;
2. every tag it drives passes `testing/test_flows.py::client_carries` — a
   promoted flow is under `test_every_tag_a_seeded_flow_names_exists_in_the_client`;
3. its first `requires: screen:` is in `run_flows.nav_hops(has_agent=False)[0]`
   (a hop exists) or is Contacts — a flow-only screen is `cannot-start`;
4. its CSD's §5 declares the platforms it should be green on;
5. the CSD's `stage:` follows `CSD.md` §1, and is edited by hand, never inferred:
   - `testable` needs the flow's `client:` floor to be no longer `unreleased`
     (any `>=X` / `>X` form) **and** the flow to run on the matrix;
   - `verified` needs that run green on every platform the CSD's §5 declares;
   - `shipped` is the stage whose floor names a published version.

   A green run is evidence for the edit, never the edit itself. A card whose
   flow is written but has not met that bar stays at `building` and says so in
   one line at the top of its §5, so the promotion is a mechanical flip.

## Flows that need a second node: `fixture: two_node`

A draft that names a value only a second node can produce — a contact's key
id, a peer to pick, a message's attestation id — says `fixture: two_node`, and
the runner stands a second `ciris-server` up beside the leg's node before the
first of them runs (`testing/gate/two_node.py`; the `${NAME}` values are in
`testing/flows/README.md`, "Two-node flows"). Every leg passes `--node-binary`,
so a fixture flow costs nothing more than `git mv`.

`csd-005-people`, `csd-006-receipt` and `csd-047-network-content` moved on the
0.5.225 run. One stays:

| file | fixture values it names | why it stays |
|---|---|---|
| `csd-091-user-chat.yaml` | `PEER_KEY_ID` to enter the room from People; `MESSAGE_ATTESTATION_ID` / `MESSAGE_TEXT` for the row | two unconferred v0.5.217 nodes never key the pair room, so no message crosses and `a_room_with_history` fails naming why (CIRISServer#698) |

## What is here

| file | CSD | first screen | why it is still here |
|---|---|---|---|
| `csd-007-files.yaml` | CSD-007 | Files | CSD at `building`: `holds_bytes:sha256:{prefix}` unconfirmed |
| `csd-020-adapter-connectors.yaml` | CSD-020 | Adapters | CSD at `building`: proposed tags and unconfirmed fields; agent-only surface |
| `csd-025-system.yaml` | CSD-025 | System | CSD at `building`: `health:liveness:{version}`, `x_private:queue_depth` proposed |
| `csd-032-network-identity.yaml` | CSD-032 | NetworkIdentity | `federation_id_card_not_on_this_node` is interpolation-built (above); also flow-only first screen |
| `csd-033-network-peers.yaml` | CSD-033 | NetworkPeers | flow-only first screen; floor `>=0.5.225`; enter from the hub tile, then move |
| `csd-036-network-ops.yaml` | CSD-036 | NetworkOps | `netops_not_on_this_node` is interpolation-built (above) |
| `csd-039-data-erasure.yaml` | CSD-039 | DataManagement | CSD at `building`: `consent:{kind}` proposed, several fields unconfirmed |
| `csd-040-storage.yaml` | CSD-040 | Storage | `storage_disk_not_on_this_node` is interpolation-built (above) |
| `csd-045-node-self-standing.yaml` | CSD-045 | NodeSelfStanding | the ConfirmSheet's and the delegation picker's tags are interpolation-built (above) |
| `csd-046-network-trust-graph.yaml` | CSD-046 | NetworkTrustGraph | flow-only first screen; floor `>=0.5.225`; enter from the hub tile, then move |
| `csd-048-network-interfaces.yaml` | CSD-048 | NetworkInterfaces | flow-only first screen; floor `>=0.5.225`; enter from the hub tile, then move |
| `csd-049-network-queue.yaml` | CSD-049 | NetworkQueue | flow-only first screen; floor `>=0.5.225`; enter from the hub tile, then move |
| `csd-053-manage-consent.yaml` | CSD-053 | ManageConsent | CSD at `building`: `x_private:for_key_id`, `x_private:attesting_key_id` proposed and unconfirmed |
| `csd-054-partnership-queue.yaml` | CSD-054 | Consent | CSD at `building`: `consent:{kind}` proposed, `x_private:partnership_signed_by` unconfirmed |
| `csd-066-child-safety.yaml` | CSD-066 | ChildSafety | CSD at `building`: `hard_case:{kind}` proposed |
| `csd-066-child-safety-states.yaml` | CSD-066 | ChildSafety | same; and step `a_refresh_is_never_nothing` has a `do:` entry with no verb, so it does not load |
| `csd-069-accord-ceremony.yaml` | CSD-069 | AccordCeremony | flow-only first screen; floor `>=0.5.224`; enter from Accord, then move |
| `csd-081-login.yaml` | CSD-081 | Login | the runner signs in before any flow; needs `--no-sign-in` or its own sign-out |
| `csd-082-setup-with-ai.yaml` | CSD-082 | Setup | flow incomplete: the Finish step cannot be gated (CSD-082 §5) |
| `csd-083-setup-without-ai.yaml` | CSD-083 | Setup | CSD at `building`: `x_private:backend_endpoint` proposed; needs an unclaimed node |
| `csd-085-claim-node.yaml` | CSD-085 | ClaimNode | CSD at `building`: proposed tags; text fields not drivable |
| `csd-087-verify-agent.yaml` | CSD-087 | VerifyAgent | CSD at `building`: proposed tags; `input_verify_hash` not drivable |
| `csd-090-duty-conferral.yaml` | CSD-090 | DutyConferral | flow-only first screen; floor `>=0.5.224`; enter from the conferring screen, then move |
| `csd-091-user-chat.yaml` | CSD-091 | Contacts → UserChat | known red on released nodes (CIRISServer#698) |
| `csd-100-household.yaml` | CSD-100 | LayerFamily | the ConfirmSheet's tags are interpolation-built (above) |
| `csd-102-communities.yaml` | CSD-102 | LayerLocalCommunity | CSD at `building`: `x_private:affiliations_declared_record` unconfirmed |
| `csd-103-community-roster.yaml` | CSD-103 | CommunityRoster | CSD at `building`: two fields unconfirmed |
| `csd-104-key-verification.yaml` | CSD-104 | NetworkPeerDetail | CSD at `building`: SAS fields proposed and unconfirmed |
| `csd-105-trust-root.yaml` | CSD-105 | TrustRoot | CSD at `building`: `x_private:witnessed_head`, `x_private:seed_fingerprint` proposed and unconfirmed |
