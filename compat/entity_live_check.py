#!/usr/bin/env python3
# Live validation of Fabric-Folia's entity-ownership capture (mandate §15).
#
# Drives a REAL dev server (./gradlew :fabric:runServer, RCON surface) through
# the entity lifecycle and asserts the registry's counters move the way the
# storm-tested protocol predicts:
#
#   1. PIN        /folia pin 0 0 + /folia pin 400 400 -> two regions form
#   2. SPAWN      5 tagged cows at (0,0) -> tracked +5 exactly
#                 (doMobSpawning false makes the deltas exact)
#   3. CANARY     a cow summoned AT (400,400) proves the destination chunk is
#                 loaded BEFORE the teleport (a teleport into a still-
#                 generating chunk parks the entity as UNLOADED_TO_CHUNK —
#                 vanilla behavior, and the confounder this eliminates)
#   4. MOVE       teleport one probe to (400,400) -> migrations +1
#                 (setPosRaw hook, region-boundary crossing)
#   5. REMOVE     kill all 6 tracked cows -> tracked -6 exactly, retired
#                 stays flat (unregister != retire — the discriminating pair)
#   6. SHUTDOWN   stop -> clean engine shutdown in the log
#
# Usage:
#   python compat/entity_live_check.py            # starts its own dev server
#   python compat/entity_live_check.py --attach   # server already running
#   FOLIA_LIVE_VERBOSE=1 ...                      # print every RCON exchange
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

VERBOSE = os.environ.get("FOLIA_LIVE_VERBOSE") == "1"


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

    def cmd(self, c):
        self.s.sendall(self._pkt(2, 2, c))
        return self._recv()

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


def rcmd(r, c):
    """Run one command, print its RCON response (verbose), return the text."""
    out = r.cmd(c)
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
    out = rcmd(r, "folia entities")
    snap = {}
    for m in re.finditer(r"(minecraft:\S+): (\d+) tracked, (\d+) migration\(s\), (\d+) retired", out):
        snap[m.group(1)] = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return out, snap


def wait_for_regions(r, want=2, seconds=120):
    deadline = time.time() + seconds
    while time.time() < deadline:
        out = rcmd(r, "folia regions")
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
            report(results)
    else:
        r = wait_for_rcon(port, 20)
        results.append(phase("attach to running server", r is not None))
        if r is None:
            report(results)

    try:
        # 1. Two regions, far apart, so a teleport is a real boundary
        # crossing. Groups live at x=0 and x=4000 — BOTH inside vanilla's
        # spawn-chunk area (the 26.2 dedicated server keeps only spawn chunks
        # loaded without a connected player; chunks beyond it stay marked-but-
        # never-loaded, so entities teleported there park as UNLOADED_TO_CHUNK
        # — vanilla behavior, verified with a control probe against vanilla
        # forceload). 4000 blocks is a real region boundary (spawn section
        # spans x=[-16,16) -> 0 is far outside it).
        rcmd(r, "folia pin 0 0")
        rcmd(r, "folia pin 250 0")
        ok, count = wait_for_regions(r, want=2)
        results.append(phase("two regions formed (pin 0 0, pin 250 0)", ok, f"{count} region(s)"))

        # Deterministic counters: natural mob spawns register too (the funnel
        # working as designed), which would otherwise confound exact deltas.
        rcmd(r, "gamerule doMobSpawning false")
        time.sleep(1)
        _, base_snap = entities_snapshot(r)
        base = base_snap.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("entities diagnostics readable", True,
                             f"baseline tracked={base[0]} migrations={base[1]} retired={base[2]}"))

        # 2. SPAWN: five tagged probe cows at chunk (0,0). Verify each
        # command's RCON response, then assert the exact +5.
        summon_failures = []
        for i in range(5):
            out = rcmd(r, f'summon minecraft:cow 8.5 120 {8.5 + i} '
                          f'{{NoAI:1b,PersistenceRequired:1b,Tags:["folia_probe"]}}')
            if "Summoned" not in out:
                summon_failures.append(out)
        time.sleep(1)
        _, after_spawn = entities_snapshot(r)
        s = after_spawn.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("add funnel registers 5 spawns",
                             not summon_failures and s[0] == base[0] + 5,
                             f"tracked {base[0]} -> {s[0]} (exact delta, spawning disabled)"
                             + (f" failures={summon_failures[:1]}" if summon_failures else "")))

        # 3. CANARY: summon AT the destination first — it only registers if
        # the chunk is loaded, which is what makes the later teleport a real
        # arrival instead of an UNLOADED_TO_CHUNK parking.
        canary_out = rcmd(r, 'summon minecraft:cow 4000.5 120 8.5 '
                             '{NoAI:1b,PersistenceRequired:1b,Tags:["folia_canary"]}')
        time.sleep(2)
        _, after_canary = entities_snapshot(r)
        c = after_canary.get("minecraft:overworld", (0, 0, 0))
        canary_ok = "Summoned" in canary_out and c[0] == s[0] + 1
        results.append(phase("canary proves destination chunk loaded", canary_ok,
                             f"canary={'Summoned' in canary_out} tracked {s[0]} -> {c[0]}"))

        # 4. MOVE: teleport one probe across regions; verify the SAME entity
        # arrived by reading its Pos AT the destination (limit=1 alone
        # re-sorts by distance and can silently read a different cow).
        tp_out = rcmd(r, "teleport @e[type=cow,tag=folia_probe,limit=1] 4000.5 120 9.5")
        time.sleep(2)
        at_dest = rcmd(r, "data get entity @e[type=cow,tag=folia_probe,"
                          "x=4000,y=120,z=8,distance=..32,limit=1] Pos")
        moved = "Teleported" in tp_out and "9.5" in at_dest
        results.append(phase("probe physically teleported", moved,
                             f"tp={tp_out!r} dest={at_dest[:60]!r}"))

        ok_move, mig = False, -1
        for _ in range(5):
            time.sleep(1)
            _, snap = entities_snapshot(r)
            m = snap.get("minecraft:overworld", (0, 0, 0))
            if m[1] >= base[1] + 1:
                ok_move, mig = True, m[1]
                break
        results.append(phase("movement hook migrates across regions", ok_move and moved,
                             f"migrations {base[1]} -> {mig} (teleported={moved})"))

        # 5. REMOVE: clear every non-player entity — the 6 tracked cows AND
        # their death drops (items and xp orbs are entities too, and they
        # correctly register through the same funnel) AND any natural mobs.
        # Final assertion: tracked returns to the exact boot baseline — every
        # entity that entered the world has left the registry.
        rcmd(r, "kill @e[type=!player]")
        time.sleep(1)
        rcmd(r, "kill @e[type=!player]")
        time.sleep(2)
        _, after_kill = entities_snapshot(r)
        k = after_kill.get("minecraft:overworld", (0, 0, 0))
        results.append(phase("removal hook unregisters every entity",
                             k[0] == base[0],
                             f"tracked base={base[0]} post-kill={k[0]} (cows + canary + drops + naturals all unregistered)"))
        results.append(phase("unregister is not retire", k[2] == base[2],
                             f"retired {base[2]} -> {k[2]}"))

        # 6. Clean shutdown.
        rcmd(r, "stop")
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
