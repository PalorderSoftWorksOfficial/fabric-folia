import argparse
import csv
import json
import math
import os
import re
import socket
import struct
import subprocess
import sys
import time
from datetime import datetime, timezone

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
LEGACY = re.compile(r"\u00a7.")

RE_MSPT = re.compile(
    r"Server MSPT \(tick work\): p50=([\d.]+) p95=([\d.]+) p99=([\d.]+) max=([\d.]+) "
    r"missed\(>50ms\)=(\d+) recent-window=(\d+)/(\d+)"
)
RE_BREAKDOWN = re.compile(
    r"breakdown: quiesce p50=([\d.]+) p95=([\d.]+) \| flush p50=([\d.]+) p95=([\d.]+) "
    r"\| drain p50=([\d.]+) p95=([\d.]+) \| body p50=([\d.]+) p95=([\d.]+)"
)
RE_WORKERS = re.compile(r"Workers: invocations=(\d+) utilization~([\d.]+)%")
RE_STRUCT = re.compile(r"Structural: merges=(\d+) splits=(\d+) aborts=(\d+)")
RE_WORK = re.compile(
    r"Work: entityTicks=(\d+) blockEntityTicks=(\d+) scheduledTicks=(\d+) playerConnectionTicks=(\d+)"
)
RE_SCHED = re.compile(r"Scheduler: globalTasks=(\d+) asyncTasks=(\d+) peakQueueDepth=(\d+)")
RE_ISOLATION = re.compile(r"Isolation: exceptionsContained=(\d+)")
RE_POOL = re.compile(r"workers total=(\d+) busy=(\d+) waiting=(\d+)")
RE_DUE = re.compile(r"queued region tasks=(\d+), due regions=(\d+)")
RE_REGION = re.compile(
    r"(?P<world>\S+) #(?P<id>\d+): state=(?P<state>\w+), ticks=(?P<ticks>\d+), "
    r"sections=(?P<sections>\d+)(?:, mspt=(?P<mspt>[\d.]+), peak=(?P<peak>[\d.]+)ms)?"
)
RE_HEAP = re.compile(r"garbage-first heap.*?used (\d+)K")
RE_PERF = re.compile(r"sun\.gc\.collector\.(\d+)\.(invocations|time)=(\d+)")
RE_GAMETIME = re.compile(r"(\d+)")


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host, port, password, timeout=10.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self.sock = None
        self.next_id = 0

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *_):
        self.close()

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)
        self._login()

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            finally:
                self.sock = None

    def _id(self):
        self.next_id += 1
        return self.next_id

    def _write(self, req_id, kind, body):
        payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)

    def _read_exact(self, count):
        chunks = []
        remaining = count
        while remaining > 0:
            block = self.sock.recv(remaining)
            if not block:
                raise RconError("connection closed by server")
            chunks.append(block)
            remaining -= len(block)
        return b"".join(chunks)

    def _read_packet(self):
        size = struct.unpack("<i", self._read_exact(4))[0]
        data = self._read_exact(size)
        req_id, kind = struct.unpack("<ii", data[:8])
        return req_id, kind, data[8:-2].decode("utf-8", errors="replace")

    def _login(self):
        req_id = self._id()
        self._write(req_id, 3, self.password)
        got_id, _, _ = self._read_packet()
        if got_id == -1 or got_id != req_id:
            raise RconError("RCON authentication failed")

    def command(self, command):
        req_id = self._id()
        self._write(req_id, 2, command)
        parts = []
        self.sock.settimeout(self.timeout)
        parts.append(self._read_packet()[2])
        self.sock.settimeout(0.25)
        try:
            while True:
                parts.append(self._read_packet()[2])
        except (socket.timeout, TimeoutError):
            pass
        finally:
            self.sock.settimeout(self.timeout)
        return clean("".join(parts))


def clean(text):
    return LEGACY.sub("", ANSI.sub("", text)).strip()


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


def round_or_none(value, digits=2):
    return None if value is None else round(value, digits)


def parse_int_list(lines, regex):
    for line in lines:
        match = regex.search(line)
        if match:
            return match
    return None


