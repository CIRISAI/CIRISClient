"""The call check's verdicts, without a device (testing/gate/call_video.py).

Each rule here is one that decides green or red on the five-platform board, so
each has its red path pinned: an agent that refuses the upgrade differently, a
forwarding agent that still refuses, a call that hears nothing.
"""

from __future__ import annotations

import socket
import threading
import unittest

from testing.gate import call_video as cv


class Versions(unittest.TestCase):
    def test_reads_tags_and_suffixes(self):
        self.assertEqual(cv.version_tuple("v0.5.253"), (0, 5, 253))
        self.assertEqual(cv.version_tuple("2.23.2-stable"), (2, 23, 2))
        self.assertEqual(cv.version_tuple(""), ())

    def test_floor(self):
        self.assertFalse(cv.at_least("v0.5.253", cv.VIDEO_SERVER_FLOOR))
        self.assertTrue(cv.at_least("0.5.254", cv.VIDEO_SERVER_FLOOR))
        self.assertIsNone(cv.at_least("nightly", cv.VIDEO_SERVER_FLOOR))


class AgentVerdict(unittest.TestCase):
    def test_old_agent_refusing_with_403_is_the_expected_failure(self):
        c = cv.agent_verdict("2.23.1", 403, "HTTP/1.1 403 Forbidden")
        self.assertEqual(c.status, cv.XFAIL)
        self.assertTrue(c.ok)

    def test_old_agent_failing_some_other_way_stays_red(self):
        for status, line in ((404, "HTTP/1.1 404 Not Found"), (0, "ConnectionRefusedError"), (101, "HTTP/1.1 101")):
            c = cv.agent_verdict("2.23.1", status, line)
            self.assertEqual(c.status, cv.FAIL, line)

    def test_forwarding_agent_passes_on_the_nodes_answer(self):
        c = cv.agent_verdict("2.23.2-stable", 401, "HTTP/1.1 401 Unauthorized")
        self.assertEqual(c.status, cv.PASS)

    def test_forwarding_agent_that_still_refuses_is_a_regression(self):
        self.assertEqual(cv.agent_verdict("2.24.0", 403, "HTTP/1.1 403 Forbidden").status, cv.FAIL)
        self.assertEqual(cv.agent_verdict("2.23.2", 502, "HTTP/1.1 502 Bad Gateway").status, cv.FAIL)

    def test_an_unreadable_agent_version_is_not_guessed(self):
        self.assertEqual(cv.agent_verdict("", 403, "HTTP/1.1 403").status, cv.FAIL)


class Judge(unittest.TestCase):
    def test_tone_and_picture_pass(self):
        got = cv.judge("leg", cv.Seen(frames=40, hz=441, picture=True, tile="frames=40 hz=441"), True, "0.5.254")
        self.assertEqual([c.status for c in got], [cv.PASS, cv.PASS])

    def test_silence_is_red(self):
        got = cv.judge("leg", cv.Seen(frames=0, hz=0), True, "0.5.254")
        self.assertEqual(got[0].status, cv.FAIL)

    def test_the_wrong_pitch_is_red(self):
        self.assertEqual(cv.judge("leg", cv.Seen(frames=40, hz=880), False, "")[0].status, cv.FAIL)

    def test_no_picture_is_red_once_the_server_frames_video(self):
        self.assertEqual(cv.judge("leg", cv.Seen(frames=40, hz=440), True, "0.5.254")[1].status, cv.FAIL)

    def test_below_the_floor_video_is_refused_not_passed(self):
        c = cv.judge("leg", cv.Seen(frames=40, hz=440), False, "v0.5.253")[1]
        self.assertEqual(c.status, cv.REFUSED)
        self.assertIn("0.5.254", c.detail)

    def test_no_audio_device_is_said_not_failed(self):
        self.assertEqual(cv.judge("leg", cv.Seen(audio_unavailable=True), False, "")[0].status, cv.REFUSED)

    def test_a_measured_tone_passes_without_a_speaker(self):
        # Run 45304697279, Windows: decoded and measured, no audio output device.
        got = cv.judge("leg", cv.Seen(frames=82, hz=425, audio_unavailable=True), False, "")
        self.assertEqual(got[0].status, cv.PASS)

    def test_the_tone_wanders_but_noise_does_not_pass(self):
        self.assertEqual(cv.judge("leg", cv.Seen(frames=26, hz=475), False, "")[0].status, cv.PASS)
        self.assertEqual(cv.judge("leg", cv.Seen(frames=26, hz=1800), False, "")[0].status, cv.FAIL)


class MobileAgentLegs(unittest.TestCase):
    def test_a_phone_does_not_gate_on_its_agent_port(self):
        class Drv:
            def state(self):
                return {"nodeUrl": "http://127.0.0.1:4243"}
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        got = cv.probe_agent(Drv(), "2.23.1", f"http://127.0.0.1:{port}", platform="ios")
        self.assertEqual(got[0].status, cv.REFUSED)
        self.assertIn("directly", got[0].detail)
        self.assertTrue(all(c.ok for c in got))


class UpgradeProbe(unittest.TestCase):
    def test_reads_the_status_a_server_answers_the_upgrade_with(self):
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]

        def answer():
            conn, _ = srv.accept()
            conn.recv(4096)
            conn.sendall(b"HTTP/1.1 403 Forbidden\r\ncontent-length: 0\r\n\r\n")
            conn.close()

        t = threading.Thread(target=answer)
        t.start()
        status, line = cv.ws_upgrade_status(f"http://127.0.0.1:{port}", "/v1/chat/x/call/frames")
        t.join()
        srv.close()
        self.assertEqual(status, 403)
        self.assertEqual(line, "HTTP/1.1 403 Forbidden")

    def test_no_server_is_status_zero(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        self.assertEqual(cv.ws_upgrade_status(f"http://127.0.0.1:{port}", "/x", timeout=2)[0], 0)


if __name__ == "__main__":
    unittest.main()
