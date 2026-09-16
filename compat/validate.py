#!/usr/bin/env python3
# Fabric-Folia compatibility validation harness.
#
# Purpose (COMPATIBILITY.md): compatibility is measured, never assumed. This
# script assembles REAL production server instances (Fabric server launcher +
# mod jars — not the dev environment), boots each optimization-mod combo, and
# drives a runtime protocol over RCON that exercises the engine for actual
# behavior: regionized random-tick mutation, worker attribution, dispatch
# cessation on unpin, diagnostics cleanliness, and clean shutdown.
#
# A successful STARTUP alone is never a passing verdict. The verdicts:
#   PASS       every runtime phase observed and clean
#   PARTIAL    booted and served, but the runtime protocol found problems
#   FAIL       could not complete the protocol (crash / hang / wrong output)
#   UNKNOWN    no 26.2 build of the mod exists to test against, or untested
#
# Usage:
#   python compat/validate.py --combo baseline            # Fabric + Fabric-Folia only
#   python compat/validate.py --combo lithium             # + Lithium
#   python compat/validate.py --combo lithium+c2me        # pairwise
#   python compat/validate.py --combo full                # the whole stack
#   python compat/validate.py --list                      # known combos
#
# Results land in compat/results/<combo>.json (raw) and are summarized in
# COMPATIBILITY.md's matrix. Nothing here is a hidden dependency of the
# production implementation: Fabric-Folia ships no code from this directory.

import argparse
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, "cache")
INSTANCES = os.path.join(HERE, "instances")
RESULTS = os.path.join(HERE, "results")

MC_VERSION = "26.2"
LOADER_VERSION = "0.19.5"
INSTALLER_VERSION = "1.1.2"
FABRIC_API = "fabric-api"  # Modrinth slug

# Every optimization target (COMPATIBILITY.md) with its Modrinth slug and the
# version actually resolved at last validation run (recorded in results).
MODS = {
    "lithium":     "lithium",
    "c2me":        "c2me-fabric",
    "ferritecore": "ferrite-core",
    "krypton":     "krypton",
    "vmp":         "vmp-fabric",
    "scalablelux": "scalablelux",
}

COMBOS = {
    "baseline":   [],
    "lithium":    ["lithium"],
    "c2me":       ["c2me"],
    "ferritecore": ["ferritecore"],
    "krypton":    ["krypton"],
    "vmp":        ["vmp"],
    "scalablelux": ["scalablelux"],
    "lithium+c2me": ["lithium", "c2me"],
    "full":       list(MODS.keys()),
    # Failure-isolation combos (COMPATIBILITY.md protocol): same mods with the
    # Fabric Folia intercept disabled - exercises the vanilla path under the
    # mod, separating "mod breaks vanilla behavior" from "mod x intercept".
    "c2me-intercept-off":       ["c2me"],
    "lithium-intercept-off":    ["lithium"],
}

# Per-combo config overrides (combo name -> partial config mapping).
COMBO_CONFIG = {
    "c2me-intercept-off":    {"general": {"regionized-random-ticks": False}},
    "lithium-intercept-off": {"general": {"regionized-random-ticks": False}},
}

# Per-combo base port (game), RCON = base + 1. Keeps runs isolated.
BASE_PORT = 25920


def log(msg):
    print(f"[compat] {msg}", flush=True)


