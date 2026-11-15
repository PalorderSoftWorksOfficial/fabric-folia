# Command reference

Commands are registered under /folia.

## Access and permissions

/folia and /folia version are open information commands. /folia help is also available as help text.

Other commands default to the server owner/OP permission. Each diagnostic command accepts any of:

- folia.command (umbrella grant)
- folia.command.<subcommand> (individual grant)
- fabricfolia.command.folia.<subcommand> (legacy individual grant)

The actual permission leaf is noted in the table. The Minecraft owner permission remains an alternative.

## Commands

| Command | Permission leaf | Purpose |
|---|---|---|
| /folia | Open | Engine and server information overview |
| /folia version | Open | Version/build information |
| /folia help [topic] | Open | Built-in help topics |
| /folia regions | regions | Region counts and structural statistics |
| /folia regions verbose | regions | Per-region detail |
| /folia workers | workers | Worker-pool state and utilization |
| /folia scheduler | scheduler | Region deadlines and queue diagnostics |
| /folia patches | patches | Registered patch and gate state |
| /folia health | health | Invariant checks and health reasons |
| /folia status | health | Feature gates, engine state, workers and region count |
| /folia threads | threads | Thread and ownership diagnostics |
| /folia metrics | admin | Runtime counters and timing metrics |
| /folia entities | admin | Entity-tracking diagnostics |
| /folia gpu | admin | Experimental OpenCL status |
| /folia gpu bench [boxes] | admin | CPU/GPU overlap benchmark |
| /folia compat | admin | Detected mod compatibility scan |
| /folia pin <chunkX> <chunkZ> | admin | Pin a chunk in the current world |
| /folia unpin <chunkX> <chunkZ> | admin | Remove a chunk pin |
| /folia shutdown | admin | Calls the server halt path; use vanilla /stop for routine saved shutdown |

The shutdown subcommand is not a substitute for the normal server stop workflow. The command must not be interpreted as a proof that all worlds were saved or all pending work was drained.

## Recommended incident sequence

1. /folia health
2. /folia status
3. /folia workers
4. /folia scheduler
5. /folia regions verbose
6. /folia metrics
7. Capture a JVM thread dump and the surrounding server log.

## Configuration

There is no /folia config command and no live config reload command. Edit config/fabric-folia.yml on disk and restart the server. See the [configuration reference](configuration.md).
