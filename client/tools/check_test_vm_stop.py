#!/usr/bin/env python3
"""A test that builds a view model stops it, AND WAITS, before resetting Main.

    python3 client/tools/check_test_vm_stop.py             # fail on any offender
    python3 client/tools/check_test_vm_stop.py --list      # show every file it judged
    python3 client/tools/check_test_vm_stop.py --self-test # prove it goes red

THE DEFECT (PR #211)
--------------------
A view model's coroutine parked in `delay` finishes cancelling only when it is
next resumed, and that resume is dispatched on `Dispatchers.Main` from the
delay timer's thread. If the test's `@AfterTest` has already called
`Dispatchers.resetMain()`, there is no Main: the resume throws "Module with the
Main dispatcher is missing" OUTSIDE any test, and kotlinx-coroutines-test pins
it on the NEXT `runTest` as `UncaughtExceptionsBeforeTest`. The failure lands
in an innocent file (`PortalConnectTest`, `BillingViewModelTest`,
`SetupReviewGapsTest`), only on a slow runner, so the leak is invisible from
the test that has it.

THE RULE
--------
A test file under `shared/src/*Test` that constructs a `…ViewModel(` and calls
`Dispatchers.resetMain()` must, in the function that resets Main, call
`stopViewModels(` BEFORE `resetMain(` — cancel every scope it made and join it.

THE ONE EXCEPTION
-----------------
A test whose Main is a `StandardTestDispatcher` (a test scheduler) does not
leak: a cancelled coroutine's resume is queued on that scheduler, which nothing
advances after the test, so it never runs — and a join there would wait
forever. Such a tearDown carries the marker `// lint: test-scheduler-main`
with the reason, in the function or the comment/annotation lines above it.

The reading is line-based: the function that resets Main is the nearest
`fun` above the call, plus the annotation and comment lines directly above
that. A resetMain with no `fun` above it is refused as unparseable, and a tree
in which no file matches is refused too: the construct plainly exists, so
finding none means the parser broke.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "shared" / "src"
MARKER = "// lint: test-scheduler-main"

VM_CTOR = re.compile(r"(?<!class )(?<!fun )\b[A-Z]\w*ViewModel\(")
RESET = re.compile(r"\bresetMain\(")
STOP = "stopViewModels("
FUN = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|override|suspend)\s+)*fun\s")
HEADER = re.compile(r"^\s*(?:@|//|/\*|\*)")


def _code(line: str) -> str:
    """The line without a trailing `//` comment (good enough: test sources rarely put `//` in a string)."""
    i = line.find("//")
    return line if i < 0 else line[:i]


def judge(path: pathlib.Path) -> tuple[bool, list[str]]:
    """(applies, problems). `applies` is False for a file the rule does not cover."""
    lines = path.read_text(encoding="utf-8").split("\n")
    code = [_code(l) for l in lines]
    builds_vm = any(VM_CTOR.search(c) for c in code if not c.lstrip().startswith(("*", "/*", "import ")))
    resets = [(i, m.start()) for i, c in enumerate(code) for m in RESET.finditer(c)
              if not c.lstrip().startswith("import ")]
    if not (builds_vm and resets):
        return False, []
    problems = []
    for i, col in resets:
        f = next((j for j in range(i, -1, -1) if FUN.match(lines[j])), None)
        if f is None:
            problems.append(f"{i + 1}: resetMain() with no enclosing `fun` above it -- cannot judge it")
            continue
        top = f
        while top > 0 and HEADER.match(lines[top - 1]):
            top -= 1
        segment = "\n".join(code[top:i] + [code[i][:col]])  # comments stripped: a commented-out stop is no stop
        if MARKER in "\n".join(lines[top:i + 1]):
            continue
        if STOP in segment:
            continue
        after = STOP in "\n".join(code[i:i + 20])
        why = "stopViewModels() comes AFTER it" if after else "no stopViewModels(...) before it"
        problems.append(f"{i + 1}: resetMain() but {why}")
    return True, problems


def scan(src: pathlib.Path) -> tuple[list[pathlib.Path], dict[pathlib.Path, list[str]]]:
    files = sorted(p for d in sorted(src.glob("*Test")) for p in d.rglob("*.kt"))
    if not files:
        raise SystemExit(f"[FAIL] no Kotlin test sources under {src}/*Test -- a denominator of zero is not a pass")
    judged, bad = [], {}
    for f in files:
        applies, problems = judge(f)
        if applies:
            judged.append(f)
            if problems:
                bad[f] = problems
    if not judged:
        raise SystemExit(f"[FAIL] {len(files)} test file(s) read and NONE both builds a view model and resets "
                         "Main -- the tree plainly does both, so the parser is broken, not the tree clean")
    return judged, bad


def run(src: pathlib.Path, show: bool = False) -> int:
    judged, bad = scan(src)
    marked = [f for f in judged if MARKER in f.read_text(encoding="utf-8")]
    print(f"  test files that build a view model and reset Main: {len(judged)} "
          f"({len(marked)} on a test-scheduler Main, marked)")
    if show:
        for f in judged:
            tag = "FAIL" if f in bad else ("sched" if f in marked else "stop")
            print(f"    [{tag:5}] {f.relative_to(src)}")
    if bad:
        print("\n::error::a view model outlives its test -- stop it (cancel AND join) before resetting Main")
        for f, problems in bad.items():
            for p in problems:
                print(f"  {f.relative_to(src)}:{p}")
        print("\n  Record each view model the test makes (`.also { made += it }`) and call")
        print("  `stopViewModels(made)` before `Dispatchers.resetMain()` in @AfterTest.")
        print(f"  If Main is a StandardTestDispatcher, mark the tearDown `{MARKER}` instead.")
        return 1
    print("\n[OK] every view model a test builds is stopped before Main is reset")
    return 0


GOOD = """\
class GoodTest {
    private val made = mutableListOf<FooViewModel>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() {
        stopViewModels(made)
        Dispatchers.resetMain()
    }
    @Test fun t() { FooViewModel(api).also { made += it } }
}
"""
SCHED = """\
class SchedTest {
    private val d = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(d)
    // lint: test-scheduler-main -- Main is a test scheduler; a join would hang
    @AfterTest fun tearDown() = Dispatchers.resetMain()
    @Test fun t() { FooViewModel(api) }
}
"""
NO_VM = "class NoVm {\n    @AfterTest fun tearDown() { Dispatchers.resetMain() }\n}\n"
PLANTS = {
    "one-line tearDown, no stop": "class P {\n    @AfterTest fun tearDown() { Dispatchers.resetMain() }\n"
                                  "    @Test fun t() { FooViewModel(api) }\n}\n",
    "stop after reset": "class P {\n    @AfterTest fun tearDown() {\n        Dispatchers.resetMain()\n"
                        "        stopViewModels(made)\n    }\n    @Test fun t() { FooViewModel(api) }\n}\n",
    "stop only in a comment": "class P {\n    @AfterTest fun tearDown() {\n        // stopViewModels(made)\n"
                              "        Dispatchers.resetMain()\n    }\n    @Test fun t() { FooViewModel(api) }\n}\n",
    "stop in ANOTHER function": "class P {\n    fun other() { stopViewModels(made) }\n"
                                "    @AfterTest fun tearDown() {\n        Dispatchers.resetMain()\n    }\n"
                                "    @Test fun t() { FooViewModel(api) }\n}\n",
}


def self_test() -> int:
    def quiet(s: pathlib.Path) -> int:
        return _silent(run, s)

    with tempfile.TemporaryDirectory() as tmp:
        src = pathlib.Path(tmp) / "src"
        t = src / "commonTest" / "kotlin"
        t.mkdir(parents=True)
        # Zero files is not a pass.
        if _silent(_exits, src) is None:
            print("[FAIL] self-test: an empty tree did not fail loudly"); return 1
        (t / "NoVm.kt").write_text(NO_VM)
        if _silent(_exits, src) is None:
            print("[FAIL] self-test: a tree where nothing matches did not fail loudly"); return 1
        (t / "GoodTest.kt").write_text(GOOD)
        (t / "SchedTest.kt").write_text(SCHED)
        if quiet(src) != 0:
            print("[FAIL] self-test: a clean tree did not pass"); return 1
        for name, body in PLANTS.items():
            p = t / "Planted.kt"
            p.write_text(body)
            if quiet(src) == 0:
                print(f"[FAIL] self-test: planted '{name}' did not go red"); return 1
            p.unlink()
        (t / "SchedTest.kt").write_text(SCHED.replace(MARKER, "// a plain comment"))
        if quiet(src) == 0:
            print("[FAIL] self-test: a scheduler test without its marker did not go red"); return 1

    # The real tree, copied: strip every stopViewModels() call from one file that has it.
    with tempfile.TemporaryDirectory() as tmp:
        dst = pathlib.Path(tmp) / "src"
        for d in SRC.glob("*Test"):
            shutil.copytree(d, dst / d.name)
        victims = [f for f in sorted(dst.rglob("*.kt")) if judge(f)[0] and STOP in f.read_text(encoding="utf-8")]
        if not victims:
            print("[FAIL] self-test: no real test file calls stopViewModels() to plant a defect in"); return 1
        v = victims[0]
        v.write_text("\n".join(l for l in v.read_text(encoding="utf-8").split("\n") if STOP not in l))
        _, bad = _silent(scan, dst)
        if v not in bad:
            print(f"[FAIL] self-test: removing stopViewModels() from {v.name} did not go red"); return 1
    print("[OK] self-test: the check goes red on a deliberate break")
    return 0


def _exits(src: pathlib.Path):
    try:
        scan(src)
    except SystemExit as e:
        return e
    return None


def _silent(fn, *a):
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()):
        return fn(*a)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="show every file judged and how")
    ap.add_argument("--self-test", action="store_true", help="prove the check goes red on a break")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    return run(SRC, show=args.list)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
