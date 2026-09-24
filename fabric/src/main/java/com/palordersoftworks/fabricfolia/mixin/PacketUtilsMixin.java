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

/**
 * The re-home check's region leg (mandate §11).
 *
 * <p>{@code PacketUtils.ensureRunningOnSameThread(packet, listener,
 * processor)} is the gate at the head of every tick-sensitive packet
 * handler: it throws {@code RunningOnDifferentThreadException} when
 * {@code processor.isSameThread()} is false, re-scheduling the handler via
 * the processor. When a handler is ALREADY executing on the region that
 * owns the listener's player — drained from the region queue by the staged
 * connection tick or routed there by the scheduleIfPossible capture — the
 * check must pass, or the handler would ping-pong back to the server
 * thread (defeating the ownership model and serializing every player).</p>
 *
 * <p>This redirect consults the routing layer first: on an owning-region
 * worker the check passes without consulting the processor (no throw, no
 * re-schedule — the handler runs in place on its region). The one deliberate
 * exception is {@code ServerboundClientCommandPacket.PERFORM_RESPAWN}: its
 * handler enters {@code PlayerList.respawn}, whose replacement-player and
 * global-list mutations must run on the server thread, so the owner-region
 * pass is rejected and vanilla's re-home queue handles it. Every other case
 * falls through to vanilla's exact comparison ({@code
 * Thread.currentThread() == processor.runningThread}), preserving
 * single-thread semantics for the server thread, event loops, login/config
 * handlers, and unowned players byte-for-byte.</p>
 *
 * <p><strong>Thread discipline:</strong> the routing check is the same
 * lock-free ownerOfChunk read the schedulers use; the redirect runs at
 * handler head on whatever thread the handler is on (server thread, event
 * loop, region worker).</p>
 */
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
		if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl
				&& RegionPlayerRouting.requiresServerThreadForRespawn(packet)) {
			// Deliberately fail the owner-region shortcut for respawn. The
			// following PacketProcessorMixin call deliberately falls through
			// to vanilla's queue, which MinecraftServer drains on its thread.
			return false;
		}
		if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gameListener
				&& RegionPlayerRouting.isOwnerRegion(gameListener)) {
			return true; // on the region owning this player: run in place
		}
		return processorOwner.isSameThread();
	}
}
