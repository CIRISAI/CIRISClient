"""Locating the built client bundles.

One distribution, ``ciris-client``, carries the client and an ``_artifacts/``
directory beside this module describing what it holds. This finds them, checks
they belong with this version, and hands back paths.

**One client, not two flavors.** The client used to ship as two payload
distributions compiled from one source with ``CIRISBuild.HAS_AGENT`` false and
true, because the agent surfaces were eliminated at COMPILE time and a node
consumer had no use for them. They are now gated at RUNTIME on the probed
``ClientMode`` instead, which is what the deployment actually requires: a node
can be upgraded with a brain, and when it is, the same installed client reveals
the agent surfaces on the next probe — no reinstall, no second wheel. So one
build ships, carrying everything, showing what the attached node can serve.

That also settles the packaging arithmetic. The desktop uber-jar is ~67 MiB and
compresses to a ~63 MiB wheel — 63% of PyPI's 104,857,600-byte limit on its own
(ProGuard is blocked on ktor 3.x, CIRISServer#379). Two of those in one wheel
did not fit; one does, with room for the artifacts still to come.

Every failure here is loud and says what to do. A resolver that quietly returns
a path to a placeholder is how a build ships an empty client.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

MANIFEST_SCHEMA = "ciris-client-artifacts/v1"

_ARTIFACTS_DIR = Path(__file__).resolve().parent / "_artifacts"


class ArtifactError(RuntimeError):
    """Base for every failure to produce a usable client artifact."""


class ArtifactUnavailable(ArtifactError):
    """The client is installed but carries no usable artifact of that kind."""


def manifest() -> dict[str, Any]:
    """The artifact manifest this installation carries.

    Raises if the payload's version disagrees with this package's — a mismatched
    pair would put one version in the banner and another in the bundle.
    """
    path = _ARTIFACTS_DIR / "manifest.json"
    if not path.is_file():
        raise ArtifactUnavailable(
            "this ciris-client carries no _artifacts/manifest.json — it was "
            "built without staging any Gradle output."
        )
    data = json.loads(path.read_text(encoding="utf-8"))

    schema = data.get("schema")
    if schema != MANIFEST_SCHEMA:
        raise ArtifactError(
            f"{path} declares schema {schema!r}, expected {MANIFEST_SCHEMA!r}"
        )

    from . import __version__

    payload_version = data.get("client_version")
    if payload_version != __version__:
        raise ArtifactError(
            f"version split: ciris-client is {__version__} but the bundles it "
            f"carries are {payload_version}. Reinstall a coherent build — "
            f'pip install "ciris-client=={__version__}"'
        )

    data["_root"] = str(_ARTIFACTS_DIR)
    return data


def _refuse_universal(data: dict[str, Any], kind: str) -> None:
    """A universal wheel has no OS payload, and that is a SUPPORTED state.

    Distinct from a placeholder on purpose. A placeholder means the build
    produced nothing and the wheel should not have shipped — a bug, reported as
    one. This means the wheel is the ``py3-none-any`` one, which exists because
    pip resolves on the WHEEL TAG: four OS-tagged wheels match nothing on
    Android or iOS, and dropping the any-tagged wheel made the package
    unresolvable there entirely (CIRISServer#493).

    So the answer is a fact, not a failure: there is no desktop jar here, the
    platform wheels have one, and the locale bundle — the part a non-desktop
    consumer actually needs — is present and works.
    """
    if data.get("universal"):
        raise ArtifactUnavailable(
            f"this is the UNIVERSAL (py3-none-any) ciris-client wheel: it carries the "
            f"locale bundle and no OS payload, so {kind!r} is not here — by design, "
            f"not by failure.\n"
            f"    locale_bundle() works from this wheel; artifact_path() does not.\n"
            f"    For a desktop jar, install on a platform with an OS wheel "
            f"(manylinux_2_17_x86_64, macosx_11_0_arm64, macosx_10_9_x86_64, "
            f"win_amd64) — pip picks it automatically there.\n"
            f"    Android and iOS consume the AAR and XCFramework from the GitHub "
            f"release, not from this wheel."
        )


def _refuse_placeholder(data: dict[str, Any]) -> None:
    if data.get("placeholder"):
        raise ArtifactUnavailable(
            "this ciris-client is a PLACEHOLDER build — it was packaged without "
            "a Gradle artifact staged into it, so there is no client here.\n"
            f"    reason: {data.get('placeholder_reason', 'not recorded')}\n"
            "Build it with: ./gradlew -p client :desktopApp:packageUberJarForCurrentOS"
        )


def artifacts() -> list[dict[str, Any]]:
    """Every artifact this installation carries, as manifest entries.

    Raises :class:`ArtifactUnavailable` on a placeholder build — callers are
    told to query this rather than assume artifact kinds, so an empty list here
    must mean "a real build carrying nothing", never "there was no build". Use
    :func:`manifest` to introspect a placeholder diagnostically.
    """
    data = manifest()
    _refuse_placeholder(data)
    return list(data.get("artifacts", []))


#: Files the locale bundle is meaningless without. Everything else it must
#: carry is DECLARED by manifest.json rather than listed here — see
#: :func:`_verify_bundle`.
_LOCALE_REQUIRED = ("en.json", "manifest.json")


def _verify_bundle(directory: "Path") -> list[str]:
    """Names the bundle promises and does not have. Empty = complete.

    Checking `en.json` and `manifest.json` was not enough. If Gradle packaging
    ever drops a single non-English locale — `yo.json`, say — those two files
    are still there, so the bundle looked complete while one audience silently
    lost its language. The source-tree localization guard cannot see that: it
    grades the repository, and this function answers for the JAR.
    manifest.json is the supported-language list, so it is also the thing to
    hold the bundle to.
    """
    import json as _json

    missing = [n for n in _LOCALE_REQUIRED if not (directory / n).is_file()]
    if missing:
        return missing
    try:
        declared = _json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except ValueError as e:
        return [f"manifest.json does not parse ({e})"]
    langs = declared.get("languages")
    if isinstance(langs, dict):
        codes = sorted(langs)
    elif isinstance(langs, list):
        codes = sorted(x.get("code", x) if isinstance(x, dict) else x for x in langs)
    else:
        # A manifest that declares no languages certifies nothing, and an empty
        # denominator must not read as a pass.
        return ["manifest.json declares no languages"]
    if not codes:
        return ["manifest.json declares no languages"]
    return [f"{c}.json" for c in codes if not (directory / f"{c}.json").is_file()]


def locale_bundle() -> Path:
    """The client's locale bundle, extracted from the desktop jar.

    Returns a directory holding ``en.json``, ``manifest.json`` and the 28 other
    locales — the same bytes the running client resolves against.

    **Why this exists.** CIRISServer emits operator messages as ``{id, text}``
    pairs, where ``id`` is a localization key with no Kotlin call site, and its
    release gates assert those ids actually RESOLVE against the client's bundle
    — the check that stops an operator reading a raw token. Those gates used to
    read `client/shared/.../localization` from a vendored tree. Once the tree is
    a dependency instead, the bundle is inside the shipped jar, and three gates
    each writing their own zip extraction is three chances to get it subtly
    different (a different member prefix, a different notion of "missing").

    The bundle is cached on disk, keyed by version and jar digest, so repeated
    calls and repeated CI steps pay the extraction once. Set
    ``CIRIS_CLIENT_CACHE`` to choose where; an unwritable cache falls back to a
    temporary directory rather than failing, because reading strings should not
    depend on the filesystem being hospitable.
    """
    import hashlib
    import os
    import shutil
    import tempfile
    import zipfile

    # The universal wheel STAGES the bundle rather than embedding it in a jar it
    # does not have. Served directly: no extraction, no cache, no jar — and the
    # same verification, because a bundle missing a locale is the same defect
    # whichever wheel it came from.
    data = manifest()
    _refuse_placeholder(data)
    staged = data.get("locales")
    if staged:
        here = _ARTIFACTS_DIR / staged
        missing = _verify_bundle(here)
        if missing:
            shown = missing if len(missing) <= 6 else missing[:6] + ["…"]
            raise ArtifactUnavailable(
                f"the staged locale bundle is missing {len(missing)} file(s) its own "
                f"manifest.json declares: {shown}"
            )
        return here

    jar = artifact_path("desktop-uber-jar")

    # Keyed by CONTENT, not just version: two builds of one version differ (the
    # jar carries a timestamp), and a stale cache silently answering for a jar
    # it did not come from is exactly the class of bug these gates exist to
    # catch.
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()[:16]
    from . import __version__

    stem = f"ciris-client-locale-{__version__}-{digest}"

    roots = []
    if os.environ.get("CIRIS_CLIENT_CACHE"):
        roots.append(Path(os.environ["CIRIS_CLIENT_CACHE"]))
    if os.environ.get("XDG_CACHE_HOME"):
        roots.append(Path(os.environ["XDG_CACHE_HOME"]) / "ciris-client")
    roots.append(Path.home() / ".cache" / "ciris-client")
    roots.append(Path(tempfile.gettempdir()) / "ciris-client")

    for root in roots:
        cached = root / stem
        if (cached / "en.json").is_file():
            # Verify the CACHE too. A bundle that was partial when it was
            # written stays partial forever otherwise, and the fast path is
            # where it would be read from every time after.
            stale = _verify_bundle(cached)
            if not stale:
                return cached
            shutil.rmtree(cached, ignore_errors=True)
        try:
            root.mkdir(parents=True, exist_ok=True)
            # Extract beside the target and rename, so a concurrent reader never
            # sees a half-written bundle.
            staging = Path(tempfile.mkdtemp(prefix=f"{stem}.", dir=root))
            with zipfile.ZipFile(jar) as zf:
                members = [
                    n for n in zf.namelist()
                    if n.startswith("localization/") and not n.endswith("/")
                ]
                if not members:
                    raise ArtifactUnavailable(
                        f"{jar.name} carries no localization/ entries — the jar "
                        f"is not the one this function was written for."
                    )
                for name in members:
                    target = staging / Path(name).name
                    target.write_bytes(zf.read(name))
            missing = _verify_bundle(staging)
            if missing:
                shown = missing if len(missing) <= 6 else missing[:6] + ["…"]
                raise ArtifactUnavailable(
                    f"the extracted locale bundle is missing {len(missing)} file(s) "
                    f"its own manifest.json declares: {shown}. Refusing to hand back "
                    f"a partial bundle a gate would then read as an absence — an id "
                    f"that resolves in 28 languages and not the 29th is exactly the "
                    f"defect these gates exist to catch."
                )
            try:
                staging.rename(cached)
            except OSError:
                # Lost the race to another process; theirs is as good as ours.
                if not (cached / "en.json").is_file():
                    raise
            return cached
        except OSError:
            continue  # this root is not writable — try the next

    raise ArtifactUnavailable(
        f"could not extract the locale bundle to any of {[str(r) for r in roots]}; "
        f"set CIRIS_CLIENT_CACHE to a writable directory"
    )


def _current_platform() -> str:
    """This interpreter's platform, in the manifest's `platform` vocabulary."""
    import platform as _platform

    machine = _platform.machine().lower()
    arch = {"amd64": "x86_64", "aarch64": "arm64"}.get(machine, machine)
    if sys.platform.startswith("linux"):
        return f"linux-{arch}"
    if sys.platform == "darwin":
        return f"darwin-{arch}"
    if sys.platform in ("win32", "cygwin"):
        return f"windows-{arch}"
    return f"{sys.platform}-{arch}"


def _wasm_bundle() -> Path:
    """The browser bundle, from its own distribution.

    It ships separately because it is the one artifact here that is host-
    independent, and because its consumer (a node serving the web UI) runs no
    JVM — pairing it with a 67 MiB desktop jar would make every web deployment
    download one.
    """
    try:
        import ciris_client_wasm
    except ImportError as exc:
        raise ArtifactUnavailable(
            "the wasm browser bundle ships in its own distribution, which is "
            "not installed.\n"
            '    pip install "ciris-client[web]"     # or: pip install ciris-client-wasm'
        ) from exc

    from . import __version__

    if ciris_client_wasm.__version__ != __version__:
        raise ArtifactError(
            f"version split: ciris-client is {__version__} but "
            f"ciris-client-wasm is {ciris_client_wasm.__version__}. Install a "
            f'matching pair — pip install "ciris-client[web]=={__version__}"'
        )
    return ciris_client_wasm.bundle_path()


def artifact_path(kind: str) -> Path:
    if kind == "wasm-browser":
        return _wasm_bundle()

    """Absolute path to the built artifact of ``kind``.

    ``kind`` is whatever the Gradle build staged — ``desktop-uber-jar``,
    ``shared-desktop-jar``, ``android-aar``. Ask :func:`artifacts` what is
    present rather than assuming; the set grows with the build.
    """
    data = manifest()
    _refuse_placeholder(data)

    entries = {a["kind"]: a for a in data.get("artifacts", [])}
    if kind not in entries:
        # Say WHICH wheel this is before saying what it lacks. "carries no
        # 'desktop-uber-jar'" reads as a broken build; on the universal wheel it
        # is the design, and the difference decides whether the reader goes
        # looking for a bug or installs the right wheel.
        _refuse_universal(data, kind)
        raise ArtifactUnavailable(
            f"this ciris-client carries no {kind!r} artifact; it has "
            f"{sorted(entries) or '[]'}"
        )
    entry = entries[kind]

    # A platform-stamped artifact on the wrong OS is not a client, it is a
    # confusing crash later. Refuse here, with both names, rather than there.
    built_for = entry.get("platform")
    if built_for is not None and built_for != _current_platform():
        raise ArtifactUnavailable(
            f"the installed {kind!r} was built for {built_for!r} but this "
            f"machine is {_current_platform()!r}. This wheel carries one OS's "
            f"desktop runtime; install the wheel built for this platform (or "
            f"build locally: ./gradlew -p client "
            f":desktopApp:packageUberJarForCurrentOS)"
        )

    path = Path(data["_root"]) / entry["path"]
    if not path.is_file():
        raise ArtifactUnavailable(
            f"manifest lists {kind!r} at {path}, but the file is missing — the "
            f"distribution was built from a manifest that outran its payload."
        )
    return path
