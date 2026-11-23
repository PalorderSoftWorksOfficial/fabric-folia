package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionChunkAccessGuardTest {

    @Test
    void alreadyLoadedChunkIsReturnedWithoutSchedulingAWait() {
        Object chunk = new Object();
        assertSame(chunk, RegionChunkAccessGuard.resolveRegionLookup(chunk, true, 4, -8, "FULL"));
    }

    @Test
    void missingNonCreatingLookupReturnsTheVanillaNullResult() {
        assertNull(RegionChunkAccessGuard.resolveRegionLookup(null, false, 4, -8, "FULL"));
    }

    @Test
    void missingCreatingLookupFailsFastAndIsCounted() {
        long before = RegionChunkAccessGuard.rejectedSynchronousLoads();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RegionChunkAccessGuard.resolveRegionLookup(null, true, 4, -8, "FULL"));
        assertTrue(error.getMessage().contains("4,-8"));
        assertTrue(error.getMessage().contains("server thread"));
        assertEquals(before + 1, RegionChunkAccessGuard.rejectedSynchronousLoads());
    }
}
