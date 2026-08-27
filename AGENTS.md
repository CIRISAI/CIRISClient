# Repository Guidelines

## Project Structure & Module Organization
- `client/` — the **vendored** KMP client. Not authored here. Read `client/VENDORING.md` before touching anything in it; §3 governs.
- `ciris_client/` — the Python API consumers use to find the built bundles. No Kotlin is compiled by pip, ever.
- `localization/` — the OTHER thing this repo owns. `glossaries/` is 29 files of canonical terminology (3,045 pairs) plus each language's standing prose rules; `localize.py` is the translate → evaluate → repair pipeline that reads them; `glossary.py` parses them. The bundles themselves live under `client/` because that is where the app loads them from.
- `packaging/` — the flavor payload projects (`node`, `agent`) and the three checks: `stage_artifacts.py`, `check_vendoring.py`, `check_wheel_size.py`. Stdlib only; they run before anything is installed.
- `readiness/client.py` — the client build gates. One function per gate, registered with `@gate(id, question)`.
- `readiness/__main__.py` — the CLI, including `--client-tree`.
- `evidence/` — the evidence registry (see `evidence/README.md`).
- The gate framework is **not** here. It lives in [CIRISGrace](../CIRISGrace) and is imported as `grace.gate`. Do not fork it; extend it there.
- Gates default to this repo's `client/` and still accept `--client-tree` for a consumer's copy.

## Working in `localization/`
- The pipeline has THREE LANES and ONE DIRECTION: `translate → evaluate → repair`. `--lane` chooses where a run enters; it always flows through the rest. There is no path that writes an unreviewed string into a shipped bundle, and that is not an accident — Haiku fan-outs produced word-salad in 5 of 28 locales that structural validation could not see.
- **Glossary-first.** Terminology is decided before translation, not after. Every request carries the glossary terms that occur in its batch, that language's prose rules, and real shipped translations as anchors. A model asked to render "node" fresh will pick something; the corpus already decided.
- **A refusal is expressible and never final.** The translate lane asks for `refusals` rather than letting a model disguise "I cannot" as a bad string, then escalates that key — one key, not the batch — up a ladder whose last rung is a different model family. If every rung refuses, the run FAILS. English under a non-English locale is not a fallback, it is a silent demotion of that audience.
- **The judge is a different family from the drafter.** A judge sharing the drafter's weights shares its blind spots and prefers its own output.
- Say what this pipeline does not guarantee, every time: native fluency, dialect coverage, cultural adaptation of metaphor, legal review. Everything it writes is `draft` / `needs_native_review` until a speaker signs off.
- `localization/TRANSLATION_GUIDE.md` §3 is NOT documentation — `localize.py` parses those bullets into all three system prompts and refuses to start without them. A failure mode found by hand goes there, and the agents have it on the next run. Anywhere else and it is a lesson nobody re-reads.
- When the evaluate lane rejects every rung of the ladder, the answer is to TRANSLATE IT YOURSELF from the shipped corpus — `localization/TRANSLATION_GUIDE.md`. Not to lower the bar, and not to file it as somebody else's problem: the languages that get there are Tier 0, ranked first precisely because models are worst at them, so "wait for a native reviewer" is where those audiences get dropped.
- Anchors are EXEMPLARS. `MAX_ANCHOR_CHARS` exists because `prompts.language_guidance` is a real key whose Yoruba value is 31,297 characters, and lexical retrieval loved it: it contains most words, so it out-scored every genuinely similar UI string and cost ~8k tokens a request to teach nothing about button labels.

## Working in `client/`
- Every file under `client/` is byte-identical to CIRISAgent@6083bdf **or** has a row in `client/VENDORING.md` §3 saying why. `packaging/check_vendoring.py` asserts exactly that, and CI runs it.
- Changing a vendored file means: make the change, add the row, and re-record the digest (`python3 packaging/check_vendoring.py --print`) in the same commit.
- Prefer pushing the change upstream to carrying it. The delta table is deliberately small so that keeping it small stays a decision someone makes, not a thing that erodes.
- Do not vendor the substrate. `androidApp/wheels/`, jniLibs, `iosApp/Resources*`, `iosApp/Frameworks/` and `iosApp/app_packages_native/` are other repos' release artifacts (§2). They are excluded on purpose and `.gitignore` does not protect you from `git add -f`.

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
- Run every gate against **all three** trees before committing — this repo's `client/`, `CIRISServer/client` and `CIRISAgent/client`. They diverge, and a gate that only works on one is not done. `version-alignment` is the worked example: two layouts, one question.
- Also run against a stale checkout. Most of these gates are about drift, and drift is only visible when something is behind.
- Record actual output in the PR — the board, not a description of the board.
- A check added here must be shown to FAIL on a deliberate break before it is believed. Both `check_localization_sync.py` and `check_vendoring.py` were negative-tested against a planted defect; a check whose red path has never run is a check with an untested half.

## Commit & Pull Request Guidelines
- Conventional commits (`feat:`, `fix:`, `docs:`), atomic scope.
- Changing what a gate asks updates `MISSION.md` §3 and the README table in the same commit.
- Upstream-blocked findings go in `evidence/blocked_upstream.tsv` with a scannable predicate, not in a comment.

## Security & Configuration Tips
- No credentials here; `gh` supplies GitHub auth.
- **The extraction happened** (`MISSION.md` §5.1, 2026-08-20). This section used to read: *"Do not vendor client source into this repo ahead of an extraction decision — a partial copy would create a third tree to keep in sync, which is the problem this repo exists to measure."* That warning was right and has not expired; it has become a deadline. Until CIRISServer and CIRISAgent depend on the package and delete their copies, there ARE three trees, and this repo is one of them. The obligation is a row in `evidence/blocked_upstream.tsv` with a scannable predicate. Do not let it become the status quo.
- Do not add a second source for anything that already has one. The version lives in `VERSION` and is projected into the wheel and into `CLIENT_VERSION`; whether the agent surfaces are live is answered by the probed `ClientMode` and nowhere else (there is no build flavor — CIRISServer#479); the locale bundles are kept identical by a check, not by hand. Adding a committed copy of any of them re-opens the drift this repo was built to close.
- Locale bundles and specs read here may contain unreleased strings; treat `--json` output as internal.

## CI
- Every `apt-get` goes through `.github/actions/apt`: `azure.archive.ubuntu.com` dropped, `timeout 300`, `Acquire::Retries=3`. No exceptions — an unhardened `apt-get update` is a coin flip that costs a whole job when it loses.
- `gradle` is REQUIRED. It shipped `continue-on-error` for one run to answer whether the vendored tree stands alone; it does, so the flag came off in the same PR. If you ever add an advisory job, write the condition for removing it next to the flag.
- The wheels job stages a placeholder when Gradle produced nothing, and the placeholder RAISES on every artifact lookup. Never make it return a path instead; a wheel that installs and silently contains no client is the failure mode this whole arrangement exists to prevent.
