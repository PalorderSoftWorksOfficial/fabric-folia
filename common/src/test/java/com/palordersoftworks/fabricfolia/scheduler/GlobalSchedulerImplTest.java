package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.RegionScheduler;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSchedulerImplTest {
    @AfterEach
    void clearContext() {
        ThreadOwnership.clear();
    }

    @Test
    void taskRunsOnTickCallerInGlobalContext() {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> executedOn = new AtomicReference<>();
        AtomicReference<ThreadContext.Kind> kind = new AtomicReference<>();
        scheduler.run(() -> {
            executedOn.set(Thread.currentThread());
            kind.set(ThreadOwnership.current().kind());
        });
        scheduler.tick();
        assertEquals(caller, executedOn.get());
        assertEquals(ThreadContext.Kind.GLOBAL, kind.get());
        assertEquals(ThreadContext.Kind.UNKNOWN, ThreadOwnership.current().kind());
        scheduler.close();
    }

    @Test
    void delayedTasksUseServerTickCounter() {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        AtomicInteger executions = new AtomicInteger();
        scheduler.runDelayed(2, executions::incrementAndGet);
        scheduler.tick();
        assertEquals(0, executions.get());
        scheduler.tick();
        assertEquals(1, executions.get());
        scheduler.close();
    }

    @Test
    void repeatingTaskCanBeCancelled() {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        AtomicInteger executions = new AtomicInteger();
        RegionScheduler.CancelHandle handle = scheduler.runAtFixedRate(1, 2, executions::incrementAndGet);
        scheduler.tick();
        assertEquals(1, executions.get());
        scheduler.tick();
        assertEquals(1, executions.get());
        scheduler.tick();
        assertEquals(2, executions.get());
        handle.cancel();
        handle.cancel();
        scheduler.tick();
        scheduler.tick();
        assertEquals(2, executions.get());
        scheduler.close();
    }

    @Test
    void crossThreadSubmissionIsVisibleToNextServerTick() throws Exception {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        Thread producer = new Thread(() -> {
            scheduler.run(executions::incrementAndGet);
            submitted.countDown();
        });
        producer.start();
        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        producer.join(5000);
        scheduler.tick();
        assertEquals(1, executions.get());
        scheduler.close();
    }

    @Test
    void submissionsAreRejectedAfterClose() {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        scheduler.close();
        assertThrows(RejectedExecutionException.class, () -> scheduler.run(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> scheduler.runDelayed(1, () -> { }));
        assertThrows(RejectedExecutionException.class, () -> scheduler.runAtFixedRate(1, 1, () -> { }));
        assertThrows(RejectedExecutionException.class, () -> scheduler.runOrSchedule(() -> { }));
    }

    @Test
    void pendingTasksCanBeDrainedAfterClose() {
        GlobalSchedulerImpl scheduler = new GlobalSchedulerImpl();
        AtomicInteger executions = new AtomicInteger();
        scheduler.run(executions::incrementAndGet);
        scheduler.close();
        scheduler.dispatchPending();
        assertEquals(1, executions.get());
    }
}
