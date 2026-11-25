package com.palordersoftworks.fabricfolia.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigReloadPlanTest {

    @Test
    void classifiesOnlyImplementedRuntimeControlsAsLive() {
        ConfigReloadPlan.Plan plan = ConfigReloadPlan.classify(Set.of(
                ConfigSchema.KEY_SERVER_BRAND,
                ConfigSchema.KEY_SERVER_BRAND_NAME,
                ConfigSchema.KEY_DEBUG,
                ConfigSchema.KEY_DEBUG_LOGGING,
                ConfigSchema.KEY_WATCHDOG,
                ConfigSchema.KEY_ENABLED,
                ConfigSchema.KEY_WORKER_THREADS,
                ConfigSchema.KEY_THREAD_CHECK,
                ConfigSchema.KEY_PATCHES_ENABLED
        ));

        assertEquals(Set.of(
                ConfigSchema.KEY_SERVER_BRAND,
                ConfigSchema.KEY_SERVER_BRAND_NAME,
                ConfigSchema.KEY_DEBUG,
                ConfigSchema.KEY_DEBUG_LOGGING,
                ConfigSchema.KEY_WATCHDOG
        ), plan.live());
        assertEquals(Set.of(
                ConfigSchema.KEY_ENABLED,
                ConfigSchema.KEY_WORKER_THREADS,
                ConfigSchema.KEY_THREAD_CHECK,
                ConfigSchema.KEY_PATCHES_ENABLED
        ), plan.restartRequired());
    }

    @Test
    void unknownFutureSettingDefaultsToRestartRequired() {
        ConfigReloadPlan.Plan plan = ConfigReloadPlan.classify(Set.of("future.experimental-setting"));

        assertEquals(Set.of(), plan.live());
        assertEquals(Set.of("future.experimental-setting"), plan.restartRequired());
    }
}
