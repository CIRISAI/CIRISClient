"""The flow verb `attach:` and the with-AI attachment flow (CIRISClient#217).

`attach: {notes.pdf: CIRIS-ATTACH-XXXXXXXX}` builds a real file carrying that
sentinel (testing/gate/attachment_fixtures.py, vendored from CIRISAgent @
380455879) and hands it to the app's paperclip through `/inject-file` — the
same `InteractViewModel.addAttachment` a picked file reaches.
"""

from __future__ import annotations

import asyncio
import base64
import io
import zipfile
from pathlib import Path

import pytest

from testing.gate import attachment_fixtures as fx
from testing.gate import run_flows
from testing.gate.flow_helper import SyncFlowHelper
from testing.gate.flow_spec import FlowSpec, SpecError
from testing.test_flows import FakeHelper, _csd_root, _flow

REPO = Path(__file__).resolve().parents[1]
FLOW = REPO / "testing" / "flows" / "agent" / "csd-010-interact-files.yaml"
SENTINEL = "CIRIS-ATTACH-217A0001"

ATTACH = """\
    flow: thing
    csd: CSD-900
    client: ">=0.5.224"
    steps:
      - step_id: land
        title: lands
        requires: {screen: Thing}
        do:
          - attach: {notes.pdf: "CIRIS-ATTACH-217A0001"}
        expect:
          visible: [thing_list]
"""


def test_attach_parses_to_a_file_and_its_sentinel(tmp_path):
    spec = FlowSpec.load(_flow(tmp_path, ATTACH), csd_root=_csd_root(tmp_path))
    a = spec.steps[0].do[0]
    assert (a.kind, a.target, a.value) == ("attach", "notes.pdf", SENTINEL)


@pytest.mark.parametrize("name", ["notes.exe", "notes", "photo.heic"])
def test_a_file_the_fixtures_cannot_build_is_a_load_error(tmp_path, name):
    with pytest.raises(SpecError, match="attach"):
        FlowSpec.load(_flow(tmp_path, ATTACH.replace("notes.pdf", name)), csd_root=_csd_root(tmp_path))


def test_a_sentinel_of_the_wrong_shape_is_a_load_error(tmp_path):
    with pytest.raises(SpecError, match="sentinel"):
        FlowSpec.load(_flow(tmp_path, ATTACH.replace(SENTINEL, "hello")), csd_root=_csd_root(tmp_path))


class _AttachingHelper(FakeHelper):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.attached = []

    async def attach_file(self, name, sentinel):
        self.calls.append(f"attach {name}")
        self.attached.append((name, sentinel))
        return True


def test_the_runner_hands_the_file_to_the_helper(tmp_path):
    spec = FlowSpec.load(_flow(tmp_path, ATTACH), csd_root=_csd_root(tmp_path))
    helper = _AttachingHelper("Thing", {"thing_list": ""})
    out = asyncio.run(run_flows.run_one(spec, helper, client_version="0.5.224", start_timeout=0))
    assert out.status == run_flows.PASS, out
    assert helper.attached == [("notes.pdf", SENTINEL)]


class _Drv:
    def __init__(self):
        self.posts = []

    def inject_file(self, filename, media_type, data):
        self.posts.append((filename, media_type, data))
        return {"success": True}


