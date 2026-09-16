#!/usr/bin/env python3
# Live validation of Fabric-Folia's entity-ownership capture (mandate §15).
#
# Drives a REAL dev server (./gradlew :fabric:runServer, RCON surface) through
# the full entity lifecycle and asserts the registry's counters move the way
# the storm-tested protocol predicts:
#
#   1. PIN        /folia pin 0 0 + /folia pin 400 400 -> two regions form
#   2. SPAWN      /summon 5 cows in chunk (0,0) -> tracked +5 (add-funnel hook)
#   3. MOVE       /teleport one cow to chunk (400,400) -> migrations +1
#                 (setPos hook, region-boundary crossing)
#   4. REMOVE     /kill all cows -> tracked returns to baseline, retired
#                 stays 0 (unregister != retire — the discriminating pair)
#   5. SHUTDOWN   stop -> clean engine shutdown in the log
#
# Usage:
#   python compat/entity_live_check.py            # starts its own dev server
#   python compat/entity_live_check.py --attach   # server already running
#
# Result lands in compat/results/entity-live.json.

import argparse
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RUN_DIR = os.path.join(PROJECT, "fabric", "run")
RESULTS = os.path.join(HERE, "results")


def rcon_port():
    props = open(os.path.join(RUN_DIR, "server.properties"), encoding="utf-8").read()
    m = re.search(r"rcon\.port=(\d+)", props)
    return int(m.group(1)) if m else 25902


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
                raise ConnectionError("RCON connection closed")
            buf += c
        return buf

    def _recv(self):
        ln = struct.unpack("<i", self._read(4))[0]
        data = b""
        while len(data) < ln:
            data += self._read(ln - len(data))
        return data[8:-2].decode(errors="replace")

    def cmd(self, *cmds):
        out = []
        for c in cmds:
            self.s.sendall(self._pkt(2, 2, c))
            out.append(self._recv())
        return out

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


VERBOSE = os.environ.get("FOLIA_LIVE_VERBOSE") == "1"


def rcmd(r, c):
    """Run one command, print its RCON response (verbose), return the text."""
    out = r.cmd(c)[0]
    if VERBOSE:
        print(f"    > {c}\n      {out}", flush=True)
    return out


def phase(name, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)
    return {"phase": name, "ok": bool(ok), "detail": detail}


