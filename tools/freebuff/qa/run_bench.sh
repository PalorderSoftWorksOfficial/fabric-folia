#!/usr/bin/env bash
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$QA_DIR/qa_lib.sh"

usage() {
	cat <<'EOF'
run_bench.sh - orchestrate one QA run against an assembled instance.

Dry run is the default: nothing is started and nothing is written until
--execute is passed. This harness is clientless. It cannot measure PvP, packet,
mount, inventory or teleport performance: those need protocol clients that do
not exist in this pass.

Usage:
  run_bench.sh [options]

  --instance DIR       assembled instance (required with --execute)
  --execute            start the server; without it this is a plan-only dry run
  --profile NAME       smoke | full (default: the profile recorded in the instance)
  --workload PATH      workload file (default: workloads/idle-scheduled.txt)
  --duration SECONDS   sampling window (default: profile SAMPLE_DURATION)
  --interval SECONDS   sampling interval (default: profile SAMPLE_INTERVAL)
  --heap SIZE          override -Xms/-Xmx, e.g. 2G
  --jvm-arg ARG        extra JVM argument; repeatable
  --jfr / --no-jfr     force JFR on or off (default: profile JFR_ENABLED)
  --boot-timeout SEC   seconds to wait for "Done (" (default: profile BOOT_TIMEOUT)
  --run-id ID          run name (default: bench-<UTC timestamp>)
  --allow-full-profile permit profile=full on a host that reports enough RAM
  --clientless-only    acknowledge that no protocol clients are attached
  -h, --help           this text

Environment:
  QA_HOME              run root, must be outside the repository
EOF
}

EXECUTE="0"
PROFILE=""
INSTANCE=""
WORKLOAD="$QA_DIR/workloads/idle-scheduled.txt"
DURATION=""
INTERVAL=""
HEAP_OVERRIDE=""
JFR_MODE="auto"
BOOT_TIMEOUT_OVERRIDE=""
RUN_ID="bench-$(date -u +%Y%m%dT%H%M%SZ)"
ALLOW_FULL="0"
CLIENTLESS_ONLY="0"
JVM_ARGS_EXTRA=""

while [ "$#" -gt 0 ]; do
	case "$1" in
		--instance) INSTANCE="${2:?--instance needs a path}"; shift 2 ;;
		--execute) EXECUTE="1"; shift ;;
		--profile) PROFILE="${2:?--profile needs a value}"; shift 2 ;;
		--workload) WORKLOAD="${2:?--workload needs a path}"; shift 2 ;;
		--duration) DURATION="${2:?--duration needs a value}"; shift 2 ;;
		--interval) INTERVAL="${2:?--interval needs a value}"; shift 2 ;;
		--heap) HEAP_OVERRIDE="${2:?--heap needs a value}"; shift 2 ;;
		--jvm-arg) JVM_ARGS_EXTRA="${JVM_ARGS_EXTRA} ${2:?--jvm-arg needs a value}"; shift 2 ;;
		--jfr) JFR_MODE="1"; shift ;;
		--no-jfr) JFR_MODE="0"; shift ;;
		--boot-timeout) BOOT_TIMEOUT_OVERRIDE="${2:?--boot-timeout needs a value}"; shift 2 ;;
		--run-id) RUN_ID="${2:?--run-id needs a value}"; shift 2 ;;
		--allow-full-profile) ALLOW_FULL="1"; shift ;;
		--clientless-only) CLIENTLESS_ONLY="1"; shift ;;
		-h|--help) usage; exit 0 ;;
		*) usage >&2; qa_die "unknown argument: $1" ;;
	esac
done

case "$RUN_ID" in
	*[!A-Za-z0-9._-]*) qa_die "run id may only contain letters, digits, dot, underscore and dash: $RUN_ID" ;;
esac

canon() {
	readlink -m "$1"
}

