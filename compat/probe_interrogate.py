#!/usr/bin/env python3
# Manual interrogation: where does a teleported probe go? One cow, step by
# step, printing every counter and query result.

import re
import socket
import struct
import time

PORT = 25902


class Rcon:
    def __init__(self, port, password="foliapass", timeout=15):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.s.sendall(self._pkt(1, 3, password))
        self._recv()

    @staticmethod
    def _pkt(rid, ptype, body):
        data = struct.pack("<ii", rid, ptype) + body.encode() + bytes(2)
        return struct.pack("<i", len(data)) + data

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            c = self.s.recv(n - len(buf))
            if not c:
                raise ConnectionError("closed")
            buf += c
        return buf

    def _recv(self):
        ln = struct.unpack("<i", self._read(4))[0]
        data = b""
        while len(data) < ln:
            data += self._read(ln - len(data))
        return data[8:-2].decode(errors="replace")

    def cmd(self, c):
        self.s.sendall(self._pkt(2, 2, c))
        return self._recv()


r = Rcon(PORT)
print("connected")


def snap():
    out = r.cmd("folia entities")
    m = re.search(r"minecraft:overworld: (\d+) tracked, (\d+) migration\(s\), (\d+) retired", out)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def count():
    r.cmd("execute store result score $n foliaCnt if entity @e[type=cow,tag=folia_probe]")
    out = r.cmd("scoreboard players get $n foliaCnt")
    m = re.search(r"\b(-?\d+)\b", out)
    return int(m.group(1)) if m else -1


r.cmd("kill @e[type=cow]")
time.sleep(1)
print("baseline:", snap(), "probes:", count())
out = r.cmd('summon minecraft:cow 8.5 120 8.5 {NoAI:1b,PersistenceRequired:1b,Tags:["folia_probe"]}')
print("summon:", out, "->", snap(), "probes:", count())
time.sleep(1)
print("after settle:", snap(), "probes:", count())

out = r.cmd("teleport @e[type=cow,tag=folia_probe,limit=1] 400.5 120 400.5")
print("teleport:", out)
for i in range(6):
    time.sleep(1)
    print(f"  t+{i+1}s: snap={snap()} probes={count()} "
          f"dest={r.cmd('data get entity @e[type=cow,tag=folia_probe,x=400,y=120,z=400,distance=..64,limit=1] Pos')!r}")

out = r.cmd("folia regions")
print("regions:", out)
r.cmd("stop")
