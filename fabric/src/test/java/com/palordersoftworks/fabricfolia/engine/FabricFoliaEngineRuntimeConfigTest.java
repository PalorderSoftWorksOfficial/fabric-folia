package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricFoliaEngineRuntimeConfigTest {

    @Test
    void appliesWatchdogAndDebugSettingsWithoutChangingStartupConfig(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fabric-folia.yml");
        FoliaConfig template = FoliaConfig.at(file);
        template.load();
        Files.writeString(file, Files.readString(file)
                .replace("worker-threads: -1", "worker-threads: 2"));

        FoliaConfig startup = FoliaConfig.at(file);
        startup.load();
        startup.freeze();
        FabricFoliaEngine engine = FabricFoliaEngine.bootstrap(startup, ignored -> { }, ignored -> { });
        try {
            assertTrue(engine.watchdogRunning());
            assertFalse(engine.runtimeConfig().debugLogging());

            Files.writeString(file, Files.readString(file)
                    .replace("watchdog: true", "watchdog: false")
                    .replace("debug-logging: false", "debug-logging: true")
                    .replace("worker-threads: 2", "worker-threads: 6"));
            FoliaConfig next = FoliaConfig.at(file);
            next.load();
            next.freeze();

            engine.applyRuntimeConfig(next);

            assertFalse(engine.watchdogRunning());
            assertTrue(engine.runtimeConfig().debugLogging());
            assertFalse(engine.config().debugLogging());
            assertTrue(engine.config().workerThreads() == 2);
            assertTrue(engine.runtimeConfig().workerThreads() == 6);

            Files.writeString(file, Files.readString(file)
                    .replace("watchdog: false", "watchdog: true"));
            FoliaConfig restored = FoliaConfig.at(file);
            restored.load();
            restored.freeze();
            engine.applyRuntimeConfig(restored);

            assertTrue(engine.watchdogRunning());
        } finally {
            engine.shutdown(5000);
        }
    }
}
