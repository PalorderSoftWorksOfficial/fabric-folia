/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.RegionStageHub;
import com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral;
import com.palordersoftworks.fabricfolia.scheduler.RegionPendingTicks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scheduled-tick drain-execution staging (mandate 21): vanilla's
 * {@code LevelTicks.runCollectedTicks} drains the due ticks and invokes the
 * level's BiConsumer ({@code ServerLevel::tickBlock}/{@code tickFluid}) for
 * each. Under regionized execution the drained body mutates block state at
 * the tick's position — exactly the mutation class that belongs to the
 * region owning that position, so the body is staged into the
 * {@code SCHEDULED_TICK} slice and executed on the owning region's worker.
 *
 * <p><strong>What stays vanilla:</strong> the entire drain machinery —
 * collection, ordering ({@code DRAIN_ORDER}/{@code subTickOrder}), the
 * {@code toRunThisTickSet} dedup, the {@code alreadyRunThisTick} list, and
 * {@code cleanupAfterTick} — runs unmodified on the server thread. Only the
 * per-tick body's execution point moves; list management is never touched
 * concurrently.</p>
 *
 * <p><strong>Why the guard is triple-checked:</strong> staging must apply
 * only when (a) deferral is active at all, (b) the server thread is inside
 * a staged world's drain (the flag {@code ServerLevelTickMixin} sets around
 * the two {@code LevelTicks.tick} calls — without it, other mods' or other
 * levels' LevelTicks would be misclassified), and (c) the caller is not a
 * region worker (a worker never drains; the flag plus the context check
 * makes the invariant explicit rather than incidental).</p>
 *
 * <p><strong>Replay safety:</strong> a replayed deferral is inserted by
 * {@code schedule} on the server thread and drained by vanilla on the
 * server thread — it flows through this redirect ONCE, executes on its
 * owning region's worker, and anything IT schedules lands in the deferral
 * buffer (the capture mixin), whose replay next tick is a fresh drain. One
 * execution per scheduled tick, one deferral hop per worker-written tick;
 * the loop terminates because the replay itself is server-thread vanilla.</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksTickMixin {

	@Redirect(
			method = "runCollectedTicks",
			at = @At(value = "INVOKE",
					target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"),
			require = 1)
	private void fabricfolia$stageDrainBody(java.util.function.BiConsumer<?, ?> consumer, Object pos, Object type) {
		LevelTicks<?> container = (LevelTicks<?>) (Object) this;
		// The tick has been DRAINED — its pending window is over whether the
		// body now runs inline (vanilla) or staged on a worker. Exactly-once:
		// every drained tick passes this site exactly once.
		RegionPendingTicks ledger = ScheduledTickDeferral.ledgerOf(container);
		if (ledger != null) {
			BlockPos blockPos = (BlockPos) pos;
			if (ledger.release(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
				// Count only real record/release closures: server-thread-
				// originated ticks have no entry, so counting every drain
				// would make records−releases meaningless as a balance.
				ScheduledTickDeferral.ledgerReleased();
			}
		}
		if (ScheduledTickDeferral.isDrainStaged(container)) {
			@SuppressWarnings("unchecked")
			java.util.function.BiConsumer<Object, Object> raw =
					(java.util.function.BiConsumer<Object, Object>) consumer;
			BlockPos blockPos = (BlockPos) pos;
			RegionStageHub.stage(
					ScheduledTickDeferral.levelOf(container),
					RegionStageHub.Slice.SCHEDULED_TICK,
					new StagedDrainBody(raw, blockPos, type));
			return;
		}
		// Vanilla path: server thread, unregistered container, or staging
		// inactive — invoked at the original call site.
		@SuppressWarnings("unchecked")
		java.util.function.BiConsumer<Object, Object> raw =
				(java.util.function.BiConsumer<Object, Object>) consumer;
		raw.accept(pos, type);
	}

	/**
	 * The staged drain body: its position resolves the owning region at
	 * flush (post-migration truth); the body IS vanilla's BiConsumer
	 * ({@code tickBlock}/{@code tickFluid}) — nothing is copied.
	 */
	private record StagedDrainBody(java.util.function.BiConsumer<Object, Object> consumer,
	                               BlockPos pos, Object type)
			implements RegionStageHub.Positioned, Runnable {

		@Override
		public net.minecraft.world.level.ChunkPos fabricfolia$position() {
			return new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
		}

		@Override
		public void run() {
			consumer.accept(pos, type);
		}
	}
}
