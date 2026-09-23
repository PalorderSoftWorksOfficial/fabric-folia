/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.RegionPlayerRouting;

import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The packet re-home queue's region leg (mandate §11). Vanilla's
 * {@code PacketProcessor.scheduleIfPossible} is the single funnel a
 * tick-sensitive packet handler passes through when
 * {@code ensureRunningOnSameThread} decides the packet must move off its
 * current thread (the thrown {@code RunningOnDifferentThreadException} is
 * swallowed by {@code Connection} — vanilla's silent re-home contract).
 *
 * <p>This capture routes that re-home to the region owning the listener's
 * player when one exists (game-phase listener, staging enabled, owned
 * chunks, enqueue accepted), so the handler executes on the same owner as
 * the staged connection tick — behind it on the same region queue,
 * preserving per-player ordering. Vanilla's shared server-thread queue is
 * the fallback in every other case: non-player listeners (login, config,
 * status), unowned chunks, dead/closed regions, engine down. The thrown
 * exception and the catch in {@code Connection.channelRead0} are vanilla's
 * own — the packet flow outside the funnel is untouched.</p>
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorMixin {

	@Inject(method = "scheduleIfPossible(Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/protocol/Packet;)V",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$routeRehomeToOwner(PacketListener listener,
	                                            Packet<?> packet, CallbackInfo ci) {
		if (RegionPlayerRouting.schedulePacketToRegion(listener, packet)) {
			ci.cancel();
		}
	}
}
