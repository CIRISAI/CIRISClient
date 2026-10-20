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
    reply_patterns = " ".join(p for s in spec.steps for t, p in s.expect.matches.items() if t == "msg_agent_1")
    for s in sentinels:
        assert s in reply_patterns, f"the reply is never checked for {s}"
