/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.scheduler.RegionPendingTicks;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-worker scheduled-tick deferral buffers, the end-of-tick
 * server-thread replay, and the drain-execution staging flag (mandate 21,
 * spec 15 — see LevelScheduleTickMixin and LevelTicksTickMixin for the two
 * capture seams).
 *
 * <p><strong>How the level is known:</strong> capture happens inside
 * {@code LevelTicks}, which has no level reference. Each world's staging
 * hub registers its level's two containers (block ticks, fluid ticks) by
 * identity at activation; capture resolves the owning level from the
 * container instance, so the buffered tick knows exactly where it belongs.
 * Unregistered containers (client-side levels, other mods' custom
 * LevelTicks) are never captured — vanilla runs for them unconditionally.</p>
 *
 * <p><strong>The replay/drain ordering guarantee (no double-run, no
 * infinite deferral):</strong> a region worker executing a staged
 * <em>schedule</em> writes nothing — the tick is buffered. At end of
 * server tick the buffered ticks are re-submitted on the SERVER thread
 * (never in REGION context, so the deferral mixin passes them straight
 * through) and land in the container as vanilla ticks. The next tick's
 * drain executes them like any vanilla tick. A worker executing a staged
 * <em>drain body</em> that calls scheduleTick defers again — one hop per
 * tick, terminating because the replayed tick's execution is a vanilla
 * drain (server thread) that the mixin never stages. Nothing is executed
 * twice: the staged drain body that ran is drained once, and anything it
 * re-schedules is new work, not a re-run.</p>
 *
 * <p><strong>Ordering:</strong> FIFO per the shared queue (capture order);
 * vanilla defines no cross-block ordering within a tick beyond container
 * order, and the replay preserves relative capture order, so per-region
 * causal order is exact and global order is container-order-equivalent.</p>
 */
public final class ScheduledTickDeferral {

	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
	/**
	 * True while the engine's drain-staging flag is on for the CURRENT
	 * server-thread tick pass: set by ServerLevelTickMixin around the two
	 * LevelTicks.tick(...) calls so the drain mixin knows the BiConsumer
	 * bodies flowing through runCollectedTicks belong to a staged world.
	 * Server thread only (the drains run there).
	 */
	private static final ThreadLocal<Boolean> DRAIN_STAGING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	/** Registered containers by identity (class identity == reference identity). */
	private static final Map<LevelTicks<?>, ServerLevel> CONTAINER_LEVELS =
			Collections.synchronizedMap(new ConcurrentHashMap<>());
	/**
	 * Registered pending-tick ledgers by container: the worker-visible,
	 * region-owned accounting that answers hasScheduledTick-class questions
	 * without racing the coordinator (mandate 21; see RegionPendingTicks).
	 */
	private static final Map<LevelTicks<?>, RegionPendingTicks> CONTAINER_LEDGERS =
			Collections.synchronizedMap(new ConcurrentHashMap<>());
	private static final ConcurrentLinkedQueue<Entry<?>> PENDING = new ConcurrentLinkedQueue<>();
	private static final AtomicLong DEFERRED_TOTAL = new AtomicLong();
	private static final AtomicLong REPLAYED_TOTAL = new AtomicLong();
	/** Ledger bookkeeping: capture-side records and drain-side releases. */
	private static final AtomicLong LEDGER_RECORDS = new AtomicLong();
	private static final AtomicLong LEDGER_RELEASES = new AtomicLong();

	/** A deferred tick plus the container it belongs to. */
	private record Entry<T>(LevelTicks<T> container, ScheduledTick<T> tick) {
	}

	private ScheduledTickDeferral() {
	}

	/** Registers a world's containers at staging activation. Idempotent. */
	static void registerLevel(ServerLevel level) {
		CONTAINER_LEVELS.put(level.getBlockTicks(), level);
		CONTAINER_LEVELS.put(level.getFluidTicks(), level);
	}

	/**
	 * Registers the pending-tick ledger for one container (block or fluid)
	 * at staging activation. Capture records into it; drain execution
	 * releases from it; the hasScheduledTick mixin queries it.
	 */
	public static void registerLedger(LevelTicks<?> container, RegionPendingTicks ledger) {
		CONTAINER_LEDGERS.put(container, ledger);
	}

	/** Removes a world's containers and ledgers (world detach). Idempotent. */
	static void unregisterLevel(ServerLevel level) {
		CONTAINER_LEVELS.remove(level.getBlockTicks());
		CONTAINER_LEVELS.remove(level.getFluidTicks());
		CONTAINER_LEDGERS.remove(level.getBlockTicks());
		CONTAINER_LEDGERS.remove(level.getFluidTicks());
	}

