/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionState;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import com.palordersoftworks.fabricfolia.thread.ViolationReporter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The engine's scheduler: dispatches due regions onto the bounded worker pool
 * earliest-deadline-first (Folia's documented EDF-style approach, clean-room),
 * executes each region's tick as [drain task queue → tick hook], and runs the
 * regionizer's tick-end protocol on the worker that ticked.
 *
 * <p><strong>Independence guarantee (spec 6):</strong> each region's next tick
 * deadline is its own ({@code nextTickDeadlineNanos}); dispatch order never
 * considers other regions' lateness. A slow region occupies a worker longer but
 * does not shift anyone else's deadline; with fewer due regions than workers,
 * all maintain 20 TPS independently. <em>Note:</em> the deadline is written by
 * {@link WorldRegionizer#completeTick} using the regionizer's injected clock —
 * regionizer and scheduler must share a time base (both default to
 * {@code System::nanoTime}; tests inject the same clock to both).</p>
 *
 * <p><strong>Dispatch contract:</strong> the coordinator loop scans READY
 * regions whose deadline has passed and submits one tick job per due region;
 * the worker then: deadline re-check → tryBeginTick → enter context → drain
 * queue → tick hook → exit context → completeTick (tick-end protocol). Two
 * guards make duplicate dispatch (a region scanned twice before its first job
 * runs) harmless without cross-thread bookkeeping:</p>
 * <ol>
 *   <li>a re-scan of a region whose tick job is still queued/running fails
 *       {@code tryBeginTick} (the region is TICKING — single-owner latch),</li>
 *   <li>a duplicate job that arrives after the tick completed finds the
 *       deadline advanced past now and skips (no out-of-cycle early tick).</li>
 * </ol>
 * Correctness never depends on scan timing; a skipped due region is picked up
 * on the next 1ms scan.
 *
 * <p><strong>Queue registry lifecycle (wired via {@link WorldRegionizer.Listener}):</strong>
 * on merge the donor's queue is re-homed into the absorbing region's queue
 * (tasks execute in the merged context that now owns their target state); on
 * death the queue is dropped with a count diagnostic (DROP policy, spec 16).
 * The registry map is a ConcurrentHashMap because registration races are legal;
 * it is a registry, not a safety mechanism (spec 18).</p>
 *
 * <p><strong>Lock hierarchy:</strong> regionizer structure lock → queue
 * registry operations. The scheduler acquires the structure lock only in
 * {@link #enqueue}'s death-race re-check and never while a tick runs; nothing
 * acquires the structure lock while holding a queue's internal state (the
 * queue is lock-free). No ordering cycle exists.</p>
 */
public final class RegionScheduler implements AutoCloseable, WorldRegionizer.Listener {

	/** Tick period: 50ms per region tick (spec 6: 20 TPS per region). */
	public static final long TICK_NANOS = 50_000_000L;

	private final WorldRegionizer regionizer;
	private final WorkerPool pool;
	private final ViolationReporter reporter;
	private final AtomicBoolean running = new AtomicBoolean(false);
	/** Set when shutdown begins: enqueue refuses once nothing will ever drain. */
	private volatile boolean closed;
	private final java.util.function.LongSupplier nanoClock = System::nanoTime;

	/** Registry of live task queues by region (see class docs re: spec 18). */
	private final Map<Region, RegionTaskQueue> queuesByRegion = new ConcurrentHashMap<>();

	/** Engine-provided region tick body. Receives the region, runs in its context. */
	private final Consumer<Region> regionTickBody;
	/** Diagnostics sink for dropped tasks and failures (spec 26 uses this too). */
	private final Consumer<String> diagnostics;

	private final int poolThreadCount;

	private Thread coordinator;

	/**
	 * True when this scheduler created (and therefore owns) its worker pool;
	 * false for shared-pool schedulers, which must not shut the pool down.
	 */
	private final boolean ownsPool;

	public RegionScheduler(WorldRegionizer regionizer,
	                       int workerThreads,
	                       ValidationMode mode,
	                       Consumer<Region> regionTickBody,
	                       Consumer<String> diagnostics) {
		this(regionizer, new WorkerPool(workerThreads, "FabricFolia-Worker"),
				workerThreads, mode, regionTickBody, diagnostics, true);
	}

	/**
	 * Shared-pool constructor: attaches an additional world's regionizer to an
	 * existing pool (multi-world regionization over ONE bounded worker set —
	 * spec 10). The sharing scheduler's coordinator dispatches independently;
	 * only the pool (and its threads) is shared. Closing a shared scheduler
	 * stops its coordinator and drops its queues but leaves the pool running.
	 */
	public RegionScheduler(WorldRegionizer regionizer,
	                       WorkerPool sharedPool,
	                       int workerThreadCount,
	                       ValidationMode mode,
	                       Consumer<Region> regionTickBody,
	                       Consumer<String> diagnostics) {
		this(regionizer, sharedPool, workerThreadCount, mode, regionTickBody, diagnostics, false);
	}

	private RegionScheduler(WorldRegionizer regionizer,
	                       WorkerPool pool,
	                       int workerThreadCount,
	                       ValidationMode mode,
	                       Consumer<Region> regionTickBody,
	                       Consumer<String> diagnostics,
	                       boolean ownsPool) {
		this.regionizer = Objects.requireNonNull(regionizer, "regionizer");
		this.pool = pool;
		this.poolThreadCount = workerThreadCount;
		this.ownsPool = ownsPool;
		this.reporter = new ViolationReporter(mode, (message, throwable) -> {
			diagnostics.accept(message);
			if (throwable != null) {
				diagnostics.accept(String.valueOf(throwable));
			}
		});
		this.regionTickBody = Objects.requireNonNull(regionTickBody, "regionTickBody");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		// Register for regionizer lifecycle so the queue registry tracks merges
		// and deaths — without this, queued tasks would be silently lost when
		// their region is absorbed (the listener is the wiring, not decoration).
		this.regionizer.addListener(this);
	}

	/** Starts the coordinator dispatch loop. */
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		coordinator = new Thread(this::coordinateLoop, "Fabric-Folia-Scheduler");
		coordinator.setDaemon(true);
		coordinator.start();
	}

	@Override
	public void close() throws Exception {
		closed = true; // first: every enqueue after this point is rejected
		running.set(false);
		if (coordinator != null) {
			coordinator.join(1000);
		}
		if (ownsPool) {
			pool.shutdown(5000);
		}
		// Drop remaining queued tasks with diagnostics (drain policy: DROP —
		// documented on RegionTaskQueue). Workers are stopped first, so nothing
		// can execute a task after this point.
		int dropped = 0;
		for (Map.Entry<Region, RegionTaskQueue> entry : queuesByRegion.entrySet()) {
			dropped += entry.getValue().dropAll();
		}
		queuesByRegion.clear();
		if (dropped > 0) {
			diagnostics.accept("Fabric-Folia scheduler stopped: dropped " + dropped + " queued tasks");
		}
	}

	// =================================================================================
	// Regionizer.Listener: queue registry lifecycle (see class docs)
	// =================================================================================

	@Override
	public void onRegionMerged(Region donor, Region into) {
		// Runs under the regionizer structure lock (merges happen there), so
		// this is serialized against every other structural transition.
		RegionTaskQueue donorQueue = queuesByRegion.remove(donor);
		if (donorQueue == null) {
			return;
		}
		RegionTaskQueue targetQueue = queuesByRegion.computeIfAbsent(into, r -> new RegionTaskQueue());
		donorQueue.rehomeInto(targetQueue);
	}

	/**
	 * Packs a chunk position into the queue entry's position field. The bit
	 * layout is (z &lt;&lt; 32) | (x & 0xFFFFFFFF) — identical to the vanilla
	 * ChunkPos the fabric module passes in, so entries carry the same long
	 * with no conversion (common must stay Minecraft-free).
	 */
	public static long packChunkPos(int chunkX, int chunkZ) {
		return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
	}

	/** @see #packChunkPos */
	public static int chunkPosX(long packed) {
		return (int) packed;
	}

	/** @see #packChunkPos */
	public static int chunkPosZ(long packed) {
		return (int) (packed >> 32);
	}

	@Override
	public void onRegionSplit(Region parent, java.util.List<Region> children) {
		// Runs under the regionizer structure lock (splits happen there); the
		// parent and children are non-dispatchable for the duration. Redistribute
		// queued work by ownership (mandate §8: a split must "redistribute
		// pending work ... establish valid ownership").
		RegionTaskQueue parentQueue = queuesByRegion.get(parent);
		if (parentQueue == null || parentQueue.size() == 0) {
			return;
		}
		for (Region child : children) {
			queuesByRegion.computeIfAbsent(child, r -> new RegionTaskQueue());
		}
		int moved = parentQueue.partitionOnSplit(chunkPos -> {
			// Resolve the chunk's owner directly from the regionizer's section
			// ownership — the same lock domain as this callback (structure lock
			// is reentrant), so the answer is the post-split truth. Entries whose
			// position has no live post-split owner (purged section) or still
			// belongs to the parent stay put: there is no other region to
			// re-home them to, and a null queue lookup would NPE the partition.
			Region owner = regionizer.ownerOfChunk(chunkPosX(chunkPos), chunkPosZ(chunkPos));
			if (owner == null || owner == parent) {
				return null;
			}
			return queuesByRegion.get(owner);
		});
		if (moved > 0) {
			diagnostics.accept("Region " + parent + " split into " + children.size()
					+ " region(s): re-homed " + moved + " queued task(s) to the owning children");
		}
	}

	@Override
	public void onRegionDead(Region region) {
		// Runs under the regionizer structure lock. DROP policy (spec 16):
		// dead-region tasks are never executed elsewhere — they were scheduled
		// under an ownership contract the dead region no longer provides.
		RegionTaskQueue queue = queuesByRegion.remove(region);
		if (queue != null) {
			int dropped = queue.dropAll();
			if (dropped > 0) {
				diagnostics.accept("Region " + region + " died: dropped " + dropped + " queued task(s)");
			}
		}
	}

	// =================================================================================
	// Public scheduling surface (fabric module adapts the API module onto these)
	// =================================================================================

	/**
	 * Schedules a task to run in the region owning the given chunk position —
	 * creating that region if none exists (Folia's RegionizedTaskQueue
	 * contract, mandate §13). Any thread. Region creation is the regionizer's
	 * own structural operation; enqueue then follows the normal two-phase
	 * death-race protocol against whatever region owns the position by then.
	 *
	 * @return false only when the scheduler is stopped (a newly created region
	 *         is a valid target, so unlike {@link #scheduleToChunk} there is no
	 *         missing-owner failure mode)
	 */
	public boolean scheduleToChunkOrCreate(int chunkX, int chunkZ, Runnable task) {
		Region region = regionizer.addChunk(chunkX, chunkZ);
		return enqueue(region, task, packChunkPos(chunkX, chunkZ));
	}

	/**
	 * Schedules a task to run in the region owning the given chunk position. Any
	 * thread. If no region owns the position, the task is dropped (the caller —
	 * the fabric module — decides policy; the API-level MissingRegionPolicy
	 * lives above this primitive).
	 */
	public boolean scheduleToChunk(int chunkX, int chunkZ, Runnable task) {
		Region region = regionizer.ownerOfChunk(chunkX, chunkZ);
		if (region == null) {
			return false;
		}
		return enqueue(region, task, packChunkPos(chunkX, chunkZ));
	}

	/**
	 * Schedules a task into a specific region object. Any thread.
	 *
	 * <p><strong>Death-race protocol:</strong> enqueue is two-phase. Phase 1
	 * adds the task to the region's current queue (lock-free). Phase 2 re-checks
	 * the region's state <em>under the regionizer structure lock</em> — the same
	 * lock domain in which merges re-home and deaths drop queues — so the check
	 * is ordered against those transitions. If the region died between the
	 * phases, the task is pulled back out (it was already dropped/re-homed with
	 * its queue; letting the stale copy through would double-execute it after a
	 * re-home). The result of phase 2 decides the return value.</p>
	 */
	public boolean enqueue(Region region, Runnable task) {
		return enqueue(region, task, null);
	}

	/**
	 * Schedules a task into a specific region object, recording the chunk
	 * position it targets so a later split can redistribute it to the owning
	 * child (see {@link #onRegionSplit}). Any thread.
	 *
	 * @param targetChunkPos packed chunk position the task targets, or null
	 *        when unknown (entries without a position stay with their region
	 *        through a split — the region whose context they were scheduled
	 *        under is then the best-known owner)
	 */
	public boolean enqueue(Region region, Runnable task, Long targetChunkPos) {
		Objects.requireNonNull(task, "task");
		if (closed) {
			// Post-shutdown: workers are gone, nothing will ever drain this —
			// reject rather than silently strand it. Enqueue BEFORE start() is
			// deliberately legal: the task waits in the region's queue and runs
			// when dispatch begins (world-load code queues ahead of the first
			// tick; tests drive structural transitions pre-start).
			return false;
		}
		RegionTaskQueue queue = queuesByRegion.get(region);
		if (queue == null) {
			queue = queuesByRegion.computeIfAbsent(region, r -> new RegionTaskQueue());
		}
		if (targetChunkPos != null) {
			queue.add(task, targetChunkPos);
		} else {
			queue.add(task);
		}
		boolean dead = regionizer.underStructureLock(region::isDead);
		if (dead) {
			queue.removeTask(task);
			return false;
		}
		return true;
	}

	/**
	 * Schedules a task to run after {@code delayTicks} of the target region's
	 * own tick counter. The delay survives merges (the queue re-homes with its
	 * deadline intact — see {@link Region#tickCount}) and dies with the region
	 * (DROP policy). Any thread.
	 */
	public void enqueueDelayed(Region region, int delayTicks, Runnable task) {
		enqueueDelayed(region, delayTicks, task, null);
	}

	/** Position-aware delayed variant (see {@link #enqueue(Region, Runnable, Long)}). */
	public void enqueueDelayed(Region region, int delayTicks, Runnable task, Long targetChunkPos) {
		if (delayTicks <= 0) {
			enqueue(region, task, targetChunkPos);
			return;
		}
		long target = region.tickCount() + delayTicks;
		enqueue(region, new DelayedRegionTask(target, task), targetChunkPos);
	}

	/** @return the queue registered for a region (engine/diagnostics use). */
	public RegionTaskQueue queueOf(Region region) {
		return queuesByRegion.get(region);
	}

	/** @return live regions known to the regionizer (diagnostics). */
	public List<Region> liveRegions() {
		return regionizer.liveRegions();
	}

	public WorldRegionizer regionizer() {
		return regionizer;
	}

	public ViolationReporter violationReporter() {
		return reporter;
	}

	// =================================================================================
	// Dispatch loop (EDF)
	// =================================================================================

	private void coordinateLoop() {
		ThreadOwnership.clear();
		while (running.get()) {
			long now = nanoClock.getAsLong();
			for (Region region : regionizer.liveRegions()) {
				if (region.state() != RegionState.READY) {
					continue;
				}
				// Deadline 0 = never ticked (region just created): due now.
				if (region.nextTickDeadlineNanos() > now) {
					continue;
				}
				// Duplicate dispatch is made harmless by the two in-job guards
				// documented on the class — no cross-thread bookkeeping needed.
				pool.submit(() -> runRegionTick(region));
			}
			// Coordinator cadence: 1ms scan. Regions whose deadline is further
			// out are skipped cheaply; correctness never depends on scan timing.
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void runRegionTick(Region region) {
		// Guard 1: skip stale duplicates of a still-ticking region.
		if (region.state() != RegionState.READY) {
			return;
		}
		// Guard 2: skip duplicates whose region already completed the tick and
		// holds a future deadline (prevents out-of-cycle early ticks).
		if (region.nextTickDeadlineNanos() > nanoClock.getAsLong()) {
			return;
		}
		// The single-owner latch (invariant 3/4): fails if the region went
		// TRANSIENT between scan and dispatch — the regionizer merges it into
		// its target and the target's tick picks the work up (documented
		// contract, not a race to suppress).
		if (!regionizer.tryBeginTick(region)) {
			return;
		}
		long start = nanoClock.getAsLong();
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(regionInfoOf(region));
		try {
			// The tick has STARTED: advance the counter before draining so the
			// delayed-task time base reads "current tick number" uniformly —
			// whether a task is enqueued mid-tick or between ticks, delay d
			// means "the d-th future tick's drain" (see Region#tickCount).
			region.advanceTickCounter();
			// 1. Drain this region's task queue (tasks scheduled from any
			//    context — spec 7's handoff diagram terminates here). Delayed
			//    tasks whose region tick counter has not reached their deadline
			//    are re-queued (their wait continues in-region), keeping the
			//    entry's target position so a later split still re-homes them.
			RegionTaskQueue queue = queuesByRegion.get(region);
			if (queue != null) {
				for (RegionTaskQueue.Entry entry : queue.drainEntries()) {
					Runnable task = entry.task();
					try {
						if (task instanceof DelayedRegionTask delayed
								&& delayed.targetTick() > region.tickCount()) {
							queue.add(task, entry.chunkPos()); // not due yet
							continue;
						}
						task.run();
					} catch (Throwable t) {
						diagnostics.accept("Region task failed in " + region + ": " + t);
						// Spec 26: failures are reported, never silent. The
						// failure-isolation policy (region vs server halt) is
						// implemented during the gameplay integration phase,
						// when region state mutation actually flows through here.
					}
				}
			}
			// 2. The region tick body (engine hook; vanilla integration later).
			regionTickBody.accept(region);
		} catch (Throwable t) {
			diagnostics.accept("Region tick failed in " + region + ": " + t);
		} finally {
			ThreadOwnership.exit(token);
			// 3. Tick-end protocol under the regionizer lock (merges → transient
			//    check → dead sections → split). Runs on this worker: the region
			//    is not dispatchable until completeTick returns.
			long duration = nanoClock.getAsLong() - start;
			region.recordTickDuration(duration);
			try {
				regionizer.completeTick(region, duration);
			} catch (Throwable t) {
				// Spec 26 fail-safe: a region whose tick-END protocol failed can
				// never be dispatched again safely — abort it (listener drops
				// its queue) and report loudly rather than wedge it as eternal
				// TICKING, which would silently freeze every chunk it owns.
				diagnostics.accept("FATAL: region tick-end protocol failed for " + region
						+ " — region aborted: " + t);
				try {
					regionizer.abortTick(region, t);
				} catch (Throwable abortFailure) {
					diagnostics.accept("FATAL: region abort also failed for " + region + ": " + abortFailure);
				}
			}
		}
	}

	private RegionInfo regionInfoOf(Region region) {
		// Regions implement RegionInfo directly in the engine (the API type is
		// structural: world + id + state).
		return region;
	}

	/** @return how many worker threads the pool has (diagnostics). */
	public int workerCount() {
		return poolThreadCount;
	}

	/**
	 * @return the underlying worker pool — used to attach additional worlds'
	 * schedulers to the SAME bounded pool (multi-world over one worker set,
	 * spec 10). Exposed deliberately narrowly: callers must use the
	 * shared-pool constructor, never submit tasks directly (that would
	 * bypass region ownership and tick-latching).
	 */
	public WorkerPool workerPool() {
		return pool;
	}

	/**
	 * A region task deferred to a future tick of its region, measured in the
	 * region's own tick counter (the time base that survives merges — see
	 * {@link Region#tickCount}). Re-queued at drain until due; runs in the
	 * context of whichever region owns its state when the deadline arrives
	 * (merges re-home the queue, so migration is handled by construction).
	 */
	public record DelayedRegionTask(long targetTick, Runnable delegate) implements Runnable {
		@Override
		public void run() {
			delegate.run();
		}
	}
}
