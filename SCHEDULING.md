# Scheduling model

## Region scheduler

The scheduler tracks live regions and dispatches ready regions to a bounded worker pool. Region deadlines use a nominal 50 ms interval. Work scheduled for a position should resolve the current owner at dispatch time; do not retain region handles after a split or merge.

Position-aware queue entries carry a packed chunk position. Region splits use that position to redistribute pending entries to the correct post-split queue. Position-less tasks cannot be redistributed by location and should be reserved for work whose semantics are genuinely independent of region ownership.

## Staged gameplay

Vanilla chooses which tick bodies are due and Fabric-Folia captures selected bodies. At flush, the stage hub groups work by region and chunk and creates bounded batches. Each staged batch targets one chunk, so split migration can move it with that chunk's current owner.

If a region is not keeping up, pending work is retained and queue backpressure limits further staging. The former rule that skipped whole region rounds has been removed because it discarded gameplay simulation. Under high load this may increase queue depth and server-thread backpressure; performance and no-loss behavior must be validated together.

## Global scheduler

Global tasks are drained on the Minecraft server thread during the start-of-tick coordination phase, after region workers have quiesced. Delays and repeat periods are measured in server ticks. This is not a dedicated global-region worker.

## Entity and async scheduling

Entity scheduling is intended for work that must follow an entity through ownership changes. Async scheduling is limited to independent computation and IO. All task submissions must have explicit rejection, cancellation, and shutdown behavior.