	/** @return the pending-tick ledger registered for this container, or null. */
	public static RegionPendingTicks ledgerOf(LevelTicks<?> container) {
		return CONTAINER_LEDGERS.get(container);
	}

	/** Activates capture (engine boot, after staging activation). Idempotent. */
	public static void activate() {
		ACTIVE.set(true);
	}

	/**
	 * Deactivates capture and discards pending content (engine shutdown:
	 * worlds are saved by vanilla afterwards; buffered ticks are game-time
	 * scheduled work for ticks that will never be simulated by this engine
	 * run — the same class of loss as any mid-tick server stop, and strictly
	 * bounded by one tick's worth of due-or-future ticks).
	 */
	public static void deactivate() {
		ACTIVE.set(false);
		DRAIN_STAGING.remove();
		PENDING.clear();
		CONTAINER_LEVELS.clear();
		CONTAINER_LEDGERS.clear();
	}

	/**
	 * Attempt to capture a scheduled tick instead of letting vanilla mutate
	 * the container on a region worker. Returns true when the caller must
	 * skip vanilla's schedule (the tick is buffered for replay). Fails open
	 * (returns false) for anything not a registered world container — the
	 * vanilla path must always be available.
	 */
	public static <T> boolean capture(LevelTicks<T> container, ScheduledTick<T> tick) {
		if (!ACTIVE.get() || ThreadOwnership.current().kind() != ThreadContext.Kind.REGION) {
			return false;
		}
		ServerLevel level = CONTAINER_LEVELS.get(container);
		if (level == null) {
			return false; // unregistered container: never defer, run vanilla
		}
		PENDING.add(new Entry<>(container, tick));
		DEFERRED_TOTAL.incrementAndGet();
		// The pending-tick ledger records the capture in the capturing
		// region's context (mandate 21): from now until drain execution the
		// tick is visible to that region's hasScheduledTick queries without
		// touching vanilla's server-thread coordinator.
		RegionPendingTicks ledger = CONTAINER_LEDGERS.get(container);
		if (ledger != null) {
			ledger.record(tick.pos().getX() >> 4, tick.pos().getZ() >> 4);
			LEDGER_RECORDS.incrementAndGet();
		}
		return true;
	}

	/**
	 * True when the current (server-thread) drain belongs to a staged world's
	 * registered container: the drain-staging flag set by ServerLevelTickMixin,
	 * an active engine, and a non-worker caller. Public because the drain
	 * mixin lives in the mixin package.
	 */
	public static <T> boolean isDrainStaged(LevelTicks<T> container) {
		return ACTIVE.get()
				&& DRAIN_STAGING.get()
				&& ThreadOwnership.current().kind() != ThreadContext.Kind.REGION
				&& CONTAINER_LEVELS.containsKey(container);
	}

	/** Sets the drain-staging flag for the current thread (ServerLevelTickMixin). */
	public static void beginDrainStaging() {
		DRAIN_STAGING.set(Boolean.TRUE);
	}

	/** Clears the drain-staging flag for the current thread. */
	public static void endDrainStaging() {
		DRAIN_STAGING.set(Boolean.FALSE);
	}

	/** @return the level a registered container belongs to, or null. */
	public static ServerLevel levelOf(LevelTicks<?> container) {
		return CONTAINER_LEVELS.get(container);
	}

	/**
	 * Replays every deferred tick on the SERVER thread through its own
	 * container. Engine end-of-tick hook (after staged-body flush).
	 */
	public static void replayOnServerThread() {
		if (PENDING.isEmpty()) {
			return;
		}
		long replayed = 0;
		Entry<?> entry;
		while ((entry = PENDING.poll()) != null) {
			scheduleVanilla(entry);
			replayed++;
		}
		REPLAYED_TOTAL.addAndGet(replayed);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void scheduleVanilla(Entry<?> entry) {
		LevelTicks container = entry.container();
		container.schedule(entry.tick()); // server thread: not captured, vanilla runs
	}

	/** @return total pending-tick ledger records since engine start (diagnostics). */
	public static long ledgerRecords() {
		return LEDGER_RECORDS.get();
	}

	/** @return total pending-tick ledger releases since engine start (diagnostics). */
	public static long ledgerReleases() {
		return LEDGER_RELEASES.get();
	}

	/** One ledger release (drain-side bookkeeping). */
	public static void ledgerReleased() {
		LEDGER_RELEASES.incrementAndGet();
	}

	/** @return total ticks deferred since engine start (diagnostics). */
	public static long deferredTotal() {
		return DEFERRED_TOTAL.get();
	}

	/** @return total ticks replayed since engine start (diagnostics). */
	public static long replayedTotal() {
		return REPLAYED_TOTAL.get();
	}

	/** @return ticks currently buffered (diagnostics snapshot). */
	public static int pendingCount() {
		return PENDING.size();
	}
}
