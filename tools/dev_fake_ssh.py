#!/usr/bin/env python3
"""Loopback-only development SSH bridge with deterministic, stateful fixtures."""
import argparse
import copy
import ipaddress
import json
import logging
import os
import socket
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass

try:
    import paramiko
except ImportError:  # The scenario and its tests use only the standard library.
    paramiko = None


MODES = ("normal", "stale-approval", "unchanged-output", "accepted-read-fail",
         "accepted-response-lost", "rpc-failure")
ASK_FORMS = ("numbered", "yes-no", "enter-esc", "colour", "boxed")


def approval_text(command="npm test", form="numbered"):
    context = "Claude Code\nCommand: " + command + "\n"
    if form == "yes-no":
        return context + "Do you want to proceed? [y/N]\n"
    if form == "enter-esc":
        return context + "Do you want to proceed?\nPress Enter to confirm, Esc to cancel\n"
    if form == "colour":
        # What a real TUI writes: the pointer sits behind an escape sequence, so a parser that
        # matches raw bytes sees no menu at all.
        return context + (
            "\x1b[1mDo you want to proceed?\x1b[0m\n"
            "\x1b[36m\x1b[1m❯\x1b[0m 1. Yes\n"
            "  2. Yes, and don't ask again for this session\n"
            "\x1b[90m  3. No, and tell Claude what to do differently (esc)\x1b[0m\n"
        )
    if form == "boxed":
        return context + (
            "╭──────────────────────────────────────╮\n"
            "│ Do you want to proceed?                    │\n"
            "│ ❯ 1. Yes                                   │\n"
            "│   2. Yes, and don't ask again              │\n"
            "│   3. No, and tell Claude what to do        │\n"
            "╰──────────────────────────────────────╯\n"
        )
    return context + (
        "Do you want to proceed?\n"
        "❯ 1. Yes\n"
        "  2. Yes, and don't ask again for this session\n"
        "  3. No, and tell Claude what to do differently (esc)\n"
    )


MENU_FORMS = ("numbered", "colour", "boxed")
MENU_LABELS = [
    "Yes",
    "Yes, and don't ask again for this session",
    "No, and tell Claude what to do differently (esc)",
]
P2_LABELS = ["Allow once", "Always allow for this session", "Deny"]


def render_menu(menu):
    """Draw a selector with its pointer where the app last moved it.

    The forms differ only in what surrounds the rows, because that is exactly what a parser has
    to survive: escape sequences, a box, or nothing.
    """
    labels, cursor, form = menu["labels"], menu["cursor"], menu["form"]
    rows = []
    for i, label in enumerate(labels):
        pointer = "❯" if i == cursor else " "
        rows.append((pointer, "%d. %s" % (i + 1, label)))
    if form == "colour":
        body = "\x1b[1mDo you want to proceed?\x1b[0m\n"
        for pointer, label in rows:
            mark = "\x1b[36m\x1b[1m%s\x1b[0m" % pointer if pointer == "❯" else pointer
            body += "%s %s\n" % (mark, label)
        return "Claude Code\nCommand: npm test\n" + body
    if form == "boxed":
        inner = 44
        lines = ["Do you want to proceed?"] + ["%s %s" % (p, l) for p, l in rows]
        body = "╭" + "─" * inner + "╮\n"
        for line in lines:
            body += "│ " + line.ljust(inner - 2) + " │\n"
        return "Claude Code\nCommand: npm test\n" + body + "╰" + "─" * inner + "╯\n"
    body = "Do you want to proceed?\n"
    for pointer, label in rows:
        body += "%s %s\n" % (pointer, label)
    return "Claude Code\nCommand: npm test\n" + body


