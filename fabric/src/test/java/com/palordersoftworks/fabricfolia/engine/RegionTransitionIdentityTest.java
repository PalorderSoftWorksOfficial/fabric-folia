package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RegionTransitionIdentityTest {
    @Test
    void entityIdsAreQualifiedBySourceWorld() {
        RegionTransitions.EntityTransitionKey overworld =
                new RegionTransitions.EntityTransitionKey("minecraft:overworld", 41);
        RegionTransitions.EntityTransitionKey nether =
                new RegionTransitions.EntityTransitionKey("minecraft:the_nether", 41);

        assertNotEquals(overworld, nether);
    }
}
