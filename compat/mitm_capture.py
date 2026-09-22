#!/usr/bin/env python3
"""Throwaway MITM capture: real bot <-> real server, hex-dump login phase.

The server log says the login stream dies with a compression framing error,
but the bot-side view looks correct. This sits between and records the exact
bytes of the COMPRESSION packet and the reply so both sides can be read.
"""
import socket
import struct
import sys
import threading
import time
import zlib

SERVER = ("127.0.0.1", 25901)
LISTEN = ("127.0.0.1", 25999)


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def dump(tag, data):
    print(f"[{tag}] {len(data):4d}B {data.hex(' ')}", flush=True)


def pump(src, dst, tag, mark_first=None):
    first = True
    try:
        while True:
            data = src.recv(65536)
            if not data:
                print(f"[{tag}] EOF", flush=True)
                break
            if first and mark_first:
                dump(tag + "-first", data)
                first = False
            else:
                dump(tag, data)
            dst.sendall(data)
    except OSError as e:
        print(f"[{tag}] {e}", flush=True)
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LISTEN)
    srv.listen(1)
    print(f"mitm listening on {LISTEN[1]} -> {SERVER[1]}", flush=True)
    cli, _ = srv.accept()
    print("client connected", flush=True)
    up = socket.create_connection(SERVER, timeout=10)
    t = threading.Thread(target=pump, args=(cli, up, "C>S"), daemon=True)
    t.start()
    pump(up, cli, "S>C")
    t.join(timeout=3)


if __name__ == "__main__":
    main()