SNAPSHOT = {
    "type": "session_snapshot", "version": "0.9.5", "protocol": 22,
    "workspaces": [{"workspace_id": "w1", "label": "herdr-mobile", "number": 1,
                    "focused": True, "tab_count": 1, "pane_count": 4}],
    "tabs": [{"tab_id": "w1:t1", "workspace_id": "w1", "label": "Development",
              "number": 1, "focused": True, "pane_count": 4, "agent_status": "blocked"}],
    "agents": [
        {"pane_id": "w1:p1", "name": "fix/login", "display_agent": "claude",
         "agent_status": "blocked", "state_change_seq": 4412, "completion_seq": 3,
         "cwd": "C:/repo/herdr-mobile", "workspace_id": "w1", "focused": True},
        {"pane_id": "w1:p2", "name": "api-retry", "display_agent": "codex",
         "agent_status": "blocked", "state_change_seq": 4410,
         "cwd": "C:/repo/gateway", "workspace_id": "w1"},
        {"pane_id": "w1:p3", "name": "docs", "display_agent": "pi",
         "agent_status": "done", "state_change_seq": 4408, "completion_seq": 1,
         "cwd": "C:/repo/herdr-mobile", "workspace_id": "w1"},
        {"pane_id": "w1:p4", "name": "watch", "display_agent": "gemini",
         "agent_status": "idle", "state_change_seq": 4401,
         "cwd": "C:/repo/gateway", "workspace_id": "w1"},
    ],
}
PANE = {
    "w1:p1": approval_text(),
    "w1:p2": "codex\nCommand: npm run release -- --dry-run\nAllow this command?\n"
             "❯ 1. Allow once\n  2. Always allow for this session\n  3. Deny\n",
    "w1:p3": "Documentation updated. Simulated task complete.\n",
    "w1:p4": "gemini\nWatching for changes...\n",
}


@dataclass(frozen=True)
class Scenario:
    mode: str = "normal"
    latency_ms: int = 0
    fail_method: str = "pane.read"
    ask_form: str = "numbered"
    delay_method: str = ""

    def __post_init__(self):
        if self.mode not in MODES or self.ask_form not in ASK_FORMS:
            raise ValueError("unknown scenario or ask form")
        if not 0 <= self.latency_ms <= 60000:
            raise ValueError("latency_ms must be between 0 and 60000")


class RpcError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class DropTransport(Exception):
    """A canned mutation committed, but its reply must never reach the client."""


def write_json(path, value):
    """Publish complete control data; never write requests or credentials."""
    with open(str(path) + ".tmp", "w", encoding="utf-8") as output:
        json.dump(value, output)
    # Windows readers briefly open the destination without delete sharing.
    # Keep publication atomic, retrying only that transient sharing violation.
    for attempt in range(100):
        try:
            os.replace(str(path) + ".tmp", path)
            return
        except PermissionError:
            if attempt == 99:
                raise
            time.sleep(0.005)


class FixtureStatus:
    """Optional aggregate counters for synchronizing subprocess transport tests."""
    def __init__(self, path=None):
        self.path = path
        self._lock = threading.Lock()
        self._counts = dict.fromkeys(("active_transports", "auth_attempts", "requests",
                                     "pane_reads", "mutation_requests", "mutations", "drops"), 0)
        self.add()

    def add(self, **changes):
        with self._lock:
            for key, delta in changes.items():
                self._counts[key] += delta
            if self.path:
                write_json(self.path, self._counts)


