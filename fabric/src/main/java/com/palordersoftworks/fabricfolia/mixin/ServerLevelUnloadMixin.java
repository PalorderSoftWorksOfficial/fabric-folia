/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk lifecycle integration (mandate §20): {@code ServerLevel.unload} is
 * the definitive per-chunk departure point — vanilla calls it exactly once
 * per unloaded chunk holder from {@code ChunkMap}'s unload lambda, on the
 * server thread, after the chunk is saved (verified against the 26.2 jar
 * bytecode). Routing it to the world's regionizer {@code removeChunk} is
 * what lets sections empty out, dead sections accumulate, and the tick-end
 * split path ever run — without this hook a loaded-then-unloaded area stayed
 * owned by its region forever (the reason merges/splits could never be
 * observed live).
 *
 * <p><strong>Ordering safety:</strong> the hook runs at TAIL, after vanilla
 * has already unregistered the chunk's tick containers and block entities —
 * region ownership release is the last step, so no vanilla code runs against
 * a section the regionizer has already released. {@code removeChunk} is
 * ordered against merges/deaths by the regionizer's structure lock and is a
 * defensive no-op on unmatched removals, so a duplicate or late call
 * degrades to nothing rather than corrupting counters.</p>
 *
 * <p><strong>Non-interference:</strong> observer only; no-op when the engine
 * is disabled or the world is not attached; failures are contained and
 * logged, never propagated into vanilla's unload path.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelUnloadMixin {

	@Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
			at = @At("TAIL"))
	private void folia$onChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || chunk == null) {
			return;
		}
		try {
			engine.onChunkUnloaded(this, chunk);
		} catch (RuntimeException e) {
			FabricFoliaMod.reportTrackerFailure("chunk unload", null, e);
		}
	}
}
