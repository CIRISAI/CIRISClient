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
