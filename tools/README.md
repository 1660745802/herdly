# Development SSH fixtures

Two loopback-only fixtures live here:

| Script | Purpose | Default port | Credentials |
| --- | --- | --- | --- |
| `dev_fake_ssh.py` | Herdr JSON-RPC bridge: agent states, approvals, fault scenarios | 2222 | `root` / `secret` |
| `dev_shell_fixture.py` | Interactive shell PTY: VT rendering, key bar, alternate screen | 2223 | `1234` / `1234` |

`dev_shell_fixture.py` serves a canned shell (banner with 16/256/truecolour samples,
`help`/`ls`/`color`/`wrap`/`alt`/`clear`/`exit`, backspace, Ctrl-C, Ctrl-D, history
via arrow keys, window-change reporting). Its `status` command reports OSC 7501 program
status (`status working`, `status blocked`, `status idle`, `status clear`) so the
terminal's status chip can be exercised without installing anything on a real host. It never executes local commands. Use
`--key PATH` to reuse an existing host key so a stored fingerprint still matches,
and `--ready-file PATH` to have it write `{"port":…,"fingerprint":…}` once listening.

```sh
.venv/bin/python tools/dev_shell_fixture.py --port 2223 --key /tmp/herdly.key
adb reverse tcp:2223 tcp:2223
```

Verify on a device through `tools/sandbox.sh` rather than by hand: it stops the
production package first, starts only the `.uxtest` build plus a loopback fixture, and
tears down only what it started.

```sh
tools/sandbox.sh up          # production app stopped, fixture + uxtest running
tools/sandbox.sh shot agent  # view tree + screenshot into $OUT
tools/sandbox.sh do shell input keyevent 66
tools/sandbox.sh down
```

The rest of this document describes `dev_fake_ssh.py`.

## Bridge transport

`dev_fake_ssh.py` provides one JSON request per SSH exec channel, matching the
mobile client's bridge transport. All clients and channels connected to one
server process share a locked `CannedSession`; separate processes/sessions are
isolated. Each TCP connection is handled on its own thread, so an idle connection
or a slow handshake does not block another host connection.

## Run locally