@pytest.mark.parametrize("name, media_type", [
    ("notes.pdf", "application/pdf"),
    ("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ("notes.txt", "text/plain"),
    ("swatch.png", "image/png"),
])
def test_the_helper_injects_a_real_file_carrying_the_sentinel(name, media_type):
    drv = _Drv()
    assert asyncio.run(SyncFlowHelper(drv).attach_file(name, SENTINEL))
    (fn, mt, data), = drv.posts
    assert (fn, mt) == (name, media_type)
    if name.endswith(".pdf"):
        assert data.startswith(b"%PDF-") and SENTINEL.encode() in data
    elif name.endswith(".docx"):
        assert SENTINEL in zipfile.ZipFile(io.BytesIO(data)).read("word/document.xml").decode()
    elif name.endswith(".txt"):
        assert SENTINEL in data.decode()
    else:
        assert data == fx.png()


def test_the_driver_posts_inject_file_in_the_servers_shape():
    from testing.driver import TestAutomationServer

    seen = {}
    drv = TestAutomationServer()
    drv._call = lambda method, route, body=None: seen.update(method=method, route=route, body=body) or {"success": True}
    drv.inject_file("a.pdf", "application/pdf", b"%PDF-1")
    assert seen["method"] == "POST" and seen["route"] == "/inject-file"
    assert seen["body"] == {"filename": "a.pdf", "mediaType": "application/pdf",
                            "dataBase64": base64.b64encode(b"%PDF-1").decode(), "sizeBytes": 6}


def test_the_with_ai_attachment_flow_asks_for_every_sentinel_in_the_reply():
    spec = FlowSpec.load(FLOW)
    attached = [a for s in spec.steps for a in s.do if a.kind == "attach"]
    assert {a.target for a in attached} == {"notes.pdf", "notes.docx", "swatch.png"}
    sentinels = {a.value for a in attached if not a.target.endswith(".png")}
    assert len(sentinels) == 2, "PDF and DOCX each carry their own sentinel, so each is proven separately"
    reply_patterns = " ".join(p for s in spec.steps for t, p in s.expect.matches.items() if t == "msg_reply_latest")
    for s in sentinels:
        assert s in reply_patterns, f"the reply is never checked for {s}"


def _flow_strings(spec):
    out = []
    for s in spec.steps:
        out += [a.target for a in s.do] + [a.value or "" for a in s.do]
        for c in (s.requires, s.expect):
            out += list(c.visible) + list(c.absent) + list(c.matches)
    return out


def test_the_flow_names_its_own_exchange_not_an_ordinal():
    """It may run after another flow on the same agent; `msg_user_1` would then be
    someone else's message. The reply is `msg_reply_latest`, keyed to this send."""
    import re

    ordinal = [t for t in _flow_strings(FlowSpec.load(FLOW)) if re.match(r"msg_(user|agent)_\d", t)]
    assert not ordinal, ordinal


def test_the_prompt_asks_for_the_reference_the_fixture_files():
    """Live models decline to recite a 'code word' (CIRISAgent run 39488523205);
    the fixture files a library catalogue reference and the prompt asks for it."""
    assert "library catalogue under reference CIRIS-ATTACH-217A0F01" in fx.sentence("CIRIS-ATTACH-217A0F01")
    prompts = [a.value for s in FlowSpec.load(FLOW).steps for a in s.do if a.kind == "input"]
    assert any("library catalogue reference" in p for p in prompts), prompts
    assert not any("code word" in p for p in prompts)


# ── the honest skip on an agent older than the policy route ─────────────────

TOO_OLD = "agent older than the attachments policy route"
PROBE = "interact_attachment_policy"


def test_the_probe_is_the_first_step_and_admits_only_live_or_the_bare_404():
    first = FlowSpec.load(FLOW).steps[0]
    assert not first.optional_step, "the probe itself must never skip"
    assert first.expect.one_of == {PROBE: ["live", TOO_OLD]}


def test_every_later_step_skips_only_when_the_probe_is_not_live():
    for step in FlowSpec.load(FLOW).steps[1:]:
        assert step.optional_step, step.step_id
        assert step.requires.one_of == {PROBE: ["live"]}, step.step_id


def test_the_probe_words_are_the_clients():
    src = (REPO / "client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/AgentAttachments.kt").read_text()
    assert f'FALLBACK_AGENT_TOO_OLD("{TOO_OLD}")' in src and 'AGENT("live")' in src


def _run_probe(probe_text):
    spec = FlowSpec.load(FLOW)
    tags = {PROBE: probe_text, "input_message": "", "btn_send": ""}
    helper = _AttachingHelper("Interact", tags)
    return asyncio.run(run_flows.run_one(spec, helper, client_version="0.5.230", start_timeout=0)), helper


def test_a_bare_404_skips_every_file_step_with_the_named_reason():
    out, helper = _run_probe(TOO_OLD)
    assert out.status == run_flows.PASS, out
    steps = [s["status"] for s in out.steps]
    assert steps == ["pass", "skipped", "skipped", "skipped"], steps
    assert all(TOO_OLD in s["detail"] for s in out.steps[1:]), [s["detail"] for s in out.steps[1:]]
    assert helper.attached == [], "nothing may be attached on an agent that cannot read it"


@pytest.mark.parametrize("probe_text", ["unavailable", "pending", ""])
def test_any_other_policy_answer_fails_the_flow_instead_of_skipping(probe_text):
    out, helper = _run_probe(probe_text)
    assert out.status == run_flows.FAIL, out
    assert [s["status"] for s in out.steps] == ["fail"]
    assert helper.attached == []


def test_the_attach_steps_run_on_every_with_ai_leg():
    """The gate's agent serves /v1/agent/attachments/policy (2.16.3+; the gate
    resolves 2.16.5 since 2026-10-25), so the three attach steps RUN on every
    with-AI leg and none is an optional skip. While it was 2.16.2 they skipped
    and this pinned 3. Re-recorded from runs 40250199252 (linux-ai) and
    40241085150 (ios-ai), where all three passed through /inject-file (#244)."""
    import json

    base = json.loads((REPO / "testing/flows/optional_skips_baseline.json").read_text())
    for leg in ("linux-ai", "windows-ai", "ios-ai"):
        assert base[leg]["csd_010_interact_files"] == 0, leg