def jcmd(pid, *args):
    try:
        result = subprocess.run(
            ["jcmd", str(pid), *args],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        if result.returncode != 0:
            return None
        return result.stdout
    except (OSError, subprocess.SubprocessError):
        return None


def heap_used_mb(pid):
    text = jcmd(pid, "GC.heap_info")
    if not text:
        return None
    match = RE_HEAP.search(text)
    if not match:
        return None
    return round(int(match.group(1)) / 1024.0, 1)


def gc_counters(pid):
    text = jcmd(pid, "PerfCounter.print")
    if not text:
        return None, None
    invocations = {}
    nanos = {}
    for collector, field, value in RE_PERF.findall(text):
        if field == "invocations":
            invocations[collector] = int(value)
        else:
            nanos[collector] = int(value)
    if not invocations and not nanos:
        return None, None
    return sum(invocations.values()), round(sum(nanos.values()) / 1e6, 1)


def take_sample(conn, pid, persist_dir, stamp):
    commands = {
        "metrics": "folia metrics",
        "regions": "folia regions verbose",
        "workers": "folia workers",
        "health": "folia health",
        "scheduler": "folia scheduler",
    }
    raw = {}
    for name, command in commands.items():
        try:
            raw[name] = conn.command(command)
        except (RconError, OSError, socket.timeout) as error:
            raw[name] = f"ERROR {error}"
        if persist_dir:
            path = os.path.join(persist_dir, f"{stamp}-{name}.txt")
            with open(path, "w", encoding="utf-8") as stream:
                stream.write(raw[name] + "\n")

    gametime_text = conn.command("time query gametime")
    gametime_match = RE_GAMETIME.search(gametime_text)
    gametime = int(gametime_match.group(1)) if gametime_match else None

    sample = {
        "ts": stamp,
        "wall": time.time(),
        "gametime": gametime,
        "raw_metrics": raw["metrics"],
        "mod_commands_available": "Unknown command" not in raw["metrics"] and "ERROR" not in raw["metrics"],
    }

    lines = raw["metrics"].splitlines()
    match = parse_int_list(lines, RE_MSPT)
    if match:
        sample["mspt"] = {
            "p50": float(match.group(1)),
            "p95": float(match.group(2)),
            "p99": float(match.group(3)),
            "max": float(match.group(4)),
            "missed_total": int(match.group(5)),
            "recent_over": int(match.group(6)),
            "recent_n": int(match.group(7)),
        }
    match = parse_int_list(lines, RE_BREAKDOWN)
    if match:
        sample["breakdown"] = {
            "quiesce_p50": float(match.group(1)),
            "quiesce_p95": float(match.group(2)),
            "flush_p50": float(match.group(3)),
            "flush_p95": float(match.group(4)),
            "drain_p50": float(match.group(5)),
            "drain_p95": float(match.group(6)),
            "body_p50": float(match.group(7)),
            "body_p95": float(match.group(8)),
        }
    match = parse_int_list(lines, RE_WORKERS)
    if match:
        sample["invocations"] = int(match.group(1))
        sample["utilization_pct"] = float(match.group(2))
    match = parse_int_list(lines, RE_STRUCT)
    if match:
        sample["structural"] = {
            "merges": int(match.group(1)),
            "splits": int(match.group(2)),
            "aborts": int(match.group(3)),
        }
    match = parse_int_list(lines, RE_WORK)
    if match:
        sample["work"] = {
            "entity_ticks": int(match.group(1)),
            "block_entity_ticks": int(match.group(2)),
            "scheduled_ticks": int(match.group(3)),
            "player_connection_ticks": int(match.group(4)),
        }
    match = parse_int_list(lines, RE_SCHED)
    if match:
        sample["scheduler"] = {
            "global_tasks": int(match.group(1)),
            "async_tasks": int(match.group(2)),
            "peak_queue_depth": int(match.group(3)),
        }
    match = parse_int_list(lines, RE_ISOLATION)
    if match:
        sample["exceptions_contained"] = int(match.group(1))

    pool = RE_POOL.search(raw["workers"])
    if pool:
        sample["workers"] = {
            "total": int(pool.group(1)),
            "busy": int(pool.group(2)),
            "waiting": int(pool.group(3)),
        }
        due = RE_DUE.search(raw["workers"])
        if due:
            sample["workers"]["queued"] = int(due.group(1))
            sample["workers"]["due"] = int(due.group(2))

    regions = []
    for match in RE_REGION.finditer(raw["regions"]):
        regions.append(
            {
                "world": match.group("world"),
                "id": int(match.group("id")),
                "state": match.group("state"),
                "ticks": int(match.group("ticks")),
                "sections": int(match.group("sections")),
                "mspt_avg": float(match.group("mspt")) if match.group("mspt") else None,
                "peak_ms": float(match.group("peak")) if match.group("peak") else None,
            }
        )
    sample["regions"] = regions

    overall = re.search(r"Overall:\s*(OK|WARNING|ERROR)", raw["health"])
    sample["health"] = overall.group(1) if overall else "unknown"

    if pid:
        sample["heap_used_mb"] = heap_used_mb(pid)
        collections, gc_ms = gc_counters(pid)
        sample["gc_collections"] = collections
        sample["gc_time_ms"] = gc_ms
    return sample


def workload_commands(path):
    entries = []
    with open(path, "r", encoding="utf-8") as stream:
        for number, raw in enumerate(stream, start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            kind, _, rest = line.partition(" ")
            kind = kind.upper()
            if kind == "WAIT":
                entries.append(("WAIT", rest.strip()))
            elif kind == "REPEAT":
                count, _, body = rest.partition(" ")
                entries.append(("REPEAT", (int(count), body.strip())))
            elif kind in ("CMD", "REQUIRES_CLIENT"):
                entries.append((kind, rest.strip()))
            else:
                raise SystemExit(f"{path}:{number}: unknown directive {kind}")
    return entries


def run_workload(conn, path, pace, dry_run):
    entries = workload_commands(path)
    if any(kind == "REQUIRES_CLIENT" for kind, _ in entries):
        print(json.dumps({"workload": path, "status": "blocked_client"}))
        return 1
    started = time.time()
    executed = 0
    for kind, body in entries:
        if kind == "WAIT":
            if not dry_run:
                time.sleep(float(body))
            continue
        if kind == "REPEAT":
            count, command = body
            for _ in range(count):
                if dry_run:
                    print(command)
                else:
                    conn.command(command)
                executed += 1
                if pace and not dry_run:
                    time.sleep(pace)
            continue
        if dry_run:
            print(body)
        else:
            conn.command(body)
        executed += 1
        if pace and not dry_run:
            time.sleep(pace)
    print(
        json.dumps(
            {
                "workload": path,
                "status": "dry-run" if dry_run else "executed",
                "commands": executed,
                "wall_s": round(time.time() - started, 2),
            }
        )
    )
    return 0


def summarize(samples, args):
    tps_values = [s["tps"] for s in samples if s.get("tps") is not None]
    mspt_last = next((s["mspt"] for s in reversed(samples) if "mspt" in s), None)
    mspt_p95_max = max((s["mspt"]["p95"] for s in samples if "mspt" in s), default=None)
    utilization = [s["utilization_pct"] for s in samples if "utilization_pct" in s]
    heaps = [s["heap_used_mb"] for s in samples if s.get("heap_used_mb") is not None]
    gc_last = next(
        ((s["gc_collections"], s["gc_time_ms"]) for s in reversed(samples) if s.get("gc_collections") is not None),
        (None, None),
    )
    gc_first = next(
        ((s["gc_collections"], s["gc_time_ms"]) for s in samples if s.get("gc_collections") is not None),
        (None, None),
    )
    regions = samples[-1]["regions"] if samples else []
    first_regions = {}
    for sample in samples:
        for region in sample["regions"]:
            key = (region["world"], region["id"])
            if key not in first_regions:
                first_regions[key] = (sample["wall"], region["ticks"])
    region_tps = []
    if samples:
        for region in samples[-1]["regions"]:
            key = (region["world"], region["id"])
            origin = first_regions.get(key)
            if not origin:
                continue
            elapsed = samples[-1]["wall"] - origin[0]
            delta_ticks = region["ticks"] - origin[1]
            region_tps.append(
                {
                    "world": region["world"],
                    "id": region["id"],
                    "ticks": region["ticks"],
                    "tps": round(delta_ticks / elapsed, 3) if elapsed > 0 else None,
                    "mspt_avg": region["mspt_avg"],
                    "peak_ms": region["peak_ms"],
                    "sections": region["sections"],
                }
            )
    summary = {
        "tag": args.tag,
        "started_at": datetime.fromtimestamp(samples[0]["wall"], timezone.utc).isoformat() if samples else None,
        "duration_s": args.duration,
        "interval_s": args.interval,
        "host": f"{args.host}:{args.port}",
        "samples": len(samples),
        "mod_commands_available": samples[-1]["mod_commands_available"] if samples else None,
        "tps": {
            "mean": round_or_none(sum(tps_values) / len(tps_values), 3) if tps_values else None,
            "min": round_or_none(min(tps_values), 3) if tps_values else None,
            "p50": round_or_none(percentile(tps_values, 0.50), 3),
            "p95": round_or_none(percentile(tps_values, 0.95), 3),
            "max": round_or_none(max(tps_values), 3) if tps_values else None,
            "samples": len(tps_values),
        },
        "mspt_ms_last": mspt_last,
        "mspt_p95_worst": round_or_none(mspt_p95_max),
        "main_thread_wait_ms_last": samples[-1].get("breakdown") if samples else None,
        "workers_last": samples[-1].get("workers") if samples else None,
        "utilization_pct": {
            "last": round_or_none(utilization[-1]) if utilization else None,
            "max": round_or_none(max(utilization)) if utilization else None,
        },
        "queue_peak": next(
            (s["scheduler"]["peak_queue_depth"] for s in reversed(samples) if "scheduler" in s), None
        ),
        "structural_last": next((s["structural"] for s in reversed(samples) if "structural" in s), None),
        "work_last": next((s["work"] for s in reversed(samples) if "work" in s), None),
        "exceptions_contained_last": next(
            (s["exceptions_contained"] for s in reversed(samples) if "exceptions_contained" in s), None
        ),
        "regions_last": regions,
        "per_region_tps": region_tps,
        "memory": {
            "heap_used_mb_last": heaps[-1] if heaps else None,
            "heap_used_mb_max": max(heaps) if heaps else None,
        },
        "gc": {
            "collections_delta": (
                gc_last[0] - gc_first[0] if gc_last[0] is not None and gc_first[0] is not None else None
            ),
            "time_ms_delta": (
                round(gc_last[1] - gc_first[1], 1)
                if gc_last[1] is not None and gc_first[1] is not None
                else None
            ),
        },
        "health_last": samples[-1]["health"] if samples else None,
        "evidence_class": "measured-here-in-isolated-instance",
        "limits": [
            "TPS is derived from time-query-gametime deltas between samples, never from warning cadence.",
            "MSPT percentiles are the engine's own 100-sample rings at the last sample; they are not recomputed here.",
            "Per-region percentiles are not instrumented; per_region_tps and mspt_avg/peak are derived from /folia regions verbose.",
            "main_thread_wait_ms_last reports quiesce/flush/drain/body rings; server-thread deferral wait time is not instrumented.",
            "GC figures come from jcmd PerfCounter and cover only the sampling window.",
        ],
    }
    if samples and samples[-1]["regions"] and not any("mspt_avg" in r and r["mspt_avg"] for r in samples[-1]["regions"]):
        summary["limits"].append("region mspt was absent because no region had completed a tick during sampling")
    return summary


def mode_sample(args):
    os.makedirs(args.out, exist_ok=True)
    raw_dir = os.path.join(args.out, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    samples = []
    started = time.time()
    last_gametime = None
    last_wall = None
    csv_path = os.path.join(args.out, "samples.csv")
    fields = [
        "ts",
        "gametime",
        "tps",
        "mspt_p50",
        "mspt_p95",
        "mspt_p99",
        "mspt_max",
        "quiesce_p95",
        "drain_p95",
        "utilization_pct",
        "busy",
        "queued",
        "regions",
        "heap_used_mb",
        "gc_time_ms",
        "health",
    ]
    with Rcon(args.host, args.port, args.password) as conn, open(csv_path, "w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        while time.time() - started < args.duration:
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
            sample = take_sample(conn, args.pid, raw_dir, stamp)
            now = sample["wall"]
            if sample["gametime"] is not None and last_gametime is not None:
                elapsed = now - last_wall
                if elapsed > 0:
                    sample["tps"] = round((sample["gametime"] - last_gametime) / elapsed, 3)
            if sample["gametime"] is not None:
                last_gametime = sample["gametime"]
                last_wall = now
            samples.append(sample)
            writer.writerow(
                {
                    "ts": sample["ts"],
                    "gametime": sample["gametime"],
                    "tps": sample.get("tps"),
                    "mspt_p50": sample.get("mspt", {}).get("p50"),
                    "mspt_p95": sample.get("mspt", {}).get("p95"),
                    "mspt_p99": sample.get("mspt", {}).get("p99"),
                    "mspt_max": sample.get("mspt", {}).get("max"),
                    "quiesce_p95": sample.get("breakdown", {}).get("quiesce_p95"),
                    "drain_p95": sample.get("breakdown", {}).get("drain_p95"),
                    "utilization_pct": sample.get("utilization_pct"),
                    "busy": sample.get("workers", {}).get("busy"),
                    "queued": sample.get("workers", {}).get("queued"),
                    "regions": len(sample.get("regions", [])),
                    "heap_used_mb": sample.get("heap_used_mb"),
                    "gc_time_ms": sample.get("gc_time_ms"),
                    "health": sample.get("health"),
                }
            )
            stream.flush()
            remaining = args.duration - (time.time() - started)
            if remaining > 0:
                time.sleep(min(args.interval, remaining))
    summary = summarize(samples, args)
    summary["csv"] = csv_path
    path = os.path.join(args.out, "metrics.json")
    with open(path, "w", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2)
        stream.write("\n")
    print(json.dumps(summary, indent=2))
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--mode", choices=["sample", "exec", "stop"], default="sample")
    parser.add_argument("--out")
    parser.add_argument("--duration", type=float, default=120)
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--tag", default="unnamed")
    parser.add_argument("--workload")
    parser.add_argument("--pace", type=float, default=0.0)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if args.mode == "sample":
        if not args.out:
            parser.error("--out is required for sample mode")
        return mode_sample(args)
    if args.mode == "exec":
        if not args.workload:
            parser.error("--workload is required for exec mode")
        if args.dry_run:
            return run_workload(None, args.workload, args.pace, True)
        with Rcon(args.host, args.port, args.password) as conn:
            return run_workload(conn, args.workload, args.pace, False)
    with Rcon(args.host, args.port, args.password) as conn:
        print(conn.command("stop"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
