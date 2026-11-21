# CSD-105 — This node's trust root (the Accord card's detail: posture, adopt a seed, un-trust)

**CSD**: CSD-105 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: split from CSD-067
§3.1 / §4. The Accord card answers *what the root
is*; this leaf answers *whether THIS node trusts it* and carries the two re-root
levers. It is its own `Screen`, so the route checker keys it by its own routes and
CSD-067 could not cite them for it (CSD.md §4.1: a citation counts on the screen
or a sibling, and a leaf that renders a different composable is neither)
**Flow**: `testing/flows/drafts/csd-105-trust-root.yaml` — draft, `client: "unreleased"`
**Sibling leaf**: CSD-108 (Steward purge), another leaf of Accord. This card acts
on THIS node's acceptance of a root. CSD-108 acts on the canonical's corpus,
under that root's cosigned `takedown_notice`, and its tombstones replicate. Only
the loopback-refusal rendering is shared (`purge_loopback_only` follows
`trust_root_loopback_only`).

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Code gaps.** Where this card and the client code disagree today, each proven against the tree by `packaging/check_csd_code_gaps.py`. Closing one means deleting its entry in the same commit.

```yaml csd:code_gaps
- id: charter-quorum
  card: 'the verdict field is signer_count (a clean break, no alias)'
  code: 'TrustRoot.kt reads charter_quorum'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
  open_while: 'charter_quorum'
  blocked_on: 'persist v62 / server 0.6'
- id: bake-not-adopted
  card: 'posture has no bake_not_adopted on v62.0; the arms carry {leg, detail}'
  code: 'TrustRoot.kt still models bake_not_adopted'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
  open_while: 'bake_not_adopted'
  blocked_on: 'O1'
- id: standing-stalled
  card: 'Stalled (revocations leave too few active founders) is its own standing, neither Rooted nor NotRooted'
  code: 'GenesisState has no stalled arm'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
  open_until: 'STALLED'
  model: root.standing.Stalled
  blocked_on: 'O1'
- id: root-head-untyped
  card: 'the root head is none, witnessed and fresh, stale, or frozen (two heads at one size)'
  code: 'TrustRoot.kt models no head freshness'
  where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
  open_until: '(?i)witness'
  model: root.head.none root.head.witnessed_fresh root.head.stale root.head.frozen
  blocked_on: 'O1, O5 (SCITT)'
```

**Model.** What this card draws and offers, cited from CIRISConstitution's formal model (pinned in `packaging/model/`) and held to it by `packaging/check_csd_model.py`: a modelled state the card leaves out, or a signer, policy or confirm that differs, fails CI.

```yaml csd:model
- var: root.standing
  states: 'NotRooted Stalled Rooted'
  code: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
  code.NotRooted: 'PRE_GENESIS'
  code.Rooted: 'ENTRENCHED'
- var: root.head
  states: 'none witnessed_fresh stale frozen'
  code: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/TrustRoot.kt
```

## 1. Mission (why)

**A person on the node's own machine can see whether this node accepts a
trust root (on v62: the root identity reference whose community the genesis
holders founded; on 0.5.x the accord family), whether persist finds that root sound — leg by
leg — and can adopt a different portable seed or withdraw the node's acceptance,
each behind a confirm that says which node, what changes, and who signs.**
Serves **Core Identity** and **Integrity**.

CC 3.2 makes both levers a MUST, not a feature: *"A conformant consumer MUST be
able to re-root: untrust the canonical group, pin a different … community
instead or in addition, or run with none"* (`part_3_the_namespace.md:637`), and
T3 makes un-trust *one row* — *"remove the acceptance edge and every downstream
gate … fails closed emergently"* (`:714`). `DELETE /v1/trust-root/{id}` is that
row; `POST /v1/trust-root/import` is the "instead or in addition". A client that
runs the genesis ceremony and cannot adopt or revoke a root has built the
walled garden CC 3.2 names as the failure.

**Two facts the screen refuses to blur.** Installing a seed's records makes a
root KNOWN; writing this node's `trust:accepts` edge makes it TRUSTED — the
server reports the two acts apart (`src/trust_root_api.rs:281-291`) and so does
the screen. And the loopback refusal is a fact about *where this device is*,
never about *what this node trusts*: off the node's machine the answer is a
sentence, not an empty list.

