# Fabric-Folia Freebuff engineering team

The launcher starts six Freebuff sessions in tmux: manager, core scheduler, Fabric
runtime, optimization compatibility, protocol-client/benchmark driver, and
QA/performance.

The sessions share the working tree with strict file ownership. The manager
integrates work. Reports are stored in tools/freebuff/reports/.

## Start or inspect sessions

Run from the repository root:

    bash tools/freebuff/launch-team.sh

Launch selected roles:

    bash tools/freebuff/launch-team.sh manager core

The launcher skips sessions that already exist and does not kill or replace them.
Run it again after a role finishes to start the next wave. `--dry-run` prints the
planned actions without touching tmux.

Attach:

    tmux attach -t fabric-folia-manager

List sessions:

    tmux list-sessions

Capture a session:

    tmux capture-pane -pt fabric-folia-manager -S -120

## Send a handoff into a running session

    bash tools/freebuff/launch-team.sh --send core --prompt-file tools/freebuff/prompts/followups/core-2026-11-22-implementation.md
    bash tools/freebuff/launch-team.sh --resume
    bash tools/freebuff/launch-team.sh --send manager --dry-run

- `--send ROLE...` pastes a prompt into an **existing** session. It never creates,
  replaces or kills one; a missing session is an error.
- `--resume [ROLE...]` sends the role prompt to every existing team session and skips
  absent ones.
- `--prompt-file PATH` overrides the role prompt (`prompts/ROLE.md` by default).
- A send is refused unless the session's input is confirmed idle. `--force` skips that
  check; use it only when you know the session is waiting. The idle check also runs
  under `--dry-run`, so previewing a send to a busy session needs `--force` too.

Session names are `$FREEBUFF_TEAM_PREFIX-ROLE` (default `fabric-folia-ROLE`) and are
matched **exactly**. The canonical roles are:

    fabric-folia-manager
    fabric-folia-core
    fabric-folia-runtime
    fabric-folia-compatibility
    fabric-folia-protocol
    fabric-folia-qa

Set the prefix to match the running team; tmux's own target prefix matching is
deliberately not used, so a send cannot land in a lookalike session. Transient review
sessions may carry a suffix (for example `fabric-folia-runtime-review-r1`).

Do not run multiple full Gradle builds concurrently. Do not commit, push, publish or
deploy without explicit approval. Preserve pre-existing dirty work.
