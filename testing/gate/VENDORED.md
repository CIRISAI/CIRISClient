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
| `build_qa_gallery.py` | `tools/dev/build_qa_gallery.py` | verbatim — the screenshot gallery |
| `platforms.py` | `tools/qa_runner/modules/web_ui/platforms.py` | **adapted** — capture transplants, bring-up could not. See below |
| `candidate_artifacts.py` | `tools/fetch_client_artifacts.py` | **the seam**, adapted — see below |

`platform_procs.py` and `build_qa_gallery.py` are stdlib-only and reference
nothing of CIRISAgent's, which is why they transplant untouched.

### `platforms.py` — and a mistake this file previously recorded as a fact

An earlier version of this document said all three vendored modules were
"stdlib-only and import nothing from CIRISAgent". That was **false for
`platforms.py`**, and the way it was checked is what made it look true: every
dependency on their code is a DEFERRED import inside a method.

```python
async def bring_up(self, args) -> int:
    from . import __main__ as web_ui_main          # CIRISAgent's bring-up
    return await web_ui_main.run_android_up(args)

def _adb(self, *args):
    adb = str(web_ui_main._android_sdk_paths()["adb"])    # and again
```

`import testing.gate.platforms` touches neither, so the module imported cleanly
while four of its five entry points raised `ImportError` on first call — and
they would have raised at the moment a run tried to boot an emulator.

What actually transplants is **capture**, which is the substance: per-platform
screenshots with the right tool for each, which is fiddly and worth reusing.
**Bring-up does not**, because theirs builds and installs their `apps/` shells
around this client, and ours are the real apps. So:

- every `bring_up` now RAISES `NotImplementedError`. Not returns 0 — a bring-up
  that silently does nothing produces a run against an app that was never
  started and reports the platform green, which is the one outcome this gate
  exists to prevent.
- `_resolve_adb()` replaces their `__main__._android_sdk_paths()`, reading
  `ANDROID_SDK_ROOT`/`ANDROID_HOME` directly. Their comment explains why this
  cannot be `shutil.which("adb")`: on a runner adb is not on PATH, and the quiet
  miss cost them a whole Android tile on their first green run.
- `httpx` became `urllib.request`. Everything else driving this client here is
  stdlib-only so CI installs nothing, and a screenshot helper is a poor reason
  to break that.

`testing/test_gate_vendoring.py` now asserts this property by AST rather than by
import, so the same mistake cannot be made silently again. It is
mutation-checked against the original code.

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
8. **a platform that cannot run must SKIP LOUDLY** — DONE at the bring-up layer
   (`bringup.CannotRun`, raised and never swallowed, naming the step and its
   stderr). A silent skip and a pass are the same colour on a dashboard.

## Bring-up (`bringup.py`) — ours, and testable without hardware

Every step needs an emulator, a simulator or a display, so a conventional
implementation cannot be tested until CI has hardware — which means the adb
sequence is first found wrong on a runner, from a timeout, with no message.
That is the position their gate was in for four runs.

So each platform builds an inspectable **plan** — a list of named steps — and
running it is separate. The plan is pure data, so ORDER and CONTENT are unit
tested anywhere, and order is where bring-up bugs live. Three invariants, each
mutation-checked:

1. **Test mode is armed before the app starts.** Android's switch is a sentinel
   file read once at startup, so touching it after `am start` yields an app with
   no automation server and a `/health` that never answers — indistinguishable
   from a crash.
2. **The node is reachable before the app starts**, via `adb reverse` so the
   emulator's `localhost:8080` is the runner's node. That keeps the client in
   the REMOTE shape of `FSD/ONE_CLIENT_N_NODES.md` and needs no Android node
   binary — just as well, since CIRISServer publishes none.
3. **Installed before forwarded.** adb accepts on the HOST socket before it
   tries the device, so a forward to an absent package succeeds and then fails
   as a socket error that looks exactly like a dead app.

Teardown steps are all optional, because teardown runs after failures too and a
teardown that fails hides the failure that caused it.

## Re-syncing

The upstream paths are recorded per-file above, so a re-sync is a diff against a
newer CIRISAgent tag rather than a re-derivation. Bump the pin at the top of this
file when you do, and re-check the "not taken" column — a file that has stopped
being agent-specific belongs on the other list.
