"""Built CIRIS client bundles — agent flavor (CIRISBuild.HAS_AGENT = true).

Payload only. Nothing imports this directly; `ciris_client.artifact_path()`
finds it. See the repo README § The consumption contract.
"""

from pathlib import Path

FLAVOR = "agent"
HAS_AGENT = True
ARTIFACTS_DIR = Path(__file__).parent / "_artifacts"
