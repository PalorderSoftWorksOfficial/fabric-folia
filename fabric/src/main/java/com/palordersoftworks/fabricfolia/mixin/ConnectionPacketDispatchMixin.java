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
 * <p>The flag is a bounded enter/exit (not a sticky tag): a Netty event
 * loop also runs non-packet work, and mislabeling it permanently would
 * poison unrelated context checks. The previous-context token captured at
 * enter is restored at exit; on an event loop the previous context is
 * UNKNOWN (the loop owns nothing), on the server thread it is whatever the
 * server had — though the enter handler does not fire there (see guard).</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionPacketDispatchMixin {

	@Inject(method = "runOnceConnected(Ljava/util/function/Consumer;)V", at = @At("HEAD"))
	private void fabricfolia$enterNetworkContext(CallbackInfo ci) {
		if (ThreadOwnership.current().kind() != ThreadContext.Kind.GLOBAL
				&& !isServerThread()) {
			// Non-server thread executing connection work: the network
			// context. (The server thread reaches this method via tick();
			// isServerThread distinguishes it from event loops cheaply and
			// without vanilla references.)
			ThreadOwnership.enterSide(ThreadContext.Kind.NETWORK);
			NetworkDispatch.noteNetworkExecution();
		}
	}

	@Inject(method = "runOnceConnected(Ljava/util/function/Consumer;)V", at = @At("RETURN"))
	private void fabricfolia$exitNetworkContext(CallbackInfo ci) {
		if (NetworkDispatch.isNetworkContext()) {
			// Only our enter sets NETWORK on this thread's current window;
			// restore to UNKNOWN (the pre-existing default for event loops).
			ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
		}
	}

	/**
	 * The vanilla server thread is the thread running the server tick loop;
	 * Fabric Folia's engine enters GLOBAL context there at tick start, so
	 * the GLOBAL check above already exempts it. This extra guard is for
	 * the pre-tick window (login processing before the first tick): the
	 * engine's global-thread registration names it, and only network loops
	 * remain. Kept as a name check rather than a vanilla reference to stay
	 * mixin-minimal.
	 */
	private static boolean isServerThread() {
		String name = Thread.currentThread().getName();
		return name.equals("Server thread");
	}
}
