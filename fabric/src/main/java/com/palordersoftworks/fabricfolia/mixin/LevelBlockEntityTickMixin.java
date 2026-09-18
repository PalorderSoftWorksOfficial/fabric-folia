/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.RegionStageHub;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The block-entity-tick staging capture (mandate 27): when regionized
 * gameplay is active for this level, the SERVER thread's individual
 * {@code TickingBlockEntity.tick()} call inside vanilla's
 * {@code tickBlockEntities()} is redirected to stage the tick onto the
 * block entity's chunk's owning region instead.
 *
 * <p><strong>Why redirect the tick call and not cancel the whole method
 * (bytecode-verified on 26.2):</strong> {@code tickBlockEntities()} is also
 * the maintainer of the shared ticker lists — it merges
 * {@code pendingBlockEntityTickers} into {@code blockEntityTickers} and
 * prunes removed tickers. Canceling the method would strand newly loaded
 * block entities (they would never leave the pending list) and leak removed
 * tickers forever. The redirect skips ONLY the tick execution; vanilla's
 * list maintenance and the removal prune still run on the server thread —
 * vanilla decides everything, the region executes.</p>
 *
 * <p><strong>Re-entry discipline:</strong> the staged body runs on a region
 * worker inside the scheduler's REGION context — but it calls
 * {@code ticker.tick()} DIRECTLY (not through tickBlockEntities), so this
 * redirect never re-enters for the staged body. The context check remains as
 * defense-in-depth for any future call path that might tick from a worker.</p>
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {

	@Redirect(
			method = "tickBlockEntities",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
	private void fabricfolia$stageBlockEntityTick(TickingBlockEntity ticker) {
		if (ThreadOwnership.current().kind() == ThreadContext.Kind.REGION) {
			ticker.tick(); // region-worker execution context: run inline
			return;
		}
		if (!RegionStageHub.isStaging((Level) (Object) this, RegionStageHub.Slice.BLOCK_ENTITY)) {
			ticker.tick(); // staging inactive: vanilla
			return;
		}
		// Removal is pruned by vanilla's own pass right after this call
		// site; a removed ticker staged now is a no-op tick, consistent
		// with vanilla's own late-removal behavior within a pass.
		final BlockPos pos = ticker.getPos();
		RegionStageHub.stage((Level) (Object) this, RegionStageHub.Slice.BLOCK_ENTITY,
				new StagedBlockEntityBody(ticker, pos));
	}

	/**
	 * The staged block-entity tick: its chunk owns it for its whole life
	 * (block entities cannot migrate), so flush-time region resolution is
	 * exact — only region death applies, handled by the hub's dispatch drop.
	 */
	private record StagedBlockEntityBody(TickingBlockEntity ticker, BlockPos pos)
			implements RegionStageHub.Positioned, Runnable {

		@Override
		public net.minecraft.world.level.ChunkPos fabricfolia$position() {
			return new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
		}

		@Override
		public void run() {
			ticker.tick();
		}
	}
}
