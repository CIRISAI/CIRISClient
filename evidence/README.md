# Evidence registry — CIRISClient

Format and doctrine follow
[`CIRISServer/evidence/`](https://github.com/CIRISAI/CIRISServer/tree/main/evidence).

## `blocked_upstream.tsv` — the adoption manifest

Tab-separated, one row per upstream obligation the client's build contract is
waiting on:

```
issue  repo  scan_root  glob  needle  files  lines  kind  predicate
```

Each row carries a **scannable predicate** — a needle whose appearance means
the obligation landed — so the wait is visible to a test rather than to
someone's memory. `kind` ∈ `absence` · `drift` · `untestable`.

Counts measured 2026-08-16 against CIRISConformance `origin/main` @`3ab8b75`
and CIRISServer `origin/main` @`515e656f` (v0.5.176).

One row is deliberately imperfect and says so: the CC 3.1.2.1 locale-manifest
domain drift (`ciris.locale_manifest.v2` / `locale` in the Constitution versus
the shipped `v1` / `lang_code`) has **no upstream issue number** yet. The row
records the obligation and states that it needs filing. An obligation with a
missing tracker is still an obligation; leaving it out of the manifest because
the number is unknown is how it gets forgotten.

## `cc_impl.tsv` — absent, deliberately

No Constitution claim is enforced by anything in this repo. These gates report
on the client's build requisites; they gate no wire format and admit nothing.
A row here would be an over-claim of exactly the kind the 2026-07 CIRISServer
evidence audit corrected.

When the client source is extracted (see `MISSION.md` §5), the client's own
enforcing symbols — the `ClientMode` capability probe, the `SubstrateGate`
registry, the locale-root check — become candidates for real rows. Not before.

## What is not evidence

- `surface-binding`'s output. It is a grep heuristic and marks itself
  `heuristic: true`; it produces a worklist, not a finding.
- A gate run against only one of the two vendored client trees. They diverge,
  and a result from one is not a result about the client.