### 1.1 On v62 (audit of 2026-11-21)

**This edition begins a new mesh.** Nothing of the earlier mesh carries: its
roots, bundles (v1–v4), holders A1/B1/C1, canonical records and family rows give
no standing here. A node upgrading to it deletes its earlier store and starts
before its genesis, and an earlier-form bundle offered to it is refused as
`earlier_form_object` (CEREMONY intro and §4; CC 2.6.1.12; persist
`src/store/edition_upgrade.rs:257` `delete_earlier_store`;
`edition2/genesis.rs:1745`).

The two levers (adopt, un-trust) and the four failure blocks still hold. What
changes:

* **Import is statements plus an anchor**, not a `GenesisBundle` JSON: persist
  `Engine::put_genesis_bundle(statements, Some(anchor))`. The anchor is the
  holders' thumbprints until the genesis's object id is published, then the
  **bundle object id** (`bundle_object_id`, CC 5.3.4: "the genesis's object id
  is published once step 4 is signed"; CEREMONY step 5; Verify
  `genesis.rs:126-140`). A node anchored by object id holds any other genesis
  void. The out-of-band compare is of the object id, before import; CIRISServer#404's
  ask becomes "show the object id and the anchor before import".
* **Refusals are Verify's tokens:** `genesis_bundle_malformed` (with reasons
  such as `recovery_commitment_shared`, `scope_missing_infra`),
  `genesis_bundle_not_anchored`, `genesis_bundle_count_unmet`,
  `genesis_bundle_row_invalid` (details such as `seat_dial_hints_missing`),
  `genesis_bundle_row_unplaced`, `genesis_holder_custody`, and
  `earlier_form_object` for any v1–v4 bundle (Verify `genesis.rs:540-555`).
* **"Held short" is a state, not a failure.** Voices may arrive one at a time;
  "imported, waiting for more voices" is named and neutral.
