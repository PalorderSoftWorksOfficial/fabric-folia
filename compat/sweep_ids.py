#!/usr/bin/env python
"""Throwaway: name every serverbound play ID via the server's own decode errors.

For each candidate ID: full login->config, then send `zeros(40)` to that ID.
If the ID exists, the server tries to decode and logs the packet NAME plus
extra/missing byte count. If it doesn't exist, nothing is logged.
Success verdicts are read by diffing fabric/run/logs/latest.log.
"""
import importlib.util
import os
import sys
import time

spec = importlib.util.spec_from_file_location(
    "probe_bot", os.path.join(os.path.dirname(__file__), "probe_bot.py"))
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

LOG = os.path.join(os.path.dirname(__file__), "..", "fabric", "run", "logs", "latest.log")
NOISE = ("UUID of player", "joined the game", "ProbeBot", "SwBot")


def log_size():
    try:
        return os.path.getsize(LOG)
    except OSError:
        return 0


def new_log_lines(mark):
    try:
        with open(LOG, "rb") as f:
            f.seek(mark)
            data = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    out = []
    for line in data.splitlines():
        if any(n in line for n in NOISE):
            continue
        low = line.lower()
        if ("error" in low or "exception" in low or "decode" in low
                or "extra" in low or "missing" in low or "unread" in low):
            out.append(line.strip())
    return out


def probe(sid, name="SwBot"):
    b = pb.Bot(name=name)
    try:
        b.handshake()
        b.login()
        b.configure()
    except Exception as e:
        return f"SETUP-FAIL {type(e).__name__}: {e}"
    mark = log_size()
    try:
        b.send(sid, b"\x00" * 40)
    except Exception as e:
        return f"SEND-FAIL {type(e).__name__}: {e}"
    time.sleep(1.2)
    # respond to one keepalive if the connection is still healthy
    try:
        b.sock.settimeout(0.8)
        try:
            pkt = b.recv_packet(timeout=0.8)
            pid, pos = pb.read_varint(pkt, 0)
            if pid == pb.GAME_CB["KEEP_ALIVE"]:
                b.send(pb.GAME_SB["KEEP_ALIVE"], pkt[pos:])
                time.sleep(0.8)
        except (TimeoutError, OSError):
            pass
    except Exception:
        pass
    try:
        b.sock.close()
    except Exception:
        pass
    lines = new_log_lines(mark)
    if not lines:
        return "NO-ERROR (id unassigned, or codec accepted 40 zeros)"
    return " | ".join(lines[:2])


def main():
    lo = int(sys.argv[1], 16) if len(sys.argv) > 1 else 0x00
    hi = int(sys.argv[2], 16) if len(sys.argv) > 2 else 0x43
    results = {}
    for sid in range(lo, hi):
        v = probe(sid, name="Sw%02x" % sid)
        results[sid] = v
        print("SB %#04x -> %s" % (sid, v[:160]), flush=True)
    print("=== SUMMARY ===")
    for sid, v in results.items():
        print("SB %#04x: %s" % (sid, v[:200]))


if __name__ == "__main__":
    main()