def http_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "fabric-folia-compat-harness"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    log(f"  downloading {os.path.basename(dest)}")
    req = urllib.request.Request(url, headers={"User-Agent": "fabric-folia-compat-harness"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest + ".part", "wb") as f:
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
    os.replace(dest + ".part", dest)
    return dest


def resolve_modrinth_file(slug, game_version, loader="fabric"):
    """Latest listed version of `slug` for the game version; returns (url, filename, version_number)."""
    versions = http_json(
        f"https://api.modrinth.com/v2/project/{slug}/version"
        f"?game_versions=%5B%22{game_version}%22%5D&loaders=%5B%22{loader}%22%5D")
    for v in versions:
        for f in v["files"]:
            if f.get("primary"):
                return f["url"], f["filename"], v["version_number"]
    # fall back to first file of first version
    v = versions[0]
    f = v["files"][0]
    return f["url"], f["filename"], v["version_number"]


def build_mod_set(combo):
    """Returns (mods_dir_contents: list[(path, filename)], resolved: dict)."""
    os.makedirs(CACHE, exist_ok=True)
    resolved = {}
    files = []
    api_url, api_name, api_ver = resolve_modrinth_file(FABRIC_API, MC_VERSION)
    download(api_url, os.path.join(CACHE, api_name))
    files.append((os.path.join(CACHE, api_name), api_name))
    resolved["fabric-api"] = api_ver
    for key in combo:
        slug = MODS[key]
        url, name, ver = resolve_modrinth_file(slug, MC_VERSION)
        download(url, os.path.join(CACHE, name))
        files.append((os.path.join(CACHE, name), name))
        resolved[key] = ver
    return files, resolved


def fabric_server_launcher(dest):
    url = (f"https://meta.fabricmc.net/v2/versions/loader/{MC_VERSION}"
           f"/{LOADER_VERSION}/{INSTALLER_VERSION}/server/jar")
    return download(url, dest)


def write_instance_config(inst_dir, port, combo=None):
    """server.properties + EULA + engine config for one validation run."""
    rcon_port = port + 1
    with open(os.path.join(inst_dir, "eula.txt"), "w", newline="\n", encoding="utf-8") as f:
        f.write("eula=true\n")
    props = {
        "server-port": port,
        "rcon.port": rcon_port,
        "rcon.password": "foliapass",
        "enable-rcon": "true",
        "pause-when-empty-seconds": "0",
        "simulation-distance": "10",
        "view-distance": "10",
        "level-name": "world",
        "online-mode": "false",
    }
    with open(os.path.join(inst_dir, "server.properties"), "w", newline="\n", encoding="utf-8") as f:
        f.write("".join(f"{k}={v}\n" for k, v in props.items()))
    os.makedirs(os.path.join(inst_dir, "config"), exist_ok=True)
    # Engine config: the slice under test is on; STRICT thread checks on.
    with open(os.path.join(inst_dir, "config", "fabric-folia.yml"), "w", newline="\n", encoding="utf-8") as f:
        f.write(
            "# Fabric Folia configuration - compatibility validation instance\n"
            "config-version: 1\n"
            "general:\n"
            "  enabled: true\n"
            "  regionized-random-ticks: true\n"
            "threads:\n"
            "  worker-threads: -1\n"
            "  thread-check-mode: STRICT\n"
            "diagnostics:\n"
            "  debug-logging: true\n"
        )
    # Apply per-combo config overrides (failure-isolation runs).
    cfg_path = os.path.join(inst_dir, "config", "fabric-folia.yml")
    for section, kv in COMBO_CONFIG.get(combo, {}).items():
        with open(cfg_path, encoding="utf-8") as f:
            content = f.read()
        lines = content.splitlines(keepends=True)
        out, in_section = [], False
        for line in lines:
            if line.startswith(section + ":"):
                in_section = True
                out.append(line)
                continue
            if in_section and (line.startswith("  ") or line.startswith("\t")):
                for key, val in kv.items():
                    if line.strip().startswith(key + ":"):
                        out.append(f"  {key}: {str(val).lower()}\n")
                        break
                else:
                    out.append(line)
            else:
                if in_section:
                    in_section = False
                out.append(line)
        with open(cfg_path, "w", encoding="utf-8", newline="\n") as f:
            f.write("".join(out))
    return rcon_port


def port_guard(port):
    """Harness hygiene: a prior run that was hard-killed (terminal timeout)
    can leave an orphaned server holding this combo's ports, which corrupts
    the next run's instance directory. Detect and terminate the stale holder.
    Product behavior is unaffected - this only touches harness leftovers."""
    out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
    pids = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1].endswith(f":{port}") and "LISTENING" in line:
            pids.add(parts[-1])
    for pid in pids:
        if pid != "0":
            log(f"WARNING: port {port} held by stale process {pid} (prior hard-killed run) - terminating it")
            subprocess.run(["taskkill", "/PID", pid, "/F"], capture_output=True)
    if pids:
        time.sleep(4)