REPO_CANON="$(canon "$REPO_ROOT")"
QA_HOME_CANON="$(canon "$QA_HOME")"
case "$QA_HOME_CANON/" in
	"$REPO_CANON"/*) qa_die "QA_HOME must be outside the repository: $QA_HOME_CANON" ;;
esac

INSTANCE_STATE="absent"
if [ -n "$INSTANCE" ]; then
	[ -d "$INSTANCE" ] || qa_die "instance directory does not exist: $INSTANCE"
	INSTANCE_CANON="$(canon "$INSTANCE")"
	case "$INSTANCE_CANON/" in
		"$REPO_CANON"/*) qa_die "instance path resolves inside the repository: $INSTANCE_CANON" ;;
		*/fabric/run/*) qa_die "refusing the dev-server run directory: $INSTANCE_CANON" ;;
	esac
	[ -f "$INSTANCE/instance.env" ] || qa_die "not an assembled QA instance (instance.env missing): $INSTANCE"
	[ -f "$INSTANCE/start.sh" ] || qa_die "instance has no start.sh: $INSTANCE"
	INSTANCE_STATE="present"
fi

if [ "$INSTANCE_STATE" = "present" ]; then
	. "$INSTANCE/instance.env"
	[ -z "$PROFILE" ] && PROFILE="$QA_PROFILE"
fi
[ -z "$PROFILE" ] && PROFILE="smoke"

listed_profiles() {
	(
		cd "$QA_DIR/profiles" || exit 1
		for candidate in *.conf; do
			[ -f "$candidate" ] && printf '%s\n' "${candidate%.conf}"
		done
	)
}

listed_profiles | grep -qxF -- "$PROFILE" || qa_die "unknown profile '$PROFILE'; allowlist is: $(listed_profiles | tr '\n' ' ')"
PROFILE_FILE="$QA_DIR/profiles/$PROFILE.conf"
. "$PROFILE_FILE"

DURATION="${DURATION:-$SAMPLE_DURATION}"
INTERVAL="${INTERVAL:-$SAMPLE_INTERVAL}"
BOOT_TIMEOUT="${BOOT_TIMEOUT_OVERRIDE:-$BOOT_TIMEOUT}"
HEAP="${HEAP_OVERRIDE:-${QA_HEAP:-$HEAP}}"
case "$JFR_MODE" in
	auto) JFR="$(printf '%s' "$JFR_ENABLED")" ;;
	*) JFR="$JFR_MODE" ;;
esac

[ -f "$WORKLOAD" ] || qa_die "workload file does not exist: $WORKLOAD"
HOST_RAM_GB="$(awk '/^MemTotal:/ {printf "%d", $2 / 1048576}' /proc/meminfo)"
OUT="$QA_HOME/runs/$RUN_ID"
OUT_CANON="$(canon "$OUT")"
case "$OUT_CANON/" in
	"$REPO_CANON"/*) qa_die "run output resolves inside the repository: $OUT_CANON" ;;
esac

if [ "$EXECUTE" = "0" ]; then
	cat <<EOF
{
  "mode": "dry-run",
  "started_processes": 0,
  "wrote_files": 0,
  "run_id": "$RUN_ID",
  "profile": "$PROFILE",
  "profile_purpose": "$PROFILE_PURPOSE",
  "instance": "${INSTANCE:-absent}",
  "instance_state": "$INSTANCE_STATE",
  "out": "$OUT",
  "host": {"ram_gb": $HOST_RAM_GB, "cpus": $(nproc)},
  "profile_limits": {"host_min_ram_gb": $HOST_MIN_RAM_GB, "heap": "$HEAP", "view_distance": $VIEW_DISTANCE, "simulation_distance": $SIMULATION_DISTANCE, "max_players": $MAX_PLAYERS, "client_target": $CLIENT_TARGET},
  "sampling": {"duration_s": $DURATION, "interval_s": $INTERVAL, "jfr": $JFR},
  "workload": "$WORKLOAD",
  "boot_timeout_s": $BOOT_TIMEOUT,
  "plan": [
    "lock QA_HOME/locks/bench.lock",
    "inventory processes and listening ports",
    "verify instance ports are free (no kill, no takeover)",
    "start instance/start.sh with setsid in its own process group",
    "wait for 'Done (' and fail on any mixin error",
    "python3 sample_metrics.py --mode sample (TPS from gametime deltas)",
    "python3 sample_metrics.py --mode exec --workload $WORKLOAD",
    "jcmd GC.heap_info / Thread.print / GC.class_histogram when available",
    "python3 sample_metrics.py --mode stop, then wait for the process group to exit",
    "copy logs, crash reports and a run manifest into $OUT"
  ],
  "evidence_limits": [
    "clientless: no players are connected, so no PvP, packet, mount, inventory or teleport path is exercised",
    "measures engine tick work, queue depth, worker utilization, memory and GC only",
    "requires a manager-approved instance assembled by assemble_server.sh"
  ]
}
EOF
	exit 0
fi

[ "$INSTANCE_STATE" = "present" ] || qa_die "--execute requires --instance DIR (assemble one with assemble_server.sh first)"

if [ "$PROFILE" = "full" ]; then
	[ "$ALLOW_FULL" = "1" ] || qa_die "profile full must not run on an unselected host: pass --allow-full-profile on a host with at least ${HOST_MIN_RAM_GB} GB RAM"
	[ "$HOST_RAM_GB" -ge "$HOST_MIN_RAM_GB" ] || qa_die "profile full needs ${HOST_MIN_RAM_GB} GB RAM but this host reports ${HOST_RAM_GB} GB"
fi
if [ "$CLIENT_TARGET" -gt 0 ] && [ "$CLIENTLESS_ONLY" != "1" ]; then
	qa_die "profile $PROFILE declares $CLIENT_TARGET protocol clients but no protocol client driver exists in this pass; pass --clientless-only to record a server-side baseline only"
fi

for entry in "$QA_RCON_PASSWORD" "$QA_RCON_PORT" "$QA_MC_PORT"; do
	[ -n "$entry" ] || qa_die "instance.env is incomplete; reassemble the instance"
done
if [ ! -f "$INSTANCE/$QA_SERVER_JAR" ]; then
	qa_die "instance game jar is missing: $INSTANCE/$QA_SERVER_JAR"
fi

mkdir -p "$QA_HOME/locks"
exec 9>"$QA_HOME/locks/bench.lock"
flock -n 9 || qa_die "another run holds $QA_HOME/locks/bench.lock"

[ -e "$OUT" ] && qa_die "run directory already exists, runs are never reused: $OUT"
mkdir -p "$OUT/qa" "$OUT/jcmd"
STEPS_TSV="$OUT/qa/steps.tsv"
: >"$STEPS_TSV"

step() {
	local name="$1" rc="$2" detail="${3:-}"
	printf '%s\t%s\t%s\n' "$name" "$rc" "$detail" >>"$STEPS_TSV"
}

qa_inventory "$OUT/inventory-before.txt"

for port in "$QA_MC_PORT" "$QA_RCON_PORT"; do
	if ! qa_port_free "$port"; then
		{
			printf 'port=%s\n' "$port"
			ss -ltnpH 2>/dev/null | grep -E "[:.]${port}[[:space:]]" || true
		} >"$OUT/qa/port-conflict.txt"
		qa_die "port $port is already in use; refusing to start (see $OUT/qa/port-conflict.txt). Nothing was killed."
	fi
done

SERVER_PID=""
SERVER_PGID=""
LOG="$OUT/server-console.log"
: >"$LOG"

cleanup() {
	local rc=$?
	if [ -n "$SERVER_PID" ] && [ -d "/proc/$SERVER_PID" ]; then
		qa_log "cleanup: stopping the process group started by this run (pid $SERVER_PID)"
		qa_stop_own "$SERVER_PID" "$INSTANCE" || true
		qa_wait_port_closed "$QA_MC_PORT" || true
	fi
	if [ "$rc" -ne 0 ]; then
		step "cleanup" 0 "rc=$rc"
	fi
}
trap cleanup EXIT

JFR_FILE="$OUT/recording.jfr"
export QA_HEAP_OVERRIDE="$HEAP_OVERRIDE"
export QA_JVM_ARGS="$JVM_ARGS_EXTRA"
export QA_JFR="$JFR"
export QA_JFR_FILE="$JFR_FILE"
qa_log "starting instance $INSTANCE (profile $PROFILE, heap $HEAP, jfr $JFR)"
PID_FILE="$OUT/server.pid"
setsid bash -c 'printf "%s\n" "$$" >"$1"; shift; exec "$@"' _ "$PID_FILE" "$INSTANCE/start.sh" \
	>>"$LOG" 2>&1 &
LAUNCHER_PID=$!

for _ in $(seq 1 60); do
	[ -s "$PID_FILE" ] && break
	sleep 0.5
done
[ -s "$PID_FILE" ] || {
	step "launch" 1 "pid file was not written by the launcher"
	qa_die "launcher did not write $PID_FILE; see $LOG"
}
SERVER_PID="$(head -n1 "$PID_FILE")"
step "launch" 0 "launcher_pid=$LAUNCHER_PID server_pid=$SERVER_PID"

[ -d "/proc/$SERVER_PID" ] || {
	step "process-group" 1 "server pid $SERVER_PID is gone"
	qa_die "server pid $SERVER_PID exited during startup; see $LOG"
}
SERVER_PGID="$(awk '{sub(/.*\) /, ""); print $3}' "/proc/$SERVER_PID/stat" 2>/dev/null || true)"
if [ "$SERVER_PGID" != "$SERVER_PID" ]; then
	step "process-group" 1 "pid=$SERVER_PID pgid=${SERVER_PGID:-unknown}"
	qa_die "process group isolation failed (pid $SERVER_PID, pgid ${SERVER_PGID:-unknown}); refusing to manage an unisolated process"
fi
step "process-group" 0 "pid=$SERVER_PID pgid=$SERVER_PGID"

BOOT_STATE="timeout"
DEADLINE=$((SECONDS + BOOT_TIMEOUT))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
	if [ ! -d "/proc/$SERVER_PID" ]; then
		BOOT_STATE="exited"
		break
	fi
	if grep -qF -- "Done (" "$LOG" 2>/dev/null; then
		BOOT_STATE="done"
		break
	fi
	sleep 2
done

MIXIN_HITS="$(grep -inE 'mixin apply failed|mixintransformererror|could not apply mixin|mixin apply error|failed to apply mixin' "$LOG" 2>/dev/null || true)"
DONE_LINE="$(grep -F -m1 -- "Done (" "$LOG" 2>/dev/null || true)"
MOD_LINE="$(grep -E -m1 'Loading [0-9]+ mods' "$LOG" 2>/dev/null || true)"
{
	printf 'boot_state=%s\n' "$BOOT_STATE"
	printf 'boot_timeout_s=%s\n' "$BOOT_TIMEOUT"
	printf 'done_line=%s\n' "$DONE_LINE"
	printf 'mods_line=%s\n' "$MOD_LINE"
	printf 'mixin_error_hits_begin\n%s\nmixin_error_hits_end\n' "$MIXIN_HITS"
} >"$OUT/boot-gate.txt"

if [ -n "$MIXIN_HITS" ]; then
	step "boot-gate" 1 "mixin error detected"
	qa_die "mixin error detected during startup; see $OUT/boot-gate.txt. Not measuring a broken boot."
fi
if [ "$BOOT_STATE" != "done" ]; then
	step "boot-gate" 1 "boot_state=$BOOT_STATE"
	qa_die "server did not reach 'Done (' within ${BOOT_TIMEOUT}s (state=$BOOT_STATE); see $LOG"
fi
step "boot-gate" 0 "$DONE_LINE"

if [ "$JFR" = "1" ] && ! java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -version 2>/dev/null | grep -q FlightRecorder; then
	JFR="0"
	step "jfr" 1 "JFR is not available in this JVM"
fi

if [ "$JFR" = "1" ] && command -v jcmd >/dev/null 2>&1; then
	if jcmd "$SERVER_PID" JFR.start "name=ff-$RUN_ID" "filename=$JFR_FILE" "settings=profile" "duration=$((DURATION + 120))s" >"$OUT/jcmd/jfr-start.txt" 2>&1; then
		step "jfr" 0 "recording=$JFR_FILE"
	else
		step "jfr" 1 "JFR.start failed; see $OUT/jcmd/jfr-start.txt"
	fi
fi

if python3 "$QA_DIR/sample_metrics.py" --mode sample --host 127.0.0.1 --port "$QA_RCON_PORT" \
	--password "$QA_RCON_PASSWORD" --out "$OUT/sample" --duration "$DURATION" --interval "$INTERVAL" \
	--pid "$SERVER_PID" --tag "$RUN_ID" >"$OUT/sample.json" 2>"$OUT/sample.err"; then
	SAMPLE_RC=0
else
	SAMPLE_RC=$?
fi
step "sample" "$SAMPLE_RC" "duration=${DURATION}s interval=${INTERVAL}s"
[ "$SAMPLE_RC" -eq 0 ] || qa_die "sampling failed (rc=$SAMPLE_RC); see $OUT/sample.err"

if ! [ -d "/proc/$SERVER_PID" ]; then
	step "liveness" 1 "server exited during sampling"
	qa_die "server exited during sampling; see $LOG"
fi
step "liveness" 0 "alive after sampling"

if python3 "$QA_DIR/sample_metrics.py" --mode exec --host 127.0.0.1 --port "$QA_RCON_PORT" \
	--password "$QA_RCON_PASSWORD" --workload "$WORKLOAD" >"$OUT/workload.json" 2>"$OUT/workload.err"; then
	WORKLOAD_RC=0
else
	WORKLOAD_RC=$?
fi
if [ "$WORKLOAD_RC" -eq 0 ]; then
	step "workload" 0 "$(basename "$WORKLOAD")"
elif grep -q '"status": "blocked_client"' "$OUT/workload.json" 2>/dev/null; then
	step "workload" 1 "blocked_client: workload needs protocol clients"
else
	step "workload" "$WORKLOAD_RC" "see $OUT/workload.err"
	qa_die "workload failed (rc=$WORKLOAD_RC); see $OUT/workload.err"
fi

if command -v jcmd >/dev/null 2>&1; then
	for capture in GC.heap_info Thread.print GC.class_histogram; do
		if jcmd "$SERVER_PID" "$capture" >"$OUT/jcmd/${capture}.txt" 2>&1; then
			step "jcmd:$capture" 0 ""
		else
			step "jcmd:$capture" 1 "jcmd failed"
		fi
	done
else
	step "jcmd" 1 "jcmd not available"
fi

if python3 "$QA_DIR/sample_metrics.py" --mode stop --host 127.0.0.1 --port "$QA_RCON_PORT" \
	--password "$QA_RCON_PASSWORD" >"$OUT/stop.json" 2>"$OUT/stop.err"; then
	step "stop:rcon" 0 "stop requested over RCON"
else
	step "stop:rcon" "$?" "see $OUT/stop.err"
fi

STOP_DEADLINE=$((SECONDS + STOP_TIMEOUT))
while [ "$SECONDS" -lt "$STOP_DEADLINE" ] && [ -d "/proc/$SERVER_PID" ]; do
	sleep 2
done
if [ -d "/proc/$SERVER_PID" ]; then
	step "stop:wait" 1 "process survived ${STOP_TIMEOUT}s after RCON stop"
	qa_stop_own "$SERVER_PID" "$INSTANCE"
else
	step "stop:wait" 0 "process exited cleanly"
fi
SERVER_PID=""
qa_wait_port_closed "$QA_MC_PORT" || step "stop:port" 1 "MC port $QA_MC_PORT still open"

[ -d "$INSTANCE/logs" ] && cp -a "$INSTANCE/logs" "$OUT/server-logs"
CRASH_DIR="$OUT/crash-reports"
if [ -d "$INSTANCE/crash-reports" ] && [ -n "$(ls -A "$INSTANCE/crash-reports" 2>/dev/null)" ]; then
	mkdir -p "$CRASH_DIR"
	cp -a "$INSTANCE/crash-reports/." "$CRASH_DIR/"
	step "crash-reports" 1 "crash reports present"
else
	step "crash-reports" 0 "none"
fi
sed 's/^QA_RCON_PASSWORD=.*/QA_RCON_PASSWORD=<redacted>/' "$INSTANCE/instance.env" >"$OUT/qa/instance.env"
sed 's/^rcon\.password=.*/rcon.password=<redacted>/' "$INSTANCE/server.properties" >"$OUT/qa/server.properties"
chmod 600 "$OUT/qa/instance.env" "$OUT/qa/server.properties"
cp -f "$INSTANCE/qa/instance-manifest.json" "$OUT/qa/instance-manifest.json"
cp -f "$INSTANCE/config/fabric-folia.yml" "$OUT/qa/fabric-folia.yml" 2>/dev/null || true
cp -f "$WORKLOAD" "$OUT/qa/workload.txt"

