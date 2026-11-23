#!/usr/bin/env bash
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$QA_DIR/qa_lib.sh"

usage() {
	cat <<'EOF'
assemble_server.sh - build an isolated, disposable QA server instance under QA_HOME.

Nothing is booted and nothing in the repository is written. Refuses to reuse an
existing run directory, refuses paths inside the repository, and never touches
fabric/run or a production world.

Usage:
  assemble_server.sh [options]

Required inputs (either an explicit path or an exactly pinned cache artifact):
  --server-bundle PATH   Mojang 26.2 server bundle jar (bundler layout required)
  --loader PATH          fabric-loader-<loader_version>.jar
  --fabric-api PATH      fabric-api-<fabric_api_version>.jar

Optional inputs:
  --mod-jar PATH         Fabric-Folia jar (default: fabric/build/libs/fabric-folia-<mod_version>.jar)
  --mod PATH             additional declared mod jar; repeatable
  --config PATH          fabric-folia.yml to install (default: configs/baseline.yml)
  --run-id ID            instance name (default: run-<UTC timestamp>)
  --profile NAME         smoke | full (default: smoke)
  --seed N               level seed recorded in server.properties (default: 4242424242)
  --max-tick-time MS     vanilla watchdog tick ceiling (default: 60000, must be > 0)
  --resolve-only         validate and print the plan, write nothing
  -h, --help             this text

Environment:
  QA_HOME                instance root, must be outside the repository
  QA_PORT_BASE           first candidate MC port (default 26800)

Exit codes: 0 ok, 1 on any usage, input or verification failure.
EOF
}

RUN_ID="run-$(date -u +%Y%m%dT%H%M%SZ)"
PROFILE="smoke"
SEED="4242424242"
MAX_TICK_TIME="60000"
RESOLVE_ONLY="0"
SERVER_BUNDLE_IN=""
LOADER_IN=""
FABRIC_API_IN=""
MOD_JAR_IN=""
CONFIG_IN="$QA_DIR/configs/baseline.yml"
DECLARED_MODS=()

while [ "$#" -gt 0 ]; do
	case "$1" in
		--server-bundle) SERVER_BUNDLE_IN="${2:?--server-bundle needs a path}"; shift 2 ;;
		--loader) LOADER_IN="${2:?--loader needs a path}"; shift 2 ;;
		--fabric-api) FABRIC_API_IN="${2:?--fabric-api needs a path}"; shift 2 ;;
		--mod-jar) MOD_JAR_IN="${2:?--mod-jar needs a path}"; shift 2 ;;
		--mod) DECLARED_MODS+=("${2:?--mod needs a path}"); shift 2 ;;
		--config) CONFIG_IN="${2:?--config needs a path}"; shift 2 ;;
		--run-id) RUN_ID="${2:?--run-id needs a value}"; shift 2 ;;
		--profile) PROFILE="${2:?--profile needs a value}"; shift 2 ;;
		--seed) SEED="${2:?--seed needs a value}"; shift 2 ;;
		--max-tick-time) MAX_TICK_TIME="${2:?--max-tick-time needs a value}"; shift 2 ;;
		--resolve-only) RESOLVE_ONLY="1"; shift ;;
		-h|--help) usage; exit 0 ;;
		*) usage >&2; qa_die "unknown argument: $1" ;;
	esac
done

case "$RUN_ID" in
	*[!A-Za-z0-9._-]*) qa_die "run id may only contain letters, digits, dot, underscore and dash: $RUN_ID" ;;
esac

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

case "$MAX_TICK_TIME" in
	""|*[!0-9]*) qa_die "max tick time must be a positive integer of milliseconds: $MAX_TICK_TIME" ;;
	esac
[ "$MAX_TICK_TIME" -gt 0 ] || qa_die "max tick time must be > 0 so a stalled tick is detected: $MAX_TICK_TIME"

canon() {
	readlink -m "$1"
}

