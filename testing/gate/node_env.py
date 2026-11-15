"""The environment a test node is started with.

From ciris-server 0.5.220 (persist v53 / edge S1) a node with no declared
`device_class` is a SERVER, and a server-class node is given no keys for a
person's self or family content: writing a note on it fails with "viewer …
holds no key_grant" (five-platform run 37224452548, csd_008 on every desktop
leg). The nodes these fixtures start stand in for a person's own device, so
they declare `laptop` — as the client app does for the node it spawns (#153)
and ciris-server's own desktop launcher does. A class already in the
environment is kept.

From ciris-server 0.5.247 a harness node also STATES AN END for its own
identity (`CIRIS_NODE_IDENTITY_TTL_S`, src/identity_ttl.rs). These nodes reach
the canonical and announce to it, as a production node does, and every run
left its key record, occurrence and owner binding there for good. With the
variable the end is signed into each of those rows and they lapse everywhere.

ONLY ON A CI RUNNER, where the home is new each job. A home that outlives the
end holds an identity that has lapsed, and that node probably cannot write
again: never set this for a home that is reused, which is every developer's.
An end already in the environment is kept.
"""
from __future__ import annotations

import os
from typing import Dict, Mapping, Optional

DEVICE_CLASS = "laptop"

IDENTITY_TTL_ENV = "CIRIS_NODE_IDENTITY_TTL_S"
# Three hours: longer than the longest leg's job limit (120 minutes), so no row
# lapses under a running leg. testing/test_node_env.py holds it to the workflow.
IDENTITY_TTL_S = "10800"


def node_env(base: Optional[Mapping[str, str]] = None) -> Dict[str, str]:
    env = dict(os.environ if base is None else base)
    if not env.get("CIRIS_DEVICE_CLASS", "").strip():
        env["CIRIS_DEVICE_CLASS"] = DEVICE_CLASS
    if env.get("GITHUB_ACTIONS") == "true" and not env.get(IDENTITY_TTL_ENV, "").strip():
        env[IDENTITY_TTL_ENV] = IDENTITY_TTL_S
    return env
