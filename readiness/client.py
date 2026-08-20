"""Client readiness gates.

These answer: *are the requisites in place to build a client I can trust?*

The client source is HERE, under `client/` — extracted from CIRISAgent and
vendored with its provenance in `client/VENDORING.md`. The default `--client-tree`
is that tree. The gates themselves did not change: the same questions, asked of
the tree this repo now owns. CIRISServer/client and CIRISAgent/client still
exist and still diverge until they switch to consuming the wheel, so keep
grading them too:

    python -m readiness --client-tree ~/CIRISServer/client
    python -m readiness --client-tree ~/CIRISAgent/client

Requisite classes, per CIRISConformance#86 §4:
  code       wheels, substrate binaries, generated-api
  data       OpenAPI spec, locale bundle + root, templates
  normative  CC version, wire vocabulary, the gate registry
"""

from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

from grace.gate import (
    ERROR,
    FAIL,
    PASS,
    UNIMPLEMENTED,
    Context,
    Result,
    gate,
    have,
    run,
)

CLIENT_VERSION_RE = re.compile(r'const val CLIENT_VERSION = "([^"]+)"')
CARGO_VERSION_RE = re.compile(r'^version = "([^"]+)"', re.M)
ENUM_ENTRY_RE = re.compile(r"^[ \t]{4}([A-Z][A-Z0-9_]*)\(", re.M)
GATE_REPO_RE = re.compile(r'repo\s*=\s*"(CIRIS[A-Za-z]+)"')
GATE_ISSUE_RE = re.compile(r"issueNumber\s*=\s*(\d+)")


def parse_substrate_gates(text: str) -> list[dict]:
    """Pull (name, repo, issue) out of the SubstrateGate enum.

    Entries use named arguments across several lines, so this finds the entry
    head and reads a window after it rather than matching one line.
    """
    out = []
    for m in ENUM_ENTRY_RE.finditer(text):
        window = text[m.end() : m.end() + 500]
        repo = GATE_REPO_RE.search(window)
        issue = GATE_ISSUE_RE.search(window)
        if repo and issue:
            out.append({"name": m.group(1), "repo": repo.group(1), "issue": int(issue.group(1))})
    return out

# The four committed runtime bundles. Canonical first; the rest must match it
# byte for byte until the locale Merkle root replaces this check entirely.
BUNDLES = (
    "shared/src/desktopMain/resources/localization",
    "androidApp/src/main/assets/localization",
    "desktopApp/src/main/resources/localization",
    "iosApp/iosApp/localization",
)


#: This repo's own vendored tree — the default subject since the extraction.
IN_REPO_CLIENT = Path(__file__).resolve().parents[1] / "client"


def client_tree(ctx: Context) -> Path:
    override = getattr(ctx, "client_tree", None)
    if override:
        return Path(override)
    if IN_REPO_CLIENT.is_dir():
        return IN_REPO_CLIENT
    # Installed from a wheel rather than a checkout: there is no source tree to
    # read. Fall back to where the source used to live rather than pretending
    # an empty path is a client.
    return ctx.repo("CIRISServer") / "client"


@gate("toolchain", "Are the build tools present for the platforms we target?")
def toolchain(ctx: Context) -> Result:
    tree = client_tree(ctx)
    found, missing = {}, []

    rc, out = run(["java", "-version"], timeout=30)
    if rc == 0:
        m = re.search(r'version "(\d+)', out)
        major = int(m.group(1)) if m else 0
        found["java"] = out.splitlines()[0] if out else "?"
        if major and major < 17:
            missing.append(f"java {major} < 17")
    else:
        missing.append("java")

    wrapper = tree / "gradlew"
    if wrapper.exists():
        found["gradlew"] = str(wrapper)
    else:
        missing.append(f"gradlew ({wrapper})")

    for tool, label in (("xcodebuild", "ios"), ("adb", "android")):
        found[label] = "present" if have(tool) else "absent"

    if missing:
        return Result("toolchain", FAIL, "missing: " + ", ".join(missing), {"found": found})
    return Result("toolchain", PASS, "jdk17+ and gradle wrapper present", {"found": found})


