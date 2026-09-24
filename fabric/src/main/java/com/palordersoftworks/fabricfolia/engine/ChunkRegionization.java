/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk activity → regionizer registration (mandate §20): the production
 * seam that makes loaded chunks actually form regions. Without this, the
 * regionizer's only production caller was the opt-in random-tick slice, so
 * a default server ran with regions=0 and every staged gameplay body fell
 * back to the server thread — the observed failure.
 *
 * <p><strong>Sources of registration:</strong></p>
 * <ul>
 *   <li>{@link ServerChunkEvents#CHUNK_LOAD} fires for every chunk that
 *       becomes a full chunk on the server thread, forever (steady state).</li>
 *   <li>attach-time backfill: the spawn chunk plus every player's chunk
 *       position, gated on the chunk actually being present
 *       ({@code getChunkNow != null}), so an already-loaded spawn area forms
 *       its regions even though its CHUNK_LOAD events fired before the
 *       engine attached. Steady-state coverage arrives via CHUNK_LOAD for
 *       every later chunk.</li>
 * </ul>
 *
 * <p><strong>Health invariant (spec 19):</strong> a live server window with
 * loaded spawn chunks but zero regions is the broken-pipeline signature;
 * {@link #healthCheckLine} surfaces it concisely (at most one report per
 * window — no per-tick log spam). A healthy idle server with genuinely no
 * loaded spawn chunks reports nothing: regions form exactly when chunk
 * activity exists, never fabricated.</p>
 *
 * <p><strong>Threading:</strong> both sources run on the server thread
 * (Fabric chunk events and the attach hook), so registration itself is
 * single-threaded; the regionizer's structure lock covers the rest.</p>
 */
public final class ChunkRegionization {

	private static final AtomicBoolean ATTACHED = new AtomicBoolean();
	/** Set when the server's worlds are attached; arms the first-tick catch-up. */
	private static final AtomicBoolean WORLDS_ATTACHED = new AtomicBoolean();
	private static final AtomicLong CHUNK_LOAD_REGISTRATIONS = new AtomicLong();
	private static final AtomicLong BACKFILL_REGISTRATIONS = new AtomicLong();
	private static final AtomicLong UNATTACHED_REFUSALS = new AtomicLong();
	private static volatile long lastHealthReportNanos;
	/** Min spacing between health-violation reports (no log spam). */
	private static final long REPORT_WINDOW_NANOS = 10_000_000_000L;

	private ChunkRegionization() {
	}

	/**
	 * Registers every chunk that loads from now on with its world's
	 * regionizer. Called once from mod init (before the server starts); the
	 * handler is inert per-event when the engine is down or the world is
	 * not attached, and re-registration is a no-op.
	 */
	public static void attach() {
		if (!ATTACHED.compareAndSet(false, true)) {
			return;
		}
		ServerChunkEvents.CHUNK_LOAD.register(ChunkRegionization::onChunkLoad);
		// Pre-attach window catch-up: vanilla loads the spawn area DURING
		// world init, before SERVER_STARTED attaches the worlds, so those
		// chunks' events fired with no regionizer to receive them (counted
		// as refusals). On the first server tick after attach, register the
		// still-loaded spawn-area spiral and every player's chunk. One-time,
		// server thread, bounded probe count; every add is idempotent.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK
				.register(server -> {
					if (!WORLDS_ATTACHED.compareAndSet(true, false)) {
						return;
					}
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					if (engine == null) {
						return;
					}
					for (ServerLevel level : server.getAllLevels()) {
						backfillSpawnArea(level, engine);
					}
				});
	}

	/** Marks the worlds as attached (arms the first-tick catch-up). */
	public static void markWorldsAttached() {
		WORLDS_ATTACHED.set(true);
	}

	/**
	 * One chunk-load event: registers the chunk position with its world's
	 * regionizer. Fires for newly generated chunks and re-loaded existing
	 * chunks alike ({@code newChunk} distinguishes them; both belong in the
	 * regionizer whenever they are live on the server).
	 */
	private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newChunk) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			return;
		}
		WorldRegionizer regionizer =
				engine.regionizerFor(level.dimension().identifier().toString());
		if (regionizer == null) {
			UNATTACHED_REFUSALS.incrementAndGet();
			return;
		}
		if (!com.palordersoftworks.fabricfolia.patches.PatchRegistry.isEnabled("regionize-chunk-load")) {
			com.palordersoftworks.fabricfolia.patches.PatchRegistry.recordFallback("regionize-chunk-load");
			return;
		}
		ChunkPos pos = chunk.getPos();
		regionizer.addChunk(pos.x(), pos.z());
		CHUNK_LOAD_REGISTRATIONS.incrementAndGet();
	}

	/**
	 * Backfills regions for an already-running world: the spawn chunk plus
	 * every player's chunk, gated on the chunk being present. Called from
	 * the engine's world attach (server thread).
	 */
	public static void backfillWorld(ServerLevel level, FabricFoliaEngine engine) {
		if (!com.palordersoftworks.fabricfolia.patches.PatchRegistry.isEnabled("regionize-chunk-load")) {
			com.palordersoftworks.fabricfolia.patches.PatchRegistry.recordFallback("regionize-chunk-load");
			return;
		}
		backfillSpawnArea(level, engine);
	}

	/**
	 * The bounded pre-attach catch-up: a spiral around the world's spawn
	 * position covering vanilla's loaded spawn area, plus every player's
	 * chunk. Only positions whose chunk is PRESENT register (getChunkNow);
	 * the CHUNK_LOAD event owns everything that loads later.
	 */
	private static void backfillSpawnArea(ServerLevel level, FabricFoliaEngine engine) {
		WorldRegionizer regionizer =
				engine.regionizerFor(level.dimension().identifier().toString());
		if (regionizer == null) {
			return;
		}
		int registered = 0;
		BlockPos spawnPos = level.getRespawnData().pos();
		ChunkPos spawn = new ChunkPos(spawnPos.getX() >> 4, spawnPos.getZ() >> 4);
		int radius = 8;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int x = spawn.x() + dx;
				int z = spawn.z() + dz;
				if (level.getChunkSource().getChunkNow(x, z) != null) {
					regionizer.addChunk(x, z);
					registered++;
				}
			}
		}
		for (ServerPlayer player : level.players()) {
			if (player.isRemoved()) {
				continue;
			}
			ChunkPos pos = player.chunkPosition();
			if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null) {
				regionizer.addChunk(pos.x(), pos.z());
				registered++;
			}
		}
		BACKFILL_REGISTRATIONS.addAndGet(registered);
	}

	/** @return the number of chunks registered through the CHUNK_LOAD hook. */
	public static long chunkLoadRegistrations() {
		return CHUNK_LOAD_REGISTRATIONS.get();
	}

	/** @return the number of chunks registered by attach-time backfill. */
	public static long backfillRegistrations() {
		return BACKFILL_REGISTRATIONS.get();
	}

	/** @return chunk-load events that arrived with no attached world (diagnostics). */
	public static long unattachedRefusals() {
		return UNATTACHED_REFUSALS.get();
	}

	/**
	 * The runtime self-check (spec 19): server active ({@code live} window
	 * true) but zero regions anywhere while a world has its spawn chunk
	 * loaded is the broken-pipeline signature. Returns null when healthy or
	 * inside the quiet window; at most one line per report window.
	 */
	public static String healthCheckLine(FabricFoliaEngine engine, Iterable<ServerLevel> levels) {
		if (engine == null) {
			return null;
		}
		if (engine.regionCount() > 0) {
			return null;
		}
		boolean anyLoadedSpawn = false;
		for (ServerLevel level : levels) {
			BlockPos spawnPos = level.getRespawnData().pos();
			if (level.getChunkSource().getChunkNow(spawnPos.getX() >> 4, spawnPos.getZ() >> 4) != null) {
				anyLoadedSpawn = true;
				break;
			}
		}
		if (!anyLoadedSpawn) {
			return null;
		}
		long now = System.nanoTime();
		if (now - lastHealthReportNanos < REPORT_WINDOW_NANOS) {
			return null;
		}
		lastHealthReportNanos = now;
		return "Fabric Folia region pipeline check: loaded spawn chunks exist but NO regions"
				+ " have formed (regions=0). Chunk registration may be failing; see"
				+ " /folia regions and docs/folia-parity.md. (At most one report per 10s.)";
	}

	/** Clears counters (tests). */
	static void resetForTests() {
		CHUNK_LOAD_REGISTRATIONS.set(0);
		BACKFILL_REGISTRATIONS.set(0);
		UNATTACHED_REFUSALS.set(0);
		lastHealthReportNanos = 0;
	}
}
