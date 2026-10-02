# CSD-105 — This node's trust root (the Accord card's detail: posture, adopt a seed, un-trust)

**CSD**: CSD-105 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: split from CSD-067
§3.1 / §4 at the accord review (2026-09-28). The Accord card answers *what the root
is*; this leaf answers *whether THIS node trusts it* and carries the two re-root
levers. It is its own `Screen`, so the route checker keys it by its own routes and
CSD-067 could not cite them for it (CSD.md §4.1: a citation counts on the screen
or a sibling, and a leaf that renders a different composable is neither)
**Flow**: `testing/flows/drafts/csd-105-trust-root.yaml` — draft, `client: "unreleased"`

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person on the node's own machine can see whether this node accepts the
accord family as its trust root, whether persist finds that root sound — leg by
leg — and can adopt a different portable seed or withdraw the node's acceptance,
each behind a confirm that says which node, what changes, and who signs.**
Serves **Core Identity** and **Integrity**.

CC 3.2 makes both levers a MUST, not a feature: *"A conformant consumer MUST be
able to re-root: untrust the canonical group, pin a different … community
instead or in addition, or run with none"* (`part_3_the_namespace.md:637`), and
T3 makes un-trust *one row* — *"remove the acceptance edge and every downstream
gate … fails closed emergently"* (`:714`). `DELETE /v1/trust-root/{id}` is that
row; `POST /v1/trust-root/import` is the "instead or in addition". A client that
mints seeds (CSD-067's re-mint) and cannot adopt or revoke one has built the
walled garden CC 3.2 names as the failure.

**Two facts the screen refuses to blur.** Installing a seed's records makes a
root KNOWN; writing this node's `trust:accepts` edge makes it TRUSTED — the
server reports the two acts apart (`src/trust_root_api.rs:281-291`) and so does
the screen. And the loopback refusal is a fact about *where this device is*,
never about *what this node trusts*: off the node's machine the answer is a
sentence, not an empty list.

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
    renders: "the posture card — ENTRENCHED is the one green arm; PRE_GENESIS names the missing leg; DIVERGENT is danger; UNREADABLE is the error treatment; a token this app has not heard of is shown verbatim and never green"
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
    example: "2 of 2 distinct holders (roster 3)"
    renders: "the charter quorum over the seated holders — met in ink, short in danger"
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
    example: "head 9f3a… · 3 of 3 witnesses · newest cosign 2026-09-20"
    renders: "the root's current lineage head and the witness quorum over it — what CC 3.2 T4a says a consumer MUST hold before attaching; no route serves it"
    tag: "proposed:row_trust_root_head_${root}"
  - ceg: x_private:seed_fingerprint
    use: display-only
    type: unconfirmed
    blocked_by: CIRISServer#404
    example: "3f9a0c1d2e4b5a67"
    renders: "the seed's fingerprint BEFORE it is installed, for the out-of-band comparison CC 3.2 T5 calls a first-class step; import installs and accepts in one call and previews nothing"
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
populated: {tag: card_trust_posture, renders: "the posture card, then one card per root the node lists (humanity-accord is always first, trust_root_api.rs:156-157), then the adopt-a-seed card"}
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
  (`mesh_genesis::charter_root_key_id`); *who signs* — this node writes its own
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

Verified against ciris-server `origin/main` at 0.5.217 (`046e1b39`,
2026-09-25), `src/trust_root_api.rs`, and CIRISPersist `federation/trust_root.rs`
at the pinned line.

| value | endpoint | owner | state |
|---|---|---|---|
| which roots this node accepts, and its genesis posture | `GET /v1/trust-root` | CIRISServer `src/trust_root_api.rs:415` (handler `:83`) | **live on this node's own machine, called** — `CIRISApiClient.getTrustRoots` (node URL) from `TrustRootViewModel.refresh` (`:112`). Body `{posture, banner, entrenched, roots[{root_key_id, root_kind, accepted, verdict}]}` (`:55-80`), modelled field for field by `TrustRootListingDto` / `TrustRootEntryDto`. **Remote reach** `blocked_by: CIRISServer#652` — the router is loopback-layered (`:422-424`); off the machine the screen renders `trust_root_loopback_only` |
| the verdict, leg by leg | `roots[].verdict` — persist's `TrustRootVerdict` verbatim (`:76-79`) | CIRISPersist `federation/trust_root.rs:374-420` | **read whole**: `edge_exists`, `root_self_declares`, `charter_has_recovery`, `last_drill_at`, `drill_freshness`, `halt_latched`, `valid`, `root_kind`, `charter_quorum{distinct_holders, required, roster_size}`, `holders_hardware[]`, `holders_hardware_attested`, `bounded_until`, `error` → `trustRootView`, pinned by `TrustRootTest.theVerdictIsReadWhole`. The two charter legs were decoded and dropped before this review; they are the T3 legs and now have a row |
| adopt a portable seed on this node | `POST /v1/trust-root/import` `{bundle, allegiance_from?}` | `src/trust_root_api.rs:416` (handler `:227`) | **live on this node's own machine, called** — `importTrustRoot`; parsed locally first (`TrustRootViewModel.prepareImport`: not a JSON object → `trust_root_import_not_json`, nothing sent), then the ConfirmSheet, then the send. Response `{installed, accepted, posture, entrenched, banner}` (`:215-226`) → `txt_trust_root_import_installed` / `_accepted` / `_partial`. Refusals by id: `trust_root.bad_request`, `.bad_bundle` (not a `GenesisBundle` — CC 3.2 T5: *a bare record list is not a seed and MUST fail to parse*), `.bundle_refused` (does not verify; nothing changed), `.install_failed`; each has its own sentence (`trustRootRefusalKey`). `allegiance_from` (CIRISServer#632 / CIRISEdge#671) is offered as an optional field. **Remote reach** `blocked_by: CIRISServer#652` |
| un-trust a root | `DELETE /v1/trust-root/{root_key_id}` | `src/trust_root_api.rs:417-420` (handler `:360`) | **live on this node's own machine, called** — `untrustRoot` behind the destructive ConfirmSheet. Response `{root_key_id, withdrawn, records_retained, entrenched, banner}` (`:388-396`) → `txt_trust_root_untrust_{withdrawn,records_retained,entrenched}`; refusals `trust_root.no_root`, `.withdraw_failed` by id. **Remote reach** `blocked_by: CIRISServer#652` |
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
T5 scopes to *tamper-evident, never authentic*. Until CIRISServer#693 and #404
land, the confirm's note is the whole of the mitigation, and this CSD says so
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
