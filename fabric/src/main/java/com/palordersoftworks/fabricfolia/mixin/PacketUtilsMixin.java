/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.RegionPlayerRouting;

import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {

	@Redirect(
			method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/PacketProcessor;isSameThread()Z"),
			require = 1)
	private static boolean fabricfolia$ownerRegionPassesCheck(PacketProcessor processorOwner,
	                                                          Packet<?> packet,
	                                                          PacketListener listener,
	                                                          PacketProcessor processor) {
		boolean processorThread = processorOwner.isSameThread();
		boolean ownerRegion = listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gameListener
				&& RegionPlayerRouting.isOwnerRegion(gameListener);
		return RegionPlayerRouting.canHandlePacketOnCurrentThread(packet, processorThread, ownerRegion);
	}
}