def assemble(combo_name):
    mods_list = COMBOS[combo_name]
    inst_dir = os.path.join(INSTANCES, combo_name)
    os.makedirs(os.path.join(inst_dir, "mods"), exist_ok=True)
    launcher = fabric_server_launcher(os.path.join(CACHE, "fabric-server-launch.jar"))
    shutil.copyfile(launcher, os.path.join(inst_dir, "fabric-server-launch.jar"))
    mods, resolved = build_mod_set(mods_list)
    # The jar under test: the real production artifact from the build.
    libs = os.path.join(PROJECT, "fabric", "build", "libs")
    jars = [j for j in os.listdir(libs) if j.endswith(".jar") and "sources" not in j]
    if not jars:
        raise SystemExit("no Fabric-Folia jar in fabric/build/libs — run ./gradlew :fabric:build first")
    shutil.copyfile(os.path.join(libs, jars[0]), os.path.join(inst_dir, "mods", jars[0]))
    resolved["fabric-folia-jar"] = jars[0]
    for src, name in mods:
        shutil.copyfile(src, os.path.join(inst_dir, "mods", name))
    # wipe previous world/logs so every run is cold
    for d in ("world", "world_nether", "world_the_end", "logs"):
        p = os.path.join(inst_dir, d)
        if os.path.exists(p):
            shutil.rmtree(p, ignore_errors=True)
    port = BASE_PORT + (list(COMBOS).index(combo_name) * 2)
    port_guard(port)
    rcon_port = write_instance_config(inst_dir, port, combo_name)
    return inst_dir, launcher, port, rcon_port, resolved


# ---------------------------------------------------------------------------
# RCON (proven transport from the live-server harness)
# ---------------------------------------------------------------------------

