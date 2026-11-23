"""Standard-library tests; no listener, external process, or SSH daemon needed."""
import concurrent.futures
import contextlib
import io
import json
import socket
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

try:
    from . import dev_fake_ssh as fake
except ImportError:
    import dev_fake_ssh as fake


class ScenarioTests(unittest.TestCase):
    def call(self, session, method, **params):
        return fake.canned_reply(json.dumps({"id": "test-id", "method": method,
                                            "params": params}).encode(), session)

    def result(self, session, method, **params):
        reply, rc = self.call(session, method, **params)
        self.assertEqual(rc, 0, reply)
        self.assertEqual(reply["id"], "test-id")
        return reply["result"]

    def read(self, session, pane="w1:p1"):
        return self.result(session, "pane.read", pane_id=pane)["read"]

    def pointer_row(self, session, pane="w1:p1"):
        text = self.read(session, pane)["text"]
        return next(line for line in text.splitlines() if "\u276f" in line)

    def test_selector_moves_the_pointer_and_only_enter_answers(self):
        """The app drives a menu with arrow keys, so the fixture has to behave like one."""
        for form in fake.MENU_FORMS:
            with self.subTest(form=form):
                session = fake.CannedSession(fake.Scenario(ask_form=form))
                self.assertIn("1.", self.pointer_row(session))
                before = self.read(session)["revision"]
                self.result(session, "pane.send_input", pane_id="w1:p1", keys=["down", "down"])
                moved = self.read(session)
                self.assertIn("3.", self.pointer_row(session))
                self.assertGreater(moved["revision"], before)
                self.assertEqual(self.result(session, "session.snapshot")["agents"][0]["agent_status"],
                                 "blocked")
                self.result(session, "pane.send_input", pane_id="w1:p1", keys=["enter"])
                self.assertIn("denied", self.read(session)["text"])

    def test_selector_accepts_the_first_row_and_refuses_other_keys(self):
        session = fake.CannedSession(fake.Scenario(ask_form="boxed"))
        with self.assertRaises(AssertionError):
            self.result(session, "pane.send_input", pane_id="w1:p1", keys=["x"])
        self.result(session, "pane.send_input", pane_id="w1:p1", keys=["enter"])
        self.assertIn("accepted", self.read(session)["text"])
        with self.assertRaises(AssertionError):
            self.result(session, "pane.send_input", pane_id="w1:p1", keys=["enter"])

    def test_approval_changes_revision_text_status_and_keeps_other_pane(self):
        session = fake.CannedSession()
        before = self.read(session)
        other = self.read(session, "w1:p2")
        self.assertIn("Always allow", other["text"])
        self.result(session, "agent.send_keys", target="w1:p1", keys=["1", "enter"])
        after = self.read(session)
        self.assertEqual(after["revision"], before["revision"] + 1)
        self.assertNotEqual(after["text"], before["text"])
        self.assertNotIn("Do you want to proceed?", after["text"])
        snapshot = self.result(session, "session.snapshot")
        agents = snapshot["agents"]
        self.assertEqual(agents[0]["agent_status"], "done")
        self.assertGreater(agents[0]["state_change_seq"], 4412)
        self.assertEqual(agents[0]["completion_seq"], 4)
        self.assertEqual(agents[1]["agent_status"], "blocked")
        self.assertEqual(self.read(session, "w1:p2"), other)
        self.assertEqual(snapshot["panes"][0]["revision"], after["revision"])
        self.assertEqual(snapshot["panes"][0]["tab_id"], snapshot["tabs"][0]["tab_id"])

    def test_stale_approval_same_question_options_different_command_context(self):
        session = fake.CannedSession(fake.Scenario(mode="stale-approval"))
        before, after = self.read(session), self.read(session)
        self.assertEqual(after["revision"], before["revision"] + 1)
        self.assertIn("Command: npm test", before["text"])
        self.assertIn("Command: npm run deploy -- --dry-run", after["text"])
        question = "Do you want to proceed?"
        self.assertEqual(before["text"].split(question)[1], after["text"].split(question)[1])
        self.assertEqual(self.read(session), after)  # Change happens only once.

    def test_unchanged_output_reports_ok_without_changing_state(self):
        for method, params in (("agent.send_keys", {"keys": ["1"]}),
                               ("agent.prompt", {"text": "new task"})):
            with self.subTest(method=method):
                session = fake.CannedSession(fake.Scenario(mode="unchanged-output"))
                before = self.read(session)
                snapshot = self.result(session, "session.snapshot")
                self.assertEqual(self.result(session, method, target="w1:p1", **params), {"type": "ok"})
                self.assertEqual(self.read(session), before)
                self.assertEqual(self.result(session, "session.snapshot"), snapshot)

    def test_accepted_mutation_then_exactly_one_failed_postread(self):
        for method, params in (("agent.send_keys", {"keys": ["1"]}),
                               ("agent.prompt", {"text": "new task"})):
            with self.subTest(method=method):
                session = fake.CannedSession(fake.Scenario(mode="accepted-read-fail"))
                before = self.read(session)
                self.result(session, method, target="w1:p1", **params)
                self.read(session, "w1:p2")  # Other pane does not consume the failure.
                reply, rc = self.call(session, "pane.read", pane_id="w1:p1")
                self.assertEqual(rc, 1)
                self.assertEqual(reply["error"]["code"], "fixture_read_failure")
                after = self.read(session)
                self.assertEqual(after["revision"], before["revision"] + 1)
                self.assertNotEqual(after["text"], before["text"])
                self.assertEqual(self.read(session), after)

    def test_ask_forms_and_denial(self):
        for form, allow, deny, marker in (("numbered", "1", "3", "❯ 1. Yes"),
                                          ("yes-no", "y", "n", "[y/N]"),
                                          ("enter-esc", "enter", "esc", "Press Enter to confirm")):
            for key, status in ((allow, "done"), (deny, "idle")):
                with self.subTest(form=form, key=key):
                    session = fake.CannedSession(fake.Scenario(ask_form=form))
                    self.assertIn(marker, self.read(session)["text"])
                    self.result(session, "agent.send_keys", target="w1:p1", keys=[key])
                    self.assertEqual(self.result(session, "session.snapshot")["agents"][0]["agent_status"], status)

    def test_prompt_has_new_parseable_approval_and_does_not_echo_secret(self):
        session = fake.CannedSession()
        before = self.read(session)
        self.result(session, "agent.prompt", target="w1:p1", text="secret task password")
        after = self.read(session)
        self.assertGreater(after["revision"], before["revision"])
        self.assertIn("Review the new task?", after["text"])
        self.assertIn("Press Enter to confirm, Esc to cancel", after["text"])
        self.assertNotIn("secret task password", after["text"])
        self.result(session, "agent.send_keys", target="w1:p1", keys=["enter"])
        self.assertIn("complete", self.read(session)["text"])

    def test_rpc_failure_and_latency(self):
        session = fake.CannedSession(fake.Scenario(mode="rpc-failure", fail_method="agent.send_keys", latency_ms=25))
        with patch.object(fake.time, "sleep") as sleep:
            before = self.read(session)
            reply, rc = self.call(session, "agent.send_keys", target="w1:p1", keys=["1"])
            self.assertEqual(rc, 1)
            self.assertEqual(reply["error"]["code"], "fixture_failure")
            self.assertEqual(self.read(session), before)
            self.assertEqual(sleep.call_count, 3)
            sleep.assert_called_with(0.025)

    def test_method_specific_delay_does_not_delay_ping_or_snapshot(self):
        session = fake.CannedSession(fake.Scenario(latency_ms=3000, delay_method="pane.read"))
        with patch.object(fake.time, "sleep") as sleep:
            self.result(session, "ping")
            self.result(session, "session.snapshot")
            sleep.assert_not_called()
            self.read(session)
            sleep.assert_called_once_with(3)
        self.assertEqual(session.status._counts["pane_reads"], 1)

    def test_lost_reply_commits_once_and_only_first_mutation_drops(self):
        for method, params in (("agent.send_keys", {"keys": ["1", "enter"]}),
                               ("agent.prompt", {"text": "private prompt"})):
            with self.subTest(method=method):
                session = fake.CannedSession(fake.Scenario(mode="accepted-response-lost"))
                # Invalid mutations must not consume the one-shot fault.
                self.assertEqual(self.call(session, "agent.prompt", target="missing", text="x")[1], 1)
                with self.assertRaises(fake.DropTransport):
                    self.call(session, method, target="w1:p1", **params)
                self.assertEqual(self.read(session)["revision"], 13)
                self.assertEqual(self.result(session, "session.snapshot")["agents"][0]["state_change_seq"], 4413)
                self.assertEqual(session.status._counts["drops"], 1)
                self.assertEqual(session.status._counts["mutations"], 1)
                # A later valid mutation succeeds normally; the fault was consumed.
                self.result(session, "agent.prompt", target="w1:p1", text="another task")
                self.assertEqual(self.read(session)["revision"], 14)
                self.assertEqual(session.status._counts["drops"], 1)
                self.assertEqual(session.status._counts["mutation_requests"], 3)

    def test_lost_reply_fault_is_atomic_between_concurrent_clients(self):
        session = fake.CannedSession(fake.Scenario(mode="accepted-response-lost"))
        def prompt(index):
            try:
                self.call(session, "agent.prompt", target="w1:p1", text=f"task {index}")
                return False
            except fake.DropTransport:
                return True
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(sum(pool.map(prompt, range(20))), 1)
        self.assertEqual(self.read(session)["revision"], 32)
        self.assertEqual(session.status._counts["mutations"], 20)
        self.assertEqual(session.status._counts["drops"], 1)

    def test_control_file_retries_transient_windows_sharing_violation(self):
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ready.json"
            replace = fake.os.replace
            with patch.object(fake.os, "replace", side_effect=[PermissionError(), None]) as publish, patch.object(fake.time, "sleep") as sleep:
                fake.write_json(path, {"port": 12345})
                self.assertEqual(publish.call_count, 2)
                sleep.assert_called_once_with(0.005)
            replace(str(path) + ".tmp", path)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"port": 12345})

    def test_status_file_contains_only_aggregate_counters(self):
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "status.json"
            status = fake.FixtureStatus(path)
            session = fake.CannedSession(status=status)
            self.result(session, "agent.prompt", target="w1:p1", text="private prompt")
            self.read(session)
            counts = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(counts, {"active_transports": 0, "auth_attempts": 0,
                                      "requests": 2, "pane_reads": 1,
                                      "mutation_requests": 1, "mutations": 1, "drops": 0})
            self.assertEqual(list(Path(directory).iterdir()), [path])

    @unittest.skipUnless(fake.paramiko, "Paramiko unavailable")
    def test_lost_reply_closes_whole_transport_without_reply_or_shell(self):
        session = fake.CannedSession(fake.Scenario(mode="accepted-response-lost"))
        server = fake.Server("user", "password", canned=True, session=session)
        channel = Mock()
        channel.recv.return_value = b'{"id":"x","method":"agent.prompt","params":{"target":"w1:p1","text":"private prompt"}}\n'
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), patch.object(fake.subprocess, "run") as run:
            server._run(channel, b"private command")
            run.assert_not_called()
        channel.get_transport.return_value.close.assert_called_once_with()
        channel.sendall.assert_not_called()
        channel.sendall_stderr.assert_not_called()
        channel.send_exit_status.assert_not_called()
        channel.close.assert_called_once_with()
        self.assertEqual(self.read(session)["revision"], 13)
        self.assertEqual(stdout.getvalue() + stderr.getvalue(), "")

    def test_isolation_detached_results_and_compatibility(self):
        first, second = fake.CannedSession(), fake.CannedSession()
        snapshot = self.result(first, "session.snapshot")
        snapshot["agents"][0]["name"] = "changed externally"
        self.assertEqual(self.result(first, "session.snapshot")["agents"][0]["name"], "fix/login")
        self.result(first, "agent.send_keys", target="w1:p1", keys=["1"])
        self.assertEqual(self.read(second)["revision"], 12)
        self.assertEqual(fake.canned_reply(b'{"method":"ping"}')[1], 0)

    def test_structure_queries_and_second_waiting_agent(self):
        session = fake.CannedSession()
        workspaces = self.result(session, "workspace.list")["workspaces"]
        tabs = self.result(session, "tab.list", workspace_id="w1")["tabs"]
        panes = self.result(session, "pane.list", tab_id=tabs[0]["tab_id"])["panes"]
        self.assertEqual(workspaces[0]["workspace_id"], tabs[0]["workspace_id"])
        self.assertEqual(len(panes), 4)
        self.assertEqual(self.result(session, "pane.list", workspace_id="missing")["panes"], [])
        first = self.read(session)
        self.result(session, "agent.send_keys", target="w1:p2", keys=["2"])
        self.assertEqual(self.read(session), first)
        agents = self.result(session, "session.snapshot")["agents"]
        self.assertEqual(next(a for a in agents if a["pane_id"] == "w1:p2")["agent_status"], "done")
        self.assertEqual(self.read(session, "w1:p2")["revision"], 13)

    def test_bad_requests_and_unknown_target_do_not_mutate(self):
        session = fake.CannedSession()
        for raw in (b"{", b"\xff", b"[]", b"null", b'{"method":7}', b'{"method":"ping","params":[]}'):
            with self.subTest(raw=raw):
                self.assertEqual(session.reply(raw)[1], 1)
        for method, params in (("pane.read", {"pane_id": "missing"}),
                               ("agent.send_keys", {"target": "missing", "keys": ["1"]}),
                               ("agent.send_keys", {"target": "w1:p1", "keys": ["wrong"]}),
                               ("agent.prompt", {"target": "w1:p1", "text": ""}),
                               ("not.a.method", {})):
            self.assertEqual(self.call(session, method, **params)[1], 1)
        self.assertEqual(self.read(session)["revision"], 12)

    def test_concurrent_clients_share_atomic_state(self):
        session = fake.CannedSession()
        def prompt(index):
            return self.call(session, "agent.prompt", target="w1:p1", text=f"task {index}")
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(prompt, range(40)))
        self.assertTrue(all(rc == 0 for _, rc in replies))
        self.assertEqual(self.read(session)["revision"], 52)
        self.assertEqual(self.result(session, "session.snapshot")["agents"][0]["state_change_seq"], 4452)

    def test_loopback_only_and_explicit_exec_opt_in(self):
        for host in ("127.0.0.1", "127.0.0.2", "::1"):
            self.assertTrue(fake.loopback_address(host).is_loopback)
        for host in ("0.0.0.0", "::", "192.168.1.1", "localhost"):
            with self.assertRaises(ValueError):
                fake.loopback_address(host)
        if fake.paramiko:
            with self.assertRaises(ValueError):
                fake.Server("user", "password")

    @unittest.skipUnless(fake.paramiko, "Paramiko unavailable")
    def test_two_live_transports_share_state_without_a_listening_server(self):
        session = fake.CannedSession()
        host_key = fake.paramiko.RSAKey.generate(2048)
        clients, workers = [], []
        try:
            for _ in range(2):
                server_socket, client_socket = socket.socketpair()
                worker = threading.Thread(target=fake.serve_client,
                                          args=(server_socket, host_key, "user", "password", True, session),
                                          daemon=True)
                workers.append(worker)
                worker.start()
                client = fake.paramiko.Transport(client_socket)
                clients.append(client)
                client.connect(username="user", password="password", hostkey=host_key)
            # Keep the first transport alive while the second issues requests.
            def request(client, method, params):
                channel = client.open_session(timeout=3)
                channel.settimeout(3)
                try:
                    channel.exec_command("ignored fixture command")
                    channel.sendall((json.dumps({"id": "live", "method": method, "params": params}) + "\n").encode())
                    with channel.makefile("rb") as output:
                        reply = json.loads(output.readline())
                    self.assertEqual(channel.recv_exit_status(), 0)
                    return reply["result"]
                finally:
                    channel.close()
            self.assertEqual(request(clients[1], "agent.send_keys", {"target": "w1:p1", "keys": ["1"]}), {"type": "ok"})
            read = request(clients[0], "pane.read", {"pane_id": "w1:p1"})["read"]
            self.assertEqual(read["revision"], 13)
            self.assertIn("complete", read["text"])
        finally:
            for client in clients:
                client.close()
            for worker in workers:
                worker.join(timeout=3)
                self.assertFalse(worker.is_alive())

    @unittest.skipUnless(fake.paramiko, "Paramiko unavailable")
    def test_canned_channel_shares_state_and_does_not_log_payload(self):
        session = fake.CannedSession()
        servers = [fake.Server("user", "password", canned=True, session=session) for _ in range(2)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), patch.object(fake.subprocess, "run") as run:
            for server, method, params in ((servers[0], "agent.prompt", {"target": "w1:p1", "text": "private prompt"}),
                                           (servers[1], "pane.read", {"pane_id": "w1:p1"})):
                channel = Mock()
                channel.recv.return_value = (json.dumps({"id": "x", "method": method, "params": params}) + "\n").encode()
                server._run(channel, b"private command")
                channel.send_exit_status.assert_called_once_with(0)
                if method == "pane.read":
                    self.assertEqual(json.loads(channel.sendall.call_args.args[0])["result"]["read"]["revision"], 13)
            run.assert_not_called()
        self.assertEqual(stdout.getvalue() + stderr.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