@gate("version-alignment", "Does CLIENT_VERSION match the node it ships against?")
def version_alignment(ctx: Context) -> Result:
    """One integer, three enforcement points upstream — this is the fourth.

    The client's CLIENT_VERSION is authoritative in committed source and is
    kept in lockstep with the node's Cargo.toml version by a script and a
    pre-commit hook. Drift shows the user a mismatch banner against the node
    bundled in the same artifact.
    """
    tree = client_tree(ctx)
    cargo = tree.parent / "Cargo.toml"

    # TWO LAYOUTS, one question. In this repo CLIENT_VERSION is generated at
    # build time from the repo-root VERSION file (client/VENDORING.md §4), so
    # the committed constant is gone on purpose. In CIRISServer/client and
    # CIRISAgent/client — which this gate must still grade, they diverge and a
    # result from one is not a result about the client — it is still a
    # hand-edited const in ClientMode.kt.
    version_file = tree.parent / "VERSION"
    cv_file = tree / "shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/ClientMode.kt"

    if version_file.is_file() and version_file.read_text().strip():
        client_v, source = version_file.read_text().strip(), "VERSION"
    elif cv_file.exists():
        m = CLIENT_VERSION_RE.search(cv_file.read_text(errors="replace"))
        if not m:
            # Neither source. Loud, per the Gate Rules: a version this gate
            # cannot read is not a version that matches.
            return Result(
                "version-alignment",
                ERROR,
                f"no VERSION file beside the tree and no CLIENT_VERSION const in {cv_file.name}",
            )
        client_v, source = m.group(1), "ClientMode.kt"
    else:
        return Result("version-alignment", ERROR, f"not found: {version_file} or {cv_file}")
    if not cargo.exists():
        return Result(
            "version-alignment",
            UNIMPLEMENTED,
            f"CLIENT_VERSION={client_v} (from {source}); no Cargo.toml beside the tree",
            {"client": client_v, "source": source},
        )
    cm = CARGO_VERSION_RE.search(cargo.read_text(errors="replace"))
    node_v = cm.group(1) if cm else None
    if client_v != node_v:
        return Result(
            "version-alignment",
            FAIL,
            f"CLIENT_VERSION={client_v} but node is {node_v}",
            {"client": client_v, "node": node_v, "source": source},
        )
    return Result(
        "version-alignment", PASS, f"both {client_v}", {"client": client_v, "source": source}
    )


@gate("locale-parity", "Do the runtime locale bundles agree, and how complete are they?")
def locale_parity(ctx: Context) -> Result:
    """Byte-identity across the four committed bundles + per-locale coverage.

    The parity half duplicates the client's own guard on purpose: this runs
    before a build, that one runs in CI. The coverage half is new — it is the
    number a release policy can gate on when the locale count grows.
    """
    tree = client_tree(ctx)
    canonical = tree / BUNDLES[0]
    if not canonical.exists():
        return Result("locale-parity", ERROR, f"canonical bundle missing: {canonical}")

    canon_files = {p.name: p.read_bytes() for p in sorted(canonical.glob("*.json"))}
    problems = []
    for rel in BUNDLES[1:]:
        other = tree / rel
        if not other.exists():
            problems.append(f"{rel}: missing")
            continue
        names = {p.name for p in other.glob("*.json")}
        for extra in sorted(names - set(canon_files)):
            problems.append(f"{rel}: extra {extra}")
        for name, blob in canon_files.items():
            if name not in names:
                problems.append(f"{rel}: missing {name}")
            elif (other / name).read_bytes() != blob:
                problems.append(f"{rel}/{name}: differs from canonical")

    def flatten(obj, prefix=""):
        keys = set()
        if isinstance(obj, dict):
            for k, v in obj.items():
                keys |= flatten(v, f"{prefix}{k}.")
        else:
            keys.add(prefix.rstrip("."))
        return keys

    coverage = {}
    en = canonical / "en.json"
    if en.exists():
        base = flatten(json.loads(en.read_text()))
        for name, blob in canon_files.items():
            if name in ("en.json", "manifest.json"):
                continue
            try:
                have_keys = flatten(json.loads(blob))
            except json.JSONDecodeError:
                problems.append(f"{name}: invalid JSON")
                continue
            coverage[name[:-5]] = round(100 * len(have_keys & base) / max(len(base), 1), 1)

    worst = min(coverage.values()) if coverage else 100.0
    locales = len(canon_files)
    if problems:
        return Result(
            "locale-parity",
            FAIL,
            f"{len(problems)} bundle problem(s); {locales} locales; worst coverage {worst}%",
            {"problems": problems[:40], "coverage": coverage},
        )
    return Result(
        "locale-parity",
        PASS,
        f"{locales} locales, bundles identical, worst coverage {worst}%",
        {"coverage": coverage},
    )


