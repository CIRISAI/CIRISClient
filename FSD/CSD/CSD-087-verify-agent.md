# CSD-087 — Verify an agent build (the surface shipped ahead of its API)

**CSD**: CSD-087 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 (a flow-only screen)
**Overlaps**: reached only from ManageNodes, so it touches the node-identity area; raised with that card's author before writing
**Flow**: unwritten — the form is not drivable; see §5

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**Look an agent build up by hash and say whether the registry has it and whether
it is still good — and when the node cannot answer, say WHICH kind of cannot.**
Serves **CC 3.4.5** in its self-report reading: a status this client cannot read
is not a verdict it may invent. On a revocation check a guess is a lie in both
directions — guess `REGISTERED` and a revoked build looks fine, guess `REVOKED`
and a good one is condemned — so an unknown wire status renders as the raw
string (`AgentVerification.kt:17-24`).

**This is the first surface shipped AHEAD of its API, so what it does when the
capability is missing IS the feature.** No node released today serves
`registry:lookup`; CIRISRegistry is still folding into CIRISServer. Four
outcomes, none of them a blank screen and none of them an error:
`UNDECLARED` (the node predates the declaration — an upgrade is the fix),
`ABSENT` (the node declared its capabilities and this was not among them),
`UNDETERMINED` (the node said it does not know), `UNREACHABLE` (we could not
ask). Three different remedies — upgrade, use another node, retry — and folding
any two of them into one sentence is a false diagnosis.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: VerifyAgent
flow_only: true
entry: "ManageNodes `btn_manage_nodes_verify` (ManageNodesScreen.kt:474 → onVerifyAgent, CIRISApp.kt:4043) — the ONLY entry, confirmed by grep"
exit: "Screen.ManageNodes (CIRISApp.kt:4922), not Interact — returning to Interact would drop a node user onto a screen their node cannot serve"
```

`screenToSurface` maps this Screen to `null` explicitly (`CIRISApp.kt:5964`).
No nav hop — `VerifyAgent` is in `FLOW_ONLY` (`screen_atlas.py:43`); CSD-080 §2
records why `flow_only:` is a checked key.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "build:registered:{target}"
    bind: {target: agent}
    use: display-only
    type: "enum[REGISTERED,DEPRECATED,REVOKED,UNKNOWN]"
    example: "REGISTERED"
    renders: "the verdict card. DEPRECATED and REVOKED colour the card `errorContainer` (`isDiscouraged`); UNKNOWN shows the node's RAW status string beside the word rather than mapping it to either end"
    tag: card_verify_result
  - ceg: "provenance:build_manifest:{target}"
    bind: {target: agent}
    use: display-only
    type: string
    example: "sha256:9f2c…"
    renders: "the hash that was looked up, echoed back so the card cannot be read against the wrong query"
    tag: "proposed:txt_verify_hash_echo"
  - ceg: "revocation:{entity_type}:{reason}"
    bind: {entity_type: agent, reason: superseded}
    use: display-only
    type: string
    example: "superseded"
    renders: "on a REVOKED record, the reason the registry gave — a revocation with no stated ground is a verdict the operator cannot act on"
    tag: "proposed:txt_verify_revocation"
  - ceg: attestation:self_verify
    use: display-only
    type: bool
    example: false
    renders: "`hasAttestation` on the record — whether the build carries one at all"
    tag: "proposed:txt_verify_attested"
  - ceg: x_private:agent_hash
    use: emit
    type: string
    example: "9f2c1b…"
    renders: "the hash field; it appears ONLY once the capability is PRESENT — offering a form against a node that cannot serve it is the dead-control defect"
    tag: input_verify_hash
  - ceg: x_private:capability_state
    use: display-only
    type: "enum[PRESENT,ABSENT,UNDECLARED,UNDETERMINED,UNREACHABLE]"
    example: "UNDECLARED"
    renders: "one of four notices, each with its own remedy; PRESENT renders NOTHING (the notice returns early) and the form appears instead"
    tag: card_verify_capability
  - ceg: x_private:lookup_outcome
    use: display-only
    type: "enum[Found,NotFound,Unavailable]"
    example: "NotFound"
    renders: "'The registry has no record of this hash' and 'We could not reach the registry' are DIFFERENT cards. The first is an answer an operator can act on; the second is not"
    tag: card_verify_result
```