class CannedSession:
    """One server's state, shared by all its transports and exec channels.

    Request handling is atomic. Returned objects are detached from live state.
    Stale approval changes p1's command just before its second pane.read.
    Accepted/read-fail fails exactly the next read of each mutated pane.
    """
    def __init__(self, scenario=None, status=None):
        self.scenario = scenario or Scenario()
        self.status = status or FixtureStatus()
        self._dropped_reply = False
        self._lock = threading.RLock()
        self._snapshot = copy.deepcopy(SNAPSHOT)
        self._text = dict(PANE)
        self._text["w1:p1"] = approval_text(form=self.scenario.ask_form)
        self._revision = dict.fromkeys(PANE, 12)
        self._reads = dict.fromkeys(PANE, 0)
        self._fail_reads = set()
        self._forms = {"w1:p1": self.scenario.ask_form, "w1:p2": "numbered"}
        # A selector the app can drive with arrow keys, the way a real TUI works: the pointer
        # moves, the screen repaints, and only Enter answers. Without this the menu path had no
        # end-to-end exercise at all.
        self._menus = {
            "w1:p1": {"cursor": 0, "labels": MENU_LABELS, "form": self.scenario.ask_form},
            "w1:p2": {"cursor": 0, "labels": P2_LABELS, "form": "numbered"},
        }
        for pane_id, menu in self._menus.items():
            if menu["form"] in MENU_FORMS:
                self._text[pane_id] = render_menu(menu)
        self._seq = 4412
        for agent in self._snapshot["agents"]:
            agent["tab_id"] = "w1:t1"

    def reply(self, raw):
        rid = "0"
        try:
            req = json.loads(raw)
            if not isinstance(req, dict):
                raise RpcError("invalid_request", "request must be an object")
            rid = req.get("id", "0")
            method = req.get("method")
            params = req.get("params", {})
            if not isinstance(method, str) or not isinstance(params, dict):
                raise RpcError("invalid_request", "method and params required")
            mutation = method in ("agent.send_keys", "agent.prompt", "pane.send_input")
            self.status.add(requests=1, pane_reads=int(method == "pane.read"),
                            mutation_requests=int(mutation))
            if (self.scenario.latency_ms and
                    (not self.scenario.delay_method or method == self.scenario.delay_method)):
                time.sleep(self.scenario.latency_ms / 1000)
            with self._lock:
                if self.scenario.mode == "rpc-failure" and method == self.scenario.fail_method:
                    raise RpcError("fixture_failure", "simulated RPC failure")
                result = self._dispatch(method, params)
                if (mutation and self.scenario.mode == "accepted-response-lost"
                        and not self._dropped_reply):
                    self._dropped_reply = True
                    self.status.add(drops=1)
                    raise DropTransport()
                return {"id": rid, "result": copy.deepcopy(result)}, 0
        except (ValueError, UnicodeError):
            return {"id": rid, "error": {"code": "parse", "message": "bad json"}}, 1
        except RpcError as exc:
            return {"id": rid, "error": {"code": exc.code, "message": str(exc)}}, 1

    def _agent(self, pane_id):
        if not isinstance(pane_id, str):
            raise RpcError("invalid_params", "pane id must be a string")
        for agent in self._snapshot["agents"]:
            if agent["pane_id"] == pane_id:
                return agent
        raise RpcError("not_found", "unknown pane")

    def _change(self, pane_id, text, status):
        agent = self._agent(pane_id)
        self._text[pane_id] = text
        self.status.add(mutations=1)
        self._revision[pane_id] += 1
        self._seq += 1
        agent["state_change_seq"] = self._seq
        agent["agent_status"] = status
        if status == "done":
            agent["completion_seq"] = agent.get("completion_seq", 0) + 1

    def _snapshot_result(self):
        snapshot = copy.deepcopy(self._snapshot)
        snapshot["panes"] = [dict(a, terminal_id=a["pane_id"], label=a["name"],
                                  revision=self._revision[a["pane_id"]])
                             for a in snapshot["agents"]]
        statuses = [a["agent_status"] for a in snapshot["agents"]]
        snapshot["tabs"][0]["agent_status"] = next(
            (s for s in ("blocked", "working", "idle", "done") if s in statuses), "idle")
        return snapshot

    def _dispatch(self, method, params):
        if method == "ping":
            return {"type": "pong", "version": "0.9.5", "protocol": 22,
                    "capabilities": {"live_handoff": False, "detached_server_daemon": True}}
        if method == "session.snapshot":
            return self._snapshot_result()
        if method in ("workspace.list", "tab.list", "pane.list"):
            key = {"workspace.list": "workspaces", "tab.list": "tabs", "pane.list": "panes"}[method]
            rows = self._snapshot_result()[key]
            for field in ("workspace_id", "tab_id"):
                if field in params:
                    rows = [row for row in rows if row.get(field) == params[field]]
            return {"type": method.replace(".", "_"), key: rows}
        if method == "pane.read":
            pane_id = params.get("pane_id", "w1:p1")
            self._agent(pane_id)
            if pane_id in self._fail_reads:
                self._fail_reads.remove(pane_id)
                raise RpcError("fixture_read_failure", "mutation accepted; simulated postread failure")
            self._reads[pane_id] += 1
            if (self.scenario.mode == "stale-approval" and pane_id == "w1:p1"
                    and self._reads[pane_id] == 2):
                self._change(pane_id, approval_text("npm run deploy -- --dry-run",
                                                   self.scenario.ask_form), "blocked")
            return {"type": "pane_read", "read": {"pane_id": pane_id,
                    "text": self._text[pane_id], "revision": self._revision[pane_id],
                    "truncated": False}}
        if method == "pane.send_input":
            pane_id = params.get("pane_id")
            if not isinstance(pane_id, str) or pane_id not in self._text:
                raise RpcError("invalid_params", "pane_id must name a pane")
            text = params.get("text")
            keys = params.get("keys") or []
            if not isinstance(keys, list) or not all(isinstance(k, str) for k in keys):
                raise RpcError("invalid_params", "keys must be a string array")
            if isinstance(text, str) and text.strip():
                self._change(pane_id, "New simulated task received.\n", "working")
                return {"type": "pane_send_input", "ok": True}
            menu = self._menus.get(pane_id)
            if menu is None or menu["form"] not in MENU_FORMS:
                raise RpcError("invalid_keys", "this pane is not drawing a selector")
            if self._agent(pane_id)["agent_status"] != "blocked":
                raise RpcError("invalid_keys", "nothing is waiting for an answer")
            for key in keys:
                k = key.lower()
                if k == "up":
                    menu["cursor"] = max(0, menu["cursor"] - 1)
                elif k == "down":
                    menu["cursor"] = min(len(menu["labels"]) - 1, menu["cursor"] + 1)
                elif k == "enter":
                    accepted = menu["cursor"] < len(menu["labels"]) - 1
                    answer = "Approval accepted. Simulated task complete.\n" if accepted else \
                             "Approval denied. Waiting for a new task.\n"
                    if self.scenario.mode != "unchanged-output":
                        self._change(pane_id, answer, "done" if accepted else "idle")
                    return {"type": "pane_send_input", "ok": True}
                else:
                    raise RpcError("invalid_keys", "unsupported key " + key)
            # Moving the pointer repaints the screen without answering it.
            self._text[pane_id] = render_menu(menu)
            self._revision[pane_id] += 1
            return {"type": "pane_send_input", "ok": True}
        if method in ("agent.send_keys", "agent.prompt"):
            pane_id = params.get("target")
            if not isinstance(pane_id, str):
                raise RpcError("invalid_params", "target must be a pane id")
            agent = self._agent(pane_id)
            if method == "agent.prompt":
                if not isinstance(params.get("text"), str) or not params["text"].strip():
                    raise RpcError("invalid_params", "text must be nonempty")
                text = "New simulated task received.\nReview the new task?\nPress Enter to confirm, Esc to cancel\n"
                status = "blocked"
            else:
                keys = params.get("keys")
                if not isinstance(keys, list) or not keys or not all(isinstance(k, str) for k in keys):
                    raise RpcError("invalid_params", "keys must be a nonempty string array")
                form = self._forms.get(pane_id)
                choices = {"numbered": ({"1", "2"}, {"3", "esc"}),
                           "yes-no": ({"y"}, {"n"}),
                           "enter-esc": ({"enter"}, {"esc"})}
                allow, deny = choices.get(form, (set(), set()))
                # The app may append enter to a numbered/yes-no answer.
                answer = keys[0].lower()
                if agent["agent_status"] != "blocked" or answer not in allow | deny:
                    raise RpcError("invalid_keys", "no matching approval option")
                if any(k.lower() != "enter" for k in keys[1:]):
                    raise RpcError("invalid_keys", "unexpected trailing keys")
                accepted = answer in allow
                text = "Approval accepted. Simulated task complete.\n" if accepted else "Approval denied. Waiting for a new task.\n"
                status = "done" if accepted else "idle"
            if self.scenario.mode != "unchanged-output":
                self._change(pane_id, text, status)
                if method == "agent.prompt":
                    self._forms[pane_id] = "enter-esc"
                if self.scenario.mode == "accepted-read-fail":
                    self._fail_reads.add(pane_id)
            return {"type": "ok"}
        raise RpcError("unknown_method", "unsupported fixture method")