REPO_CANON="$(canon "$REPO_ROOT")"
QA_HOME_CANON="$(canon "$QA_HOME")"
case "$QA_HOME_CANON/" in
	"$REPO_CANON"/*) qa_die "QA_HOME must be outside the repository: $QA_HOME_CANON" ;;
esac

INSTANCE="$QA_HOME/instances/$RUN_ID"
INSTANCE_CANON="$(canon "$INSTANCE")"
case "$INSTANCE_CANON/" in
	"$REPO_CANON"/*) qa_die "instance path resolves inside the repository: $INSTANCE_CANON" ;;
	*/fabric/run/*) qa_die "refusing to use the dev-server run directory: $INSTANCE_CANON" ;;
esac
if [ -d "$INSTANCE" ] && [ "$RESOLVE_ONLY" != "1" ]; then
	qa_die "instance already exists, runs are never reused: $INSTANCE"
fi

resolve_input() {
	local label="$1" explicit="$2" cached="$3" hint="$4"
	if [ -n "$explicit" ]; then
		[ -f "$explicit" ] || qa_die "$label: no such file: $explicit"
		printf '%s\n' "$explicit"
		return 0
	fi
	if [ -n "$cached" ] && [ -f "$cached" ]; then
		printf '%s\n' "$cached"
		return 0
	fi
	qa_die "$label: not supplied and no exact pinned artifact in the cache. Pass --$hint PATH. No download is attempted. Expected pinned location: ${cached:-<unknown>}"
}

SERVER_BUNDLE_PATH="$(resolve_input "server bundle" "$SERVER_BUNDLE_IN" "$SERVER_BUNDLE" "server-bundle")"
LOADER_PATH="$(resolve_input "fabric loader" "$LOADER_IN" "$(qa_find_loader)" "loader")"
FABRIC_API_PATH="$(resolve_input "fabric api" "$FABRIC_API_IN" "$(qa_find_fabric_api)" "fabric-api")"
MOD_JAR_PATH="$(resolve_input "fabric-folia jar" "$MOD_JAR_IN" "$MOD_JAR" "mod-jar")"
[ -f "$CONFIG_IN" ] || qa_die "config: no such file: $CONFIG_IN"

for declared in "${DECLARED_MODS[@]+"${DECLARED_MODS[@]}"}"; do
	[ -f "$declared" ] || qa_die "declared mod: no such file: $declared"
done

python3 - "$SERVER_BUNDLE_PATH" "$MINECRAFT_VERSION" <<'PY'
import sys
import zipfile

bundle, version = sys.argv[1], sys.argv[2]
try:
    archive = zipfile.ZipFile(bundle)
except zipfile.BadZipFile:
    sys.exit(f"{bundle}: not a zip archive; a Mojang server bundle jar is required")
names = set(archive.namelist())
missing = [n for n in ("META-INF/versions.list", "META-INF/libraries.list") if n not in names]
if missing:
    sys.exit(f"{bundle}: not a Mojang server bundle (missing {', '.join(missing)})")
rows = [line.split("\t") for line in archive.read("META-INF/versions.list").decode("utf-8").splitlines() if line.strip()]
if not any(len(r) == 3 and r[1] == version for r in rows):
    sys.exit(f"{bundle}: bundle does not contain Minecraft {version}; found {[r[1] for r in rows if len(r) == 3]}")
PY

MC_PORT=""
RCON_PORT=""
if ports="$(qa_pick_ports 2>/dev/null)"; then
	MC_PORT="${ports%% *}"
	RCON_PORT="${ports##* }"
elif [ "$RESOLVE_ONLY" = "1" ]; then
	MC_PORT="unresolved"
	RCON_PORT="unresolved"
else
	qa_die "no free MC/RCON port pair in ${PORT_BASE}..$((PORT_BASE + 400))"
fi

RCON_PASSWORD="qa$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
HOST_RAM_GB="$(awk '/^MemTotal:/ {printf "%d", $2 / 1048576}' /proc/meminfo)"
GIT_HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD)"
GIT_BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
GIT_DIRTY="$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal | wc -l | tr -d ' ')"
JAVA_VERSION="$(java -version 2>&1 | head -n1)"
PYTHON_VERSION="$(python3 --version 2>&1)"
SETSID_VERSION="$(setsid --version 2>&1 | head -n1)"

if [ "$RESOLVE_ONLY" = "1" ]; then
	cat <<EOF
{
  "mode": "resolve-only",
  "run_id": "$RUN_ID",
  "profile": "$PROFILE",
  "purpose": "$PROFILE_PURPOSE",
  "instance_dir": "$INSTANCE",
  "qa_home": "$QA_HOME",
  "host": {"ram_gb": $HOST_RAM_GB, "cpus": $(nproc), "kernel": "$(uname -srm)"},
  "git": {"head": "$GIT_HEAD", "branch": "$GIT_BRANCH", "dirty_entries": $GIT_DIRTY},
  "toolchain": {"minecraft": "$MINECRAFT_VERSION", "loader": "$LOADER_VERSION", "fabric_api": "$FABRIC_API_VERSION", "mod": "$MOD_VERSION", "java": "$JAVA_VERSION"},
  "artifacts": [
    {"role": "server-bundle", "path": "$SERVER_BUNDLE_PATH", "sha256": "$(qa_sha256 "$SERVER_BUNDLE_PATH")"},
    {"role": "loader", "path": "$LOADER_PATH", "sha256": "$(qa_sha256 "$LOADER_PATH")"},
    {"role": "fabric-api", "path": "$FABRIC_API_PATH", "sha256": "$(qa_sha256 "$FABRIC_API_PATH")"},
    {"role": "fabric-folia", "path": "$MOD_JAR_PATH", "sha256": "$(qa_sha256 "$MOD_JAR_PATH")"}
  ],
  "ports": {"mc": "$MC_PORT", "rcon": "$RCON_PORT"},
  "seed": "$SEED",
  "max_tick_time_ms": $MAX_TICK_TIME,
  "writes": "none (resolve-only)"
}
EOF
	exit 0
fi

if [ "$HOST_RAM_GB" -lt "$HOST_MIN_RAM_GB" ]; then
	qa_die "profile $PROFILE declares HOST_MIN_RAM_GB=$HOST_MIN_RAM_GB but this host reports ${HOST_RAM_GB} GB; use --profile smoke or assemble on the benchmark host"
fi

mkdir -p "$INSTANCE/loader" "$INSTANCE/launcher" "$INSTANCE/mods" "$INSTANCE/config" "$INSTANCE/libraries" "$INSTANCE/versions" "$INSTANCE/qa"
ARTIFACTS_TSV="$INSTANCE/qa/artifacts.tsv"
: >"$ARTIFACTS_TSV"

record_artifact() {
	local role="$1" source="$2" target="$3"
	printf '%s\t%s\t%s\t%s\t%s\n' "$role" "$source" "$(qa_sha256 "$source")" "$(stat -c %s "$source")" "$target" >>"$ARTIFACTS_TSV"
}

record_artifact "server-bundle" "$SERVER_BUNDLE_PATH" "external (not copied: the bundle is extracted instead)"

python3 - "$SERVER_BUNDLE_PATH" "$MINECRAFT_VERSION" "$INSTANCE" <<'PY'
import hashlib
import json
import os
import sys
import zipfile

bundle, version, instance = sys.argv[1], sys.argv[2], sys.argv[3]
archive = zipfile.ZipFile(bundle)
verified = []


def entries(prefix):
    rows = []
    for line in archive.read(prefix + ".list").decode("utf-8").splitlines():
        if not line.strip():
            continue
        digest, coord, path = line.split("\t")
        rows.append((digest, coord, path))
    return rows


root = os.path.realpath(instance) + os.sep


def place(kind, rel, digest, coord):
    source = f"META-INF/{kind}/{rel}"
    normalized = os.path.normpath(rel)
    if os.path.isabs(rel) or normalized.startswith(".." + os.sep) or normalized == "..":
        sys.exit(f"{source}: bundle entry path escapes the instance directory: {rel}")
    target = os.path.realpath(os.path.join(instance, kind, normalized))
    if not target.startswith(root):
        sys.exit(f"{source}: resolved target {target} is outside {root}")
    data = archive.read(source)
    got = hashlib.sha256(data).hexdigest()
    if got != digest:
        sys.exit(f"{source}: sha256 mismatch, bundle entry is corrupt or was repacked (expected {digest}, got {got})")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "wb") as stream:
        stream.write(data)
    verified.append({"kind": kind, "path": rel, "coordinate": coord, "sha256": got, "bytes": len(data)})
    return rel


server_rel = None
for digest, ident, rel in entries("META-INF/versions"):
    if ident == version:
        server_rel = place("versions", rel, digest, ident)
if server_rel is None:
    sys.exit(f"bundle has no version entry for Minecraft {version}")

libraries = [place("libraries", rel, digest, coord) for digest, coord, rel in entries("META-INF/libraries")]
with open(os.path.join(instance, "libraries.txt"), "w", encoding="utf-8") as stream:
    for rel in libraries:
        stream.write(rel + "\n")
with open(os.path.join(instance, "versions.txt"), "w", encoding="utf-8") as stream:
    stream.write(server_rel + "\n")
with open(os.path.join(instance, "qa", "bundle-extract.json"), "w", encoding="utf-8") as stream:
    json.dump({"bundle": bundle, "minecraft": version, "verified_entries": verified}, stream, indent=2)
    stream.write("\n")
print(server_rel)
PY

SERVER_JAR_REL="versions/$(cat "$INSTANCE/versions.txt")"
[ "$(wc -l <"$INSTANCE/libraries.txt")" -ge 20 ] || qa_die "bundle extraction produced too few libraries; refusing to continue"

cp -f "$LOADER_PATH" "$INSTANCE/loader/fabric-loader-$LOADER_VERSION.jar"
record_artifact "loader" "$LOADER_PATH" "loader/fabric-loader-$LOADER_VERSION.jar"
cp -f "$FABRIC_API_PATH" "$INSTANCE/mods/fabric-api-$FABRIC_API_VERSION.jar"
record_artifact "fabric-api" "$FABRIC_API_PATH" "mods/fabric-api-$FABRIC_API_VERSION.jar"
cp -f "$MOD_JAR_PATH" "$INSTANCE/mods/fabric-folia-$MOD_VERSION.jar"
record_artifact "fabric-folia" "$MOD_JAR_PATH" "mods/fabric-folia-$MOD_VERSION.jar"
cp -f "$CONFIG_IN" "$INSTANCE/config/fabric-folia.yml"
record_artifact "fabric-folia-config" "$CONFIG_IN" "config/fabric-folia.yml"

mod_index=0
for declared in "${DECLARED_MODS[@]+"${DECLARED_MODS[@]}"}"; do
	mod_index=$((mod_index + 1))
	mod_name="$(basename "$declared")"
	cp -f "$declared" "$INSTANCE/mods/$mod_name"
	record_artifact "declared-mod-$mod_index" "$declared" "mods/$mod_name"
done

printf 'launch.mainClass=net.fabricmc.loader.impl.launch.knot.KnotServer\n' >"$INSTANCE/launcher/fabric-server-launch.properties"

cat >"$INSTANCE/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$MC_PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
enable-query=false
enable-status=true
pause-when-empty-seconds=0
online-mode=false
enforce-secure-profile=false
level-name=qa-world
level-seed=$SEED
level-type=minecraft\:normal
gamemode=survival
difficulty=normal
spawn-protection=0
max-players=$MAX_PLAYERS
view-distance=$VIEW_DISTANCE
simulation-distance=$SIMULATION_DISTANCE
max-tick-time=$MAX_TICK_TIME
sync-chunk-writes=true
white-list=false
motd=fabric-folia-qa-$RUN_ID
EOF

grep -qx "pause-when-empty-seconds=0" "$INSTANCE/server.properties" || qa_die "server.properties is missing pause-when-empty-seconds=0"
grep -qx "enable-rcon=true" "$INSTANCE/server.properties" || qa_die "server.properties is missing enable-rcon=true"
grep -qx "max-tick-time=$MAX_TICK_TIME" "$INSTANCE/server.properties" || qa_die "server.properties is missing max-tick-time=$MAX_TICK_TIME"
chmod 600 "$INSTANCE/server.properties"
chmod 700 "$INSTANCE"

cat >"$INSTANCE/instance.env" <<EOF
QA_RUN_ID=$RUN_ID
QA_PROFILE=$PROFILE
QA_PROFILE_PURPOSE=$PROFILE_PURPOSE
QA_MC_PORT=$MC_PORT
QA_RCON_PORT=$RCON_PORT
QA_RCON_PASSWORD=$RCON_PASSWORD
QA_INSTANCE_DIR=$INSTANCE
QA_MC_VERSION=$MINECRAFT_VERSION
QA_LOADER_VERSION=$LOADER_VERSION
QA_FABRIC_API_VERSION=$FABRIC_API_VERSION
QA_MOD_VERSION=$MOD_VERSION
QA_HEAP=$HEAP
QA_SERVER_JAR=$SERVER_JAR_REL
QA_SEED=$SEED
EOF
chmod 600 "$INSTANCE/instance.env"

cat >"$INSTANCE/start.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
INSTANCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$INSTANCE_DIR/instance.env"

HEAP="${QA_HEAP_OVERRIDE:-$QA_HEAP}"
JAVA_BIN="${QA_JAVA_BIN:-java}"
FLAGS=(
	"-Xms$HEAP"
	"-Xmx$HEAP"
	"-XX:+UseG1GC"
	"-XX:+AlwaysPreTouch"
	"-XX:+PerfDisableSharedMem"
	"-XX:+DisableExplicitGC"
	"-XX:MaxGCPauseMillis=200"
	"-Dterminal.jline=false"
	"-Dterminal.ansi=false"
	"-Dfabric.side=server"
	"-Dfabric.gameVersion=$QA_MC_VERSION"
	"-Dfabric.gameJarPath=$INSTANCE_DIR/$QA_SERVER_JAR"
)
if [ -n "${QA_JVM_ARGS:-}" ]; then
	IFS=' ' read -r -a EXTRA_ARGS <<<"$QA_JVM_ARGS"
	FLAGS+=("${EXTRA_ARGS[@]}")
fi
if [ "${QA_JFR:-0}" = "1" ]; then
	[ -n "${QA_JFR_FILE:-}" ] || { printf '%s\n' "QA_JFR=1 requires QA_JFR_FILE" >&2; exit 2; }
	FLAGS+=("-XX:StartFlightRecording=filename=$QA_JFR_FILE,settings=profile,dumponexit=true")
fi
CLASSPATH="$INSTANCE_DIR/$QA_SERVER_JAR"
while IFS= read -r LIB; do
	[ -n "$LIB" ] || continue
	CLASSPATH="$CLASSPATH:$INSTANCE_DIR/libraries/$LIB"
done <"$INSTANCE_DIR/libraries.txt"
CLASSPATH="$CLASSPATH:$INSTANCE_DIR/loader/fabric-loader-$QA_LOADER_VERSION.jar:$INSTANCE_DIR/launcher"
cd "$INSTANCE_DIR"
exec "$JAVA_BIN" "${FLAGS[@]}" -cp "$CLASSPATH" net.fabricmc.loader.impl.launch.server.FabricServerLauncher nogui
EOF
chmod 755 "$INSTANCE/start.sh"

printf 'run_id=%s\nprofile=%s\ninstance_dir=%s\nmc_port=%s\nrcon_port=%s\nseed=%s\n' \
	"$RUN_ID" "$PROFILE" "$INSTANCE" "$MC_PORT" "$RCON_PORT" "$SEED" >"$INSTANCE/qa/summary.txt"

python3 - "$INSTANCE" "$ARTIFACTS_TSV" "$QA_DIR" <<'PY'
import hashlib
import json
import os
import sys

instance, tsv, qa_dir = sys.argv[1], sys.argv[2], sys.argv[3]
env = {}
with open(os.path.join(instance, "instance.env"), encoding="utf-8") as stream:
    for line in stream:
        if "=" in line:
            key, _, value = line.rstrip("\n").partition("=")
            env[key] = value
artifacts = []
with open(tsv, encoding="utf-8") as stream:
    for line in stream:
        role, source, digest, size, target = line.rstrip("\n").split("\t")
        artifacts.append({"role": role, "source": source, "sha256": digest, "bytes": int(size), "target": target})
properties = {}
with open(os.path.join(instance, "server.properties"), encoding="utf-8") as stream:
    for line in stream:
        if "=" in line and not line.startswith("#"):
            key, _, value = line.rstrip("\n").partition("=")
            properties[key] = value
password_digest = hashlib.sha256(env["QA_RCON_PASSWORD"].encode("utf-8")).hexdigest()
properties.pop("rcon.password", None)
manifest = {
    "run_id": env["QA_RUN_ID"],
    "profile": env["QA_PROFILE"],
    "purpose": env["QA_PROFILE_PURPOSE"],
    "instance_dir": instance,
    "seed": env["QA_SEED"],
    "toolchain": {
        "minecraft": env["QA_MC_VERSION"],
        "loader": env["QA_LOADER_VERSION"],
        "fabric_api": env["QA_FABRIC_API_VERSION"],
        "fabric_folia": env["QA_MOD_VERSION"],
    },
    "ports": {"mc": int(env["QA_MC_PORT"]), "rcon": int(env["QA_RCON_PORT"])},
    "rcon_password_sha256": password_digest,
    "heap": env["QA_HEAP"],
    "server_properties": properties,
    "artifacts": artifacts,
    "launch": {
        "main_class": "net.fabricmc.loader.impl.launch.server.FabricServerLauncher",
        "launcher_main": "net.fabricmc.loader.impl.launch.knot.KnotServer",
        "game_jar": env["QA_SERVER_JAR"],
        "classpath_entries": 1 + sum(1 for _ in open(os.path.join(instance, "libraries.txt"), encoding="utf-8")) + 2,
    },
    "isolation": {
        "qa_home": os.environ.get("QA_HOME", ""),
        "inside_repository": False,
        "dev_run_directory_used": False,
        "level_name": properties.get("level-name"),
    },
    "limits": [
        "Instance assembly only: no server was started by this script.",
        "Clientless diagnostic capability: no protocol clients exist in this harness.",
    ],
}
with open(os.path.join(instance, "qa", "instance-manifest.json"), "w", encoding="utf-8") as stream:
    json.dump(manifest, stream, indent=2)
    stream.write("\n")
print(json.dumps(manifest, indent=2))
PY

qa_log "instance assembled: $INSTANCE"
qa_log "ports: mc=$MC_PORT rcon=$RCON_PORT"
qa_log "start with: setsid $INSTANCE/start.sh"
