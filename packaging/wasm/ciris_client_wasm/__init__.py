"""The CIRIS client's WebAssembly browser bundle.

    import ciris_client_wasm
    ciris_client_wasm.bundle_path()      # the directory to serve
    ciris_client_wasm.index_html()       # its entry point

Serve the whole directory: the loader `.js`, the `.wasm` modules it fetches,
`index.html`, and `composeResources/`. Serving only `index.html` gets a blank
page, which is why :func:`bundle_path` checks the parts before returning.

`ciris_client` resolves this too, when installed —
`ciris_client.artifact_path("wasm-browser")` — so a consumer holding the
resolver API does not need to special-case the web bundle.
"""

from __future__ import annotations

from pathlib import Path

_ARTIFACTS = Path(__file__).resolve().parent / "_artifacts"

#: Files the bundle is useless without — a loader with no module is a blank page.
_REQUIRED = ("index.html", "ciris-shared.js", "ciris-shared.wasm")


class BundleUnavailable(RuntimeError):
    """The package is installed but carries no usable bundle."""


def bundle_path() -> Path:
    """The directory to serve. Raises if it is absent or incomplete.

    The location comes from the staged manifest rather than a hardcoded
    directory name: the Gradle task's output directory is its business, not
    this package's, and hardcoding it means a rename ships a blank page.
    """
    import json

    manifest = _ARTIFACTS / "manifest.json"
    if not manifest.is_file():
        raise BundleUnavailable(
            "ciris-client-wasm carries no _artifacts/manifest.json — it was "
            "built without staging the Gradle output."
        )
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("placeholder"):
        raise BundleUnavailable(
            f"this ciris-client-wasm is a PLACEHOLDER build — no bundle here.\n"
            f"    reason: {data.get('placeholder_reason', 'not recorded')}"
        )
    entries = [a for a in data.get("artifacts", []) if a["kind"] == "wasm-browser"]
    if not entries:
        raise BundleUnavailable(
            "the manifest lists no wasm-browser artifact; this wheel carries "
            f"{[a['kind'] for a in data.get('artifacts', [])] or '[]'}"
        )

    root = _ARTIFACTS / entries[0]["path"]
    missing = [n for n in _REQUIRED if not (root / n).is_file()]
    if missing:
        raise BundleUnavailable(
            f"the wasm bundle at {root} is incomplete: missing {missing}. "
            f"Serving it would give a blank page; refusing to hand back the "
            f"directory."
        )
    return root


def index_html() -> Path:
    """The bundle's entry point."""
    return bundle_path() / "index.html"


def _version() -> str:
    from importlib.metadata import PackageNotFoundError, version

    try:
        return version("ciris-client-wasm")
    except PackageNotFoundError:
        v = Path(__file__).resolve().parents[2] / "VERSION"
        return v.read_text(encoding="utf-8").strip() if v.is_file() else "0.0.0"


__version__ = _version()
__all__ = ["BundleUnavailable", "__version__", "bundle_path", "index_html"]
