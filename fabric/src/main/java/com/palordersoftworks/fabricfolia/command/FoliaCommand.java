/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.region.Region;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The /folia command tree: /folia, /folia status, /folia regions,
 * /folia threads, /folia compat, /folia pin|unpin.
 *
 * <p><strong>Command context policy (spec 22):</strong> these commands are
 * diagnostics, not gameplay mutation: they read scheduler/regionizer state and
 * never touch region-owned game state, so they are safe from the server
 * command context as-is. Player-context gameplay commands route through the
 * schedulers when gameplay lands. Console-originated invocations are fine for
 * the same reason; nothing here silently executes against "whichever region is
 * convenient".</p>
 *
 * <p><strong>Mixin/Fabric API note (spec 15):</strong> command registration
 * uses Fabric API's CommandRegistrationCallback — no interception needed.</p>
 */
public final class FoliaCommand {

	private FoliaCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("folia");

		root.executes(context -> {
			context.getSource().sendSuccess(
					() -> Component.literal(
							"Fabric-Folia — regionized multithreaded execution. Use /folia status, /folia regions, /folia threads."),
					false);
			return 1;
		});

		root.then(Commands.literal("status").executes(context -> {
			FabricFoliaEngine engine = FabricFoliaMod.engine();
			if (engine == null) {
			context.getSource().sendSuccess(() -> Component.literal(
					"Fabric Folia: DISABLED (vanilla execution). See the startup log for the reason (invalid config, disabled in config, or bootstrap failure), and TROUBLESHOOTING.md for fixes."),
					false);
				return 1;
			}
			var config = engine.config();
			// Honest per-flag reporting (spec 17): the random-tick slice is the
			// only interception that exists; nothing else is claimed.
			// Wording matches the startup summary (one vocabulary for admins). A
			// SUPPRESSED slice was armed by config but held back by measured
			// startup policy (e.g. C2ME) — the admin must be able to tell.
			String randomTickLine;
			if (!config.regionizedRandomTicks()) {
				randomTickLine = "disabled (vanilla execution)";
			} else if (engine.randomTickInterceptSuppressed()) {
				randomTickLine = "SUPPRESSED this session (measured mod interaction - see docs/compatibility/c2me.md)";
			} else {
				randomTickLine = "enabled (per-chunk random ticks on region workers)";
			}
			context.getSource().sendSuccess(() -> Component.literal(
					"Fabric Folia status:\n"
							+ "  Engine: ACTIVE\n"
							+ "  Regionized random ticks: " + randomTickLine + "\n"
							+ "  Entity ownership tracking: active (add/remove/move hooks; /folia entities)\n"
							+ "  Still on server thread: entity/block-entity ticking, scheduled ticks, worldgen, spawning\n"
							+ "  Worker threads: " + engine.primaryWorkerCount() + "\n"
							+ "  Thread-check mode: " + config.threadCheckMode() + "\n"
							+ "  Region section size: " + config.regionSectionSize() + " (bookkeeping cell, not region shape)\n"
							+ "  Live regions (all worlds): " + engine.regionCount()),
					false);
			return 1;
		}));

		root.then(Commands.literal("regions").executes(context -> {
			FabricFoliaEngine engine = FabricFoliaMod.engine();
			if (engine == null) {
				context.getSource().sendSuccess(() -> Component.literal(
						"Fabric Folia is disabled; no regions exist."), false);
				return 1;
			}
			StringBuilder text = new StringBuilder("Regions by world:\n");
			for (String line : engine.regionCountsByWorld()) {
				text.append("  ").append(line).append("\n");
			}
			if (engine.regionCount() == 0) {
				text.append("  (no live regions - regions form when chunks are regionized)\n");
			}
			// Per-region detail (admin diagnostics): state, tick count, size.
			for (String line : engine.regionDetailLines()) {
				text.append(line).append("\n");
			}
			context.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
			return 1;
		}));

		root.then(Commands.literal("entities").executes(context -> {
			FabricFoliaEngine engine = FabricFoliaMod.engine();
			if (engine == null) {
				context.getSource().sendSuccess(() -> Component.literal(
						"Fabric Folia is disabled; entity tracking is not running."), false);
				return 1;
			}
			StringBuilder text = new StringBuilder("Entity ownership tracking (mandate \u00a715; see THREADING.md):\n");
			for (String line : engine.entityTrackingLines()) {
				text.append(line).append("\n");
			}
			context.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
			return 1;
		}));

