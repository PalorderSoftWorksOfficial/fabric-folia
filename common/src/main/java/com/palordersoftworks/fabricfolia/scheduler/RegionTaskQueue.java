/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A region's task queue: tasks scheduled INTO this region (from any context)
 * that run during the region's tick (spec 7's handoff diagram).
 *
 * <p><strong>Semantics:</strong></p>
 * <ul>
 *   <li><strong>Multi-producer:</strong> any thread may enqueue — another
 *       region's tick context, the global context, a network thread handing
 *       off packet work. Producers therefore need a concurrency-safe queue
 *       structure; {@link ConcurrentLinkedQueue} provides lock-free,
 *       corruption-free enqueue with safe publication of the enqueued task.
 *       (Spec 18 review note: the concurrent queue exists because multiple
 *       producers are LEGAL, not to make cross-region mutation safe.)</li>
 *   <li><strong>Single-consumer (the ownership rule):</strong> ONLY the
 *       region's owning execution context dequeues — the drain happens inside
 *       that region's tick, after {@code tryBeginTick} succeeded. The queue
 *       structure is not the safety mechanism (spec 3/18): ownership is.</li>
 *   <li><strong>Drain policy at region death (spec 16):</strong> tasks are
 *       DROPPED with a diagnostic, or re-homed into the absorbing region on
 *       merge (see {@link #rehomeInto}); they are never executed on a context
 *       that does not own the state they target.</li>
 * </ul>
 *
 * <p><strong>State classification (spec 4):</strong> REGION-LOCAL while owned;
 * the queue is re-homed wholesale on region merge (no task-level migration,
 * so no task can observe a half-merged queue).</p>
 */
public final class RegionTaskQueue {
	/**
	 * One queued unit of work: the task plus the position it targets (when
	 * known). The position is what makes split redistribution correct: during
	 * a split, tasks re-home to whichever child owns their target chunk —
	 * without it, children would start with stranded ownership (mandate §8:
	 * "redistribute region-local data"; spec 16 groundwork).
	 */
	public record Entry(Runnable task, long chunkPos) {
		public static final long NO_POSITION = Long.MIN_VALUE;
	}

	private final Queue<Entry> tasks = new ConcurrentLinkedQueue<>();

	/**
	 * Enqueues a task. Called from any thread — the ONLY entry point from
	 * foreign contexts. Death-race recovery (removing a task whose region died
	 * between add and the caller's post-add re-check) goes through		 * {@link #removeTask}; no dead-region pre-check here — the scheduler owns
	 * that protocol.
	 */
	public void add(Runnable task) {
		tasks.add(new Entry(task, Entry.NO_POSITION));
	}

	/** Enqueues a task that targets a specific chunk position (split-aware). */
	public void add(Runnable task, long chunkPos) {
		tasks.add(new Entry(task, chunkPos));
	}

	/**
	 * Removes ONE occurrence of the entry containing {@code task} (identity
	 * comparison on the runnable) — the enqueue-side recovery primitive for
	 * the region-died-mid-enqueue race.
	 *
	 * @return true if this queue held and removed the task
	 */
	public boolean removeTask(Runnable task) {
		for (java.util.Iterator<Entry> it = tasks.iterator(); it.hasNext(); ) {
			if (it.next().task() == task) {
				it.remove();
				return true;
			}
		}
		return false;
	}

	/** @return and removes all pending tasks; called only by the owning context. */
	public List<Runnable> drain() {
		List<Runnable> out = new ArrayList<>();
		for (Entry entry : drainEntries()) {
			out.add(entry.task());
		}
		return out;
	}

	/**
	 * @return and removes all pending entries (task + target position); called
	 *         only by the owning context. The scheduler uses this so delayed
		 *         re-queues keep their position for later split redistribution.
	 */
	public List<Entry> drainEntries() {
		if (tasks.isEmpty()) {
			return List.of();
		}
		List<Entry> out = new ArrayList<>();
		Entry entry;
		while ((entry = tasks.poll()) != null) {
			out.add(entry);
		}
		return out;
	}

	/** @return number of pending tasks (diagnostics; O(n) on this structure). */
	public int size() {
		return tasks.size();
	}

	/**
	 * Moves every pending task to {@code target}'s queue during a region merge.
	 * Task runnables are context-independent closures over positions and game
	 * state — they do not capture the source region's identity — so moving them
	 * wholesale preserves their semantics: they execute in the merged region's
	 * context, which now owns the state they target.
	 */
	public void rehomeInto(RegionTaskQueue target) {
		Entry entry;
		while ((entry = tasks.poll()) != null) {
			target.tasks.add(entry);
		}
	}

	/** Drops all tasks at region death (drain policy: DROP — see class docs). */
	public int dropAll() {
		int n = 0;
		while (tasks.poll() != null) {
			n++;
		}
		return n;
	}

	/**
	 * Splits this (parent) queue during a region split: entries whose target
	 * chunk position is owned by a split child move to that child's queue;
	 * everything else (no position, or a position still owned by the parent)
	 * stays. Called by the scheduler's split listener under the regionizer
	 * structure lock — parent and children are non-dispatchable for the
	 * duration, so one pass is correct.
	 *
	 * @param childQueueForChunkPos maps a chunk position to the split child
	 *                              that owns it, or null when the parent still owns it
	 * @return the number of entries moved out of this queue
	 */
	public int partitionOnSplit(java.util.function.LongFunction<RegionTaskQueue> childQueueForChunkPos) {
		int moved = 0;
		for (java.util.Iterator<Entry> it = tasks.iterator(); it.hasNext(); ) {
			Entry entry = it.next();
			if (entry.chunkPos() == Entry.NO_POSITION) {
				continue;
			}
			RegionTaskQueue childQueue = childQueueForChunkPos.apply(entry.chunkPos());
			if (childQueue != null) {
				it.remove();
				childQueue.tasks.add(entry);
				moved++;
			}
		}
		return moved;
	}
}
