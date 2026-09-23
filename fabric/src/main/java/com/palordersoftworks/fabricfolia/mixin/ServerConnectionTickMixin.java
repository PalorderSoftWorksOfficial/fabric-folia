/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.RegionPlayerRouting;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player connection capture (mandate §11): routes each live game
 * connection's per-tick body — packet drain, {@code SGPLI.tick} (with its
 * {@code doTick} physics chain), outbound flush — into the staging hub's
 * PLAYER slice for execution on the region owning the player's chunk.
 *
 * <p><strong>The seam:</strong> vanilla's server tick calls
 * {@code MinecraftServer.tickConnection()} AFTER all level ticks, which
 * calls {@code ServerConnectionListener.tick()}: inside a synchronized
 * block it iterates the connection list and calls {@code Connection.tick()}
 * for every live connection (skipping connecting ones). The redirect hands
 * the per-connection body — vanilla's own method invocation, not a
 * reimplementation — to {@link RegionPlayerRouting}, which stages it into
 * the hub. The synchronized iteration itself stays vanilla on the server
 * thread (single decision point; no forked iteration logic).</p>
 *
 * <p><strong>Why the whole connection body:</strong> the physics chain
 * ({@code Player.tick} movement/collisions) is driven by
 * {@code SGPLI.tickPlayer} → {@code ServerPlayer.doTick()} INSIDE the
 * connection tick — {@code ServerPlayer.tick()} (the entity pass) does not
 * call the physics super-chain (bytecode-verified on 26.2). Staging the
 * connection body keeps packet handling, connection tick, and physics on
 * the ONE region owning the player: a player's state machine is owned by
 * exactly one context per tick, serialized with that region's other work
 * and parallel across regions.</p>
 *
 * <p><strong>Ordering:</strong> the hub flushes the PLAYER slice after the
 * ENTITY slice (declaration order mirrors vanilla's intra-tick pass order:
 * entity pass, then {@code tickConnection}), and every staged body executes
 * on the region queue inside the REGION context — so the
 * {@code ensureRunningOnSameThread} re-home check (redirected by
 * {@link PacketUtilsMixin}) passes on the owning region and handlers drain
 * in place, in queue order.</p>
 *
 * <p><strong>Not staged:</strong> connecting connections (login/config
 * handshake — vanilla skips them), memory connections (in-process
 * clients), pre-play listeners, disconnected/dead connections, and players
 * whose chunks are unowned or whose 3×3 chunk neighborhood is not fully
 * loaded — all fall through to vanilla's server-thread execution
 * unchanged (fallbacks stay whole: the packet re-home route consults the
 * same gate, so a fallback player is never split across contexts).</p>
 */
@Mixin(net.minecraft.server.network.ServerConnectionListener.class)
public abstract class ServerConnectionTickMixin {

	@Redirect(
			method = "tick()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/Connection;tick()V"),
			require = 1)
	private void fabricfolia$stageConnectionBody(Connection connection) {
		if (RegionPlayerRouting.stageConnectionTick(connection, connection::tick)) {
			return;
		}
		connection.tick();
	}
}