class Rcon:
    def __init__(self, port, password="foliapass", timeout=15):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.s.sendall(self._pkt(1, 3, password))
        self._recv()

    @staticmethod
    def _pkt(rid, ptype, body):
        data = struct.pack("<ii", rid, ptype) + body.encode() + bytes(2)
        return struct.pack("<i", len(data)) + data

    def _recv(self):
        ln = struct.unpack("<i", self._read(4))[0]
        data = b""
        while len(data) < ln:
            data += self._read(ln - len(data))
        return data[8:-2].decode(errors="replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            c = self.s.recv(n - len(buf))
            if not c:
                raise ConnectionError("RCON connection closed")
            buf += c
        return buf

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


# ---------------------------------------------------------------------------
# The runtime protocol
# ---------------------------------------------------------------------------

def phase(name, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)
    return {"phase": name, "ok": bool(ok), "detail": detail}


def wait_for_rcon(rcon_port, boot_seconds):
    deadline = time.time() + boot_seconds
    while time.time() < deadline:
        try:
            r = Rcon(rcon_port, timeout=5)
            out = r.cmd("folia regions")[0]
            # Ready = RCON answering AND the command surface replying —
            # including the legitimate engine-disabled reply, which the
            # engine-status phase below must report honestly.
            if ("Regions by world" in out or "no live regions" in out
                    or "disabled" in out.lower()):
                return r
            r.close()
        except (OSError, ConnectionError):
            pass
        time.sleep(3)
    return None


def count_block(rcon, x, y, z, block, score):
    """Count a 5x5 footprint of `block` at height y centered on (x,z) via scoreboard readback."""
    rcon.cmd(f"scoreboard players set {score} ff 0")
    cmds = [f"execute if block {x+dx} {y} {z+dz} {block} run scoreboard players add {score} ff 1"
            for dx in range(-2, 3) for dz in range(-2, 3)]
    rcon.cmd(*cmds)
    time.sleep(0.4)
    out = rcon.cmd(f"scoreboard players get {score} ff")[0]
    m = re.search(r"has (-?\d+)", out)
    return int(m.group(1)) if m else None


def run_protocol(inst_dir, rcon_port, combo_name):
    results = []
    log_path = os.path.join(inst_dir, "logs", "latest.log")

    def grep(pat):
        try:
            with open(log_path, encoding="utf-8", errors="replace") as f:
                return [l for l in f if re.search(pat, l)]
        except OSError:
            return []

    # --- boot (necessary but never sufficient) ---
    r = wait_for_rcon(rcon_port, 420)
    if r is None:
        results.append(phase("boot", False, "RCON never became ready"))
        return results, False
    results.append(phase("boot", True))

    # --- engine diagnostics ---
    # Isolation combos run with the intercept disabled by design: phases that
    # only exist to observe the intercept are inapplicable there and are
    # skipped rather than counted as failures.
    intercept_off = combo_name in COMBO_CONFIG
    suppressed = False
    try:
        status = r.cmd("folia status")[0]
        results.append(phase("engine-status", "Engine: ACTIVE" in status,
                             "ACTIVE" if "Engine: ACTIVE" in status else status[:90]))
        # The server's own status line is the source of truth for what runs:
        # a suppressed slice (measured startup policy, e.g. C2ME) means the
        # intercept phases are inapplicable this session — asserted as such.
        suppressed = "SUPPRESSED" in status
        if not intercept_off and not suppressed:
            inter = "Regionized random ticks: enabled (per-chunk random ticks on region workers)" in status
            results.append(phase("engine-intercept-armed", inter))
        if suppressed:
            results.append(phase("intercept-suppressed-with-warning",
                                 bool(grep("C2ME detected")) and bool(grep("SUPPRESSED")),
                                 "status reports suppression AND the startup warning is in the log"))
    except (OSError, ConnectionError) as e:
        results.append(phase("engine-status", False, str(e)))
        r.close()
        return results, False

    # --- world mutation under pin (the real behavior, not "it starts") ---
    # Under a suppressed intercept, vanilla executes random ticks on the server
    # thread: the scenario still must show vanilla mutating the pinned area
    ux = 192000  # chunk 12000,0 — far from spawn-chunk interference
    r.cmd("scoreboard objectives add ff dummy", f"folia pin 12000 0")
    if not intercept_off and not suppressed:
        regionized = False
        for _ in range(30):
            regs = r.cmd("folia regions")[0]
            if re.search(r"minecraft:overworld: [1-9]", regs):
                regionized = True
                break
            time.sleep(2)
        results.append(phase("regionization", regionized))
    r.cmd(*[f"setblock {ux+dx} -58 {dz} grass_block" for dx in range(-2, 3) for dz in range(-2, 3)])
    r.cmd(*[f"setblock {ux+dx} -57 {dz} minecraft:stone" for dx in range(-2, 3) for dz in range(-2, 3)])
    r.cmd(*[f"setblock {ux+dx} -58 {20+dz} minecraft:dirt" for dx in range(-2, 3) for dz in range(-2, 3)])
    time.sleep(2)
    grass0 = count_block(r, ux, -58, 0, "grass_block", "GRASS_A")
    dirt0 = count_block(r, ux, -58, 20, "minecraft:dirt", "DIRT_B")
    roof0 = count_block(r, ux, -57, 0, "minecraft:stone", "ROOF_A")
    # The roof check makes the scenario self-verifying: if the build failed,
    # uncovered grass legitimately won't die and a mod must not be blamed.
    # Grass baseline may be < 25: random ticks start the moment the pin lands,
    # so a few deaths between build and read are valid worker-tick behavior.
    # Causality is asserted on the RELATIVE drop over the window instead.
    results.append(phase("scenario-built", dirt0 == 25 and roof0 == 25 and grass0 >= 18,
                         f"grass={grass0} dirt={dirt0} roof={roof0} (dirt/roof want 25; grass >= 18)"))
    results.append(phase("baseline-readable", dirt0 == 25,
                         f"grass={grass0} dirt={dirt0} roof={roof0} (dirt must be 25)"))
    time.sleep(75)
    grass1 = count_block(r, ux, -58, 0, "grass_block", "GRASS_A")
    dirt1 = count_block(r, ux, -58, 20, "minecraft:dirt", "DIRT_B")
    died = (grass1 is not None and grass0 is not None and grass1 < grass0)
    mutation_phase = "vanilla-tick-mutation" if suppressed else "worker-tick-mutation"
    results.append(phase(mutation_phase, died,
                         f"grass {grass0}->{grass1} (deaths={None if grass1 is None else grass0-grass1}), dirt control={dirt1}"))
    if not intercept_off and not suppressed:
        workers = set(re.findall(r"on '(FabricFolia-Worker-\d+)'", "\n".join(grep("WORKER-EVIDENCE"))))
        results.append(phase("worker-attribution", len(workers) >= 1, f"workers seen: {sorted(workers)}"))

        # --- dispatch cessation on unpin ---
        r.cmd("folia unpin 12000 0")
        time.sleep(5)
        before = len(grep(r"pass: dispatched"))
        time.sleep(45)
        after = len(grep(r"pass: dispatched"))
        results.append(phase("dispatch-cessation", after == before, f"dispatch lines {before}->{after}"))
    else:
        # Still unpin so the shutdown path is the production one.
        r.cmd("folia unpin 12000 0")

    # --- diagnostics cleanliness ---
    # "Region task failed" is the scheduler's per-task failure report — the
    # line that exposed the C2ME random-guard failures once grepped for.
    bad = grep(r"VIOLATION|Region tick failed|Region task failed|dropped [1-9][0-9]* queued")
    results.append(phase("diagnostics-clean", not bad, f"{len(bad)} offending log lines"))

    # --- graceful shutdown ---
    try:
        r.cmd("stop")
    except (OSError, ConnectionError):
        pass
    r.close()
    stopped = False
    for _ in range(60):
        time.sleep(2)
        try:
            with open(log_path, encoding="utf-8", errors="replace") as f:
                tail = f.read()[-4000:]
            if re.search(r"Thread RCON Listener stopped|All dimensions are saved", tail):
                stopped = True
                break
        except OSError:
            break
    results.append(phase("graceful-shutdown", stopped))
    return results, True


def validate(combo_name):
    log(f"=== combo '{combo_name}': {COMBOS[combo_name] or ['(no optimization mods)']} ===")
    inst_dir, launcher, port, rcon_port, resolved = assemble(combo_name)
    log(f"instance: {inst_dir} (game port {port}, rcon {rcon_port})")
    log(f"resolved versions: {resolved}")
    proc = subprocess.Popen(
        ["java", "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
        cwd=inst_dir,
        stdout=open(os.path.join(HERE, f"{combo_name}.stdout.log"), "wb"),
        stderr=subprocess.STDOUT)
    verdict = "FAIL"
    phases = []
    try:
        phases, booted = run_protocol(inst_dir, rcon_port, combo_name)
        if booted:
            ok_all = all(p["ok"] for p in phases)
            verdict = "PASS" if ok_all else "PARTIAL"
        else:
            verdict = "FAIL"
    finally:
        if proc.poll() is None:
            try:
                proc.wait(timeout=180)
            except subprocess.TimeoutExpired:
                proc.kill()
                verdict = "FAIL"
    result = {
        "combo": combo_name,
        "mods": resolved,
        "minecraft": MC_VERSION,
        "fabric-folia": open(os.path.join(PROJECT, "gradle.properties")).read()
            .split("mod_version=")[1].splitlines()[0].strip(),
        "date": time.strftime("%Y-%m-%d"),
        "verdict": verdict,
        "phases": phases,
    }
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, f"{combo_name}.json"), "w", newline="\n") as f:
        json.dump(result, f, indent=2)
    log(f"=== '{combo_name}' verdict: {verdict} "
        f"({sum(1 for p in phases if p['ok'])}/{len(phases)} phases) ===")
    return verdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--combo", required=False, help="combo name (see --list)")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    if args.list or not args.combo:
        for name, mods in COMBOS.items():
            print(f"{name:14s} + {', '.join(mods) if mods else '(baseline: no optimization mods)'}")
        return
    if args.combo not in COMBOS:
        print(f"unknown combo '{args.combo}'. --list for options.", file=sys.stderr)
        sys.exit(2)
    verdict = validate(args.combo)
    # Only a full PASS exits zero: PARTIAL means real problems were observed.
    sys.exit(0 if verdict == "PASS" else 1)


if __name__ == "__main__":
    main()
