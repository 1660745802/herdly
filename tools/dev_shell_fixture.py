#!/usr/bin/env python3
"""Local SSH *shell* fixture for the terminal layer.

Unlike ``dev_fake_ssh.py`` (which answers Herdr bridge ``exec`` requests), this
fixture accepts an interactive shell channel so the client's PTY path can be
exercised on a device without touching a real machine.

It never runs local commands: every keystroke is interpreted by a tiny canned
line editor, so it is safe to run anywhere and fully deterministic.

    python tools/dev_shell_fixture.py --port 2223 --user 1234 --password 1234

Behaviour worth testing:
  * banner with ANSI 16 / 256 / truecolour samples
  * ``ls``, ``color``, ``wrap``, ``alt``, ``clear``, ``help``, ``exit``
  * backspace, Ctrl-C, Ctrl-D, arrow keys (history up/down)
  * window size reports after a resize
"""

from __future__ import annotations

import argparse
import os
import re
import socket
import sys
import tempfile
import threading
import time

try:  # pragma: no cover - import guard mirrors dev_fake_ssh.py
    import paramiko
except ImportError:  # pragma: no cover
    paramiko = None

PROMPT = "\x1b[38;5;114mdemo@fixture\x1b[0m:\x1b[38;5;110m~\x1b[0m$ "
BANNER = (
    "\x1b]0;fixture shell\x07"
    "\x1b[1mHerdly shell fixture\x1b[0m — canned, no local execution\r\n"
    "16 colours : \x1b[31mred\x1b[0m \x1b[32mgreen\x1b[0m \x1b[33myellow\x1b[0m "
    "\x1b[34mblue\x1b[0m \x1b[35mmagenta\x1b[0m \x1b[36mcyan\x1b[0m\r\n"
    "256 colours: \x1b[38;5;203m203\x1b[0m \x1b[38;5;45m45\x1b[0m \x1b[38;5;220m220\x1b[0m\r\n"
    "truecolour : \x1b[38;2;255;120;40m255;120;40\x1b[0m \x1b[38;2;90;200;255m90;200;255\x1b[0m\r\n"
    "styles     : \x1b[1mbold\x1b[0m \x1b[2mdim\x1b[0m \x1b[3mitalic\x1b[0m \x1b[4munderline\x1b[0m\r\n"
    "type \x1b[36mhelp\x1b[0m for the canned command list\r\n\r\n"
    + PROMPT
)
HELP = (
    "canned commands: help ls color wrap alt clear exit status\r\n"
    "  status [working|blocked|idle|clear] reports OSC 7501 program status\r\n"
    "line editing: backspace, Ctrl-C (cancel), Ctrl-D (logout), up/down (history)\r\n"
)


class ShellState:
    def __init__(self) -> None:
        self.history: list[str] = []
        self.buffer = ""
        self.cursor = 0
        self.history_pos = 0
        self.size = (80, 24)
        self.alternate = False


def _erase_line(width: int, prompt: str) -> str:
    # Redraw the editable line in place: CR + prompt + buffer + erase to EOL,
    # then park the cursor where the edit point is.
    return "\r" + prompt + "\x1b[K"


