You are the Fabric runtime implementation specialist for one isolated P0 fix.

Work ONLY in /tmp/ff-reconcile. Never edit the original checkout. Verify HEAD c2fbe21 and no unmerged paths.

Read tools/freebuff/reports/runtime.md (R1), defect-ledger.md, docs/folia-parity.md, EntityTeleportMixin.java, ServerPlayerTeleportMixin.java, RegionTransitions.java and all related tests.

Implement R1 only: non-player entity teleport re-dispatch currently uses registry-based isSameRegion, so the re-entered worker body still appears to be in the source region and is cancelled again. RegionTransitions.dispatch writes the IN_FLIGHT timestamp before scheduler/world validation and before enqueue; if enqueue fails, a retry inside the 50 ms window is treated as a duplicate even though no task was accepted.

Required behavior:
- A call already executing in the owning region context must allow the vanilla teleport body to run.
- A call from the wrong context must be staged to the target owner and cancelled only after dispatch acceptance is confirmed.
- A failed/rejected enqueue must never leave a duplicate marker that suppresses a later valid retry.
- Duplicate suppression must represent an accepted/in-flight dispatch outcome, not merely a recent timestamp.
- Clear ownership/dispatch tokens on success, failure, retirement and shutdown without leaking per-entity entries.
- Preserve world-qualified ownership and vanilla return semantics. If the owner cannot be resolved or dispatch is rejected, use an explicit safe fallback; never cancel without a queued body.

Add deterministic failing-first tests for:
1. source-context re-entry is allowed through;
2. wrong-context call dispatches once and only cancels after acceptance;
3. enqueue rejection does not suppress a retry;
4. duplicate concurrent dispatch is idempotent;
5. token cleanup occurs on completion and failure.

Only edit EntityTeleportMixin.java, RegionTransitions.java and directly related Fabric tests. Do not touch RegionPlayerRouting, ServerThreadDeferral, common scheduler, config, docs, QA or compatibility files. No code comments/docstrings. No commit/push. Run focused Fabric tests only, record exact command/test count/exit code in tools/freebuff/reports/runtime-implementation.md, then stop for manager review. If the safe semantics cannot be implemented without a broader ownership API, stop and report the blocker rather than adding a timestamp workaround.
