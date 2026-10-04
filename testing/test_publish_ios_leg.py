"""Guards for publish.yml's iOS leg and the watchdog it runs under.

Two failures from the v0.5.225 publish run, both on `macos-14` (3-core M1,
7 GB), both invisible to a PR check until now:

  * `linkReleaseFrameworkIosArm64` died with `GC overhead limit exceeded`.
    `client/gradle.properties` gives Gradle `-Xmx8g` — more than the runner —
    and KGP 2.0.21 runs the Kotlin/Native link INSIDE that JVM by default. The
    leg now bounds both JVMs on its own matrix entry; these tests keep the
    bound present, keep it inside the machine, and keep it off the desktop
    property everyone else uses.
  * `packaging/run_with_watchdog.sh` printed `syntax error: invalid arithmetic
    operator` on its first heartbeat and then no heartbeat at all: BSD `ps`
    prints CPU time with hundredths, bash arithmetic does not take "438.74",
    and an expansion error ends the subshell. The script carries a
    `--self-test` that feeds that exact output through the same expression;
    this file runs it, and proves it goes RED with the fraction left in.

Neither test needs a Mac. The macOS-only half — that the bounded link actually
fits in 4 GB — is a budget stated in the workflow comment, not a measurement,
and the publish run is where it gets measured.
"""

from __future__ import annotations

import pathlib
import re
import shutil
import subprocess

import pytest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
PUBLISH = ROOT / ".github" / "workflows" / "publish.yml"
WATCHDOG = ROOT / "packaging" / "run_with_watchdog.sh"
LOCAL_BUILD = ROOT / "packaging" / "build_xcframework_local.sh"

#: What the runner has, and what the two JVM caps together may claim of it.
#: A cap is not a commitment, but two caps that sum past physical RAM are the
#: defect this guards against.
#: GitHub's standard runners for a public repo, by label: memory in GB.
#: macos-14 (arm64, 3 cores) ran the link out of heap on 0.5.225 and 0.5.226;
#: macos-15-intel (x86_64, 4 cores) is the leg's runner since.
RUNNER_MEMORY_GB = {"macos-14": 7, "macos-15": 7, "macos-15-intel": 14}
#: Leave 2 GB of the runner to the OS, Xcode's tools and the link's native memory.
HEADROOM_GB = 2
#: KGP 2.0.21's own default for the out-of-process compiler JVM. Going below
#: it would be a regression dressed as a bound.
KGP_DEFAULT_NATIVE_HEAP_GB = 3


def _xmx_gb(token: str) -> float:
    m = re.search(r"-Xmx(\d+)([gGmM])", token)
    assert m, f"no -Xmx in {token!r}"
    n, unit = int(m.group(1)), m.group(2).lower()
    return n if unit == "g" else n / 1024


@pytest.fixture(scope="module")
def mobile_job() -> dict:
    wf = yaml.safe_load(PUBLISH.read_text(encoding="utf-8"))
    jobs = [j for j in wf["jobs"].values()
            if any(e.get("name") == "ios-xcframework"
                   for e in j.get("strategy", {}).get("matrix", {}).get("include", []))]
    assert len(jobs) == 1, "expected exactly one job whose matrix carries ios-xcframework"
    return jobs[0]


@pytest.fixture(scope="module")
def ios_entry(mobile_job) -> dict:
    return next(e for e in mobile_job["strategy"]["matrix"]["include"] if e["name"] == "ios-xcframework")


def test_the_ios_leg_bounds_both_jvms(ios_entry):
    args = str(ios_entry.get("gradle_args", "")).split()
    assert "--max-workers=1" in args, "the iOS leg must link one target at a time"
    assert "-Pkotlin.native.disableCompilerDaemon=true" in args, (
        "without disableCompilerDaemon the link runs inside the Gradle JVM and "
        "kotlin.native.jvmArgs is never read"
    )
    gradle = [a for a in args if a.startswith("-Dorg.gradle.jvmargs=")]
    native = [a for a in args if a.startswith("-Pkotlin.native.jvmArgs=")]
    assert len(gradle) == 1 and len(native) == 1, f"one heap each, got {args}"
    g, n = _xmx_gb(gradle[0]), _xmx_gb(native[0])
    runner = ios_entry["runner"]
    assert runner in RUNNER_MEMORY_GB, f"unknown runner {runner!r}: add its memory to RUNNER_MEMORY_GB"
    runner_gb = RUNNER_MEMORY_GB[runner]
    assert g + n <= runner_gb - HEADROOM_GB, (
        f"Gradle {g} GB + link {n} GB = {g + n} GB does not fit a {runner_gb} GB {runner} "
        f"with {HEADROOM_GB} GB headroom (max {runner_gb - HEADROOM_GB})"
    )
    assert n >= KGP_DEFAULT_NATIVE_HEAP_GB, (
        f"link heap {n} GB is below KGP's own default of {KGP_DEFAULT_NATIVE_HEAP_GB} GB"
    )
    assert n > g, "the link is the memory consumer; Gradle only waits on it"


