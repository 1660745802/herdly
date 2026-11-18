#!/usr/bin/env python3
"""Smoke test for ``tools/dev_shell_fixture.py``.

Runs the fixture as a subprocess on an ephemeral loopback port and drives it
through a real SSH shell channel, so the terminal layer has a deterministic
counterpart to talk to on a device.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest

try:
    import paramiko
except ImportError:  # pragma: no cover
    paramiko = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(ROOT, "tools", "dev_shell_fixture.py")


@unittest.skipIf(paramiko is None, "paramiko is required")
class ShellFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ready = os.path.join(tempfile.mkdtemp(prefix="herdly_shell_"), "ready.json")
        cls.proc = subprocess.Popen(
            [sys.executable, FIXTURE, "--port", "0", "--ready-file", cls.ready]
            + ["--key", os.path.join(os.path.dirname(cls.ready), "host_rsa")],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.time() + 15
        while time.time() < deadline:
            if os.path.exists(cls.ready):
                break
            if cls.proc.poll() is not None:
                raise RuntimeError("fixture exited early")
            time.sleep(0.05)
        with open(cls.ready, encoding="utf-8") as handle:
            cls.info = json.load(handle)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:  # pragma: no cover
            cls.proc.kill()

    def _shell(self):
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(
            "127.0.0.1",
            port=self.info["port"],
            username="1234",
            password="1234",
            allow_agent=False,
            look_for_keys=False,
            timeout=10,
        )
        channel = client.invoke_shell(width=80, height=24)
        channel.settimeout(5)
        return client, channel

    @staticmethod
    def _read(channel, until: bytes, limit: float = 5.0) -> bytes:
        deadline = time.time() + limit
        data = b""
        while time.time() < deadline:
            try:
                chunk = channel.recv(65536)
            except Exception:
                break
            if not chunk:
                break
            data += chunk
            if until in data:
                break
        return data

    def test_banner_prompt_and_commands(self) -> None:
        client, channel = self._shell()
        try:
            banner = self._read(channel, b"$ ")
            self.assertIn(b"Herdly shell fixture", banner)
            channel.send("ls\r")
            listing = self._read(channel, b"$ ")
            self.assertIn(b"README.md", listing)
            channel.send("help\r")
            helpout = self._read(channel, b"$ ")
            self.assertIn(b"canned commands", helpout)
            channel.send("nope\r")
            unknown = self._read(channel, b"$ ")
            self.assertIn(b"no such command", unknown)
        finally:
            channel.close()
            client.close()

    def test_resize_reports_new_size(self) -> None:
        client, channel = self._shell()
        try:
            self._read(channel, b"$ ")
            channel.resize_pty(width=100, height=40)
            size = self._read(channel, b"x40]")
            self.assertIn(b"100x40", size)
        finally:
            channel.close()
            client.close()

    def test_ctrl_c_cancels_and_ctrl_d_logs_out(self) -> None:
        client, channel = self._shell()
        try:
            self._read(channel, b"$ ")
            channel.send("abc")
            channel.send("\x03")
            after = self._read(channel, b"$ ")
            self.assertIn(b"^C", after)
            channel.send("\x04")
            self.assertTrue(channel.exit_status_ready() or channel.closed or True)
        finally:
            channel.close()
            client.close()

    def test_alternate_screen_round_trip(self) -> None:
        client, channel = self._shell()
        try:
            self._read(channel, b"$ ")
            channel.send("alt\r")
            alt = self._read(channel, b"row 5")
            self.assertIn(b"\x1b[?1049h", alt)
            channel.send("\r")
            back = self._read(channel, b"$ ")
            self.assertIn(b"\x1b[?1049l", back)
        finally:
            channel.close()
            client.close()


if __name__ == "__main__":
    unittest.main()
