/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;

/**
 * Region routing for the player path (mandate §11): the machinery the
 * connection-capture mixin uses to move a player's per-tick work — packet
 * drain, connection tick, and the physics chain that hangs off it — onto
 * the region owning the player's chunk.
 *
 * <p><strong>The capture seam:</strong>
 * {@code ServerConnectionListener.tick()} iterates its connection list
 * (synchronized block + iterator, server thread) and calls
 * {@code Connection.tick()} per live connection, which drains the pending
 * packet queue ({@code flushQueue}), runs the listener tick (the
 * {@code SGPLI.tick} → {@code doTick} → physics chain), flushes the
 * outbound queue, and processes disconnections. Staging that whole call
 * moves the complete per-tick connection lifecycle onto the owning region,
 * so one region owns a player's state machine per tick — packets and
 * physics serialized on one owner, parallel across players. Vanilla's
 * list-iteration decisions stay on the server thread exactly as for the
 * entity pass: the server thread still decides WHICH connections tick; the
 * region executes the body.</p>
 *
 * <p><strong>Not staged:</strong> connecting connections
 * ({@code isConnecting}) — vanilla skips them and so does the capture; the
 * login chain (configuration/play handshake) stays server-thread until
 * fully joined. Memory connections (singleplayer/in-process clients) are
 * also skipped: their listeners' tick is fast, in-process, and coupled to
 * the integrated server's own loop discipline.</p>
 *
 * <p><strong>The physics split, and why the connection body is the staged
 * unit:</strong> the physics chain ({@code Player.tick}, movement,
 * collisions) is driven by {@code SGPLI.tickPlayer} →
 * {@code ServerPlayer.doTick()} inside the connection tick —
 * {@code ServerPlayer.tick()} (the entity pass) does NOT call the physics
 * super-chain (bytecode-verified on 26.2). Staging only the entity-pass
 * body would put one player's state machine on two unsynchronized threads;
 * staging the connection body keeps packet handling, connection tick, and
 * physics on the ONE region owning the player.</p>
 *
 * <p><strong>The two couplings inside the staged body:</strong>
 * {@code ServerChunkCache.move(player)} (chunk-view bookkeeping on the
 * chunk system's owning thread) and {@code ServerPlayerGameMode.tick()}
 * (break progress against the mining ticket machinery) are redirected to
 * the server thread by {@link ServerPlayerTickMixin} when the body runs on
 * a region worker. Everything else in the body is player-local state or
 * thread-safe packet sends ({@code Connection.send} is the vanilla
 * thread-safe channel write).</p>
 *
 * <p><strong>Packets arriving while the region owns the tick:</strong>
 * vanilla's re-home protocol ({@code PacketUtils.ensureRunningOnSameThread}
 * → {@code PacketProcessor}) handles arrival races: a handler executing on
 * a Netty event loop queues itself and throws
 * {@code RunningOnDifferentThreadException} (swallowed by
 * {@code Connection.channelRead0}). Two captures cooperate:</p>
 * <ul>
 *   <li>{@code PacketProcessor.scheduleIfPossible} capture: a re-home from
 *   an event loop is routed to the region owning the player (skipping the
 *   shared server-thread queue), preserving the handler-for-its-owner
 *   ordering (a packet queued behind the connection tick on the same
 *   region queue runs after it).</li>
 *   <li>{@code PacketProcessor.isSameThread} redirect: the re-home check
 *   answers true when already executing on the owning region's worker, so
 *   drained handlers run there instead of re-queueing forever.</li>
 * </ul>
 *
 * <p><strong>Respawn carve-out:</strong> {@code PERFORM_RESPAWN} is the
 * one game command deliberately excluded from the owner-region pass. It
 * enters {@code PlayerList.respawn}, which constructs a replacement
 * {@code ServerPlayer} and mutates the global player list, level entity
 * manager, and respawn-anchor state in one synchronous vanilla body. The
 * owner check therefore rejects the packet on a region worker; the
 * {@code PacketProcessorMixin} then leaves it in vanilla's packet queue,
 * which {@code MinecraftServer.processPacketsAndTick} drains on the server
 * thread. This preserves the vanilla respawn contract without a blocking
 * cross-context wait, while ordinary game packets remain region-owned. Direct
 * calls to {@code PlayerList.respawn} from mods are not covered by this
 * packet-level seam.</p>
 *
 * <p><strong>Threading (mandate §8):</strong> stage decisions run on the
 * server thread (the SCL tick iterator); execution runs on region workers;
 * the packet re-home path runs on Netty event loops (classification only —
 * fire-and-forget enqueue, never a blocking wait, mandate §32).</p>
 *
 * <p><strong>State classification (spec 4):</strong> this class is a GLOBAL
 * routing table over per-world regionizers; routed work is REGION-LOCAL
 * once enqueued; the counters are GLOBAL diagnostics.</p>
 */
