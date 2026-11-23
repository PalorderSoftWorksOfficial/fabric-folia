package com.palordersoftworks.fabricfolia.engine;

import java.util.concurrent.atomic.AtomicLong;

public final class RegionChunkAccessGuard {

    private static final AtomicLong REJECTED_SYNCHRONOUS_LOADS = new AtomicLong();

    private RegionChunkAccessGuard() {
    }

    public static <T> T resolveRegionLookup(T loaded, boolean create, int chunkX, int chunkZ, String status) {
        if (loaded != null || !create) {
            return loaded;
        }
        long attempt = REJECTED_SYNCHRONOUS_LOADS.incrementAndGet();
        throw new IllegalStateException("Synchronous chunk load rejected on a region worker for chunk "
                + chunkX + "," + chunkZ + " at status " + status + " (rejected request " + attempt
                + "). Perform the load on the server thread or use an asynchronous chunk API.");
    }

    public static long rejectedSynchronousLoads() {
        return REJECTED_SYNCHRONOUS_LOADS.get();
    }
}
