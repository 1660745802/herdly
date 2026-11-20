#!/usr/bin/env bash
# Isolated verification shell: the app under test and a canned host, never the real phone state.
#
# The official Herdr repo keeps a sandbox for the same reason (scripts/sandbox: its own home, core
# and display) and states the rule we follow here: never run pointer or key automation against
# someone's real session. We cannot give Android a private screen, so the separation is by package
# and by process: the production app is force-stopped first, only the `.uxtest` build runs, and the
# canned host lives on loopback.
#
#   tools/sandbox.sh up            stop the real app, start the canned host + the test build
#   tools/sandbox.sh status        what is running, what is on screen, what is reversed
#   tools/sandbox.sh shot NAME     dump the view tree and save a screenshot to $OUT
#   tools/sandbox.sh do CMD...     run one adb command (kept here so every call has the guards)
#   tools/sandbox.sh down          stop the test build, kill the host we started, drop the reverse
#
# Anything this script starts is recorded, and only that is stopped again — it never kills a host it
# did not start, which is how the real fixture earlier got taken down by an unrelated `pkill`.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-C:/Users/likunhong/Documents/platform-tools/adb}"
PY="${PYTHON:-python}"
OUT="${OUT:-C:/Users/likunhong/AppData/Local/Temp/herdly-sandbox}"
PKG_PROD="dev.herdwatch"
PKG_TEST="dev.herdwatch.uxtest"
PORT="${PORT:-2223}"
KEY="${KEY:-C:/Users/likunhong/AppData/Local/Temp/herdwatch_fake_ssh_rsa}"
PIDFILE="$OUT/host.pid"

mkdir -p "$OUT"

started_by_us() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

cmd_up() {
    echo "== stopping the real app"
    "$ADB" shell am force-stop "$PKG_PROD" || true
    "$ADB" shell am force-stop "$PKG_TEST" || true

    if started_by_us; then
        echo "== canned host already up (pid $(cat "$PIDFILE"))"
    else
        echo "== starting the canned host on 127.0.0.1:$PORT"
        # stdin from /dev/null and a full redirect: a background host that inherits the caller's
        # pipes keeps the calling shell alive, which looks like the script hanging.
        ( cd "$ROOT" && nohup "$PY" tools/dev_fake_ssh.py --canned --port "$PORT" \
            --user root --password secret --key "$KEY" >"$OUT/host.log" 2>&1 </dev/null & echo $! >"$PIDFILE" )
        sleep 2
    fi

    "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
    "$ADB" shell am start -n "$PKG_TEST/dev.herdwatch.MainActivity" >/dev/null
    sleep 2
    cmd_status
}

cmd_status() {
    echo "== resumed activity"
    "$ADB" shell dumpsys activity activities \
        | grep -m1 -E "topResumedActivity|ResumedActivity" || true
    echo "== reverse"
    "$ADB" reverse --list || true
    echo "== canned host"
    if started_by_us; then echo "pid $(cat "$PIDFILE") on :$PORT"; else echo "not started by this script"; fi
}

cmd_shot() {
    local name="${1:-shot}"
    # MSYS_NO_PATHCONV stops Git Bash from rewriting /sdcard into a Windows path.
    MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /sdcard/sandbox.xml >/dev/null
    MSYS_NO_PATHCONV=1 "$ADB" shell screencap -p /sdcard/sandbox.png
    "$ADB" pull /sdcard/sandbox.xml "$OUT/$name.xml" >/dev/null
    "$ADB" pull /sdcard/sandbox.png "$OUT/$name.png" >/dev/null
    echo "$OUT/$name.png"
}

cmd_do() {
    [ "$#" -gt 0 ] || { echo "usage: sandbox.sh do CMD..." >&2; exit 2; }
    "$ADB" "$@"
}

cmd_down() {
    echo "== stopping the test build"
    "$ADB" shell am force-stop "$PKG_TEST" || true
    if started_by_us; then
        echo "== killing the canned host we started"
        kill "$(cat "$PIDFILE")" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi
    "$ADB" reverse --remove-all >/dev/null 2>&1 || true
    echo "== done (the real app was never started by this script)"
}

case "${1:-status}" in
    up) shift; cmd_up ;;
    status) cmd_status ;;
    shot) shift; cmd_shot "${1:-shot}" ;;
    do) shift; cmd_do "$@" ;;
    down) cmd_down ;;
    *) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
