package com.palordersoftworks.fabricfolia.util;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfemCollisionTest {

    @Test
    void fastDiagonalMovementUsesDirectionalQuery() {
        assertTrue(OfemCollision.checkMovement(new Vec3(10.0, 10.0, 10.0)));
    }

    @Test
    void smallAndAxisAlignedMovementUsesVanillaQuery() {
        assertFalse(OfemCollision.checkMovement(new Vec3(0.5, 0.5, 0.5)));
        assertFalse(OfemCollision.checkMovement(new Vec3(20.0, 0.0, 0.0)));
        assertFalse(OfemCollision.checkMovement(new Vec3(10.0, 10.0, 2.0)));
    }

    @Test
    void nonFiniteMovementUsesVanillaQuery() {
        assertFalse(OfemCollision.checkMovement(new Vec3(Double.POSITIVE_INFINITY, 10.0, 10.0)));
        assertFalse(OfemCollision.checkMovement(new Vec3(Double.NaN, 10.0, 10.0)));
    }

    @Test
    void axisSearchIsInflatedAndClippedToVanillaBounds() {
        AABB current = new AABB(10.0, 10.0, 10.0, 11.0, 11.0, 11.0);
        AABB vanilla = new AABB(0.0, 0.0, 0.0, 15.0, 15.0, 15.0);

        AABB result = OfemCollision.axisSearchBox(current, vanilla, Direction.Axis.X, 5.0);

        assertEquals(9.0, result.minX);
        assertEquals(15.0, result.maxX);
        assertEquals(9.0, result.minY);
        assertEquals(12.0, result.maxY);
        assertEquals(9.0, result.minZ);
        assertEquals(12.0, result.maxZ);
    }
}
