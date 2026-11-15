# Changelog

## Unreleased

- Move global scheduler callbacks onto the Minecraft server thread after region-worker quiescence.
- Make global scheduler submission reject after shutdown and measure delayed tasks in server ticks.
- Prevent workers from starting queued tasks after shutdown begins and expose abandoned-task accounting.
- Correct chunk-regionization patch configuration and apply scheduler/ownership-index toggles to each world scheduler.
- Make staged queue batches target one chunk and redistribute them during region splits.
- Remove the staged-round overload path that discarded simulation bodies.
- Add a standalone Maven-published API module, API tests, and API guide.
- Replace unconditional stable publication with hashed release candidates and an explicit production evidence gate.
- Mark experimental regionized gameplay and player connection staging as opt-in by default.
- Clarify project status, compatibility limits, operator commands, configuration, and contributor guidance.
