/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player physics path joins region staging (mandate §11).
 *
 * <p><strong>Where the player body enters region ownership:</strong> two
 * surfaces join the staged pipeline. The entity pass funnels every entity
 * — players included — through {@code guardEntityTick} on the server
 * thread, and the connection tick's per-connection body (which drives
 * {@code SGPLI.tick} → {@code ServerPlayer.doTick()} → the physics
 * super-chain) is staged by {@code ServerConnectionTickMixin} into the hub's
 * PLAYER slice. In both, the player's work executes on the region worker
 * owning the player's chunk, serialized with that region's other work.
 * Within a tick the entity pass runs before {@code tickConnection}, and the
 * hub's flush order (ENTITY slice, then PLAYER) preserves that.</p>
 *
 * <p><strong>What stays server-thread and why (bytecode-verified on 26.2):</strong>
 * the staged player body contains exactly two couplings to server-thread
 * machinery:</p>
 * <ul>
 *   <li>{@code ServerChunkCache.move(player)} — chunk-view bookkeeping on
 *   the chunk system's owning thread.</li>
 *   <li>{@code ServerPlayerGameMode.tick()} — block-break progress, which
 *   reaches the mining ticket machinery only the server thread pumps.</li>
 * </ul>
 *
 * <p>Both are redirected onto the server thread via {@code MinecraftServer}
 * execution when the body runs on a region worker, and run inline otherwise
 * — a no-op redirect on the vanilla path. Everything else in the player
 * body is player-local state or thread-safe packet sends ({@code
 * Connection.send} is the vanilla thread-safe channel write).</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickMixin {

	/**
	 * The chunk-view update: bounce to the server thread from a region
	 * worker, inline on the vanilla path.
	 */
	@Redirect(
			method = "tick()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerChunkCache;move(Lnet/minecraft/server/level/ServerPlayer;)V"),
			require = 1)
	private void fabricfolia$moveOnServerThread(ServerChunkCache chunkCache, ServerPlayer player) {
		if (ThreadOwnership.current().kind() == ThreadContext.Kind.REGION) {
			// level() is ServerPlayer's covariant override returning ServerLevel.
			((ServerPlayer) (Object) this).level().getServer().execute(() -> chunkCache.move(player));
			return;
		}
		chunkCache.move(player);
	}

	/**
	 * The block-break-progress tick: same boundary as the chunk-view move.
	 * One call site in 26.2's ServerPlayer.tick (verified).
	 */
	@Redirect(
			method = "tick()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayerGameMode;tick()V"),
			require = 1)
	private void fabricfolia$gameModeOnServerThread(ServerPlayerGameMode gameMode) {
		if (ThreadOwnership.current().kind() == ThreadContext.Kind.REGION) {
			// The mixin body merges into ServerPlayer: `this` IS the player
			// whose game mode is ticking (verified: the redirect site is
			// player.tick()'s single gameMode.tick() call).
			((ServerPlayer) (Object) this).level().getServer().execute(gameMode::tick);
			return;
		}
		gameMode.tick();
	}
}
