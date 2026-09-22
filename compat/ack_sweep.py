#!/usr/bin/env python
"""Throwaway: the bot's ACCEPT_TELEPORTATION(0x00) ack never clears the
server's awaiting-teleport state (every move resyncs to spawn). Sweep every
play serverbound id 0..59 on fresh connections: send the ack body
(varint of the observed teleport id) to candidate X, then move; a NEW
teleport resync within 1.5s means not cleared. Also test an empty-body
variant. Success = no resync after the move."""
import importlib.util
import socket
import struct
import sys
import time

spec = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

HOST, PORT = "127.0.0.1", 25901


def fresh_bot(name):
    b = pb.Bot(host=HOST, port=PORT, name=name)
    b.handshake()
    b.login()
    b.configure()
    return b


def read_for(b, secs):
    """Drain inbound for secs; return list of (pid, body)."""
    got = []
    end = time.time() + secs
    while time.time() < end:
        try:
            pkt = b.recv_packet(timeout=max(0.05, end - time.time()))
        except (socket.timeout, TimeoutError):
            continue
        except OSError:
            break
        if pkt is None:
            break
        pid, pos = pb.read_varint(pkt, 0)
        got.append((pid, pkt[pos:]))
    return got


def one_candidate(x, with_body):
    b = fresh_bot("SweepBot")
    # wait for first teleport (0x48), parse its id
    tid = None
    end = time.time() + 6
    while time.time() < end and tid is None:
        try:
            pkt = b.recv_packet(timeout=0.3)
        except (socket.timeout, TimeoutError):
            continue
        except OSError:
            return "conn-closed"
        if pkt is None:
            return "conn-closed"
        pid, pos = pb.read_varint(pkt, 0)
        if pid == 0x48:
            tid, _ = pb.read_varint(pkt, pos)
    if tid is None:
        return "no-teleport"
    body = pb.varint(tid) if with_body else b""
    try:
        b.send(x, body)
    except OSError:
        return "send-fail"
    # confirm state: move, then watch for resync
    b.send_move((8.5, 64.0, 70.5), (0.0, 0.0))
    resynced = False
    for pid, _ in read_for(b, 1.5):
        if pid == 0x48:
            resynced = True
    return "resynced" if resynced else "CLEARED"


def main():
    lo, hi = int(sys.argv[1]), int(sys.argv[2])
    for x in range(lo, hi + 1):
        r = one_candidate(x, True)
        mark = " <== CANDIDATE" if r == "CLEARED" else ""
        print(f"id 0x{x:02X} body=varint -> {r}{mark}", flush=True)
        if r == "CLEARED":
            return
    print("no candidate cleared awaiting state", flush=True)


if __name__ == "__main__":
    main()
