"""The environment a test node is started with.

From ciris-server 0.5.220 (persist v53 / edge S1) a node with no declared
`device_class` is a SERVER, and a server-class node is given no keys for a
person's self or family content: writing a note on it fails with "viewer …
holds no key_grant" (five-platform run 37224452548, csd_008 on every desktop
leg). The nodes these fixtures start stand in for a person's own device, so
they declare `laptop` — as the client app does for the node it spawns (#153)
and ciris-server's own desktop launcher does. A class already in the
environment is kept.
"""
from __future__ import annotations

import os
from typing import Dict, Mapping, Optional

DEVICE_CLASS = "laptop"


def node_env(base: Optional[Mapping[str, str]] = None) -> Dict[str, str]:
    env = dict(os.environ if base is None else base)
    if not env.get("CIRIS_DEVICE_CLASS", "").strip():
        env["CIRIS_DEVICE_CLASS"] = DEVICE_CLASS
    return env
