/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The respawn packet is the one game command deliberately excluded from
 * owner-region execution: it must use vanilla's server-thread packet queue
 * while the rest of the player path remains region-owned.
 */
class PlayerRespawnRoutingTest {

	@Test
	void onlyPerformRespawnIsClassifiedForTheServerThread() {
		assertTrue(RegionPlayerRouting.isRespawnCommand(
				new ServerboundClientCommandPacket(
						ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)));
		assertFalse(RegionPlayerRouting.isRespawnCommand(
				new ServerboundClientCommandPacket(
						ServerboundClientCommandPacket.Action.REQUEST_STATS)));
		assertFalse(RegionPlayerRouting.isRespawnCommand(
				new ServerboundClientCommandPacket(
						ServerboundClientCommandPacket.Action.REQUEST_GAMERULE_VALUES)));
	}

	@Test
	void classifierDoesNotClaimTheServerThreadWithoutTheRegionCarveout() {
		// The routing predicate also requires a live engine, the player-path
		// gate, and REGION context. With no engine, even the respawn packet
		// must not claim that it is being re-homed.
		ServerboundClientCommandPacket packet = new ServerboundClientCommandPacket(
				ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
		assertFalse(RegionPlayerRouting.requiresServerThreadForRespawn(packet));
	}
}
