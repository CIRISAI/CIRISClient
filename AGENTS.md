# Repository Guidelines

## Project Structure & Module Organization
- `readiness/client.py` — the client build gates. One function per gate, registered with `@gate(id, question)`.
- `readiness/__main__.py` — the CLI, including `--client-tree`.
- `evidence/` — the evidence registry (see `evidence/README.md`).
- The gate framework is **not** here. It lives in [CIRISGrace](../CIRISGrace) and is imported as `grace.gate`. Do not fork it; extend it there.
- There is no client source in this repo. Gates read a tree given by `--client-tree` (default `<root>/CIRISServer/client`).

## Build, Test, and Development Commands
- `pip install -e ../CIRISGrace && pip install -e .` (until `ciris-grace` is published).
- `python -m readiness` · `python -m readiness gates` · `python -m readiness run <id>`.
- Grade the other vendored tree: `python -m readiness --client-tree ~/CIRISAgent/client`.
- Node-dependent gates need `--node <url>`; without it they report `unimplemented`, not `pass`.

## Coding Style & Naming Conventions
- Python 3.10+, four-space indents, `snake_case`, type hints on public surfaces.
- Stdlib only beyond `ciris-grace`. The client toolchain is already heavy; the gate that inspects it must not be.
- Gate ids are `kebab-case`; each gate belongs to exactly one requisite class (`code`, `data`, `normative`) and says which in `MISSION.md` §3.
- Kotlin/Gradle/JSON are parsed with `re` and `json`, not by shelling out to a build. A readiness check that requires a build has already lost.

## Gate Rules
- Four statuses, no partial credit: `pass`, `fail`, `unimplemented`, `error`. `unimplemented` is not a pass.
- **A parser that finds nothing where the construct plainly exists must fail loudly**, not pass. `nav-gate-registry` fails when `enum class SubstrateGate` is present but zero entries parse — that check exists because the first version of that regex silently returned zero and reported green.
- Heuristic gates set `heuristic: true` in `detail` and say so in the summary. `surface-binding` is the current example.
- Gates never write to the client tree.

## Testing Guidelines
- Run every gate against **both** vendored trees (`CIRISServer/client` and `CIRISAgent/client`) before committing; they diverge, and a gate that only works on one is not done.
- Also run against a stale checkout. Most of these gates are about drift, and drift is only visible when something is behind.
- Record actual output in the PR — the board, not a description of the board.

## Commit & Pull Request Guidelines
- Conventional commits (`feat:`, `fix:`, `docs:`), atomic scope.
- Changing what a gate asks updates `MISSION.md` §3 and the README table in the same commit.
- Upstream-blocked findings go in `evidence/blocked_upstream.tsv` with a scannable predicate, not in a comment.

## Security & Configuration Tips
- No credentials here; `gh` supplies GitHub auth.
- Do not vendor client source into this repo ahead of an extraction decision (`MISSION.md` §5) — a partial copy would create a third tree to keep in sync, which is the problem this repo exists to measure.
- Locale bundles and specs read here may contain unreleased strings; treat `--json` output as internal.