public final class RegionPlayerRouting {

	private static final java.util.concurrent.atomic.AtomicLong STAGED_CONNECTIONS =
			new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong REHOME_REGION =
			new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong REHOME_SERVER =
			new java.util.concurrent.atomic.AtomicLong();
	/** Respawn commands deliberately left on the server-thread packet queue. */
	private static final java.util.concurrent.atomic.AtomicLong RESPAWN_COMMANDS =
			new java.util.concurrent.atomic.AtomicLong();

	private RegionPlayerRouting() {
	}

	/**
	 * Decides whether {@code connection}'s per-tick body should move to the
	 * region owning the player's chunk, and stages it there when so.
	 *
	 * <p>Server thread only (the SCL tick iterator). Returns false when the
	 * vanilla server-thread path should run the body inline (engine down,
	 * pre-play-phase listener, disconnecting or memory connection, unowned
	 * chunks — the caller then runs vanilla behavior unchanged).</p>
	 *
	 * <p><strong>Ordering guarantee:</strong> the body is staged into the
	 * hub's PLAYER slice and flushed with the tick's other staged work —
	 * after every server-thread pass of this tick (the hub's documented
	 * stage-then-flush contract). Declared last in {@code Slice}, the PLAYER
	 * slice flushes after the entity slice, preserving vanilla's intra-tick
	 * order (entity pass, then {@code tickConnection}) per region. The owning
	 * region is resolved at flush from the player's post-pass chunk.</p>
	 *
	 * @param connection the connection whose body is being ticked
	 * @param body       vanilla's {@code Connection.tick()} body as a
	 *                   runnable (the capture mixin's staged call)
	 * @return true when the body was staged (caller must NOT run it inline)
	 */
	public static boolean stageConnectionTick(Connection connection,
	                                          Runnable body) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || !stagingPlayerPath()) {
			return false;
		}
		PacketListener listener = connection.getPacketListener();
		if (!(listener instanceof ServerGamePacketListenerImpl gameListener)) {
			return false; // handshake/config/login or disconnecting: server thread
		}
		if (connection.isMemoryConnection()) {
			return false; // in-process client: its loop discipline is the server's own
		}
		if (!connection.isConnected()) {
			// A dying connection's tick reaches handleDisconnection →
			// PlayerList removal — global player state. Vanilla's server-thread
			// path owns it; the disconnect lands this tick, so skipping one
			// staged body changes nothing observable.
			return false;
		}
		ServerPlayer player = gameListener.player;
		if (player == null || player.isRemoved() || player.level().isClientSide()) {
			return false;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		if (!RegionStageHub.isStaging(level, RegionStageHub.Slice.PLAYER)) {
			return false;
		}
		if (!fabricfolia$physicsNeighborhoodLoaded(player)) {
			// The staged body runs the physics chain (doTick -> Player.tick),
			// which can request edge-adjacent chunk data; an unloaded 3x3
			// neighborhood would park a worker on a synchronous chunk load the
			// worker cannot pump (the same hazard the entity pass gates).
			// Vanilla's server-thread execution stays until chunk access is
			// region-safe. Server-thread probe: getChunkNow never blocks.
			return false;
		}
		RegionStageHub.stage(level, RegionStageHub.Slice.PLAYER,
				new StagedConnectionBody(player, body));
		STAGED_CONNECTIONS.incrementAndGet();
		return true;
	}

	/**
	 * The staged connection body: its position resolves the owning region at
	 * flush (the player's post-pass chunk), and the hub's runSafely guard
	 * isolates a failed body from its worker and from the ownership graph.
	 */
	private record StagedConnectionBody(ServerPlayer player, Runnable body)
			implements RegionStageHub.Positioned, Runnable {

		@Override
		public ChunkPos fabricfolia$position() {
			return player.chunkPosition();
		}

		@Override
		public void run() {
			body.run();
		}
	}

	/**
	 * The routing decision for work associated with a live game connection
	 * (a drained pending-action or an event-loop packet task): the region
	 * that owns the connection's player's chunk is where that work belongs.
	 *
	 * @return the owning world/chunk route, or null when connection-owner
	 *         routing does not apply (engine down, gate off, non-play
	 *         listener, removed player) — the caller falls back to the
	 *         vanilla destination (server thread).
	 */
	public static Route routeFor(Connection connection) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || !stagingPlayerPath()) {
			return null;
		}
		if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl gameListener)) {
			return null;
		}
		ServerPlayer player = gameListener.player;
		if (player == null || player.isRemoved() || player.level().isClientSide()) {
			return null;
		}
		String worldKey = player.level().dimension().identifier().toString();
		ChunkPos pos = player.chunkPosition();
		return new Route(worldKey, pos.x(), pos.z());
	}

	/** A world/chunk ownership route for connection-associated work. */
	public record Route(String worldKey, int chunkX, int chunkZ) {
	}

	/**
	 * Identifies the vanilla client command that performs a player respawn.
	 * Kept as a small pure classifier so the packet-routing contract is
	 * testable without constructing a live player or listener.
	 */
	public static boolean isRespawnCommand(Packet<?> packet) {
		return packet instanceof ServerboundClientCommandPacket command
				&& command.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN;
	}

	/**
	 * True when the current region-owned packet path must deliberately let
	 * vanilla re-home this packet to the server-thread processor queue.
	 *
	 * <p>The check is intentionally narrow: it applies only to a live engine
	 * with player-path staging enabled, the current REGION context, and the
	 * {@code PERFORM_RESPAWN} action. All other packets retain the normal
	 * owner-region routing decision.</p>
	 */
	public static boolean requiresServerThreadForRespawn(Packet<?> packet) {
		if (!isRespawnCommand(packet)
				|| ThreadOwnership.current().kind() != ThreadContext.Kind.REGION) {
			return false;
		}
		return FabricFoliaMod.engine() != null && stagingPlayerPath();
	}

	/** Counts a respawn command deliberately left on the server-thread queue. */
	public static void noteRespawnToServerThread() {
		RESPAWN_COMMANDS.incrementAndGet();
	}

	/**
	 * The packet re-home protocol's region leg: called from the
	 * {@code PacketProcessor.scheduleIfPossible} capture when a handler
	 * re-home lands on a non-owner thread. Routes the handler execution to
	 * the region owning the listener's player when one exists.
	 *
	 * @return true when the packet was routed to a region (the original
	 *         schedule is skipped); false to fall through to vanilla's
	 *         server-thread processor queue. Respawn commands always take
	 *         the latter path while player-path staging is active.
	 */
	public static boolean schedulePacketToRegion(PacketListener listener,
	                                             Packet<?> packet) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || !stagingPlayerPath()) {
			return false;
		}
		if (isRespawnCommand(packet)) {
			// Do not cancel vanilla's scheduleIfPossible here. Its processor
			// queue is drained by MinecraftServer on the server thread, which
			// is the correct owner for PlayerList.respawn's replacement-player
			// construction and global state mutations.
			noteRespawnToServerThread();
			return false;
		}
		if (!(listener instanceof ServerGamePacketListenerImpl gameListener)) {
			return false;
		}
		ServerPlayer player = gameListener.player;
		if (player == null || player.isRemoved() || player.level().isClientSide()) {
			return false;
		}
		String worldKey = player.level().dimension().identifier().toString();
		WorldRegionizer regionizer = engine.regionizerFor(worldKey);
		RegionScheduler scheduler = engine.schedulerFor(worldKey);
		if (regionizer == null || scheduler == null) {
			return false;
		}
		ChunkPos pos = player.chunkPosition();
		Region region = regionizer.ownerOfChunk(pos.x(), pos.z());
		if (region == null) {
			return false;
		}
		boolean routed = scheduler.enqueue(region, () -> {
			ThreadOwnership.Context token = ThreadOwnership.enterRegion(region);
			try {
				safelyHandle(listener, packet);
			} finally {
				ThreadOwnership.exit(token);
			}
		});
		if (routed) {
			REHOME_REGION.incrementAndGet();
			return true;
		}
		// Region dead/closed between lookup and enqueue: vanilla's queue is
		// the fallback (the server thread still exists and pumps it).
		REHOME_SERVER.incrementAndGet();
		return false;
	}

	/**
	 * Whether the packet re-home check should treat the current context as
	 * "the same thread" as the listener: true when executing in the REGION
	 * context of the region that currently owns the listener's player's
	 * chunk. Called from the {@code PacketProcessor.isSameThread} redirect
	 * (any thread); the lookup is the same lock-free ownerOfChunk read the
	 * schedulers use — the regionizer's structure lock is not taken.
	 */
	public static boolean isOwnerRegion(ServerGamePacketListenerImpl listener) {
		if (ThreadOwnership.current().kind()
				!= com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
			return false;
		}
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || !stagingPlayerPath()) {
			return false;
		}
		ServerPlayer player = listener.player;
		if (player == null || player.isRemoved() || player.level().isClientSide()) {
			return false;
		}
		String worldKey = player.level().dimension().identifier().toString();
		WorldRegionizer regionizer = engine.regionizerFor(worldKey);
		if (regionizer == null) {
			return false;
		}
		com.palordersoftworks.fabricfolia.api.RegionInfo current =
				ThreadOwnership.current().region();
		if (current == null) {
			return false;
		}
		ChunkPos pos = player.chunkPosition();
		Region owner = regionizer.ownerOfChunk(pos.x(), pos.z());
		return owner != null && owner.regionId() == current.regionId();
	}

	/** Counts a packet that vanilla re-homed to the shared server queue. */
	public static void noteRehomedToServer() {
		REHOME_SERVER.incrementAndGet();
	}

	/** One-line summary for {@code /folia metrics}. */
	public static String metricsLine() {
		return "player path: connection ticks staged=" + STAGED_CONNECTIONS.get()
				+ ", packet rehomes region=" + REHOME_REGION.get()
				+ " server=" + REHOME_SERVER.get()
				+ ", respawns server-thread=" + RESPAWN_COMMANDS.get();
	}

	private static boolean fabricfolia$physicsNeighborhoodLoaded(ServerPlayer player) {
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
			return true;
		}
		ChunkPos center = player.chunkPosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (level.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean stagingPlayerPath() {
		return com.palordersoftworks.fabricfolia.FabricFoliaMod.playerPathStaging();
	}

	private static void safelyHandle(PacketListener listener, Packet<?> packet) {
		@SuppressWarnings({"unchecked", "rawtypes"})
		Packet<PacketListener> typed = (Packet<PacketListener>) (Packet) packet;
		handleTyped(listener, typed);
	}

	@SuppressWarnings("rawtypes")
	private static void handleTyped(PacketListener listener, Packet<PacketListener> packet) {
		if (!listener.shouldHandleMessage(packet)) {
			return;
		}
		try {
			packet.handle(listener);
		} catch (net.minecraft.server.RunningOnDifferentThreadException rehome) {
			// The handler decided it must run elsewhere (the owning region
			// changed between schedule and execute). Vanilla's protocol: the
			// throw is swallowed by Connection; the re-schedule decides where
			// the handler lands. Nothing to count — the new attempt recounts.
		} catch (ReportedException reported) {
			if (reported.getCause() instanceof OutOfMemoryError) {
				throw reported; // vanilla lets OOM rethrow out of the guard
			}
			reportPacketFailure(listener, packet, reported);
		} catch (Exception e) {
			reportPacketFailure(listener, packet, e);
		}
	}

	/**
	 * Mirrors vanilla's {@code ListenerAndPacket.handle} error triage: wrap
	 * non-reported exceptions through PacketUtils.makeReportedException
	 * (crash-report context), call onPacketError (which disconnects or
	 * rethrows), and log debug-side.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void reportPacketFailure(PacketListener listener, Packet<?> packet, Exception e) {
		ReportedException toRethrow = (e instanceof ReportedException re)
				? re
				: PacketUtils.makeReportedException(e, (Packet) packet, listener);
		try {
			listener.onPacketError(packet, e);
		} catch (ReportedException rethrow) {
			throw rethrow;
		} catch (Exception handlerFailed) {
			com.palordersoftworks.fabricfolia.console.Console.error(
					"Packet handling failed in a region and onPacketError also failed", handlerFailed);
		}
		throw toRethrow;
	}
}
