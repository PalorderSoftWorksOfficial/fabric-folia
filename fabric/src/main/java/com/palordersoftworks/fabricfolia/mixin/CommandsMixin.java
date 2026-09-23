/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.thread.RegionChecks;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Region-aware command execution (mandate §12). Vanilla runs every command
 * on the server thread; under regionized execution a command whose source is
 * an entity must run in the region owning that entity — block/place/break
 * commands mutate region-owned state.
 *
 * <p><strong>Console/RCON (no entity source):</strong> server-wide work; the
 * execution is tagged GLOBAL for its duration (enter at HEAD, restore at
 * RETURN through a per-thread token stack — recursion from function chains
 * nests correctly). No cancellation: vanilla's parse/error handling runs
 * untouched. The tag is what lets server-thread command bodies pass
 * global-ownership predicates in the check facade.</p>
 *
 * <p><strong>Entity-sourced commands:</strong> when a packet-driven dispatch
 * lands on a network event loop, the command body is re-enqueued onto the
 * owning region's task queue (fire-and-forget; the network thread never
 * blocks on a tick thread). Server-thread dispatch of the same command runs
 * inline — the server thread serializes with region ticks by construction,
 * and vanilla expects synchronous command completion (feedback, follow-ups).</p>
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

	/** Per-thread stack of tokens pushed by the console-context tagging. */
	private static final ThreadLocal<Deque<ThreadOwnership.Context>> CONSOLE_TOKENS =
			ThreadLocal.withInitial(ArrayDeque::new);

	@Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
			at = @At("HEAD"))
	private void fabricfolia$tagConsoleContext(
			com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults,
			String command, CallbackInfo ci) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			return;
		}
		CommandSourceStack source = parseResults.getContext().getSource();
		net.minecraft.world.entity.Entity entity = source.getEntity();
		if (entity == null || entity.level().isClientSide()) {
			if (ThreadOwnership.current().kind()
					!= com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.GLOBAL
					&& !ThreadOwnership.current().threadName().startsWith("Server thread")) {
				CONSOLE_TOKENS.get().push(ThreadOwnership.enterGlobal());
			}
		}
	}

	@Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
			at = @At("RETURN"))
	private void fabricfolia$restoreConsoleContext(
			com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults,
			String command, CallbackInfo ci) {
		Deque<ThreadOwnership.Context> tokens = CONSOLE_TOKENS.get();
		ThreadOwnership.Context token = tokens.pollLast();
		if (token != null) {
			ThreadOwnership.exit(token);
		}
	}

	@Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$regionAwareDispatch(
			com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults,
			String command, CallbackInfo ci) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			return; // engine down: vanilla threading is correct as-is
		}
		CommandSourceStack source = parseResults.getContext().getSource();
		net.minecraft.world.entity.Entity entity = source.getEntity();
		if (entity == null || entity.level().isClientSide()) {
			return; // console path: handled by the context tagging above
		}

		// Entity-sourced command: route network-thread dispatch to the owning region.
		if (ThreadOwnership.current().kind()
				!= com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.NETWORK) {
			return; // server thread (inline), region worker (already owned), etc.
		}
		String worldKey = entity.level().dimension().identifier().toString();
		var scheduler = engine.schedulerFor(worldKey);
		var regionizer = engine.regionizerFor(worldKey);
		if (scheduler == null || regionizer == null) {
			return; // world not attached: vanilla execution
		}
		net.minecraft.world.level.ChunkPos pos = entity.chunkPosition();
		var region = regionizer.ownerOfChunk(pos.x(), pos.z());
		if (region == null) {
			return; // unowned chunks: vanilla execution
		}
		if (RegionChecks.ownsRegion(region)) {
			return; // already executing in the owning region: inline
		}
		boolean enqueued = scheduler.enqueue(region, () -> {
			ThreadOwnership.Context token = ThreadOwnership.enterRegion(region);
			try {
				source.dispatcher().execute(parseResults);
			} catch (Exception e) {
				// Brigadier surfaces command errors as exceptions on this path
				// (performCommand normally handles them); report to the source
				// so the player still sees the failure.
				source.sendFailure(net.minecraft.network.chat.Component.literal(
						"Command failed: " + e.getMessage()));
			} finally {
				ThreadOwnership.exit(token);
			}
		});
		if (enqueued) {
			ci.cancel();
		}
	}
}
