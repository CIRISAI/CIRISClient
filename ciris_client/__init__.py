"""ciris-client — the CIRIS Kotlin Multiplatform client, as a dependency.

The client is built by Gradle, not by pip. This package is how a Python
consumer (CIRISServer, CIRISAgent) finds the built bundles and asks which node
version they pair with, without either repo keeping its own copy of ~200k lines
of Kotlin.

    import ciris_client

    ciris_client.__version__                     # '0.5.186' — pairs with ciris-server 0.5.186
    ciris_client.artifacts()                     # what this build carries
    ciris_client.artifact_path('desktop-uber-jar')

    pip install ciris-client

ONE client, not a flavor per consumer. The agent surfaces are carried by every
build and gated at RUNTIME on the probed node, so a node that is later upgraded
with a brain reveals them without reinstalling anything. See README § The
consumption contract.
"""

from __future__ import annotations

from .artifacts import (
    ArtifactError,
    ArtifactUnavailable,
    artifact_path,
    artifacts,
    locale_bundle,
    manifest,
)

__all__ = [
    "ArtifactError",
    "ArtifactUnavailable",
    "__version__",
    "artifact_path",
    "artifacts",
    "locale_bundle",
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