		root.then(Commands.literal("threads").executes(context -> {
			FabricFoliaEngine engine = FabricFoliaMod.engine();
			if (engine == null) {
				context.getSource().sendSuccess(() -> Component.literal(
						"Fabric Folia is disabled; no worker threads exist."), false);
				return 1;
			}
			StringBuilder text = new StringBuilder("Fabric Folia threads:\n");
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				String name = thread.getName();
				if (name.startsWith("FabricFolia-")) {
					text.append("  ").append(name).append(" (").append(thread.getState()).append(")\n");
				}
			}
			text.append("Workers are not pinned to regions: a region may run on a different worker each tick (see THREADING.md).");
			context.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
			return 1;
		}));

		root.then(Commands.literal("compat").executes(context -> {
			StringBuilder text = new StringBuilder(
					"Fabric Folia compatibility (from the startup scan; measured results in COMPATIBILITY.md):\n");
			var entries = FabricFoliaMod.compatEntries();
			if (entries.isEmpty()) {
				text.append("  (no scan results - the mod may still be initializing)\n");
			}
			for (var entry : entries) {
				text.append("  ").append(entry.id()).append(" ").append(entry.version())
						.append(": ").append(entry.status()).append("\n");
			}
			text.append("Undeclared does not mean incompatible - see docs/compatibility/README.md.");
			context.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
			return 1;
		}));

		root.then(Commands.literal("pin")
				.then(Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
				.then(Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
				.executes(context -> pin(context.getSource(),
						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "chunkX"),
						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "chunkZ"))))));

		root.then(Commands.literal("unpin")
				.then(Commands.argument("chunkX", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
				.then(Commands.argument("chunkZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
				.executes(context -> unpin(context.getSource(),
						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "chunkX"),
						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "chunkZ"))))));

		dispatcher.register(root);
	}

	/**
	 * Pins entity-ticking-level tickets at a chunk position (all attached
	 * worlds' overworld analog: currently the dimension of the command
	 * source, or the overworld from console). A pinned area is where the
	 * regionized random-tick slice is observable without a connected client.
	 * Admin primitive: mirrors a vanilla /forceload plus a simulation-ticket
	 * add; removed by {@link #unpin} or automatically at server stop.
	 */
	private static final int PIN_RADIUS = 2;

	private static int pin(net.minecraft.commands.CommandSourceStack source, int chunkX, int chunkZ) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			source.sendSuccess(() -> Component.literal("Fabric Folia is disabled; pinning is unavailable."), false);
			return 0;
		}
		net.minecraft.server.level.ServerLevel level = source.getLevel();
		net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
		// Vanilla's own symmetric helper: it derives the ticket level from
		// FullChunkStatus.FULL minus the radius, so unpin's removal ticket
		// matches exactly. (A hand-rolled Ticket(level) here does NOT match
		// removeTicketWithRadius's derived level — that asymmetry made an
		// earlier version of unpin a silent no-op; found by the live
		// playtest, see TESTING.md.)
		level.getChunkSource().addTicketWithRadius(
				net.minecraft.server.level.TicketType.FORCED, pos, PIN_RADIUS);
		source.sendSuccess(() -> Component.literal("Pinned " + worldLabel(level)
				+ " at chunk [" + chunkX + "," + chunkZ + "] (entity-ticking r=1, block-ticking r=" + PIN_RADIUS
				+ ") — regionized random ticks will process this area on worker threads."), false);
		return 1;
	}

	private static int unpin(net.minecraft.commands.CommandSourceStack source, int chunkX, int chunkZ) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			source.sendSuccess(() -> Component.literal("Fabric Folia is disabled; unpinning is unavailable."), false);
			return 0;
		}
		net.minecraft.server.level.ServerLevel level = source.getLevel();
		level.getChunkSource().removeTicketWithRadius(
				net.minecraft.server.level.TicketType.FORCED,
				new net.minecraft.world.level.ChunkPos(chunkX, chunkZ), PIN_RADIUS);
		source.sendSuccess(() -> Component.literal("Unpinned chunk [" + chunkX + "," + chunkZ + "]."), false);
		return 1;
	}

	private static String worldLabel(net.minecraft.server.level.ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