Install the pinned Python dependencies in a virtual environment; do not install
globally.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
```

Commands below run from the project root using the virtual environment. On
Windows, create it with `py -3 -m venv .venv` and replace `.venv/bin/python` with
`.\.venv\Scripts\python.exe`.

```sh
.venv/bin/python tools/dev_fake_ssh.py --canned
# USB-connected device or emulator:
adb reverse tcp:2222 tcp:2222
```

Stop the fixture and run `adb reverse --remove tcp:2222` after testing.

Connect to `127.0.0.1:2222`, user `root`, password `secret`. The server generates
and reuses a host key in the OS temporary directory; use `--key PATH` for an
explicit key location. Additional instances can use different `--port` and
`--key` values for multihost testing.

Only literal loopback addresses are accepted, including `::1`. There is no public
bind override. Canned mode ignores the exec command and never invokes a shell.
Local command execution requires the explicit `--allow-local-exec` flag instead
of `--canned`. It executes commands with the current user's permissions and is
intended only for trusted local clients. Do not forward that mode to other
machines. The fixture does not log commands, request bodies, prompt text,
passwords, or exception details.

## Approval fixtures

`w1:p1` initially waits at a numbered approval menu. Its command context is
`npm test`; option 2 grants a standing permission. `w1:p2` is a second waiting
agent with an “Always allow for this session” option. The remaining agents are
done and idle. Workspace/tab/pane data in snapshots lets Structure display the
same agents and revisions.

`agent.send_keys` uses `target` plus a string array `keys`, as the mobile client
does. Numbered approval accepts 1/2 or denies 3/esc; yes/no accepts y/n;
enter/escape accepts enter/esc. A trailing enter is allowed. Successful approval
moves the agent to done; denial moves it to idle. Both change text, revision,
and state sequence. Completion sequence advances when entering done. Repeating
an approval after it has finished returns an error.

`agent.prompt` accepts a nonempty `text`, advances revision/state sequence, and
replaces the output with a new blocked enter/escape confirmation. It does not
echo or retain the submitted prompt. These fixtures use the numbered, yes/no,
and enter/escape text forms recognized by `AskParser`.

```sh
.venv/bin/python tools/dev_fake_ssh.py --canned --ask-form yes-no
.venv/bin/python tools/dev_fake_ssh.py --canned --scenario stale-approval
.venv/bin/python tools/dev_fake_ssh.py --canned --scenario accepted-read-fail
.venv/bin/python tools/dev_fake_ssh.py --canned --scenario accepted-response-lost
.venv/bin/python tools/dev_fake_ssh.py --canned --latency-ms 3000 --delay-method pane.read
.venv/bin/python tools/dev_fake_ssh.py --canned --scenario rpc-failure --fail-method agent.send_keys
.venv/bin/python tools/dev_fake_ssh.py --canned --latency-ms 750
```

| Scenario | Deterministic behavior |
| --- | --- |
| `normal` | Mutation changes text, revision, status and sequence immediately. |
| `stale-approval` | The second `pane.read` of p1 changes its command to `npm run deploy -- --dry-run` and increments revision. The question and all options stay identical. Later reads stay stable. |
| `unchanged-output` | Valid sends/prompts return success but leave output, revision and status unchanged. |
| `accepted-read-fail` | Mutation commits and returns success. Exactly the next `pane.read` of that pane returns `fixture_read_failure`; subsequent reads reveal the committed state. Other panes do not consume the failure. |
| `accepted-response-lost` | The first valid `agent.send_keys` or `agent.prompt` commits, then closes the entire SSH transport before sending any reply or exit status. The fault is consumed once per server process, across all connections; later mutations respond normally. Invalid mutations do not consume it. A fresh connection can inspect the committed state. |
| `rpc-failure` | Every request for `--fail-method` fails with `fixture_failure` before mutation; default method is `pane.read`. |

`--latency-ms` delays each valid request before acquiring the state lock; it can
be combined with any scenario (0–60000 ms). Add `--delay-method pane.read` to
delay only pane reads, leaving SSH authentication, ping and snapshots responsive.
The request is counted before sleeping, so tests can distinguish server receipt
from a cancellation before dispatch. `--ask-form` selects `numbered`,
`yes-no`, or `enter-esc` for p1. Scenarios expose no destructive controls and
never execute the commands shown in fixture text.

Supported methods: `ping`, `session.snapshot`, `pane.read`, `agent.send_keys`,
`agent.prompt`, `agent.explain`, `workspace.list`, `tab.list`, and `pane.list`.
Lists support workspace/tab filtering. Snapshots use the flat shape accepted by
`BridgeCodec`, and pane reads use its `read` wrapper. This is a focused approval
fixture: Structure mutations and NewTask creation/start calls are not implemented
and return `unknown_method`; it is not a full Herdr emulator.

## Real JVM SSH transport regression tests

The JUnit `dev.herdwatch.ssh.HerdrClientTransportTest` suite exercises the actual
`HerdrClient` and JSch over loopback SSH, with a separate canned Python subprocess,
ephemeral port and temporary RSA host key for every test. It requires no external
host or running SSH daemon. From the project root:

```sh
./gradlew :app:testDebugUnitTest --tests dev.herdwatch.ssh.HerdrClientTransportTest
# Windows command prompt / PowerShell:
# .\gradlew.bat :app:testDebugUnitTest --tests dev.herdwatch.ssh.HerdrClientTransportTest
```

These tests also run with the normal JVM unit test task. Interpreter discovery
tries `HERDR_TEST_PYTHON`, `PYTHON`, `python`, then `python3`; environment overrides
are executable paths (not shell commands). Each candidate must import Paramiko.
If none works, JUnit `Assume` skips with an explicit Python/Paramiko prerequisite
message. To enable tests with a project virtual environment:

```sh
export HERDR_TEST_PYTHON="$PWD/.venv/bin/python"
# Windows PowerShell:
# .venv\Scripts\python -m pip install -r tools/requirements.txt
# $env:HERDR_TEST_PYTHON = "$PWD\.venv\Scripts\python.exe"
```

The fixture script is located relative to the JVM working directory or its
parents, so both project-root and Gradle app-directory execution work.
`tools/requirements.txt` pins Paramiko 5.1.0.

The tests cover real connect/ping/read/mutation, the client's own request
deadline producing a request error rather than caller cancellation, an outer
coroutine timeout that cancels a delayed pane read in under one second and
closes the remote socket, and recovery with a fresh client. Lost replies are
tested for both keys and prompts: the call must fail without a reported acceptance or automatic
retry, while a fresh snapshot/read proves exactly one committed mutation.
Matching-pin/wrong-password errors must remain authentication failures; an
actual pin mismatch must report the observed fingerprint before the server
receives any password authentication attempt. This tests transport acceptance
uncertainty, independently of UI state handling.

For subprocess orchestration, canned mode supports `--port 0` (OS-assigned
port), `--ready-file PATH` (atomic JSON containing `port` and `fingerprint`), and
`--status-file PATH` (atomic aggregate counters: `active_transports`,
`auth_attempts`, `requests`, `pane_reads`, `mutation_requests`, `mutations`,
`drops`). Use a unique directory/key/files for each process. No request content
or credentials are written to either file. Test failures include the fixture's
payload-free combined output; expected SSH rejection details are suppressed.
All clients, child processes and temporary directories are closed in `finally`
via `use`, including failed startup, assertions and cancellation. Teardown kills
the child if graceful termination does not finish promptly.

## Tests and in-process scenarios

Tests use the standard-library `unittest` runner and require no listening server:

```sh
.venv/bin/python -m unittest discover -s tools -p 'test_*.py' -v
```

The scenario API works even if Paramiko is unavailable (SSH-specific tests skip).
The Python suite covers state transitions, concurrent mutations, fault atomicity,
method-specific delay, aggregate status publication and closing a transport with
no reply or shell execution. Its socket-pair tests do not bind a TCP listener.

```python
import json
from tools.dev_fake_ssh import CannedSession, Scenario, canned_reply

session = CannedSession(Scenario(mode="accepted-read-fail"))
request = {"id": "1", "method": "agent.send_keys",
           "params": {"target": "w1:p1", "keys": ["1", "enter"]}}
reply, exit_status = canned_reply(json.dumps(request).encode(), session)
# Reuse session for pane.read and subsequent requests.
```

`canned_reply(raw)` is callable with a fresh fixture for that single call. Pass a
session explicitly to preserve state. Replies are
copied so caller changes cannot mutate live fixture state. With
`Scenario(mode="accepted-response-lost")`, the first successful mutation raises
`DropTransport` after committing; the SSH adapter catches it and closes the
transport. Direct scenario callers can catch it and then inspect the same session.
