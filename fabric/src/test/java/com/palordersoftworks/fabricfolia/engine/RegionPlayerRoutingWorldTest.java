package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionPlayerRoutingWorldTest {
    @Test
    void sameNumericRegionIdInDifferentWorldIsNotOwnerMatch() {
        WorldRegionizer overworld = new WorldRegionizer("minecraft:overworld",
                RegionizerConfig.forTests(), new AtomicInteger(1)::getAndIncrement, System::nanoTime);
        WorldRegionizer nether = new WorldRegionizer("minecraft:the_nether",
                RegionizerConfig.forTests(), new AtomicInteger(1)::getAndIncrement, System::nanoTime);
        Region overworldRegion = overworld.addChunk(0, 0);
        Region netherRegion = nether.addChunk(0, 0);
        RegionInfo current = netherRegion;

        assertTrue(overworldRegion.regionId() == netherRegion.regionId());
        assertFalse(RegionPlayerRouting.isWorldQualifiedOwner("minecraft:overworld", overworldRegion, current));
    }

    @Test
    void matchingWorldAndRegionIdIsOwnerMatch() {
        WorldRegionizer overworld = new WorldRegionizer("minecraft:overworld",
                RegionizerConfig.forTests(), new AtomicInteger(1)::getAndIncrement, System::nanoTime);
        Region region = overworld.addChunk(0, 0);
        RegionInfo current = region;

        assertTrue(RegionPlayerRouting.isWorldQualifiedOwner("minecraft:overworld", region, current));
    }
}