def _run_command(state: ShellState, command: str, cols: int) -> str:
    word = command.strip()
    if not word:
        return ""
    if word == "help":
        return HELP
    if word == "ls":
        return (
            "\x1b[34mapp\x1b[0m  \x1b[34mdocs\x1b[0m  \x1b[34mtools\x1b[0m  "
            "README.md  build.gradle.kts\r\n"
        )
    if word == "color":
        return (
            "".join(f"\x1b[48;5;{n}m \x1b[0m" for n in range(16, 52)) + "\r\n"
            "".join(f"\x1b[38;5;{n}m{n:>4}\x1b[0m" for n in range(16, 28)) + "\r\n"
        )
    if word == "wrap":
        return ("0123456789" * (max(1, cols // 10) + 1)) + "\r\n"
    if word == "alt":
        state.alternate = True
        inner = "\x1b[?1049h\x1b[2J\x1b[H"
        inner += "\x1b[7m ALT SCREEN \x1b[0m press any line to return\r\n"
        for row in range(1, 6):
            inner += f"  row {row} " + "─" * 20 + "\r\n"
        return inner
    if word == "clear":
        return "\x1b[2J\x1b[H"
    if word == "status" or word.startswith("status "):
        # The Herdr "Program Status Protocol": a program says what it is doing and the terminal
        # never has to guess from pixels. ``clear`` is the documented way to say "nothing
        # outstanding"; anything else is a live report.
        arg = word[len("status"):].strip() or "blocked"
        if arg == "clear":
            return "\x1b]7501;state=clear\x07"
        if arg == "working":
            return "\x1b]7501;state=working:progress=40:app=fixture\x07"
        if arg == "idle":
            return "\x1b]7501;state=idle:app=fixture\x07"
        return "\x1b]7501;state=blocked:kind=permission:app=fixture\x07"
    if word == "exit":
        return None  # sentinel: close the channel
    return f"bash: {word}: canned fixture has no such command\r\n"


CSI = re.compile(r"\x1b\[([0-9;]*)([A-Za-z])")


def _apply_escape(state: "ShellState", params: str, final: str) -> str:
    """Handle the handful of CSI sequences a phone keyboard actually sends."""
    if final == "A":
        if state.history:
            state.history_pos = max(0, state.history_pos - 1)
            state.buffer = state.history[state.history_pos]
        return _erase_line(state.size[0], PROMPT) + state.buffer
    if final == "B":
        if state.history:
            state.history_pos = min(len(state.history), state.history_pos + 1)
            state.buffer = (
                state.history[state.history_pos] if state.history_pos < len(state.history) else ""
            )
        return _erase_line(state.size[0], PROMPT) + state.buffer
    if final == "C":
        return "\x1b[C"
    if final == "D":
        return "\x1b[D"
    return ""


def serve_shell(channel) -> None:
    state = ShellState()
    cols, rows = state.size
    print("[fixture] serve_shell start", flush=True)
    # Give the client a moment after the shell-success reply: some clients (JSch) only start
    # buffering channel data once the request confirmation has been processed.
    time.sleep(0.25)
    channel.send(BANNER.encode())
    print("[fixture] banner sent", flush=True)
    pending = b""
    while True:
        try:
            data = channel.recv(4096)
        except Exception:
            return
        if not data:
            return
        pending += data
        # Decode UTF-8 incrementally so multi-byte input survives.
        try:
            text = pending.decode("utf-8")
            pending = b""
        except UnicodeDecodeError:
            if len(pending) > 8:
                pending = b""
            continue
        out = []
        index = 0
        while index < len(text):
            if text[index] == "\x1b":
                match = CSI.match(text, index)
                if match:
                    out.append(_apply_escape(state, match.group(1), match.group(2)))
                    index = match.end()
                    continue
                index += 1  # lone ESC (e.g. Android hardware key) is ignored
                continue
            ch = text[index]
            index += 1
            if ch == "\r" or ch == "\n":
                command = state.buffer
                if state.alternate:
                    state.alternate = False
                    out.append("\x1b[?1049l")
                    state.buffer = ""
                    out.append(_erase_line(cols, PROMPT))
                    continue
                out.append("\r\n")
                result = _run_command(state, command, cols)
                if result is None:
                    channel.send("logout\r\n".encode())
                    channel.close()
                    return
                if command.strip():
                    state.history.append(command)
                state.history_pos = len(state.history)
                state.buffer = ""
                out.append(result)
                out.append(_erase_line(cols, PROMPT))
            elif ch == "\x7f" or ch == "\b":
                state.buffer = state.buffer[:-1]
                out.append(_erase_line(cols, PROMPT) + state.buffer)
            elif ch == "\x03":
                state.buffer = ""
                out.append("^C\r\n" + _erase_line(cols, PROMPT))
            elif ch == "\x04":
                if not state.buffer:
                    channel.send("logout\r\n".encode())
                    channel.close()
                    return
            elif ch >= " ":
                state.buffer += ch
                out.append(ch)
        payload = "".join(out)
        if payload:
            channel.send(payload.encode())


class Server(paramiko.ServerInterface if paramiko else object):
    def __init__(self, user: str, password: str) -> None:
        if paramiko is None:
            raise RuntimeError("Paramiko is required to serve SSH")
        self.user = user
        self.password = password
        self.size = (80, 24)
        self.shell_requested = threading.Event()
        self.channel = None

    def check_auth_password(self, username, password):
        return (
            paramiko.AUTH_SUCCESSFUL
            if (username == self.user and password == self.password)
            else paramiko.AUTH_FAILED
        )

    def get_allowed_auths(self, username):
        return "password"

    def check_channel_request(self, kind, chanid):
        return (
            paramiko.OPEN_SUCCEEDED
            if kind == "session"
            else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        )

    def check_channel_pty_request(self, channel, term, width, height, pixelwidth, pixelheight, modes):
        self.size = (int(width), int(height))
        return True

    def check_channel_shell_request(self, channel):
        # The canonical paramiko-server pattern: only remember the request here and let the
        # accept loop own the channel. Sending from this callback loses data for some clients
        # (JSch opens the channel but never sees the first packet).
        self.channel = channel
        self.shell_requested.set()
        return True

    def check_channel_window_change_request(self, channel, width, height, pixelwidth, pixelheight):
        self.size = (int(width), int(height))
        try:
            channel.send(f"\r\n\x1b[2m[size {width}x{height}]\x1b[0m\r\n".encode())
        except Exception:
            pass
        return True


_ACCEPTED: list = []  # keep accepted channels referenced, otherwise they get GC-closed


def serve_client(client: socket.socket, host_key, user: str, password: str) -> None:
    transport = paramiko.Transport(client)
    transport.add_server_key(host_key)
    server = Server(user, password)
    transport.start_server(server=server)
    # serve_shell owns all reads on the channel; this loop only keeps accepting.
    try:
        while transport.is_active():
            channel = transport.accept(1)
            if channel is None:
                continue
            _ACCEPTED.append(channel)
            waited = server.shell_requested.wait(5)
            print(f"[fixture] accept shell_requested={waited} same={channel is server.channel}", flush=True)
            if waited:
                threading.Thread(target=serve_shell, args=(channel,), daemon=True).start()
                server.shell_requested.clear()
    except Exception:
        pass
    finally:
        transport.close()


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=2223)
    ap.add_argument("--user", default="1234")
    ap.add_argument("--password", default="1234")
    ap.add_argument("--key", default=os.path.join(tempfile.gettempdir(), "herdly_shell_fixture_rsa"))
    ap.add_argument("--ready-file", help="write {port, fingerprint} JSON once bound")
    args = ap.parse_args(argv)
    if paramiko is None:
        print("paramiko is required", file=sys.stderr)
        return 2
    if not os.path.exists(args.key):
        paramiko.RSAKey.generate(2048).write_private_key_file(args.key)
    host_key = paramiko.RSAKey(filename=args.key)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((args.host, args.port))
        sock.listen(16)
        port = sock.getsockname()[1]
        if args.ready_file:
            import json

            with open(args.ready_file, "w", encoding="utf-8") as handle:
                json.dump({"port": port, "fingerprint": host_key.fingerprint}, handle)
        print(f"shell fixture listening on {args.host}:{port}", flush=True)
        while True:
            client, _ = sock.accept()
            threading.Thread(
                target=serve_client, args=(client, host_key, args.user, args.password), daemon=True
            ).start()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
