package com.palordersoftworks.fabricfolia.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigSchemaPatchTest {
    @Test
    void chunkRegionizationHasItsOwnDefaultEnabledToggle() {
        ConfigSchema.Option<?> option = ConfigSchema.get().option(ConfigSchema.KEY_PATCH_CHUNK_REGIONIZATION);
        assertEquals(Boolean.class, option.type());
        assertEquals(Boolean.TRUE, option.defaultValue());
    }

    @Test
    void unsupportedEventDispatchToggleIsNotAdvertised() {
        assertNull(ConfigSchema.get().option("patches.fabric.event-dispatch"));
    }

    @Test
    void gameplayAndPlayerStagingAreOptInByDefault() {
        assertEquals(Boolean.FALSE, ConfigSchema.get().option(ConfigSchema.KEY_GAMEPLAY).defaultValue());
        assertEquals(Boolean.FALSE, ConfigSchema.get().option(ConfigSchema.KEY_PLAYER_PATH).defaultValue());
    }

}
