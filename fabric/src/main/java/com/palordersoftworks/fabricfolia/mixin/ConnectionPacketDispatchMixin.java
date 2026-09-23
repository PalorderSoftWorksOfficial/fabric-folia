/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.NetworkDispatch;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Network-context classification (mandate 28) with the two vanilla packet
 * paths distinguished:
 *
 * <ul>
 *   <li>{@code Connection.tick()} runs on the SERVER thread and drains the
 *   same pending-actions queue — tagging there would mislabel the server
 *   thread as a network context (the defect the first draft shipped).</li>
 *   <li>{@code Connection.runOnceConnected(...)} is the task-execution
 *   surface: vanilla submits packet handling from the Netty event loop
 *   ({@code channelRead0} runs inside it), and each submitted task executes
 *   either on the loop or later on the server thread via {@code tick}.
 *   The flag wraps ONLY the execution, so a task vanilla moved to the
 *   server thread keeps its server context.</li>
 * </ul>
 *
 * <p>The flag is a bounded enter/exit with a captured token (not a sticky
 * tag): a Netty event loop also runs non-packet work, and mislabeling it
 * permanently would poison unrelated context checks. The guard fires only
 * on UNKNOWN contexts — event loops idle there; the server thread is
 * GLOBAL (engine-registered); region workers carry REGION during the
 * staged connection tick, and a worker draining connection work inside a
 * region context must keep that context, not be relabeled NETWORK.</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionPacketDispatchMixin {

	@Inject(method = "runOnceConnected(Ljava/util/function/Consumer;)V", at = @At("HEAD"))
	private void fabricfolia$enterNetworkContext(CallbackInfo ci) {
		if (ThreadOwnership.current().kind() == ThreadContext.Kind.UNKNOWN) {
			// Unknown contexts are Netty event loops (and other unowned
			// threads). The server thread is GLOBAL (engine registration),
			// region workers are REGION inside the staged connection tick —
			// both must keep their contexts, which own this work already.
			ThreadOwnership.enterSide(ThreadContext.Kind.NETWORK);
			NetworkDispatch.noteNetworkExecution();
		}
	}

	@Inject(method = "runOnceConnected(Ljava/util/function/Consumer;)V", at = @At("RETURN"))
	private void fabricfolia$exitNetworkContext(CallbackInfo ci) {
		if (ThreadOwnership.current().kind() == ThreadContext.Kind.NETWORK) {
			// This mixin is enterSide(NETWORK)'s only caller, so NETWORK on
			// this thread's current window means WE tagged it: restore the
			// event-loop idle default. Thread-local, so concurrent
			// runOnceConnected entries on one connection cannot clobber
			// each other's restore.
			ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
		}
	}
}