**Two `shows:` rows carry the same tag** (`card_verify_result`), because the card
renders the status and the outcome together. That is honest and it is also the
limit of what `§4` can assert: a `text:` predicate on one tag cannot tell the
`NotFound` card from a `Found`-with-`UNKNOWN`-status card. A per-fact tag is the
ask.

```yaml csd:states
populated: {tag: card_verify_result, renders: "the record: hash, type, version, status, registered-at, whether it carries an attestation"}
empty:     {tag: card_verify_result, renders: "`LookupResult.NotFound` — 'The registry answered and holds no record for this hash.' This is a RESULT, not an absence, and it must never be drawn as a blank card"}
loading:   {tag: btn_verify_submit, renders: "the submit control disabled while `inFlight`; the previous result is cleared the moment the hash changes, so a stale verdict never sits under a new query"}
error:     {tag: card_verify_capability, renders: "the could-not-ask family — UNREACHABLE ('try again'), UNDETERMINED ('the node said it does not know'), UNDECLARED ('this node predates the declaration; upgrade'), ABSENT ('this node does not confer registry lookup'). Each gets btn_verify_retry_probe, because an operator can upgrade the node or install registry support at the SAME url"}
```

`empty` and `error` here are the distinction the whole screen is built on, and
the code states it three times over — `CapabilityState`, `LookupResult` and
`ModeProbe` all encode "silence is not an answer"
(`models/capability/NodeCapabilities.kt:62-79`).

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| what this node confers | `GET /v1/federation/conformance` → `capabilities: Option<Vec<String>>` | CIRISServer (`src/conformance.rs:488`) | live — and `null` vs `[]` is deliberately distinguished server-side, for exactly the reason the client distinguishes UNDETERMINED from ABSENT |
| look a build up by hash | `GET /v1/registry/lookup` | CIRISServer | **missing** — no route literal in `src/*.rs`. CIRISServer#499 is the declaration; the route itself has no owner yet |

**The one upstream ask.** **CIRISServer**: `GET /v1/registry/lookup`, and
`registry:lookup` in the conformance capability list on the nodes that serve it.
Until then every node on earth renders `card_verify_capability` and the form
never appears — which is the designed behaviour, not a bug, and is why this CSD
is `sketched` rather than blocked.

**The card-vs-CC delta.** The Locked Spec has no "verify" tab. A build's
registration and revocation status is an `Everyone`/`GLOBAL_COMMONS` fact —
`build:registered:*` and `revocation:*` are both `registry`-owned families, and
the commons is where federation-wide records live. This screen reaches it
through *My things › This node › manage nodes*, which frames a commons fact as a
device setting. **Recommended placement: Everyone › Record**, alongside the
other federation-wide attestations, with the ManageNodes entry kept as a
shortcut.

## 4. Flow (how)

Open it against any node released today.

```yaml
expect:
  state: error
  visible: [txt_verify_title, card_verify_capability, btn_verify_retry_probe]
  absent: [input_verify_hash, btn_verify_submit]
```

Press `btn_verify_retry_probe` — the probe re-runs
(`capabilityProbeAttempt++`, `CIRISApp.kt:4008`) and the same notice returns.

Against a node that declares `registry:lookup`, with a registered hash:

```yaml
expect:
  state: populated
  visible: [input_verify_hash, btn_verify_submit, card_verify_result]
  absent: [card_verify_capability]
```

With a hash the registry does not hold:

```yaml
expect:
  state: empty
  visible: [card_verify_result]
```

**Only the first step runs today**, and it runs on every platform because it is
the only state any node can produce.

## 5. QA plan

**Platforms.** All five — and the UNDECLARED path is worth running on all five
precisely because it is the only path, so a screen that fails to compose there
fails everywhere at once.

**Not tested here.** Every PRESENT-capability state: there is no node that
serves the route, so `Found` / `NotFound` / `Unavailable` and all four
`AgentStatus` values are unreachable from a live fixture. They want a fake —
`LookupResult` is a sealed interface over plain data, so a unit test over
`VerifyAgentResult` would cover the four verdicts and the discouraged colouring
without a node at all, and nothing does that today.

**`input_verify_hash` is tagged and not drivable.**
`client/tools/check_ui_drivable.py --list` names it: it is
`Modifier.testable("input_verify_hash")` with no `rememberInputSinks` anywhere
in `VerifyAgentScreen.kt`. The fix is `CirisTextField`
(`ui/primitives/Controls.kt:91`), which declares its own sink. Same defect as
CSD-084 and CSD-085 — three flow-only screens, one missing line each, and the
tool has all three in its baseline of 199.
