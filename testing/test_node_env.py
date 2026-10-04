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
    for f in ("testing/gate/node_fixture.py", "testing/gate/two_node.py"):
        assert "env=node_env()" in (root / f).read_text(encoding="utf-8"), f
