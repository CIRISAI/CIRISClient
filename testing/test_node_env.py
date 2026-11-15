from pathlib import Path

from testing.gate.node_env import node_env


def test_a_test_node_is_a_laptop_when_nothing_is_set():
    assert node_env({})["CIRIS_DEVICE_CLASS"] == "laptop"
    assert node_env({"CIRIS_DEVICE_CLASS": "  "})["CIRIS_DEVICE_CLASS"] == "laptop"


def test_a_class_already_set_is_kept():
    assert node_env({"CIRIS_DEVICE_CLASS": "phone"})["CIRIS_DEVICE_CLASS"] == "phone"


def test_the_rest_of_the_environment_is_passed_through():
    env = node_env({"PATH": "/bin", "X": "1"})
    assert env["PATH"] == "/bin" and env["X"] == "1"


def test_both_fixtures_start_their_node_with_it():
    """From ciris-server 0.5.220 a node with no declared class is a server and
    is given no keys for a person's self content (run 37224452548, csd_008)."""
    root = Path(__file__).resolve().parent.parent
    for f in ("testing/gate/node_fixture.py", "testing/gate/two_node.py", "testing/node_fixture.py"):
        assert "env=node_env()" in (root / f).read_text(encoding="utf-8"), f


def test_the_workflow_node_action_starts_a_laptop():
    """The leg's own node is started by the composite action, not a fixture."""
    root = Path(__file__).resolve().parent.parent
    action = (root / ".github/actions/ciris-node/action.yml").read_text(encoding="utf-8")
    assert "CIRIS_DEVICE_CLASS: laptop" in action


# -- CIRIS_NODE_IDENTITY_TTL_S (ciris-server 0.5.247) -------------------------

def test_a_ci_node_states_an_end_for_its_identity():
    """On a CI runner the home is new each job, so the node's identity rows
    carry a stated end and leave nothing live on the canonical afterwards."""
    env = node_env({"GITHUB_ACTIONS": "true"})
    assert env["CIRIS_NODE_IDENTITY_TTL_S"] == "10800"


def test_a_node_off_ci_keeps_an_identity_with_no_end():
    """A developer's home is reused across days: an end signed into it would
    lapse that node for good. Nothing is set off CI."""
    assert "CIRIS_NODE_IDENTITY_TTL_S" not in node_env({})
    assert "CIRIS_NODE_IDENTITY_TTL_S" not in node_env({"GITHUB_ACTIONS": "false"})


def test_an_end_already_chosen_is_kept():
    env = node_env({"GITHUB_ACTIONS": "true", "CIRIS_NODE_IDENTITY_TTL_S": "900"})
    assert env["CIRIS_NODE_IDENTITY_TTL_S"] == "900"


def test_the_end_outlasts_the_longest_leg():
    """A row that lapses while its leg still runs fails the leg for a reason
    nobody would look for. Every job limit in the workflow fits inside it."""
    import re
    from testing.gate.node_env import IDENTITY_TTL_S
    root = Path(__file__).resolve().parent.parent
    wf = (root / ".github/workflows/five-platform-live-qa.yml").read_text(encoding="utf-8")
    limits = [int(m) for m in re.findall(r"^\s+timeout-minutes:\s*(\d+)\s*$", wf, re.M)]
    assert limits, "no job limits parsed from the workflow"
    assert max(limits) * 60 < int(IDENTITY_TTL_S)
    assert int(IDENTITY_TTL_S) >= 600  # the server's own floor


def test_the_peer_console_calls_carry_the_same_environment():
    """`identity create` and `claim` sign the owner's key and binding in their
    own process: without the variable there, those rows get no end."""
    root = Path(__file__).resolve().parent.parent
    src = (root / "testing/gate/two_node.py").read_text(encoding="utf-8")
    assert "subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=node_env())" in src


def test_the_workflow_actions_state_the_same_end():
    """The leg's own node and the with-AI leg's agent are started by composite
    actions, which do not pass through node_env()."""
    from testing.gate.node_env import IDENTITY_TTL_S
    root = Path(__file__).resolve().parent.parent
    node = (root / ".github/actions/ciris-node/action.yml").read_text(encoding="utf-8")
    agent = (root / ".github/actions/ciris-agent/action.yml").read_text(encoding="utf-8")
    assert f'CIRIS_NODE_IDENTITY_TTL_S: "{IDENTITY_TTL_S}"' in node
    assert f"CIRIS_NODE_IDENTITY_TTL_S={IDENTITY_TTL_S}" in agent


def test_the_e2e_node_lets_the_server_pick_its_key():
    """The e2e walk's node (testing/node_fixture.py, build.yml `e2e (desktop)`)
    passed `--key-id ciris-client` on a fresh home every run, so every push
    registered a new `ciris-client-<fp>` node key on the canonical (23 between
    2026-11-13 and 11-15, the server session's count). From 0.5.246 a bare
    `--home` picks the home's own key, which is what the app does."""
    from testing.node_fixture import RealNode
    root = Path(__file__).resolve().parent.parent
    src = (root / "testing/node_fixture.py").read_text(encoding="utf-8")
    assert '"ciris-client"' not in src
    assert RealNode(binary="ciris-server", home=Path("/h")).command() == ["ciris-server", "--home", "/h"]
