package com.palordersoftworks.fabricfolia;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricFoliaConfigReloadTest {

    private final FoliaConfig savedStartup = FabricFoliaMod.config;
    private final FoliaConfig savedRuntime = FabricFoliaMod.runtimeConfig;

    @AfterEach
    void restore() {
        FabricFoliaMod.config = savedStartup;
        FabricFoliaMod.runtimeConfig = savedRuntime;
        com.palordersoftworks.fabricfolia.console.Console.setDebugEnabled(
                savedRuntime != null && (savedRuntime.debug() || savedRuntime.debugLogging()));
    }

    @Test
    void engineDisabledKeepsBrandRestartRequiredWhileApplyingDebugLive() throws Exception {
        assertNull(FabricFoliaMod.engine());

        Path dir = Files.createTempDirectory("folia-reload-engine-disabled");
        Path file = dir.resolve("fabric-folia.yml");
        Files.writeString(file, """
                general:
                  enabled: false
                  server-brand: true
                  server-brand-name: "startup-brand"
                diagnostics:
                  debug: false
                  debug-logging: false
                  watchdog: true
                """);
        FoliaConfig startup = FoliaConfig.at(file);
        startup.load();
        startup.freeze();
        FabricFoliaMod.config = startup;
        FabricFoliaMod.runtimeConfig = startup;

        Files.writeString(file, Files.readString(file)
                .replace("server-brand-name: \"startup-brand\"", "server-brand-name: \"reloaded-brand\"")
                .replace("debug-logging: false", "debug-logging: true")
                .replace("watchdog: true", "watchdog: false"));

        FabricFoliaMod.ConfigReloadResult result = FabricFoliaMod.reloadConfiguration();

        assertSame(startup, FabricFoliaMod.config());
        assertEquals("reloaded-brand", FabricFoliaMod.runtimeConfig().serverBrandName());
        assertTrue(result.liveApplied().contains("diagnostics.debug-logging"));
        assertFalse(result.liveApplied().contains("diagnostics.watchdog"));
        assertTrue(result.restartRequired().contains("general.server-brand-name"));
        assertTrue(result.restartRequired().contains("diagnostics.watchdog"));
        assertFalse(result.restartRequired().contains("diagnostics.debug-logging"));
    }
}
