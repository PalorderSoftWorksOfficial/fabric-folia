/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.NetworkDispatch;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The packet drain seam (mandate 28): {@code Connection.flushQueue} drains
 * the pending-actions queue — the tasks vanilla submits for packet handling
 * — and executes each {@code Consumer<Connection>} inline. Vanilla assumes
 * the drain happens on the server thread ({@code Connection.tick}); under
 * load or eager processing an event loop can drain it too
 * ({@code runOnceConnected} submits from {@code channelRead0}).
 *
 * <p>This redirect routes every drained action through
 * {@code NetworkDispatch.runOnOwner}: on the server thread (the normal
 * path) the action executes exactly where vanilla would have it — zero
 * behavioral delta, and the global scheduler's dispatch is skipped because
 * the context check runs first. On a network-classified thread the action
 * is hopped to the global context (the server thread's serialization)
 * instead of mutating game state on the event loop. No packet content is
 * parsed, classified, or delayed; the boundary is the thread, which is the
 * only thing that can race.</p>
 *
 * <p>Why not wrap the packet handler bodies: vanilla's
 * {@code ensureRunningOnSameThread} already re-homes tick-sensitive
 * handlers to the server thread inside the handler itself; the queue drain
 * is the single point where ANY submitted task crosses threads, so this one
 * redirect covers every packet path without touching 61 handler methods.</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionFlushQueueMixin {

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Redirect(
			method = "flushQueue",
			at = @At(value = "INVOKE",
					target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),
			require = 1)
	private void fabricfolia$dispatchDrainedAction(java.util.function.Consumer action,
	                                               Object connection) {
		NetworkDispatch.runOnOwner(FabricFoliaMod.engine(), null, 0, 0,
				() -> action.accept(connection));
	}
}