* **The posture's four arms stand**, each with `{leg, detail}`, `leg ∈ {anchor,
  canonical}` (persist `federation/genesis/posture.rs:233-250`). There is **no**
  `bake_not_adopted` and no bake on v62.0. Details to render (not parse): "no
  `ceg-genesis-bundle/2` is held" (before the genesis); "a fork of the genesis …
  a reader roots on neither" (`PreGenesis{leg: anchor}`, `:430-433`), shown as
  "this node roots on neither; re-anchor by the object id" (CEREMONY §5); "held
  genesis bundles no longer read Valid" (divergent).
* **The verdict's `charter_quorum` is now `signer_count`** (same members; a clean
  break with no alias, persist `federation/trust_root.rs:596-597`). Its wording is
  "a strict majority of the standing accord roster (2·yes > N; N from the holders
  of the bundle in force)", with the numbers as the node reports them.
* **What the confirm names.** bundle/2 has no `genesis-charter` attestation. Fact
  2 of the adopt confirm is the bundle's `family_key_id`, its holders (count N),
  its seats and its object id (Verify `genesis.rs:1-20`).
* **The seats' dial hints** are the cold-start seed: each seat's binding carries
  signed, ordered `dial_hints` (at most 4, at least one `tcp`; a host is a DNS
  name or an IP literal), signed by the seat's own custody. Once a link is up
  the seat's live signed route row supersedes them (CC part_5:738; CC 2.6.8;
  refusals `seat_dial_hints_missing`, `dial_hint_malformed`).
* **Witnesses.** The SCITT model replaces witnesses with transparency services.
  The genesis charter is witnessed-off with no witnessing member (Eric's
  ruling); a later charter adding services is a later bundle at a strict
  majority. The field shape is **OPEN — CIRISConstitution rc22 (O5)**.
* **One acceptance edge.** `trust:accepts:v1` names the root's held head (T4a);
  the pre-v53 legacy edge is gone (persist `edition2/fixture_trust_root.rs:14`).
* **The halt's reach.** Only a node holding a live acceptance edge is in the
  halt's reach, and un-trust stops the next halt, not one already latched (CC
  4.2.1; CC 4.2.6, second residual).
* **Routes.** `trust-root`, `/import`, `DELETE /{id}` and `/bundle` are kept and
  re-implemented; their shapes are 0.6: pending (CIRISServer, O1). What
  `GET /v1/trust-root` lists first is **OPEN — CIRISServer (O6)**.

## 2. Surface (what)

```yaml csd:surface
surface: null                     # NOT a NavSurface — a leaf of the Accord card
flow_only: true                   # no sidebar row reaches it
screen: TrustRoot                 # `object TrustRoot : Screen()` — CIRISApp.kt
entry: Accord's `btn_accord_open_trust_root` (`AccordTrustRootEntry.kt`; `onOpenTrustRoot`, CIRISApp.kt) — Everyone › Safety › Accord, then the row
exit: back to `Screen.Accord`; `screenToSurface` keeps `NavSurface.Accord` lit (`ScreenToSurfaceTest.theStatedConventionStillHolds`)
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:genesis_posture
    use: display-only
    type: "enum[entrenched,pre_genesis,divergent,unreadable,unknown]"
    example: "entrenched"
    renders: "the posture card — ENTRENCHED is the one green arm; PRE_GENESIS names the missing leg, except (0.5.x only; v62 has no bake and no `bake_not_adopted`, §1.1) when persist v53 says the leg's newer bake was not adopted (`reason.kind: bake_not_adopted`): with `held_root_in_force` it reads 'Newer root not adopted: the previous root stays in force ({leg} leg)', never 'not rooted', and the node's banner (persist's ROOT NOT ADOPTED) rides beneath as given; DIVERGENT is danger; UNREADABLE is the error treatment; a token this app has not heard of is shown verbatim and never green"
    tag: txt_trust_posture_state
  - ceg: x_private:node_accepts_root
    use: display-only
    type: bool
    example: true
    renders: "'Accepted by this node — yes' from verdict.edge_exists, never from the wire's `accepted` (CIRISServer#681)"
    tag: "row_trust_root_accepted_${root}"
  - ceg: x_private:root_valid
    use: display-only
    type: bool
    example: true
    renders: "persist's trust_root_valid gate for this root; unknown when the root could not be evaluated"
    tag: "row_trust_root_valid_${root}"
  - ceg: x_private:charter_legs
    use: display-only
    type: string
    example: "self-declares this root: yes · carries a recovery commitment: yes"
    renders: "the two charter legs of CC 3.2 T3 (root_self_declares, charter_has_recovery), so a person un-trusting on a failed verdict sees which leg failed"
    tag: "row_trust_root_charter_${root}"
  - ceg: x_private:charter_quorum
    use: display-only
    type: string
    example: "2 of 2 required (roster 3)"
    renders: "the count over the seated holders — met in ink, short in danger. On v62 the verdict field is `signer_count` (was `charter_quorum`, a clean break, §1.1), read as a strict majority of the standing roster with m and N from the node"
    tag: "row_trust_root_quorum_${root}"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: yubikey_5_fips}
    use: display-only
    type: "list[string]"
    example: ["ExternalSecureElement"]
    renders: "one row per seated holder: the class Layer A found, Layer A / Layer B pass·fail·unchecked, and the refusal sentence when there is one"
    tag: "row_trust_root_holder_${root}_${holder}"
  - ceg: x_private:drill_band
    use: display-only
    type: "enum[green,yellow,red]"
    example: "green"
    renders: "the last drill, banded 0–90 · 90–180 · 180+/never (CC 3.2 T4) — a signal, so no band is ever drawn in the danger tone"
    tag: "row_trust_root_drill_${root}"
  - ceg: "accord:halt_status"
    use: display-only
    type: "enum[halted,not_halted,unknown]"
    example: "not_halted"
    renders: "whether the halt latch is set on this root, as persist reports it"
    tag: "row_trust_root_halt_${root}"
  - ceg: x_private:bounded_until
    use: display-only
    type: string
    example: "2026-12-01T00:00:00Z"
    renders: "when this verdict can first stop holding on time alone, or 'nothing time-bounded'"
    tag: "row_trust_root_bounded_${root}"
  - ceg: x_private:import_installed
    use: display-only
    type: bool
    example: true
    renders: "'Installed — yes': the seed's records were written (the root is KNOWN)"
    tag: txt_trust_root_import_installed
  - ceg: x_private:import_accepted
    use: display-only
    type: bool
    example: true
    renders: "'Accepted — yes': this node's trust:accepts edge was written (the root is TRUSTED); installed-but-not-accepted is its own sentence"
    tag: txt_trust_root_import_accepted
  - ceg: x_private:untrust_withdrawn
    use: display-only
    type: string
    example: "humanity-accord"
    renders: "which acceptance was withdrawn, or that there was none to withdraw; records retained; whether the node is still entrenched"
    tag: txt_trust_root_untrust_withdrawn
  - ceg: x_private:witnessed_head
    use: display-only
    type: unconfirmed
    blocked_by: CIRISServer#693
    example: "head 9f3a… · no transparency services at genesis — the genesis charter is witnessed-off; a later bundle, at a strict majority of the standing roster, may add them (O5)"
    renders: "the root's current lineage head and the witness quorum over it — what CC 3.2 T4a says a consumer MUST hold before attaching; no route serves it"
    tag: "proposed:row_trust_root_head_${root}"
  - ceg: x_private:seed_fingerprint
    use: display-only
    type: unconfirmed
    blocked_by: CIRISServer#404
    example: "3f9a0c1d2e4b5a67"
    renders: "the seed's fingerprint BEFORE it is installed, for the out-of-band comparison CC 3.2 T5 calls a first-class step; import installs and accepts in one call and previews nothing. On v62 the value is the bundle's object id (before its publication, the holders' thumbprints), §1.1"
    tag: "proposed:txt_trust_root_import_fingerprint"
