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
    void masterSwitchOffResolvesEveryPatchInactive() throws Exception {
        Path file = tempDir.resolve("fabric-folia.yml");
        FoliaConfig defaults = FoliaConfig.at(file);
        defaults.load();
        String yaml = Files.readString(file);
        String disabled = yaml.replaceFirst("(?m)^(  enabled: )true$", "$1false");
        if (disabled.equals(yaml)) {
            throw new AssertionError("could not find general.enabled in the generated config");
        }
        Files.writeString(file, disabled);

        FoliaConfig config = FoliaConfig.at(file);
        config.load();
        config.freeze();
        FabricFoliaMod.declarePatches();
        FabricFoliaMod.resolvePatchStates(config);

        assertFalse(config.enabled());
        assertTrue(PatchRegistry.isResolved());
        assertTrue(PatchRegistry.patches().stream().noneMatch(patch ->
                patch.status() == PatchRegistry.Status.ACTIVE));
        assertFalse(PatchRegistry.isEnabled("entity-collision-opt"));
        assertFalse(PatchRegistry.isEnabled("hard-hitbox-entity-collision"));
        assertFalse(PatchRegistry.isEnabled("regionize-chunk-load"));
        assertFalse(PatchRegistry.isEnabled("packet-dispatch"));
    }

    @Test
    void r4InterlockKeepsWorldMutationStagesOnVanillaEvenWhenRequested() throws Exception {
        Path file = tempDir.resolve("fabric-folia.yml");
        FoliaConfig defaults = FoliaConfig.at(file);
        defaults.load();
        String yaml = Files.readString(file)
                .replace("regionized-gameplay: false", "regionized-gameplay: true")
                .replace("regionized-random-ticks: false", "regionized-random-ticks: true")
                .replace("stage-player-path: false", "stage-player-path: true")
                .replace("entity-tick-optimization: false", "entity-tick-optimization: true")
                .replace("chunk-tick-optimization: false", "chunk-tick-optimization: true")
                .replace("random-tick-optimization: false", "random-tick-optimization: true");
        Files.writeString(file, yaml);

        FoliaConfig config = FoliaConfig.at(file);
        config.load();
        config.freeze();
        FabricFoliaMod.declarePatches();
        FabricFoliaMod.resolvePatchStates(config);

        assertFalse(PatchRegistry.isEnabled("stage-entity-ticks"));
        assertFalse(PatchRegistry.isEnabled("stage-block-entity-ticks"));
        assertFalse(PatchRegistry.isEnabled("stage-scheduled-ticks"));
        assertFalse(PatchRegistry.isEnabled("stage-player-path"));
        assertFalse(PatchRegistry.isEnabled("regionized-random-ticks"));
        assertFalse(PatchRegistry.isEnabled("packet-dispatch"));
        assertTrue(PatchRegistry.isEnabled("regionize-chunk-load"));
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