def canned_reply(raw: bytes, session=None):
    """Compatibility helper; pass a CannedSession to retain state between calls.

    Without a session this remains an isolated one-request fixture.
    """
    return (session if session is not None else CannedSession()).reply(raw)


def loopback_address(host):
    """Use a literal address so DNS cannot turn a local bind into a public one."""
    address = ipaddress.ip_address(host)
    if not address.is_loopback:
        raise ValueError("fake SSH requires a literal loopback address")
    return address


class Server(paramiko.ServerInterface if paramiko else object):
    def __init__(self, user: str, password: str, canned: bool = False,
                 session=None, allow_local_exec=False):
        if paramiko is None:
            raise RuntimeError("Paramiko is required to serve SSH")
        if not canned and not allow_local_exec:
            raise ValueError("local shell execution requires explicit opt-in")
        self.user = user
        self.password = password
        self.canned = canned
        self.session = session if session is not None else CannedSession()

    def check_auth_password(self, username, password):
        self.session.status.add(auth_attempts=1)
        return paramiko.AUTH_SUCCESSFUL if (username == self.user and password == self.password) else paramiko.AUTH_FAILED

    def get_allowed_auths(self, username):
        return "password"

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel, command):
        threading.Thread(target=self._run, args=(channel, command), daemon=True).start()
        return True

    def _run(self, channel, command):
        try:
            channel.settimeout(15)
            data = b""
            while b"\n" not in data:
                chunk = channel.recv(65536)
                if not chunk:
                    break
                data += chunk
                if len(data) > 1024 * 1024:
                    raise ValueError("request too large")
            if self.canned:
                reply, rc = self.session.reply(data.split(b"\n", 1)[0])
                channel.sendall((json.dumps(reply) + "\n").encode())
                channel.send_exit_status(rc)
                return
            if isinstance(command, bytes):
                command = command.decode("utf-8", errors="replace")
            proc = subprocess.run(command, shell=True, input=data, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, timeout=60)
            if proc.stdout:
                channel.sendall(proc.stdout)
            if proc.stderr:
                channel.sendall_stderr(proc.stderr)
            channel.send_exit_status(proc.returncode)
        except DropTransport:
            # Close the whole SSH connection, not just this exec channel. No reply
            # or exit status is sent after the already committed mutation.
            channel.get_transport().close()
        except Exception:  # Never include request, command, credentials or exception text.
            try:
                channel.sendall_stderr(b"fake-ssh: request failed\n")
                channel.send_exit_status(1)
            except Exception:
                pass
        finally:
            channel.close()


