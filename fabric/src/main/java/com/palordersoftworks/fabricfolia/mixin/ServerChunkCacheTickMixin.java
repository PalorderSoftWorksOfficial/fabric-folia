/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.RegionTickInterceptor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * The first genuine interception (spec 15): diverts vanilla's per-chunk
 * random-tick work from the server thread onto Fabric-Folia's region workers.
 *
 * <p><strong>Verified against the 26.2 Mojang-mapped jar (2026-09-14):</strong>
 * {@code ServerLevel.tick(BooleanSupplier)} calls
 * {@code ServerChunkCache.tick(BooleanSupplier,boolean)} (the {@code chunkSource}
 * profiler section); its private {@code tickChunks(ProfilerFiller,long)} reaches
 * the per-chunk site through
 * {@code ChunkMap.forEachBlockTickingChunk(Consumer<LevelChunk>)}, whose
 * DistanceManager-driven enumeration decides WHICH chunks tick. The lambda
 * body wraps {@code ServerLevel.tickChunk(LevelChunk,int)} — the per-chunk
 * precipitation + random-tick work.</p>
 *
 * <p><strong>Why interception here specifically (why not Fabric API):</strong>
 * no Fabric API event exists for "the per-chunk block-tick enumeration" —
 * lifecycle and entity events cannot restructure WHERE this work executes.
 * Redirecting the single forEach call preserves vanilla's chunk-selection
 * logic byte-for-byte and swaps only the executor, which is the minimal
 * possible interception that changes the execution model (the spec's Mixin
 * test).</p>
 *
 * <p><strong>Invariants established:</strong></p>
 * <ul>
 *   <li>When the engine is disabled or this world is not attached, the
 *       redirect runs vanilla's enumeration with vanilla's own consumer —
 *       100% vanilla behavior (spec 16's "disabled means disabled").</li>
 *   <li>When intercepting, the chunks vanilla selected are offered to
 *       {@link RegionTickInterceptor}, which regionizes them (invariant 1:
 *       each chunk joins exactly one region) and executes
 *       {@code ServerLevel.tickChunk} on the owning region's worker thread
 *       with the single-owner latch held (invariants 3/4) and thread-context
 *       diagnostics active (spec 8).</li>
 * </ul>
 *
 * <p><strong>Interaction with unmodified vanilla when Fabric-Folia is
 * disabled:</strong> none — the handler just forwards to vanilla's method
 * with the original argument; the cost is one static engine check per
 * tickChunks call.</p>
 *
 * <p><strong>Known compatibility concerns:</strong> any other mod that also
 * redirects the same call site (a fork of this mixin, or a mod patching
 * tickChunks' enumeration) will conflict — Mixin redirects are exclusive at
 * a call site. Mods that merely ADD behavior around chunk ticking (events,
 * extra consumers) are unaffected. Documented here per spec 15; conflicts
 * surface loudly at mixin apply time, never silently.</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheTickMixin {

	/**
	 * The vanilla per-chunk enumeration call in
	 * {@code ServerChunkCache.tickChunks(ProfilerFiller,long)}.
	 *
	 * <p>When interception is off, forwards to vanilla's method with
	 * vanilla's own consumer (unchanged behavior). When on, supplies the
	 * collecting consumer — vanilla keeps its selection pass but hands the
	 * chunks to the interceptor instead of executing them inline — and flushes
	 * the pass (regionize + dispatch onto region workers) at exactly the point
	 * where vanilla would have been executing the work.</p>
	 *
	 * @param chunkMap        the receiver of the redirected call
	 * @param vanillaConsumer the consumer vanilla built for this pass
	 */
	@Redirect(
			method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"
			)
	)
	private void folia$interceptChunkEnumeration(ChunkMap chunkMap, Consumer<LevelChunk> vanillaConsumer) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		RegionTickInterceptor interceptor = engine == null ? null : engine.interceptorOrNull();
		ServerChunkCache self = (ServerChunkCache) (Object) this;
		// getLevel() is typed Level on the public API; the field it returns is
		// the ServerLevel this cache belongs to (verified on the 26.2 jar).
		ServerLevel level = (ServerLevel) self.getLevel();
		// "minecraft:overworld" — stable registry-style name (ResourceKey →
		// Identifier; verified against the 26.2 jar).
		String worldName = level.dimension().identifier().toString();

		if (interceptor == null || !interceptor.isIntercepting(worldName)) {
			// Not intercepting: run vanilla's own enumeration body unchanged.
			chunkMap.forEachBlockTickingChunk(vanillaConsumer);
			return;
		}

		// Offer every chunk vanilla selected; the work executes on region
		// workers after the enumeration completes (flush below).
		Consumer<LevelChunk> collector = chunk -> interceptor.offer(worldName, level, chunk);
		chunkMap.forEachBlockTickingChunk(collector);
		// Post-enumeration: regionize and dispatch the collected chunks at
		// exactly the point vanilla would have executed them inline.
		interceptor.flushPass(worldName);
	}
}
