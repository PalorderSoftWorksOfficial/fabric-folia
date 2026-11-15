package com.palordersoftworks.fabricfolia;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricFoliaPatchConfigTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetRegistry() {
        PatchRegistry.resetForTests();
    }

    @Test
    void chunkRegionizationAndRegionLookupUseIndependentConfigKeys() throws Exception {
        Path file = tempDir.resolve("fabric-folia.yml");
        FoliaConfig defaults = FoliaConfig.at(file);
        defaults.load();
        String yaml = Files.readString(file);
        yaml = yaml.replace("chunk-regionization: true", "chunk-regionization: false");
        Files.writeString(file, yaml);

        FoliaConfig config = FoliaConfig.at(file);
        config.load();
        config.freeze();
        FabricFoliaMod.declarePatches();
        FabricFoliaMod.resolvePatchStates(config);

        assertFalse(PatchRegistry.isEnabled("regionize-chunk-load"));
        assertTrue(PatchRegistry.isEnabled("region-lookup"));
        assertTrue(PatchRegistry.isEnabled("scheduler-dispatch"));
    }
}
