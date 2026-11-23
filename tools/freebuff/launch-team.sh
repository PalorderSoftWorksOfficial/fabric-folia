#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TEAM_DIR="$ROOT/tools/freebuff"
PREFIX="${FREEBUFF_TEAM_PREFIX:-fabric-folia}"
READY_PATTERN="${FREEBUFF_READY_PATTERN:-Enter a coding task}"
ROLES=(manager core runtime compatibility protocol qa)

MODE=start
DRY_RUN=0
FORCE=0
PROMPT_OVERRIDE=""
SELECTED=()

usage() {
  cat <<'EOF'
Usage:
  launch-team.sh [ROLE...] [--prompt-file PATH] [--dry-run]
      Start the named roles (default: all six), skipping sessions that exist.

  launch-team.sh --send ROLE [ROLE...] [--prompt-file PATH] [--dry-run] [--force]
      Paste a prompt into an existing session. Never creates, replaces or kills one.

  launch-team.sh --resume [ROLE...] [--prompt-file PATH] [--dry-run] [--force]
      Paste the role prompt into every existing team session, skipping absent ones.

Options:
  --prompt-file PATH   Prompt to send instead of tools/freebuff/prompts/ROLE.md
  --dry-run            Print the planned actions and do not touch tmux
  --force              Send even when the session's input is not confirmed idle
  -h, --help           Show this help

Roles: manager core runtime compatibility protocol qa
Session names are $FREEBUFF_TEAM_PREFIX-ROLE (default fabric-folia-ROLE) and are
matched exactly; set the prefix to match the running team.
EOF
}

session_name() {
  printf '%s-%s' "$PREFIX" "$1"
}

prompt_for() {
  if [[ -n "$PROMPT_OVERRIDE" ]]; then
    printf '%s' "$PROMPT_OVERRIDE"
  else
    printf '%s/prompts/%s.md' "$TEAM_DIR" "$1"
  fi
}

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '%s is required\n' "$1"
    return 1
  fi
}

session_idle() {
  tmux capture-pane -pt "=$1:0.0" -S -40 2>/dev/null | grep -qE "$READY_PATTERN"
}

send_prompt() {
  local role="$1"
  local session prompt
  session="$(session_name "$role")"
  prompt="$(prompt_for "$role")"
  if [[ ! -f "$prompt" ]]; then
    printf 'Missing prompt: %s\n' "$prompt"
    return 1
  fi
  if ! tmux has-session -t "=$session" 2>/dev/null; then
    printf 'Not running: %s (send mode never starts a session)\n' "$session"
    return 1
  fi
  if [[ "$FORCE" -ne 1 ]] && ! session_idle "$session"; then
    printf 'Refusing to send: %s is not confirmed idle (re-run with --force)\n' "$session"
    return 1
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf 'Would send %s -> %s\n' "$prompt" "$session"
    return 0
  fi
  tmux load-buffer -b "send-$session" "$prompt"
  tmux paste-buffer -b "send-$session" -t "=$session:0.0" -d
  sleep 1
  tmux send-keys -t "=$session:0.0" Enter
  printf 'Sent %s -> %s\n' "$prompt" "$session"
}

start_agent() {
  local role="$1"
  local session prompt ready attempt
  session="$(session_name "$role")"
  prompt="$(prompt_for "$role")"
  if [[ ! -f "$prompt" ]]; then
    printf 'Missing prompt: %s\n' "$prompt"
    return 1
  fi
  if tmux has-session -t "=$session" 2>/dev/null; then
    printf 'Already running: %s\n' "$session"
    return 0
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf 'Would start %s with %s\n' "$session" "$prompt"
    return 0
  fi
  tmux new-session -d -s "$session" -c "$ROOT" "exec freebuff --cwd '$ROOT' --trust-agents"
  ready=0
  for attempt in $(seq 1 45); do
    if tmux capture-pane -pt "=$session" -S -60 2>/dev/null | grep -qE "$READY_PATTERN"; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "$ready" -ne 1 ]]; then
    printf 'Freebuff did not become ready: %s\n' "$session"
    return 1
  fi
  tmux load-buffer -b "$session" "$prompt"
  tmux paste-buffer -b "$session" -t "=$session:0.0" -d
  sleep 1
  tmux send-keys -t "=$session:0.0" Enter
  printf 'Started %s\n' "$session"
}

while (($#)); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --force)
      FORCE=1
      shift
      ;;
    --prompt-file)
      if (($# < 2)); then
        printf 'Missing value for --prompt-file\n'
        exit 2
      fi
      PROMPT_OVERRIDE="$2"
      shift 2
      ;;
    --send)
      MODE=send
      shift
      ;;
    --resume)
      MODE=resume
      shift
      ;;
    --)
      shift
      while (($#)); do
        SELECTED+=("$1")
        shift
      done
      ;;
    -*)
      printf 'Unknown option: %s\n' "$1"
      usage
      exit 2
      ;;
    *)
      SELECTED+=("$1")
      shift
      ;;
  esac
done

case "$MODE" in
  start)
    require_tool tmux || exit 1
    require_tool freebuff || exit 1
    mkdir -p "$TEAM_DIR/reports"
    if [[ ${#SELECTED[@]} -eq 0 ]]; then
      SELECTED=("${ROLES[@]}")
    fi
    status=0
    for role in "${SELECTED[@]}"; do
      start_agent "$role" || status=1
    done
    if [[ "$DRY_RUN" -eq 0 ]]; then
      printf '\n'
      tmux list-sessions
    fi
    exit "$status"
    ;;
  send)
    require_tool tmux || exit 1
    if [[ ${#SELECTED[@]} -eq 0 ]]; then
      printf 'send mode needs at least one role: --send ROLE [ROLE...]\n'
      exit 2
    fi
    status=0
    for role in "${SELECTED[@]}"; do
      send_prompt "$role" || status=1
    done
    exit "$status"
    ;;
  resume)
    require_tool tmux || exit 1
    if [[ ${#SELECTED[@]} -eq 0 ]]; then
      SELECTED=("${ROLES[@]}")
    fi
    status=0
    for role in "${SELECTED[@]}"; do
      if tmux has-session -t "=$(session_name "$role")" 2>/dev/null; then
        send_prompt "$role" || status=1
      else
        printf 'Skipping (not running): %s\n' "$(session_name "$role")"
      fi
    done
    exit "$status"
    ;;
esac
