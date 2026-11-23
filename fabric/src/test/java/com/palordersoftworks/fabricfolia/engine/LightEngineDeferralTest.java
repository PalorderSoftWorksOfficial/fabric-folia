package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightEngineDeferralTest {

    @AfterEach
    void cleanup() {
        ThreadOwnership.clear();
    }

    @Test
    void engineOffRunsLightMutationInlineOnNetworkContext() {
        ThreadOwnership.Context previous = ThreadOwnership.enterSide(ThreadContext.Kind.NETWORK);
        AtomicInteger executions = new AtomicInteger();
        try {
            LightEngineDeferral.runMutation(executions::incrementAndGet);
            assertEquals(1, executions.get());
        } finally {
            ThreadOwnership.exit(previous);
        }
    }

    @Test
    void engineOffRunsLightMutationInlineOnUnknownContext() {
        AtomicInteger executions = new AtomicInteger();
        LightEngineDeferral.runMutation(executions::incrementAndGet);
        assertEquals(1, executions.get());
    }
}
