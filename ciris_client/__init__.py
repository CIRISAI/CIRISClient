"""ciris-client — the CIRIS Kotlin Multiplatform client, as a dependency.

The client is built by Gradle, not by pip. This package is how a Python
consumer (CIRISServer, CIRISAgent) finds the built bundles and asks which node
version they pair with, without either repo keeping its own copy of ~200k lines
of Kotlin.

    import ciris_client

    ciris_client.__version__                     # '0.5.181' — pairs with ciris-server 0.5.181
    ciris_client.installed_flavors()             # ('node',)
    ciris_client.artifact_path('desktop-uber-jar')

The bundles ship in a separate distribution per flavor, pulled in by an extra:

    pip install "ciris-client[node]"     # HAS_AGENT = false
    pip install "ciris-client[agent]"    # HAS_AGENT = true

Install exactly one. See README § The consumption contract.
"""

from __future__ import annotations

from .artifacts import (
    FLAVORS,
    ArtifactError,
    ArtifactUnavailable,
    FlavorNotInstalled,
    artifact_path,
    artifacts,
    installed_flavors,
    manifest,
)

__all__ = [
    "FLAVORS",
    "ArtifactError",
    "ArtifactUnavailable",
    "FlavorNotInstalled",
    "__version__",
    "artifact_path",
    "artifacts",
    "installed_flavors",
    "manifest",
]


def _version() -> str:
    from importlib.metadata import PackageNotFoundError, version

    try:
        return version("ciris-client")
    except PackageNotFoundError:
        # Running from a source checkout rather than an install. Read the same
        # file the wheel's version and the Kotlin CLIENT_VERSION come from.
        from pathlib import Path

        v = Path(__file__).resolve().parents[1] / "VERSION"
        if v.is_file():
            return v.read_text(encoding="utf-8").strip()
        return "0+unknown"


#: The client version, which is also the ``ciris-server`` version it targets.
#: The Kotlin ``CLIENT_VERSION`` constant is generated from the same source, so
#: the version-mismatch banner and the wheel cannot disagree.
__version__: str = _version()
