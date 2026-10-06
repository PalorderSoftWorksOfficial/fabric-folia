package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded drain (DRAIN_MAX_ENTRIES / DRAIN_BUDGET_NANOS): a region tick's
 * drain must always terminate even against a sustained producer. The previous
 * drain-until-empty loop could never return while the end-of-tick flush kept
 * enqueuing — the region latched TICKING forever, its queue grew without
 * bound, and the heap OOMed at 28.1M queued bodies (reproduced live,
 * 2026-10-05).
 */
class RegionDrainBudgetTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static RegionScheduler newScheduler(WorldRegionizer rz, int workers) {
		return new RegionScheduler(rz, workers, ValidationMode.OFF,
				region -> { },
				message -> { });
	}

	@Test
	void boundedDrainDeliversEveryTaskExactlyOnce() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		AtomicInteger executed = new AtomicInteger();

		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			int total = RegionScheduler.DRAIN_MAX_ENTRIES + 1_024;
			for (int i = 0; i < total; i++) {
				assertTrue(scheduler.enqueue(region, executed::incrementAndGet));
			}
			scheduler.start();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && executed.get() < total) {
				Thread.sleep(10);
			}
			// Exactly once: no loss across the bounded per-tick drain boundary,
			// no double delivery from re-queued remainders.
			assertEquals(total, executed.get(),
					"bounded drain must deliver every queued task exactly once");
		}
	}

	@Test
	void sustainedProducerCannotLatchRegionTicking() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);

		try (RegionScheduler scheduler = newScheduler(rz, 1)) {
			scheduler.start();

			// The wedge shape: every executed task enqueues its successor, so
			// the queue NEVER reads empty. The old drain-until-empty loop would
			// spin inside the drain forever (region latched TICKING, watchdog
			// spam, queue growth). The bounded drain must still complete ticks.
			AtomicBoolean producing = new AtomicBoolean(true);
			AtomicReference<Runnable> self = new AtomicReference<>();
			self.set(() -> {
				if (producing.get()) {
					scheduler.enqueue(region, self.get());
				}
			});
			assertTrue(scheduler.enqueue(region, self.get()));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && region.tickCount() < 3) {
				Thread.sleep(20);
			}
			long ticksSeen = region.tickCount();
			assertTrue(ticksSeen >= 3,
					"region must keep ticking under sustained production (tickCount="
							+ ticksSeen + ") — drain-until-empty wedge regression");

			// Let it run a few more ticks: the tick-end protocol keeps firing
			// (completeTick re-arms the deadline) instead of latching TICKING.
			Thread.sleep(300);
			assertTrue(region.tickCount() > ticksSeen,
					"region stopped ticking while the producer kept enqueueing");
			producing.set(false);
		}
	}
}