def test_every_gradle_arg_is_a_single_token(ios_entry):
    """The Build step splices `gradle_args` in unquoted. A value with a space
    inside a token (`-Xmx4g -XX:...` under one -P) would split into two
    arguments Gradle cannot parse; keep each token self-contained."""
    for tok in str(ios_entry["gradle_args"]).split():
        assert tok.startswith(("-", "--")), f"{tok!r} is not a flag"
        assert "=" in tok or tok.startswith("--"), f"{tok!r} carries no value"


def test_the_build_step_splices_the_args_before_the_task(mobile_job):
    build = next(s for s in mobile_job["steps"] if s.get("name") == "Build")
    commands = [ln.strip() for ln in str(build["run"]).splitlines()
                if ln.strip() and not ln.strip().startswith("#")]
    gradle = " ".join(ln for ln in commands if "gradlew" in ln)
    assert "${{ matrix.gradle_args }}" in gradle, "the Build step never passes matrix.gradle_args"
    assert gradle.index("${{ matrix.gradle_args }}") < gradle.index("${{ matrix.task }}")
    assert '"${{ matrix.gradle_args }}"' not in gradle, "quoted, it becomes one unknown argument"


def test_the_desktop_property_is_not_where_the_bound_lives():
    """The bound is per leg. gradle.properties is every developer's desktop
    setting and an input to the XCFramework cache key; moving the cap there
    would invalidate the cache and slow every laptop for one runner."""
    props = (ROOT / "client" / "gradle.properties").read_text(encoding="utf-8")
    assert "kotlin.native.disableCompilerDaemon" not in props
    assert "kotlin.native.jvmArgs" not in props
    assert re.search(r"^org\.gradle\.jvmargs=-Xmx8g", props, re.M), (
        "gradle.properties' desktop heap changed; the leg's comment quotes it"
    )


def test_the_local_build_is_not_bounded():
    """packaging/build_xcframework_local.sh is the desktop path — the one with
    3-4x the memory — and must keep the desktop heap."""
    text = LOCAL_BUILD.read_text(encoding="utf-8")
    assert "kotlin.native.jvmArgs" not in text
    assert "org.gradle.jvmargs" not in text


def test_the_scripts_parse():
    for script in (WATCHDOG, LOCAL_BUILD):
        subprocess.run(["bash", "-n", str(script)], check=True)


def test_the_watchdog_self_test_passes():
    out = subprocess.run(["bash", str(WATCHDOG), "--self-test"], capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
    assert "[OK]" in out.stdout


def test_the_watchdog_self_test_goes_red_with_the_fraction_left_in(tmp_path):
    """The check's other half. Put the script back the way it was — no
    fraction stripped, `print total + 0` — and the self-test must fail,
    otherwise it is proving nothing about the defect it was written for."""
    broken = tmp_path / "run_with_watchdog.sh"
    text = WATCHDOG.read_text(encoding="utf-8")
    assert 'sub(/\\.[0-9]+$/, "", t)' in text
    assert 'printf "%d\\n", total' in text
    text = text.replace('sub(/\\.[0-9]+$/, "", t)', "")
    text = text.replace('printf "%d\\n", total', "print total + 0")
    broken.write_text(text, encoding="utf-8")
    shutil.copymode(WATCHDOG, broken)
    out = subprocess.run(["bash", str(broken), "--self-test"], capture_output=True, text=True)
    assert out.returncode != 0, "the self-test passed on the broken arithmetic"
    assert "[FAIL]" in out.stdout


def test_the_watchdog_still_reports_the_command_exit_code(tmp_path):
    ok = subprocess.run(["bash", str(WATCHDOG), "5", "1", "t", "--", "true"], capture_output=True, text=True)
    assert ok.returncode == 0, ok.stdout + ok.stderr
    bad = subprocess.run(["bash", str(WATCHDOG), "5", "1", "t", "--", "sh", "-c", "exit 3"],
                         capture_output=True, text=True)
    assert bad.returncode == 3, "a watchdog that swallows the exit code is a new way to report green"


def _run_watchdog(windows: str, sleep_s: int) -> "subprocess.CompletedProcess[str]":
    import os, subprocess
    env = dict(os.environ, WATCHDOG_STILL_WINDOWS=windows)
    return subprocess.run(["bash", str(WATCHDOG), "1", "1", "probe", "--", "sleep", str(sleep_s)],
                          capture_output=True, text=True, env=env, timeout=60)


def test_a_process_still_for_fewer_windows_than_the_limit_is_not_killed():
    """A paging link advances no CPU for a while and then moves again: one or
    two still heartbeats must not kill it (0.5.227, LLVMContextDispose)."""
    r = _run_watchdog("10", 4)
    assert "Treating as hung" not in r.stdout + r.stderr, r.stdout


def test_a_process_still_for_the_full_count_is_treated_as_hung():
    r = _run_watchdog("3", 30)
    assert "Treating as hung" in r.stdout + r.stderr, r.stdout
    assert "3 heartbeats in a row" in r.stdout + r.stderr, r.stdout