def wait_for_rcon(port, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            r = Rcon(port, timeout=5)
            return r
        except (OSError, ConnectionError):
            time.sleep(3)
    return None


def entities_snapshot(r):
    """Parses /folia entities -> {world: (tracked, migrations, retired)}."""
    out = r.cmd("folia entities")[0]
    snap = {}
    for m in re.finditer(r"(minecraft:\S+): (\d+) tracked, (\d+) migration\(s\), (\d+) retired", out):
        snap[m.group(1)] = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return out, snap


def wait_for_regions(r, want=2, seconds=90):
    deadline = time.time() + seconds
    while time.time() < deadline:
        out = r.cmd("folia regions")[0]
        m = re.search(r"minecraft:overworld: (\d+) region\(s\)", out)
        if m and int(m.group(1)) >= want:
            return True, int(m.group(1))
        time.sleep(3)
    return False, 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--attach", action="store_true", help="server already running")
    args = ap.parse_args()

    port = rcon_port()
    proc = None
    results = []
    print(f"Entity-ownership live check (RCON port {port})", flush=True)

    if not args.attach:
        print("Starting dev server (./gradlew :fabric:runServer)...", flush=True)
        stdout = open(os.path.join(RUN_DIR, "logs", "folia-live-stdout.log"), "w", encoding="utf-8")
        gradlew = os.path.join(PROJECT, "gradlew.bat")
        proc = subprocess.Popen(f'"{gradlew}" :fabric:runServer --console=plain',
                                cwd=PROJECT, shell=True,
                                stdout=stdout, stderr=subprocess.STDOUT)
        r = wait_for_rcon(port, 420)
        results.append(phase("dev server boot + RCON ready", r is not None))
        if r is None:
            report(results); sys.exit(1)
    else:
        r = wait_for_rcon(port, 20)
        results.append(phase("attach to running server", r is not None))
        if r is None:
            report(results); sys.exit(1)

    try:
        # 1. Two regions, far apart, so a teleport is a real boundary crossing.
        r.cmd("folia pin 0 0", "folia pin 400 400")
        ok, count = wait_for_regions(r, want=2)
        results.append(phase("two regions formed (pin 0 0, pin 400 400)", ok, f"{count} region(s)"))

        base_out, base = entities_snapshot(r)
        over = base.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("entities diagnostics readable", True,
                             f"baseline overworld tracked={over[0]} migrations={over[1]} retired={over[2]}"))

        # 2. SPAWN: five tagged probe cows, so natural spawns can't interfere
        # with the arithmetic. Verify each command's RCON response.
        rcmd(r, 'scoreboard objectives add foliaCnt dummy')
        summon_failures = []
        for i in range(5):
            out = rcmd(r, f'summon minecraft:cow 8.5 120 {8.5 + i} {{NoAI:1b,PersistenceRequired:1b,Tags:["folia_probe"]}}')
            if "No entity" in out or "Unknown" in out or "Incorrect" in out or "Expected" in out:
                summon_failures.append(out)
        time.sleep(2)
        probe_count = rcmd(r, 'execute if entity @e[type=cow,tag=folia_probe] run seed')
        probes_exist = "seed" in probe_count.lower() or "[" not in probe_count
        _, after_spawn = entities_snapshot(r)
        s = after_spawn.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("tagged probe summons succeeded", not summon_failures and probes_exist,
                             f"failures={summon_failures[:1]} probes_visible={probes_exist}"))
        results.append(phase("add funnel registers 5 spawns", s[0] >= over[0] + 5,
                             f"tracked {over[0]} -> {s[0]}"))        # 3. MOVE: bounce one tagged probe across regions — out to (400,400)
        # and back. The return leg runs seconds later, after the far chunk
        # has regionized (a teleport into a not-yet-regionized chunk is a
        # documented lazy-skip; the return leg is the real crossing).
        # Same-entity verification: read the probe AT the destination via a
        # distance selector (limit=1 alone re-sorts by distance after the
        # move and silently reads a different cow).
        def probe_count():
            rcmd(r, 'execute store result score $n foliaCnt if entity @e[type=cow,tag=folia_probe]')
            out = rcmd(r, 'scoreboard players get $n foliaCnt')
            m = re.search(r"\b(-?\d+)\b", out)
            return int(m.group(1)) if m else -1

        tp_out = rcmd(r, 'teleport @e[type=cow,tag=folia_probe,limit=1] 400.5 120 400.5')
        time.sleep(3)
        at_dest = rcmd(r, 'data get entity @e[type=cow,tag=folia_probe,x=400,y=120,z=400,distance=..64,limit=1] Pos')
        count_after_tp = probe_count()
        moved = "400.5" in at_dest and "Teleported" in tp_out
        results.append(phase("probe physically teleported out", moved,
                             f"tp={tp_out!r} dest={at_dest[:50]!r} probes_remaining={count_after_tp}"))
        rcmd(r, 'teleport @e[type=cow,tag=folia_probe,x=400,y=120,z=400,distance=..64,limit=1] 8.5 120 8.5')
        time.sleep(3)

        ok_move, mig = False, -1
        for _ in range(5):
            time.sleep(1)
            _, snap = entities_snapshot(r)
            m = snap.get("minecraft:overworld", (0, 0, 0))
            if m[1] >= over[1] + 1:
                ok_move, mig = True, m[1]
                break
        results.append(phase("movement hook migrates across regions", ok_move and moved,
                             f"migrations {over[1]} -> {mig} (teleported={moved})"))

        # 4. REMOVE: kill exactly the probes; unregister (not retire).
        # Fresh-delta arithmetic: natural spawns register between phases, so
        # every expectation is a delta against the snapshot taken NOW.
        _, pre_kill = entities_snapshot(r)
        pk = pre_kill.get("minecraft:overworld", (0, 0, 0))
        rcmd(r, "kill @e[type=cow,tag=folia_probe]")
        time.sleep(2)
        _, after_kill = entities_snapshot(r)
        k = after_kill.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("removal hook unregisters all probes", k[0] == pk[0] - 5,
                             f"tracked {pk[0]} -> {k[0]} (expect {pk[0] - 5})"))
        results.append(phase("unregister is not retire", k[2] == over[2],
                             f"retired {over[2]} -> {k[2]}"))

        # 5. Clean shutdown.
        r.cmd("stop")
        time.sleep(5)
    finally:
        r.close()
        if proc is not None:
            try:
                proc.wait(timeout=90)
            except subprocess.TimeoutExpired:
                proc.terminate()
                results.append(phase("server process exited cleanly", False, "had to terminate"))

    log_path = os.path.join(RUN_DIR, "logs", "latest.log")
    clean = False
    if os.path.exists(log_path):
        log = open(log_path, encoding="utf-8", errors="replace").read()
        clean = "Engine shut down cleanly" in log
    results.append(phase("engine shutdown logged", clean))

    report(results)


def report(results):
    passed = sum(1 for x in results if x["ok"])
    verdict = "PASS" if passed == len(results) and results else "FAIL"
    print(f"\nVERDICT: {verdict} ({passed}/{len(results)} phases)", flush=True)
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, "entity-live.json"), "w", encoding="utf-8") as f:
        json.dump({"verdict": verdict, "phases": results}, f, indent=2)
    sys.exit(0 if verdict == "PASS" else 2)


if __name__ == "__main__":
    main()