@gate("spec-drift", "Does the committed OpenAPI spec match what the node serves?")
def spec_drift(ctx: Context) -> Result:
    """The spec the client is judged against must come from the node.

    A spec the client owns cannot be the contract the client is graded on.
    Until the node publishes it, this gate needs `--node <url>` to ask.
    """
    tree = client_tree(ctx)
    committed = tree / "openapi.json"
    if not committed.exists():
        return Result("spec-drift", ERROR, f"not found: {committed}")
    if not ctx.node_url:
        return Result(
            "spec-drift",
            UNIMPLEMENTED,
            "no --node given; cannot compare committed spec to a served one",
            {"committed": str(committed)},
        )
    url = ctx.node_url.rstrip("/") + "/openapi.json"
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            served = json.load(r)
    except Exception as exc:
        return Result("spec-drift", ERROR, f"{url}: {exc}")
    local = json.loads(committed.read_text())
    lp, sp = set(local.get("paths", {})), set(served.get("paths", {}))
    if lp != sp:
        return Result(
            "spec-drift",
            FAIL,
            f"{len(sp - lp)} served path(s) missing locally, {len(lp - sp)} stale",
            {"missing_locally": sorted(sp - lp)[:40], "stale": sorted(lp - sp)[:40]},
        )
    return Result("spec-drift", PASS, f"{len(lp)} paths match", {"paths": len(lp)})


@gate("surface-binding", "Does every documented endpoint reach a client surface?")
def surface_binding(ctx: Context) -> Result:
    """The gap report: endpoints the client never mentions.

    HEURISTIC — it greps the shared module for each path literal, so a path
    built by string concatenation reads as unbound. Treat the output as a
    worklist to confirm, not a verdict. It exists to turn silent omission
    into something with a number attached.
    """
    tree = client_tree(ctx)
    spec = tree / "openapi.json"
    shared = tree / "shared" / "src" / "commonMain"
    if not spec.exists() or not shared.exists():
        return Result("surface-binding", ERROR, "openapi.json or shared/commonMain missing")
    paths = list(json.loads(spec.read_text()).get("paths", {}))
    blob = "\n".join(
        p.read_text(errors="replace") for p in shared.rglob("*.kt")
    )
    # Compare on the literal prefix before any {param}, which is what shows up
    # in Kotlin source when a URL is templated.
    unbound = []
    for path in paths:
        stem = path.split("{")[0].rstrip("/")
        if stem and stem not in blob:
            unbound.append(path)
    pct = round(100 * (len(paths) - len(unbound)) / max(len(paths), 1), 1)
    status = PASS if not unbound else FAIL
    return Result(
        "surface-binding",
        status,
        f"{len(paths) - len(unbound)}/{len(paths)} documented paths referenced ({pct}%)",
        {"unbound": sorted(unbound)[:60], "heuristic": True},
    )