def serve_client(client, host_key, user, password, canned, session, allow_local_exec=False):
    transport = paramiko.Transport(client)
    try:
        session.status.add(active_transports=1)
        transport.add_server_key(host_key)
        transport.start_server(server=Server(user, password, canned, session, allow_local_exec))
        # Paramiko keeps weak channel references; retain accepted channels until
        # workers finish, including the interval before the exec request arrives.
        channels = []
        while transport.is_active():
            channel = transport.accept(timeout=1)
            channels = [active for active in channels if not active.closed]
            if channel is not None:
                channels.append(channel)
    except Exception:
        print("fake-ssh: connection ended", flush=True)
    finally:
        transport.close()
        client.close()
        session.status.add(active_transports=-1)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=2222)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="secret")
    ap.add_argument("--key", default=os.path.join(tempfile.gettempdir(), "herdwatch_fake_ssh_rsa"))
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--canned", action="store_true", help="serve simulated Herdr JSON")
    mode.add_argument("--allow-local-exec", action="store_true", help="explicitly allow local shell execution")
    ap.add_argument("--scenario", choices=MODES, default="normal")
    ap.add_argument("--latency-ms", type=int, default=0)
    ap.add_argument("--fail-method", default="pane.read")
    ap.add_argument("--ask-form", choices=ASK_FORMS, default="numbered")
    ap.add_argument("--delay-method", default="", help="delay only this method; default delays all")
    ap.add_argument("--ready-file", help="publish bound port and SHA256 host fingerprint as JSON")
    ap.add_argument("--status-file", help="publish aggregate fixture counters as JSON")
    args = ap.parse_args()
    try:
        address = loopback_address(args.host)
        scenario = Scenario(args.scenario, args.latency_ms, args.fail_method, args.ask_form,
                            args.delay_method)
        if not 0 <= args.port <= 65535:
            raise ValueError("port must be between 0 and 65535")
        if not args.canned and (args.ready_file or args.status_file or args.delay_method
                                or args.scenario != "normal"):
            raise ValueError("transport test controls require --canned")
    except ValueError as exc:
        ap.error(str(exc))
    if paramiko is None:
        ap.error("Paramiko is unavailable; install it in a project virtual environment")
    if not os.path.exists(args.key):
        paramiko.RSAKey.generate(2048).write_private_key_file(args.key)
    host_key = paramiko.RSAKey(filename=args.key)
    # Paramiko may otherwise print handshake traceback details during expected
    # auth/pin failures. Keep subprocess diagnostics fixed and payload-free.
    logger = logging.getLogger("paramiko")
    logger.handlers = [logging.NullHandler()]
    logger.propagate = False
    session = CannedSession(scenario, FixtureStatus(args.status_file))
    family = socket.AF_INET6 if address.version == 6 else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((str(address), args.port))
        sock.listen(16)
        port = sock.getsockname()[1]
        if args.ready_file:
            write_json(args.ready_file, {"port": port, "fingerprint": host_key.fingerprint})
        print(f"fake ssh listening on {address}:{port} canned={args.canned}", flush=True)
        while True:
            client, _ = sock.accept()
            threading.Thread(target=serve_client,
                             args=(client, host_key, args.user, args.password, args.canned,
                                   session, args.allow_local_exec), daemon=True).start()


if __name__ == "__main__":
    main()
