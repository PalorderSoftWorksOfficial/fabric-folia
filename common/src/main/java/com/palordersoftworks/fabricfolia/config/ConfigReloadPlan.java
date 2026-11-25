package com.palordersoftworks.fabricfolia.config;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public final class ConfigReloadPlan {
    private static final Set<String> LIVE_KEYS = Set.of(
            ConfigSchema.KEY_SERVER_BRAND,
            ConfigSchema.KEY_SERVER_BRAND_NAME,
            ConfigSchema.KEY_DEBUG,
            ConfigSchema.KEY_DEBUG_LOGGING,
            ConfigSchema.KEY_WATCHDOG
    );

    private ConfigReloadPlan() {
    }

    public static Plan classify(Set<String> changedKeys) {
        Set<String> live = new TreeSet<>();
        Set<String> restart = new TreeSet<>();
        for (String key : changedKeys) {
            if (LIVE_KEYS.contains(key)) {
                live.add(key);
            } else {
                restart.add(key);
            }
        }
        return new Plan(live, restart);
    }

    public record Plan(Set<String> live, Set<String> restartRequired) {
        public Plan {
            live = Collections.unmodifiableSet(new TreeSet<>(live));
            restartRequired = Collections.unmodifiableSet(new TreeSet<>(restartRequired));
        }
    }
}