qa_inventory "$OUT/inventory-after.txt"

python3 - "$OUT" "$INSTANCE" "$PROFILE" "$PROFILE_PURPOSE" "$RUN_ID" "$DURATION" "$INTERVAL" "$JFR" "$HEAP" "$HOST_RAM_GB" "$CLIENT_TARGET" "$CLIENTLESS_ONLY" <<'PY'
import json
import os
import sys

out, instance, profile, purpose, run_id, duration, interval, jfr, heap, ram, clients, clientless = sys.argv[1:13]
env = {}
with open(os.path.join(instance, "instance.env"), encoding="utf-8") as stream:
    for line in stream:
        if "=" in line:
            key, _, value = line.rstrip("\n").partition("=")
            env[key] = value
steps = []
with open(os.path.join(out, "qa", "steps.tsv"), encoding="utf-8") as stream:
    for line in stream:
        name, rc, detail = (line.rstrip("\n").split("\t") + ["", ""])[:3]
        steps.append({"step": name, "exit_code": int(rc), "detail": detail})
sample = {}
if os.path.exists(os.path.join(out, "sample", "metrics.json")):
    with open(os.path.join(out, "sample", "metrics.json"), encoding="utf-8") as stream:
        sample = json.load(stream)
manifest = {
    "run_id": run_id,
    "profile": profile,
    "purpose": purpose,
    "instance": instance,
    "toolchain": {
        "minecraft": env.get("QA_MC_VERSION"),
        "loader": env.get("QA_LOADER_VERSION"),
        "fabric_api": env.get("QA_FABRIC_API_VERSION"),
        "fabric_folia": env.get("QA_MOD_VERSION"),
    },
    "host": {"ram_gb": int(ram), "cpus": os.cpu_count()},
    "heap": heap,
    "sampling": {"duration_s": float(duration), "interval_s": float(interval), "jfr": jfr == "1"},
    "protocol_clients": 0,
    "requested_client_target": int(clients),
    "clientless_acknowledged": clientless == "1",
    "evidence_level": "clientless-server-side",
    "steps": steps,
    "steps_all_zero": all(s["exit_code"] == 0 for s in steps),
    "measured": {
        "tps": sample.get("tps"),
        "mspt_ms_last": sample.get("mspt_ms_last"),
        "mspt_p95_worst": sample.get("mspt_p95_worst"),
        "utilization_pct": sample.get("utilization_pct"),
        "queue_peak": sample.get("queue_peak"),
        "memory": sample.get("memory"),
        "gc": sample.get("gc"),
        "per_region_tps": sample.get("per_region_tps"),
        "structural_last": sample.get("structural_last"),
        "health_last": sample.get("health_last"),
    },
    "limits": [
        "Clientless run: no player is connected, so PvP, packet, mount, inventory and teleport performance are not measured.",
        "protocol_clients is 0: no protocol client driver exists in this pass. requested_client_target records what the profile asked for.",
        "TPS is derived from time-query-gametime deltas over RCON, never from warning cadence.",
        "MSPT percentiles are the engine's own 100-sample rings, not recomputed by the harness.",
        "Per-region percentiles and server-thread deferral wait are not instrumented.",
    ],
}
with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as stream:
    json.dump(manifest, stream, indent=2)
    stream.write("\n")
print(json.dumps({"run_id": run_id, "out": out, "steps_all_zero": manifest["steps_all_zero"]}))
PY

if ! awk -F'\t' '$2 != 0 {exit 1}' "$STEPS_TSV"; then
	qa_log "run finished with non-zero steps; see $STEPS_TSV"
	exit 4
fi

qa_log "run complete: $OUT"