@gate("nav-gate-registry", "Is every SubstrateGate pointing at an open issue?")
def nav_gate_registry(ctx: Context) -> Result:
    """SubstrateGate(repo, issue, prefixFamily, fsdSection) in the nav tree.

    A surface declaring a blocking issue that is already closed is a surface
    that should have lifted its gate. A closed issue here is the client-side
    twin of a strict xfail that started passing.
    """
    tree = client_tree(ctx)
    nav = tree / "shared/src/commonMain/kotlin/ai/ciris/mobile/shared/ui/nav/EpistemicNav.kt"
    if not nav.exists():
        return Result("nav-gate-registry", ERROR, f"not found: {nav}")
    text = nav.read_text(errors="replace")
    entries = parse_substrate_gates(text)
    if not entries:
        # A parser that finds nothing where the enum plainly exists is a false
        # negative, which is worse than no gate. Fail loudly instead.
        if "enum class SubstrateGate" in text:
            return Result(
                "nav-gate-registry",
                FAIL,
                "SubstrateGate enum is present but the parser read zero entries — parser is wrong",
            )
        return Result("nav-gate-registry", PASS, "no SubstrateGate enum in this tree")
    if ctx.offline or not have("gh"):
        return Result(
            "nav-gate-registry",
            UNIMPLEMENTED,
            f"{len(entries)} gate(s) declared; need gh + network to check issue state",
            {"gates": entries},
        )
    stale = []
    for e in entries:
        rc, out = run(
            ["gh", "api", f"repos/CIRISAI/{e['repo']}/issues/{e['issue']}", "--jq", ".state"]
        )
        if rc == 0 and out.strip() == "closed":
            stale.append(f"{e['name']} → {e['repo']}#{e['issue']} (closed)")
    if stale:
        return Result(
            "nav-gate-registry",
            FAIL,
            f"{len(stale)} gate(s) still declared on closed issues",
            {"stale": stale},
        )
    return Result("nav-gate-registry", PASS, f"{len(entries)} gate(s), all issues open")


@gate("substrate-binaries", "Are the per-platform substrate artifacts present?")
def substrate_binaries(ctx: Context) -> Result:
    tree = client_tree(ctx)
    wheels = sorted((tree / "androidApp" / "wheels").glob("*.whl"))
    jni = sorted((tree / "androidApp" / "src" / "main" / "jniLibs").rglob("*.so"))
    if not wheels and not jni:
        # In THIS repo that is by design and is written down: the substrate is
        # other repositories' release artifacts, deliberately not vendored
        # (client/VENDORING.md §2). Still a FAIL — a tree without them cannot
        # produce a device build, and a gate that passes on a documented absence
        # is a gate that has learned to say yes.
        note = (
            " — deliberate here: see client/VENDORING.md §2 for the excluded set "
            "and how to re-hydrate it"
            if tree == IN_REPO_CLIENT
            else ""
        )
        return Result(
            "substrate-binaries",
            FAIL,
            f"no wheels or jniLibs in the client tree{note}",
            {"by_design": tree == IN_REPO_CLIENT},
        )
    return Result(
        "substrate-binaries",
        PASS,
        f"{len(wheels)} wheel(s), {len(jni)} native lib(s)",
        {"wheels": [w.name for w in wheels], "jniLibs": len(jni)},
    )


@gate("generated-api-drift", "Does generated-api match the spec it claims to come from?")
def generated_api_drift(ctx: Context) -> Result:
    """Not implemented: regeneration is not in the build graph.

    `generated-api` is ~730 committed files produced by openapi-generator
    (config in `openapi-generator-config.yaml`), and nothing re-runs the
    generator, so spec drift is currently silent. Implementing this means
    regenerating into a temp dir and diffing.
    """
    return Result(
        "generated-api-drift",
        UNIMPLEMENTED,
        "generator is not wired into the build; drift is unchecked (Conformance#86 §4)",
    )
