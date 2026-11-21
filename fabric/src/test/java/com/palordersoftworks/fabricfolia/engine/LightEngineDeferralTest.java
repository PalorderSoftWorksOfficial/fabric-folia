package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightEngineDeferralTest {

    @BeforeEach
    void setServerThread() {
        ServerThreadDeferral.noteServerThread(Thread.currentThread());
        ServerThreadDeferral.clearAll();
    }

    @AfterEach
    void clear() {
        ServerThreadDeferral.clearAll();
        ServerThreadDeferral.noteServerThread(Thread.currentThread());
    }

    @Test
    void serverThreadMutationsRunImmediately() {
        List<Integer> order = new ArrayList<>();
        LightEngineDeferral.runMutation(() -> order.add(1));
        assertEquals(List.of(1), order);
        assertEquals(0, ServerThreadDeferral.pendingCount());
    }

    @Test
    void workerMutationsDeferAndReplayInSubmissionOrder() throws InterruptedException {
        List<Integer> order = new ArrayList<>();
        Thread worker = new Thread(() -> {
            LightEngineDeferral.runMutation(() -> order.add(1));
            LightEngineDeferral.runMutation(() -> order.add(2));
        });
        worker.start();
        worker.join();

        assertEquals(List.of(), order);
        assertEquals(2, ServerThreadDeferral.pendingCount());

        assertEquals(2, ServerThreadDeferral.drainAll());
        assertEquals(List.of(1, 2), order);
    }
}
