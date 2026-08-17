# CIRISClient

Build-readiness gates for the CIRIS Kotlin Multiplatform client.

**There is no client source in this repo.** The client is still vendored in
`CIRISServer/client` and mirrored in `CIRISAgent/client`. This repo holds the
gates that answer *"are the requisites in place to build a client I can
trust?"* — which is the question that has to be answerable before an
extraction is worth doing. See [MISSION.md](MISSION.md) §5.

## Install

Python 3.10+. The gate framework lives in [CIRISGrace](../CIRISGrace):

```bash
pip install -e ../CIRISGrace     # until ciris-grace is published
pip install -e .
```

## Use

```bash
python -m readiness                              # run every gate
python -m readiness gates                        # list them
python -m readiness run locale-parity toolchain
python -m readiness --client-tree ~/CIRISAgent/client   # grade the other tree
python -m readiness --node http://127.0.0.1:4243       # enable node-dependent gates
python -m readiness --json out.json
```

Default client tree is `<root>/CIRISServer/client`. Exit code is 0 only when
every gate passed.

## Gates

| id | class | asks |
|---|---|---|
| `toolchain` | code | Are the build tools present for the platforms we target? |
| `substrate-binaries` | code | Are the per-platform substrate artifacts present? |
| `version-alignment` | code | Does CLIENT_VERSION match the node it ships against? |
| `generated-api-drift` | code | Does generated-api match its spec? — **not implemented** |
| `locale-parity` | data | Do the runtime locale bundles agree, and how complete are they? |
| `spec-drift` | data | Does the committed OpenAPI spec match what the node serves? (needs `--node`) |
| `surface-binding` | data | Does every documented endpoint reach a client surface? |
| `nav-gate-registry` | normative | Is every `SubstrateGate` pointing at an open issue? |

## Reading the board

`pass` · `fail` · `unimplemented` · `error`. `unimplemented` is **not** a pass
and does not count toward `passed_all_gates`.

Two gates need care when you read them:

- **`surface-binding` is a heuristic.** It greps the shared module for each
  documented path literal, so a URL built by string concatenation reads as
  unbound. The output is a worklist to confirm, not a verdict; the report marks
  it `heuristic: true`. It is the noisiest gate here by a wide margin.
- **`locale-parity` duplicates the client's own CI guard** on purpose — that
  one runs after you push, this one runs before you build. It adds a per-locale
  key-coverage number the CI guard does not compute.

## What is not here yet

- The client source (deliberately — MISSION §5).
- `generated-api` regeneration and drift detection: the generator is not in the
  build graph, so spec drift is currently silent.
- Anything reading the substrate's signed locale Merkle root. Until then the
  four-bundle byte-identity check stands in for it.

## Status

Early. Every gate that runs was run against both the CIRISServer and
CIRISAgent client trees before being committed.
