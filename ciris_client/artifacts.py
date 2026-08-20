"""Locating the built client bundles.

Each flavor ships as its own distribution (``ciris-client-node``,
``ciris-client-agent``) providing a top-level package that carries an
``_artifacts/`` directory and a ``manifest.json`` describing it. This module
finds them, checks they belong with this version, and hands back paths.

Why a separate distribution per flavor rather than one wheel with both: the
desktop uber-jar alone is ~46 MiB of ``.class`` (ProGuard is blocked on ktor
3.x — CIRISServer#379). Two flavors plus an Android AAR in one wheel runs at
the PyPI limit of 104,857,600 bytes with no room for the localization bundles,
which are the product and are never cut to save size. A consumer needs one
flavor, so it downloads one.

Every failure here is loud and says what to do. A resolver that quietly returns
a path to a placeholder is how a build ships an empty client.
"""

from __future__ import annotations

import importlib
import json
from pathlib import Path
from types import ModuleType
from typing import Any

#: The two build flavors. ``node`` is the base product (the AI-free client,
#: ``CIRISBuild.HAS_AGENT == false``); ``agent`` is the superset.
FLAVORS: tuple[str, ...] = ("node", "agent")

_PAYLOAD_MODULE = {"node": "ciris_client_node", "agent": "ciris_client_agent"}

MANIFEST_SCHEMA = "ciris-client-artifacts/v1"


class ArtifactError(RuntimeError):
    """Base for every failure to produce a usable client artifact."""


class FlavorNotInstalled(ArtifactError):
    """The requested flavor's payload distribution is not installed."""


class ArtifactUnavailable(ArtifactError):
    """The payload is installed but carries no usable artifact of that kind."""


def _payload(flavor: str) -> ModuleType:
    if flavor not in FLAVORS:
        raise ValueError(f"unknown flavor {flavor!r}; expected one of {FLAVORS}")
    module = _PAYLOAD_MODULE[flavor]
    try:
        return importlib.import_module(module)
    except ImportError as exc:
        raise FlavorNotInstalled(
            f"the {flavor!r} client bundles are not installed "
            f"(no module {module!r}).\n"
            f'    pip install "ciris-client[{flavor}]"'
        ) from exc


def installed_flavors() -> tuple[str, ...]:
    """Which flavors' payload distributions are importable right now."""
    found = []
    for flavor in FLAVORS:
        try:
            importlib.import_module(_PAYLOAD_MODULE[flavor])
        except ImportError:
            continue
        found.append(flavor)
    return tuple(found)


def _sole_flavor() -> str:
    found = installed_flavors()
    if not found:
        raise FlavorNotInstalled(
            "no client bundles are installed.\n"
            '    pip install "ciris-client[node]"    # the AI-free node client\n'
            '    pip install "ciris-client[agent]"   # the agent build'
        )
    if len(found) > 1:
        # Both installed and no flavor named. Refusing beats guessing: the two
        # differ in which surfaces exist at all, so picking one silently ships
        # an app the caller did not ask for.
        raise ArtifactError(
            f"both flavors are installed {found}; pass flavor= explicitly, or "
            f"install exactly one. They are the same client compiled with "
            f"CIRISBuild.HAS_AGENT false and true."
        )
    return found[0]


def manifest(flavor: str | None = None) -> dict[str, Any]:
    """The artifact manifest for ``flavor`` (default: the only one installed).

    Raises if the payload's version disagrees with this package's — a mismatched
    pair would put one version in the banner and another in the bundle.
    """
    flavor = flavor or _sole_flavor()
    payload = _payload(flavor)
    root = Path(payload.__file__).parent  # type: ignore[arg-type]
    path = root / "_artifacts" / "manifest.json"
    if not path.is_file():
        raise ArtifactUnavailable(
            f"{payload.__name__} carries no _artifacts/manifest.json — the "
            f"distribution was built without staging any Gradle output."
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
            f"version split: ciris-client is {__version__} but the {flavor!r} "
            f"bundles are {payload_version}. Install a matching pair — "
            f'pip install "ciris-client[{flavor}]=={__version__}"'
        )

    data["_root"] = str(root / "_artifacts")
    return data


def artifacts(flavor: str | None = None) -> list[dict[str, Any]]:
    """Every artifact the installed payload carries, as manifest entries."""
    return list(manifest(flavor).get("artifacts", []))


def artifact_path(kind: str, flavor: str | None = None) -> Path:
    """Absolute path to the built artifact of ``kind`` for ``flavor``.

    ``kind`` is whatever the Gradle build staged — ``desktop-uber-jar``,
    ``shared-desktop-jar``, ``android-aar``. Ask :func:`artifacts` what is
    present rather than assuming; the set grows with the build.
    """
    flavor = flavor or _sole_flavor()
    data = manifest(flavor)

    if data.get("placeholder"):
        raise ArtifactUnavailable(
            f"the {flavor!r} payload is a PLACEHOLDER — it was built without a "
            f"Gradle artifact staged into it, so there is no client here.\n"
            f"    reason: {data.get('placeholder_reason', 'not recorded')}\n"
            f"Build it with: ./gradlew -p client :desktopApp:packageUberJarForCurrentOS"
            + (" -PhasAgent=true" if flavor == "agent" else "")
        )

    entries = {a["kind"]: a for a in data.get("artifacts", [])}
    if kind not in entries:
        raise ArtifactUnavailable(
            f"the {flavor!r} payload carries no {kind!r} artifact; it has "
            f"{sorted(entries) or '[]'}"
        )

    path = Path(data["_root"]) / entries[kind]["path"]
    if not path.is_file():
        raise ArtifactUnavailable(
            f"manifest lists {kind!r} at {path}, but the file is missing — the "
            f"distribution was built from a manifest that outran its payload."
        )
    return path
