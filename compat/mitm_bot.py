#!/usr/bin/env python3
"""Throwaway: drive a login through the MITM proxy, printing our sends."""
import socket
import struct
import sys

sys.path.insert(0, "compat")
import importlib.util

spec = importlib.util.spec_from_file_location("pb", "compat/probe_bot.py")
pb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pb)

s = socket.create_connection(("127.0.0.1", 25999), timeout=15)
hs = pb.varint(776) + pb._str("127.0.0.1") + struct.pack(">H", 25901) + pb.varint(2)
frame = pb.varint(len(hs)) + hs
print("C SEND handshake frame:", frame.hex(" "), flush=True)
s.sendall(frame)
hello = pb._str("MitmBot") + pb._uuid(1)
frame = pb.varint(len(hello)) + hello
print("C SEND hello frame:", frame.hex(" "), flush=True)
s.sendall(frame)

s.settimeout(20)
buf = b""
t0 = __import__("time").time()
while __import__("time").time() - t0 < 20:
    try:
        d = s.recv(65536)
    except socket.timeout:
        print("client timeout waiting", flush=True)
        break
    if not d:
        print("server closed", flush=True)
        break
    buf += d
    print("C RECV:", d.hex(" "), flush=True)

# interpret what we got
pos = 0
n = len(buf)
while pos < n:
    # try length varint
    length = 0
    shift = 0
    start = pos
    ok = True
    while pos < n:
        b = buf[pos]
        pos += 1
        length |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            break
    else:
        print("partial frame at", start, flush=True)
        break
    body = buf[pos:pos + length]
    pos += length
    if len(body) < length:
        print(f"need {length}, have {len(body)} (partial)", flush=True)
        break
    pid, p2 = pb.read_varint(body, 0)
    print(f"frame: len={length} pid={pid:#x} body={body.hex(' ')}", flush=True)
    if pid == 0x03:
        thr, _ = pb.read_varint(body, p2)
        print(f"  COMPRESSION threshold={thr}", flush=True)
    elif pid == 0x01:
        print("  HELLO (encryption request?)", flush=True)
    elif pid == 0x02:
        print("  LOGIN_FINISHED", flush=True)
