/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.HashedStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

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
	void containerInteractionPacketsStayOnTheServerThreadForModCallbacks() {
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundContainerClickPacket(8, 2, (short) 0, (byte) 0, ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundContainerButtonClickPacket(8, 0)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundContainerClosePacket(8)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundSetCreativeModeSlotPacket((short) 0, ItemStack.EMPTY)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP, 0)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false), 0)));
		assertTrue(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0.0f, 0.0f)));
		assertFalse(RegionPlayerRouting.requiresServerThreadForPacket(
				new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS)));
	}

	@Test
	void everyServerThreadPacketRejectsRegionShortcutButPassesOnProcessorThread() {
		var packets = java.util.List.of(
				new ServerboundContainerClickPacket(8, 2, (short) 0, (byte) 0, ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY),
				new ServerboundContainerButtonClickPacket(8, 0),
				new ServerboundContainerClosePacket(8),
				new ServerboundContainerSlotStateChangedPacket(1, 8, true),
				new ServerboundSetCreativeModeSlotPacket((short) 0, ItemStack.EMPTY),
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, BlockPos.ZERO, Direction.UP, 0),
				new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false), 0),
				new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0.0f, 0.0f));
		for (var packet : packets) {
			assertFalse(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, false, true));
			assertTrue(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, true, false));
		}
	}

	@Test
	void respawnRejectsRegionShortcutButPassesOnProcessorThread() {
		ServerboundClientCommandPacket packet = new ServerboundClientCommandPacket(
				ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
		assertFalse(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, false, true));
		assertTrue(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, true, false));
	}

	@Test
	void ordinaryPlayerPacketsMayRunOnTheirOwningRegion() {
		ServerboundClientCommandPacket packet = new ServerboundClientCommandPacket(
				ServerboundClientCommandPacket.Action.REQUEST_STATS);
		assertTrue(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, false, true));
		assertFalse(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, false, false));
		assertTrue(RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, true, false));
	}
}
