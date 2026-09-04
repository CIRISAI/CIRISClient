# The five-platform gate, vendored — CIRISClient#31

**Source:** CIRISAgent, tag `gate-vendor-2026-09-03` = `01233afd8403c9b0d1ad48399ca692e080cabd78`
(branch `fix/mobile-runners-green`, PR CIRISAgent#1138).

## Why we vendored it

Their loop and ours cost a release per defect:

> you publish → we adopt → our gate finds it → you fix → you publish again

Every defect in 0.5.199–0.5.201 followed it. #30, #32, #34 and #35 were all
found by their gate against a published build, and each one spent a release.
The two most expensive were things a candidate build would have surfaced in
minutes: `/input` acknowledging work it had not done, and a stale registry
entry making `/tree` describe a screen that was not on screen.

## What we took, and what we did NOT

The walked import closure at that commit is 16,286 lines. Copying it whole would
import code that cannot run in this repo — their engine lifecycle, their app
shells, their simulator Python substrate — and shipping code nobody thinks is in
the tree is the exact failure their own `prune_stale` docstring exists to
prevent. So the split is deliberate and recorded rather than left to inference.

### Taken

| here | upstream | how |
|---|---|---|
| `platform_procs.py` | `tools/qa_runner/platform_procs.py` | verbatim — port ownership, Windows `netstat` / POSIX `lsof` |
| `platforms.py` | `tools/qa_runner/modules/web_ui/platforms.py` | verbatim — per-platform screenshot capture |
| `build_qa_gallery.py` | `tools/dev/build_qa_gallery.py` | verbatim — the screenshot gallery |
| `candidate_artifacts.py` | `tools/fetch_client_artifacts.py` | **the seam**, adapted — see below |

All three verbatim files are stdlib-only and import nothing from CIRISAgent,
which is why they transplant without edits.

### Not taken, with the reason

| upstream | why not |
|---|---|
| `web_ui/server_manager.py` | starts and stops **their** CIRIS engine; we have no engine in this repo |
| `web_ui/__main__.py` | their bring-up, around their `apps/` shells. Ours are `client/androidApp`, `client/iosApp`, `client/desktopApp` — the real apps, not shells |
| `web_ui/licensed_agent_flow.py`, `federation_walk_*.py`, `scenarios.py`, `test_cases.py`, `test_runner.py`, `desktop_test.py`, `browser_helper.py` | agent product flows, not client contract |
| `tools/update_substrate_libs.py`, `.github/workflows/refresh-ios-substrate.yml` | builds their embedded simulator Python; our iOS app does not embed one |
| `tests/workflows/test_ios_bundle_pipeline.py` | asserts that substrate's layout |
| `tests/workflows/test_android_release_fetches_client.py` | asserts **their** `apps/android/libs` fetch — the mirror image of our publish step |
| `tests/workflows/test_ios_diagnostics_can_see_the_app.py` | their os_log predicates and teardown |
| `web_ui/desktop_app_helper.py` | we already have `testing/driver.py` doing this job, stdlib-only and raising on every failure. Two drivers would be two contracts; the RULES are ported instead — see below |
| all seven of `tests/workflows/*.py` | **checked, not assumed.** Every one imports `desktop_app_helper` or asserts against their `apps/` shells, so they test THEIR driver, not the contract. Vendoring them would have shipped seven files that cannot run — the exact thing this document exists to prevent. Rewritten as `testing/test_driver_rules.py` against ours |

## The seam

Upstream, `fetch_client_artifacts.py` has exactly one source: resolve the
`ciris-client==` pin and download that release's assets. That is right for a
consumer and useless for us — by the time a release exists the defect is
published, which is the cost we are trying to stop paying.

So `candidate_artifacts.py` resolves **this tree's build outputs** by default and
keeps `--from-release` working, because reproducing a downstream failure against
the exact bytes they adopted is worth keeping.

It also asserts something upstream had no need to: **that the artifact is
actually this tree's.** "Freshest on disk" and "built from this tree" are
different questions, and answering the first while meaning the second reports a
platform green for code that is not the candidate. Writing it hit that
immediately — the newest jar in a working checkout was `1.5.195` against a
`VERSION` of `0.5.201`, six releases stale, and three older ones sat beside it.
This repo has already paid for the same class once from the other direction: a
universal wheel shipped `CIRIS-linux-x64-1.5.188.jar` and the size check passed,
because a stale jar is a perfectly plausible size.

A version mismatch fails. An mtime that merely looks old warns — a checkout or a
rebase moves source mtimes without changing a byte that matters, and failing on
that would train people to pass `--force` habitually.

## Rules ported into `testing/driver.py` rather than a second driver

The intent was to vendor their `tests/workflows/` verbatim. Checking each import
closure showed that none of the seven can run here: they exercise
`desktop_app_helper` and their `apps/` shells. So the rules were rewritten as
tests of our driver, in `testing/test_driver_rules.py`, against a real
`http.server` on a real socket — two of the four defects this driver has met
were in the transport itself, and a mock passes both.

Wiring them into CI meant adding pytest to the build: nothing in this repo ran
it, so they would otherwise have sat in the tree looking like coverage while
never executing. Both implemented rules are mutation-checked.

Each is a rule that exists because it already went green while the product was
broken:

1. **read-back after `/input`** — DONE. `success` means posted, not applied.
   A field exposing no value is UNVERIFIABLE, not failed, or the driver becomes
   unusable against the pre-0.5.200 versions worth reproducing against; a field
   holding something *different* raises. Masked fields are exempt, because
   verifying one fails every correct run and a check that cries wolf gets
   switched off.
2. **presence is not drivability** — DONE, and not from their list. #30 was a
   stale registry entry that looked exactly like a live control, so
   `Element.is_ghost` reads the live `canClick`/`canInput` to tell a corpse from
   a control in one poll. `None` is "this client cannot say", never "not
   drivable" — reading absence as a negative is the mistake that produced #21
   and #34.
3. **lenient JSON** — TODO. A raw control character in a response must not kill
   the driver before it can say which route and which bytes.
4. **dropdown poll** — TODO. Poll until the option count settles; never a fixed
   sleep.
5. **pre-send baseline** — TODO. A stale row must not satisfy the reply assertion.
6. **an error row is not a reply** — TODO.
7. **screenshots at every stage, including on success** — TODO.
8. **a platform that cannot run must SKIP LOUDLY** — TODO, and it belongs with
   the workflow rather than the driver.

## Re-syncing

The upstream paths are recorded per-file above, so a re-sync is a diff against a
newer CIRISAgent tag rather than a re-derivation. Bump the pin at the top of this
file when you do, and re-check the "not taken" column — a file that has stopped
being agent-specific belongs on the other list.
