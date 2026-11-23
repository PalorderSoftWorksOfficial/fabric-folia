You are the R1 teleport patch reviewer and implementation finisher.

Work only in /tmp/ff-reconcile. Preserve the existing uncommitted R1 diff. Verify HEAD c2fbe21 and no unmerged paths.

Read tools/freebuff/reports/runtime.md, defect-ledger.md, the current R1 diff, EntityTeleportMixin.java, RegionTransitions.java, ServerPlayerTeleportMixin.java, ThreadOwnership.java and the related tests.

The prior R1 agent wrote a DispatchToken/resolveEntityTransition implementation and tests but stopped before validation. Review and finish R1 only.

Required context semantics:
- REGION context with destination owner equal to current region: INLINE.
- GLOBAL context with no current destination region owner: INLINE.
- Physical server thread: preserve baseline behavior — same-region teleport INLINE, cross-region/cross-dimension teleport DISPATCH.
- NETWORK and ASYNC contexts: never mutate world inline; dispatch to destination region or global lane.
- Do not blanket-return for every non-REGION context.
- If a dispatch is rejected, never execute vanilla teleport on a wrong-owner REGION/NETWORK/ASYNC thread. Use a proven safe continuation or fail-stop with a visible error.
- Use position-aware scheduling: RegionScheduler.scheduleToChunk or enqueue with targetChunkPos, not positionless enqueue. The core D4 patch rehomes position-bearing tasks after split.
- Dispatch identity must not collapse two independent teleports to different exact coordinates/rotations in the same chunk. Prefer an invocation token propagated into the body; do not use a coarse entity+chunk key as duplicate identity.
- Token liveness must follow the current destination owner and scheduler lifecycle, not only a stale Region reference. Do not use a short timestamp TTL. If retirement cleanup needs a common scheduler callback that is not available, stop and document the dependency for manager approval.
- Do not swallow Throwable from the vanilla teleport body. If this is outside R1, keep it explicitly open as R8 and do not add new swallowing behavior.

Tests required: region owner re-entry; global no-owner re-entry; server-thread same-region and cross-region; NETWORK/ASYNC never inline; distinct exact destinations in the same chunk; two separate identical invocations both execute; enqueue rejection safe fallback; accepted task follows destination chunk after split if possible.

Only edit EntityTeleportMixin.java, RegionTransitions.java and directly related Fabric tests. No common changes, no config, no docs except tools/freebuff/reports/runtime-implementation.md. No comments/docstrings in new code. Run focused Fabric tests only. If safe semantics require a broader API, stop and write the exact blocker and tests still missing. No commit/push.
