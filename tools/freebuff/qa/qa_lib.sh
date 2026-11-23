#!/usr/bin/env bash
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../../.." && pwd)"
QA_HOME="${QA_HOME:-$HOME/.cache/fabric-folia-qa}"
PORT_BASE="${QA_PORT_BASE:-26800}"

prop() {
  sed -n "s/^$1=//p" "$REPO_ROOT/gradle.properties" | head -n1
}

MOD_VERSION="$(prop mod_version)"
MINECRAFT_VERSION="$(prop minecraft_version)"
LOADER_VERSION="$(prop loader_version)"
FABRIC_API_VERSION="$(prop fabric_api_version)"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}"
MOD_JAR="$REPO_ROOT/fabric/build/libs/fabric-folia-${MOD_VERSION}.jar"
API_JAR="$REPO_ROOT/api/build/libs/fabric-folia-api-${MOD_VERSION}.jar"
SERVER_BUNDLE="$GRADLE_CACHE/caches/fabric-loom/${MINECRAFT_VERSION}/minecraft-server.jar"

qa_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

qa_log() {
  printf '%s %s\n' "$(qa_now)" "$*" >&2
}

qa_die() {
  qa_log "ERROR: $*"
  exit 1
}

qa_require_cmd() {
  command -v "$1" >/dev/null 2>&1 || qa_die "required command missing: $1"
}

qa_sha256() {
  sha256sum "$1" | cut -d' ' -f1
}

qa_outside_repo() {
  local path
  path="$(cd "$(dirname "$1")" 2>/dev/null && pwd || true)"
  case "$path" in
    "$REPO_ROOT"*) return 1 ;;
  esac
  return 0
}

qa_port_free() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$" && return 1
    return 0
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$" && return 1
    return 0
  fi
  qa_die "neither ss nor netstat is available for the port inventory"
}

qa_pick_ports() {
  local candidate="$PORT_BASE"
  local limit=$((candidate + 400))
  while [ "$candidate" -lt "$limit" ]; do
    if qa_port_free "$candidate" && qa_port_free "$((candidate + 1))" && qa_port_free "$((candidate + 2))"; then
      printf '%s %s\n' "$candidate" "$((candidate + 1))"
      return 0
    fi
    candidate=$((candidate + 10))
  done
  return 1
}

qa_inventory() {
  local out="$1"
  mkdir -p "$(dirname "$out")"
  {
    printf 'generated=%s\n' "$(qa_now)"
    printf 'kernel=%s\n' "$(uname -srm)"
    printf 'cpus=%s\n' "$(nproc)"
    grep -E '^(MemTotal|MemAvailable|SwapTotal)' /proc/meminfo
    printf 'jdk=%s\n' "$(java -version 2>&1 | head -n1)"
    printf 'wrapper=%s\n' "$(sed -n 's/^distributionUrl=//p' "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties")"
    printf 'git_head=%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
    printf 'git_branch=%s\n' "$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
    printf 'git_dirty_entries=%s\n' "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal | wc -l | tr -d ' ')"
    printf 'git_behind_origin=%s\n' "$(git -C "$REPO_ROOT" rev-list --count "HEAD..@{upstream}" 2>/dev/null || printf 'unknown')"
    printf 'java_processes_begin\n'
    ps -eo pid,ppid,etimes,rss,args | grep -E '[j]ava|[g]radle' | sed 's/^/  /' || true
    printf 'java_processes_end\n'
    printf 'listening_tcp_begin\n'
    if command -v ss >/dev/null 2>&1; then
      ss -ltnpH 2>/dev/null | sed 's/^/  /' || true
    fi
    printf 'listening_tcp_end\n'
    printf 'qa_port_range=%s-%s\n' "$PORT_BASE" "$((PORT_BASE + 400))"
  } >"$out"
}

qa_stop_own() {
  local pid="$1"
  local instance="$2"
  local i
  [ -n "${pid:-}" ] || return 0
  [ -d "/proc/$pid" ] || return 0
  if ! tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -qF "$instance"; then
    qa_log "refusing to signal pid $pid: command line does not reference $instance"
    return 0
  fi
  qa_log "stopping pid $pid (process group) for $instance"
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  for i in $(seq 1 45); do
    [ -d "/proc/$pid" ] || return 0
    sleep 1
  done
  qa_log "pid $pid survived SIGTERM; sending SIGKILL to its own process group"
  kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
}

qa_wait_port_closed() {
  local port="$1"
  local deadline=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$deadline" ]; do
    qa_port_free "$port" && return 0
    sleep 1
  done
  return 1
}

qa_find_loader() {
  find "$GRADLE_CACHE/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/$LOADER_VERSION" \
    -name "fabric-loader-$LOADER_VERSION.jar" 2>/dev/null | head -n1
}

qa_find_fabric_api() {
  find "$GRADLE_CACHE/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/$FABRIC_API_VERSION" \
    -name "fabric-api-*.jar" 2>/dev/null | head -n1
}

qa_manifest_json() {
  local out="$1"
  shift
  python3 - "$out" "$@" <<'PY'
import json
import sys

path = sys.argv[1]
pairs = {}
for item in sys.argv[2:]:
    key, _, value = item.partition("=")
    pairs[key] = value
with open(path, "w", encoding="utf-8") as stream:
    json.dump(pairs, stream, indent=2)
    stream.write("\n")
PY
}
