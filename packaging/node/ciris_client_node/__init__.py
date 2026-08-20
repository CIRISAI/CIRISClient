"""Built CIRIS client bundles — node flavor (CIRISBuild.HAS_AGENT = false).

Payload only. Nothing imports this directly; `ciris_client.artifact_path()`
finds it. See the repo README § The consumption contract.
"""

from pathlib import Path

FLAVOR = "node"
HAS_AGENT = False
ARTIFACTS_DIR = Path(__file__).parent / "_artifacts"
