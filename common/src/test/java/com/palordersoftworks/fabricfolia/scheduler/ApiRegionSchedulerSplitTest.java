package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.RegionScheduler.MissingRegionPolicy;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRegionSchedulerSplitTest {

    @AfterEach
    void clearContext() {
        ThreadOwnership.clear();
    }

    @Test
    void voidSchedulingMethodsRejectWhenTheEngineIsClosed() throws Exception {
        RegionizerConfig config = new RegionizerConfig(2, 1, 2, 4, 10);
        WorldRegionizer regionizer = new WorldRegionizer("test:world", config,
                new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement, System::nanoTime);
        regionizer.addChunk(0, 0);
        RegionScheduler engine = new RegionScheduler(regionizer, 1,
                com.palordersoftworks.fabricfolia.api.ValidationMode.STRICT, region -> { }, message -> { });
        com.palordersoftworks.fabricfolia.api.RegionScheduler api = new ApiRegionScheduler(
                engine, regionizer, MissingRegionPolicy.DROP, null);
        com.palordersoftworks.fabricfolia.api.RegionScheduler.Position target =
                com.palordersoftworks.fabricfolia.api.RegionScheduler.Position.of("test:world", 0, 0);
        engine.close();

        org.junit.jupiter.api.Assertions.assertThrows(RejectedExecutionException.class,
                () -> api.run(target, () -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(RejectedExecutionException.class,
                () -> api.runDelayed(target, 1, () -> { }));
        assertFalse(api.send(target, () -> { }));
    }

    @Test
    void scheduledApiTasksFollowTheirTargetChunkAcrossARegionSplit() throws Exception {
        RegionizerConfig config = new RegionizerConfig(2, 1, 2, 4, 10);
        WorldRegionizer regionizer = new WorldRegionizer("test:world", config,
                new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement, System::nanoTime);
        for (int x = 0; x <= 33; x++) {
            regionizer.addChunk(x, 0);
        }
        Region parent = regionizer.ownerOfChunk(29, 0);

        try (RegionScheduler engine = new RegionScheduler(regionizer, 2,
                com.palordersoftworks.fabricfolia.api.ValidationMode.STRICT, region -> { }, message -> { })) {
            com.palordersoftworks.fabricfolia.api.RegionScheduler api = new ApiRegionScheduler(
                    engine, regionizer, MissingRegionPolicy.DROP, null);
            com.palordersoftworks.fabricfolia.api.RegionScheduler.Position target =
                    com.palordersoftworks.fabricfolia.api.RegionScheduler.Position.of("test:world", 29, 0);
            CountDownLatch completed = new CountDownLatch(5);
            List<Region> executionOwners = new CopyOnWriteArrayList<>();
            Runnable record = () -> {
                executionOwners.add((Region) ThreadOwnership.currentRegion().orElseThrow());
                completed.countDown();
            };
            AtomicReference<com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle> repeating =
                    new AtomicReference<>();

            api.run(target, record);
            api.runDelayed(target, 1, record);
            api.runOrSchedule(target, record);
            assertTrue(api.send(target, record));
            repeating.set(api.runAtFixedRate(target, 1, 100, () -> {
                record.run();
                repeating.get().cancel();
            }));

            for (int x = 10; x <= 23; x++) {
                regionizer.removeChunk(x, 0);
            }
            assertTrue(regionizer.tryBeginTick(parent));
            regionizer.completeTick(parent, 1);

            Region targetOwner = regionizer.ownerOfChunk(33, 0);
            assertNotNull(targetOwner);
            assertTrue(parent != targetOwner);

            engine.start();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(5, executionOwners.size());
            assertTrue(executionOwners.stream().allMatch(owner -> owner == targetOwner),
                    "all API scheduler variants must be redistributed to the child owning the target chunk");
        }
    }
}
