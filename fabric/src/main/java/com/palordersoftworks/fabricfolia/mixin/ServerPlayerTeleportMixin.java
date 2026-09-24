/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.RegionTransitions;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player teleport funnel (mandates §16/§19) — the hole behind
 * {@link EntityTeleportMixin}: {@code ServerPlayer} OVERRIDES
 * {@code teleport(TeleportTransition)} with a covariant return
 * ({@code ServerPlayer} — bytecode-verified on the 26.2 jar), so an
 * {@code Entity}-level injection never sees player transitions. Every
 * player dimension change and portal crossing reaches vanilla's teleport
 * funnel through THIS override:
 * <ul>
 *   <li>{@code Entity.handlePortal()} virtual-dispatches
 *       {@code teleport(transition)} to it (players do not override
 *       {@code handlePortal});</li>
 *   <li>plugins/commands/mods call it directly;</li>
 *   <li>{@code ServerPlayer.teleportTo(ServerLevel, ...)} delegates to it
 *       through {@code Player.teleportTo}.</li>
 * </ul>
 * (Player respawn does NOT funnel here — {@code PlayerList.respawn} builds a
 * fresh {@code ServerPlayer}; that flow needs its own hook. Same for
 * {@code teleportTo(DDD)}/{@code teleportRelative(DDD)}, which only send a
 * client teleport packet and touch no world state server-side — verified
 * against the 26.2 bytecode.)
 *
 * <p><strong>The hazard it fixes:</strong> with player-path staging on
 * ({@code gameplay.stage-player-path}), the staged connection body drives
 * {@code SGPLI.tickPlayer} → {@code doTick} → ... → {@code handlePortal} ON
 * A REGION WORKER. Without this capture the override's cross-dimension
 * branch runs from the wrong context: it removes the player from the old
 * level ({@code removePlayerImmediately} — entity-list mutation racing the
 * old region's worker), adds it to the new one
 * ({@code addDuringTeleport} — racing the destination's worker), and
 * touches the global {@code PlayerList} (permission/level info broadcasts)
 * and the profiler — none of which the departing region owns.</p>
 *
 * <p><strong>The fix mirrors {@code EntityTeleportMixin}:</strong> on a
 * region worker whose current region does NOT own the destination chunk,
 * the whole vanilla body is dispatched to the destination context
 * (destination region's queue; global scheduler when no region owns the
 * chunk) and the original call is cancelled. When the current context
 * already owns the destination — including the re-invoked
 * {@code teleport} INSIDE a dispatched body — the vanilla body runs inline,
 * preserving the same-dimension fast path and closing the recursion.</p>
 *
 * <p><strong>Accepted risk, stated:</strong> the dispatched body still
 * executes the cross-dimension branch's global {@code PlayerList} mutations
 * on the destination region's worker rather than the server thread. Vanilla
 * itself defers cross-dimension moves inside its own tick loop (the lag
 * matches), and the alternative — a synchronous hop to the server thread
 * from a region worker — is the cross-context wait mandate §32 forbids.
 * The body's exceptions are isolated by the dispatch wrapper, so a failed
 * transition never kills the executing worker.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {

	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$regionSafePlayerTeleport(TeleportTransition transition,
			CallbackInfoReturnable<ServerPlayer> cir) {
		ThreadOwnership.Context context = ThreadOwnership.current();
		if (context.kind() != ThreadContext.Kind.REGION) {
			return; // server thread / network / global / async: vanilla behavior
		}

		ServerPlayer self = (ServerPlayer) (Object) this;
		ServerLevel newLevel = transition.newLevel();
		if (newLevel == null || newLevel.isClientSide()) {
			return;
		}

		String destWorldKey = newLevel.dimension().identifier().toString();
		int destChunkX = SectionPos.blockToSectionCoord(transition.position().x());
		int destChunkZ = SectionPos.blockToSectionCoord(transition.position().z());

		// The current context already owns the destination (same region, or
		// the re-invoked call inside a dispatched body): run inline. This is
		// also the recursion breaker — without it the dispatched body's own
		// teleport call would re-enter and enqueue forever.
		if (RegionTransitions.isCurrentContextOwner(destWorldKey, destChunkX, destChunkZ)) {
			return;
		}

		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			return; // engine down: vanilla behavior from wherever we run
		}

		RegionTransitions.recordPlayerTransition();
		boolean dispatched = RegionTransitions.dispatchKeyed(engine, destWorldKey,
				destChunkX, destChunkZ, self, () -> self.teleport(transition));
		if (dispatched) {
			// Vanilla's own no-op return convention (Entity.teleport's
			// null/this contract): the transition completes on the
			// destination context.
			cir.setReturnValue(self);
		}
		// Not dispatched (engine cannot serve this world / enqueue race):
		// fall through to the vanilla body — a worker running vanilla's own
		// teleport is the pre-existing risk path, and dropping the move
		// entirely would strand players mid-portal.
	}
}