```

**`x_private:` throughout, on purpose.** The values this screen draws are
persist's *verdict* about a root and the node's *posture* — `TrustRootVerdict`
and `GenesisPosture` (CIRISPersist v48.0.0 `federation/trust_root.rs:374`,
`genesis/posture.rs:255`) — not CEG rows. The one CEG object underneath, the
node's own `delegates_to(node → root)` bearing `trust:accepts:v1` (CC 3.1.1),
is what `DELETE` removes and `import` writes; the screen shows its *existence*
(`edge_exists`) and never the row. `accord:halt_status` and
`hardware_custody:{platform}` bind the same way CSD-067 binds them.

```yaml csd:states
populated: {tag: card_trust_posture, renders: "the posture card, then one card per root the node lists (on 0.5.x humanity-accord is always first, trust_root_api.rs:156-157; what lists first on v62 is OPEN, O6), then the adopt-a-seed card"}
empty:     {tag: trust_root_empty, renders: "No roots listed — reachable only if the node answers with an empty list, which it never does today"}
loading:   {tag: trust_root_loading, renders: "the StateBlock spinner while GET /v1/trust-root is in flight; nothing below it prints its empty sentence"}
error:     {tag: trust_root_error, renders: "'Could not read this node's trust root' with the node's detail — and NOT trust_root_loopback_only, which is its own block: a fact about where this device is"}
```

**Four blocks that are not the same block.** `trust_root_loading`,
`trust_root_empty`, `trust_root_error` and `trust_root_loopback_only` are four
tags on four renderings (`TrustRootFailureBlock`), plus `trust_root_not_on_this_node`
for a node that predates the routes (404, no `reason_id`). The flow's second
leg asserts the loopback block *and the absence of* `card_trust_posture` and
`trust_root_empty`, because a phone rendering "no roots" for a node that trusts
the accord is the precise lie CIRISServer#652 exists to prevent.

### 2.1 The two confirms, three facts each

* **Adopt** (`sheet_trust_root_import`): *which node* — this node, by URL;
  *what changes* — the root the seed's charter names, read from the
  `genesis-charter` attestation the way the server reads it
  (`mesh_genesis::charter_root_key_id`; on v62 there is no such attestation and
  the fact is the bundle's `family_key_id`, holders, seats and object id, §1.1); *who signs* — this node writes its own
  `trust:accepts` edge, the app holds no keys. Plus a `note`
  (`trust_root_import_note`), which is not a fourth fact: the node offers no
  preview of a seed before installing it, so the T5 out-of-band comparison
  cannot happen here (CIRISServer#404), and the sheet says so instead of
  pretending to a check.
* **Un-trust** (`sheet_trust_root_untrust`, destructive): *which root*; *what
  changes* — when it is the last accepted root, that every root-requiring gate
  fails closed and no `trace:*` row is served (`untrustConsequence`, conservative:
  a root whose acceptance is unknown does not count as "another remains"); *who
  signs* — this node withdraws its own edge; the root's records are kept.

## 3. Contracts (who)

Verified against ciris-server 0.5.217, `src/trust_root_api.rs`, and
CIRISPersist `federation/trust_root.rs` at the pinned version. **To re-verify
against server 0.6 and persist v62 once the routes exist (0.6: pending,
CIRISServer, O1); §1.1 lists what changes.**

| value | endpoint | owner | state |
|---|---|---|---|
| which roots this node accepts, and its genesis posture | `GET /v1/trust-root` | CIRISServer `src/trust_root_api.rs:415` (handler `:83`) | **live on this node's own machine, called** — `CIRISApiClient.getTrustRoots` (node URL) from `TrustRootViewModel.refresh` (`:112`). Body `{posture, banner, entrenched, roots[{root_key_id, root_kind, accepted, verdict}]}` (`:55-80`), modelled field for field by `TrustRootListingDto` / `TrustRootEntryDto`. **Remote reach** `blocked_by: CIRISServer#652` — the router is loopback-layered (`:422-424`); off the machine the screen renders `trust_root_loopback_only` |
| the verdict, leg by leg | `roots[].verdict` — persist's `TrustRootVerdict` verbatim (`:76-79`) | CIRISPersist `federation/trust_root.rs:374-420` | **read whole**: `edge_exists`, `root_self_declares`, `charter_has_recovery`, `last_drill_at`, `drill_freshness`, `halt_latched`, `valid`, `root_kind`, `charter_quorum{distinct_holders, required, roster_size}` (`signer_count` on v62, §1.1), `holders_hardware[]`, `holders_hardware_attested`, `bounded_until`, `error` → `trustRootView`, pinned by `TrustRootTest.theVerdictIsReadWhole`. The two charter legs are the T3 legs and have their own row |
| adopt a portable seed on this node | `POST /v1/trust-root/import` `{bundle, allegiance_from?}` | `src/trust_root_api.rs:416` (handler `:227`) | **On v62: statements + an anchor, Verify's refusal tokens, "held short" as a state (§1.1); wire shape 0.6: pending (CIRISServer).** On 0.5.x **live on this node's own machine, called** — `importTrustRoot`; parsed locally first (`TrustRootViewModel.prepareImport`: not a JSON object → `trust_root_import_not_json`, nothing sent), then the ConfirmSheet, then the send. Response `{installed, accepted, posture, entrenched, banner}` (`:215-226`) → `txt_trust_root_import_installed` / `_accepted`, and when installed and not accepted the named state `trust_root_import_not_yet_accepted` — "Imported, not yet accepted" with why (the node does not hold the root's genesis records yet and retries at the next start or import, or the acceptance could not be written) and the node's banner as small print; never a success. From 0.5.220 (CIRISServer#726) a DEFERRED acceptance reports `accepted: false` here. Pinned: `TrustRootTest.anImportInstalledButNotAcceptedIsNeverASuccess`. Refusals by id: `trust_root.bad_request`, `.bad_bundle` (not a `GenesisBundle` — CC 3.2 T5: *a bare record list is not a seed and MUST fail to parse*), `.bundle_refused` (does not verify; nothing changed), `.install_failed`; each has its own sentence (`trustRootRefusalKey`). **0.5.220** (persist v53, CC 3.2 T4a; `src/trust_root_api.rs`): an old bundle whose trust rows carry no job labels is refused **422 `trust_root.bundle_unlabelled`** before anything installs — on v53 it would install as no charter at all; before 0.5.220 the same bundle answered `200 installed: true, accepted: false`. Rendered as `trust_root_import_bundle_unlabelled`: the title "This seed is from before the final genesis. Nothing was installed." and, in place of the node's English, the client's guidance (`trustRootRefusalBodyKey`): what an unlabelled bundle is, and that a version 3 seed from the final genesis is what to import. The node's own words go beneath the guidance as small print: from CIRISServer#726 a labelled charter with an unlabelled GRANT is the same refusal and that detail names the row. Pinned: `TrustRootTest.anUnlabelledBundleIsANamedRefusalWithGuidanceNotTheNodesRawText`, `TrustRootTest.aLabelledCharterWithAnUnlabelledGrantIsTheSameRefusalAndItsDetailNamesTheRow`. `allegiance_from` (CIRISServer#632 / CIRISEdge#671) is offered as an optional field. **Remote reach** `blocked_by: CIRISServer#652` |
| the bundle this node runs on (on v62: the route serving the bundle of this form and its object id, with the holders' thumbprints as the default anchor, CEREMONY §1; `community` is not in bundle/2; shape 0.6: pending, CIRISServer) | `GET /v1/trust-root/bundle` → `{bundle, community, bundle_fingerprint, charter_root_key_id, served_by}` (`community` null before a v3 bundle) | CIRISServer#726 (`src/trust_root_api.rs::serve_bundle` @ `4da726e8`, PUBLIC — no loopback gate, no session; headed for 0.5.220) | **called** — `CIRISApiClient.getTrustRootBundle` from `TrustRootViewModel.refresh`, no bearer; `card_trust_root_served_bundle` shows `txt_trust_root_bundle_fingerprint` and `txt_trust_root_bundle_charter_root` for the out-of-band compare. A node without the route answers a bare 404 and the card is simply absent (never a failed listing). A node not entrenched on the bundle it carries answers `409 trust_root.bundle_not_in_force` (CIRISServer#726: a bake it did not adopt must not be advertised as its root); that is the named state `trust_root_bundle_not_in_force`, "This node isn't running on its seed yet, so it has no seed to show for comparison." — neutral tone, never an error (`ServedBundleRead.NotInForce`; `TrustRootTest.aNodeNotOnItsBundleIsANamedStateAndAnOlderNodeShowsNoCard`). Pinned: `TrustRootBundleWireTest`, `TrustRootTest.theServedBundleShowsItsFingerprintAndCharterRoot` |
| un-trust a root | `DELETE /v1/trust-root/{root_key_id}` | `src/trust_root_api.rs:417-420` (handler `:360`) | **live on this node's own machine, called** — `untrustRoot` behind the destructive ConfirmSheet. Response `{root_key_id, withdrawn, records_retained, entrenched, banner}` (`:388-396`) → `txt_trust_root_untrust_{withdrawn,records_retained,entrenched}`; refusals `trust_root.no_root`, `.withdraw_failed` by id. From CIRISServer#726 the delete withdraws EVERY live acceptance of the root (an upgraded node held its pre-v53 edge and the labelled one; on v62 there is one acceptance edge, §1.1); "Acceptance withdrawn" is exactly true, and the copy claims nothing about the owner's acceptance. **Remote reach** `blocked_by: CIRISServer#652` |
| this node's own acceptance, as the wire states it | `roots[].accepted` | CIRISServer | **wrong on the wire, deliberately unread** — `list_roots` fills it from `verdict.user_accepts` (`:106-109`), a field `TrustRootVerdict` does not have; it is `false` for every root including the one the node is entrenched under. CIRISServer#681. The screen reads `verdict.edge_exists` (`TrustRootTest.acceptanceIsReadFromEdgeExistsNotTheBrokenAcceptedField`) |
| the root's kind | `roots[].root_kind` | CIRISServer / CIRISPersist | `Family` / `Key` on the wire (the enum has no `rename_all`), lowercase in the handler's doc (`:71-72`) — compared case-insensitively, shown verbatim when unknown. CIRISServer#681 |
| the lineage head and its witness quorum | **missing** — no route serves a root's current head or the cosignatures over it | CIRISServer | **blocks** `row_trust_root_head_<root>`: CC 3.2 T4a / T6 make holding a witnessed, fresh head a MUST *before attaching*, and the acceptance edge MUST carry `attached_head_digest`; `import_root` writes the edge with neither. `blocked_by: CIRISServer#693` (the lineage-head cosign route, rc6) |
| a seed's fingerprint before it is installed | **missing** — `import_root` installs and accepts in one call; `ImportResponse` carries no `root_key_id` or `fingerprint` | CIRISServer | **blocks** the T5 out-of-band step here; the sheet's `trust_root_import_note` states the limit. `blocked_by: CIRISServer#404` (the comment on that issue is this ask) |
| who may call these | `require_loopback` and nothing else (`:422-424`) | CIRISServer | **weaker than `/v1/self/identity`** for "the most consequential local act there is" (the module's own words, `:26-29`): no owner session, no Origin check. CIRISServer#404's fix. The client already sends the owner bearer (`token`), so an owner gate lands without a client change |

**The loopback refusal is typed as a place, not a verdict.** `require_loopback`
answers a bare 403 with a text body and no `reason_id` (`src/auth/loopback.rs`);
`trustRootFailure` maps a 403 with no id — or `trust_root.loopback_required`, the
id CIRISServer#652 asks for — to `TrustRootFailure.LoopbackOnly`, every other
refusal to `Refused(reasonId, detail)`, and a 404 with no id to
`NotOnThisNode`. Pinned by `TrustRootTest.theLoopbackRefusalIsItsOwnFailure`.

**What CC 3.2 says this screen still cannot do.** T4a: *"A consumer that
attaches a root … MUST hold that root's current lineage head together with a
witness quorum of cosignatures over it"* — nothing on the wire carries a head,
so adopting here is attaching on the bundle's self-verification alone, which
T5 scopes to *tamper-evident, never authentic*. Without CIRISServer#693
and #404, the confirm's note is the whole of the mitigation, and this CSD says so
rather than moving the field to `proposed:` where it would read as a client
gap.

## 4. Flow (how)

Real tags only. The runner cannot walk the hop (flow-only, CSD-069 §2.3's
open ask); the entry is asserted by CSD-067 §4 (`btn_accord_open_trust_root`)
and the draft flow states the precondition rather than encoding it.

On the node's own machine, against an entrenched node:

```yaml
expect:
  visible: [screen_trust_root, card_trust_posture, txt_trust_posture_state,
            card_trust_root_humanity_accord, row_trust_root_accepted_humanity_accord,
            row_trust_root_charter_humanity_accord,
            card_trust_root_import, input_trust_root_seed, btn_trust_root_import_review]
  absent:  [trust_root_loopback_only, trust_root_error, trust_root_not_on_this_node]
```

From any other device the same step renders the refusal, never an empty list
(CIRISServer#652):

```yaml
expect:
  visible: [trust_root_loopback_only]
  absent:  [card_trust_posture, trust_root_empty]
```

Type `not json` into `input_trust_root_seed` and press
`btn_trust_root_import_review` — refused locally, nothing sent, no confirm:

```yaml
expect:
  visible: [trust_root_import_not_json]
  absent:  [sheet_trust_root_import]
```

Paste a seed-shaped object whose charter names `mesh-test`, review, and cancel:

```yaml
expect:
  visible: [sheet_trust_root_import, trust_root_import_fact_1,
            trust_root_import_fact_2, trust_root_import_fact_3, trust_root_import_note]
  text:
    trust_root_import_fact_2: "mesh-test"
```

Press `btn_trust_root_untrust_humanity_accord` on a node whose only accepted
root is the accord, then cancel:

```yaml
expect:
  visible: [sheet_trust_root_untrust, trust_root_untrust_fact_1,
            trust_root_untrust_fact_2, trust_root_untrust_fact_3]
  text:
    trust_root_untrust_fact_1: "humanity-accord"
    trust_root_untrust_fact_2: "trace:*"
```

*Cannot yet assert on a runner:* the import and un-trust confirms end to end.
Adopting needs a signed seed, and un-trusting the only root takes the node's
`trace:*` plane down; both are pinned by `TrustRootTest` and the draft flow walks
the confirms against a scratch node and cancels them.

## 5. QA plan

**Platforms.** Desktop in practice — every route is loopback-only, and the
client is co-located with its node only there. The screen composes on all five
and renders the loopback refusal on the other four, which is the assertion.

**Acceptance — functional**
1. "Could not read", "not on this machine", "no such route" and "no roots" are
   four renderings.
2. Acceptance is read from the verdict's edge, not the wire's `accepted`.
3. Both levers open a three-fact confirm and send nothing until it is confirmed.
4. Installed and accepted are reported apart.

**Not tested here.**
* **The confirms' effects.** A real import needs a signed bundle; a real
  un-trust of the only root drops the node's `trace:*` plane. Both are pinned
  at the model (`TrustRootTest`) and not exercised on a runner.
* **The verdict.** Whether `valid` is right is persist's question; the screen
  draws what it says.
* **Acceptance 3's fingerprint leg** — there is no fingerprint to compare
  (§3, CIRISServer#404), and no witnessed head (CIRISServer#693).
