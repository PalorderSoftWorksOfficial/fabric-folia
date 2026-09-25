/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The /folia administration and information command (spec 19).
 *
 * <p><strong>Structure:</strong> an open information tier (/folia,
 * /folia version, /folia help) and an OP-default diagnostics tier
 * (regions, workers, scheduler, patches, health, threads, metrics, …).
 * Every subcommand requires the matching
 * {@code fabricfolia.command.folia.<sub>} permission atom — a permission
 * plugin can grant or revoke each one independently; defaults grant OP.</p>
 *
 * <p><strong>One source of truth (spec 36):</strong> every diagnostic reads
 * the real engine objects (regionizer, schedulers, worker pool, patch
 * registry) through {@link FoliaDiagnostics}; there is no parallel stats
 * collector. All user-facing text is authored in MiniMessage and rendered
 * through {@link FoliaMessages} (spec 30/32).</p>
 */
public final class FoliaCommand {

	private FoliaCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				register(dispatcher));
	}

	private static Permission permission(String leaf) {
		return Permission.Atom.create("fabricfolia.command.folia." + leaf);
	}

	/**
	 * OP-default permission check: granted by the custom atom (fine-grained
	 * override for permission plugins) OR by server owner level (OP default).
	 * Level-based sets never contain custom atoms, so the OR is what makes
	 * this OP-only-by-default rather than inaccessible-by-default.
	 */
	private static boolean allowed(CommandSourceStack source, String leaf) {
		return source.permissions().hasPermission(permission(leaf))
				|| source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
	}

	private static void send(CommandSourceStack source, List<MutableComponent> lines) {
		for (MutableComponent line : lines) {
			source.sendSuccess(() -> line, false);
		}
	}

	private static void send(CommandSourceStack source, MutableComponent line) {
		source.sendSuccess(() -> line, false);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("folia");

		root.executes(context -> {
			FabricFoliaEngine engine = FabricFoliaMod.engine();
			List<MutableComponent> lines = new ArrayList<>();
			lines.add(FoliaMessages.header("FabricFolia"));
			lines.add(FoliaMessages.rule());
			lines.add(FoliaMessages.row("Minecraft", FoliaVersion.minecraft()));
			lines.add(FoliaMessages.row("Fabric Loader", FoliaVersion.loader()));
			lines.add(FoliaMessages.row("Fabric API", FoliaVersion.fabricApi()));
			lines.add(FoliaMessages.row("FabricFolia", FoliaVersion.fabricFolia()));
			lines.add(FoliaMessages.row("Java", FoliaVersion.java()));
			lines.add(FoliaMessages.rule());
			if (engine == null) {
				lines.add(FoliaMessages.warn("Engine disabled — vanilla execution"));
			} else {
				var summary = FoliaDiagnostics.regionSummary(engine);
				lines.add(FoliaMessages.ok("Regionized server"));
				lines.add(FoliaMessages.row("Regions", String.valueOf(summary.total())));
				lines.add(FoliaMessages.row("Workers", engine.workerPool().workerCount()
						+ " (" + engine.workerPool().busyCount() + " busy)"));
				lines.add(FoliaMessages.info("Use /folia help for the command list."));
			}
			send(context.getSource(), lines);
			return 1;
		});

		root.then(Commands.literal("version")
				.executes(context -> {
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("FabricFolia Version"));
					lines.add(FoliaMessages.rule());
					for (FoliaVersion.Row row : FoliaVersion.rows()) {
						lines.add(FoliaMessages.row(row.label(), row.value()));
					}
					lines.add(FoliaMessages.rule());
					lines.add(FoliaMessages.info("Regionized server — not affiliated with Paper/Folia."));
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("help")
				.executes(context -> {
					List<MutableComponent> lines = new ArrayList<>();
					for (String mini : FoliaHelp.overview()) {
						lines.add(FoliaMessages.toMinecraft(mini));
					}
					send(context.getSource(), lines);
					return 1;
				})
				.then(Commands.argument("topic", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((context, builder) -> {
							for (String topic : FoliaHelp.topics()) {
								if (topic.startsWith(builder.getRemainingLowerCase())) {
									builder.suggest(topic);
								}
							}
							return builder.buildFuture();
						})
						.executes(context -> {
							String topic = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "topic");
							List<String> content = FoliaHelp.topic(topic);
							List<MutableComponent> lines = new ArrayList<>();
							if (content == null) {
								lines.add(FoliaMessages.error("Unknown help topic: " + topic));
								lines.add(FoliaMessages.info("Topics: " + String.join(", ", FoliaHelp.topics())));
							} else {
								for (String mini : content) {
									lines.add(FoliaMessages.toMinecraft(mini));
								}
							}
							send(context.getSource(), lines);
							return content == null ? 0 : 1;
						})));

		root.then(Commands.literal("regions")
				.requires(source -> allowed(source, "regions"))
				.executes(context -> regions(context.getSource(), false))
				.then(Commands.literal("verbose")
						.executes(context -> regions(context.getSource(), true))));

		root.then(Commands.literal("workers")
				.requires(source -> allowed(source, "workers"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Workers"));
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled — vanilla execution, no worker pool"));
					} else {
						for (String line : FoliaDiagnostics.workerLines(engine)) {
							lines.add(FoliaMessages.toMinecraft("<gray>" + line + "</gray>"));
						}
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("scheduler")
				.requires(source -> allowed(source, "scheduler"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Scheduler"));
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled — vanilla execution"));
					} else {
						for (String line : FoliaDiagnostics.schedulerLines(engine)) {
							boolean problem = line.startsWith("DIAGNOSIS") || line.startsWith("PROBLEM");
							lines.add(problem
									? FoliaMessages.error(line)
									: FoliaMessages.toMinecraft("<gray>" + line + "</gray>"));
						}
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("patches")
				.requires(source -> allowed(source, "patches"))
				.executes(context -> {
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("FabricFolia Patches"));
					lines.add(FoliaMessages.rule());
					String currentLayer = null;
					for (var patch : PatchRegistry.patches()) {
						String layer = patch.layer().name().toLowerCase(Locale.ROOT);
						if (!layer.equals(currentLayer)) {
							currentLayer = layer;
							lines.add(FoliaMessages.toMinecraft(
									"<yellow><bold>" + layer + "</bold></yellow>"));
						}
						String statusTag = switch (patch.status()) {
							case ACTIVE -> "<green>ENABLED</green>";
							case DISABLED -> "<gray>DISABLED</gray>";
							case BLOCKED_DEPENDENCY, BLOCKED_CONFLICT -> "<yellow>BLOCKED</yellow>";
							case FAILED -> "<red>FAILED</red>";
						};
						lines.add(FoliaMessages.toMinecraft("  <aqua>" + patch.name()
								+ "</aqua> " + statusTag
								+ " <dark_gray>[" + patch.lifecycle().name().toLowerCase(Locale.ROOT) + "]</dark_gray>"));
						if (patch.status() != PatchRegistry.Status.ACTIVE) {
							lines.add(FoliaMessages.toMinecraft(
									"    <dark_gray>" + patch.statusReason() + "</dark_gray>"));
						}
					}
					if (PatchRegistry.patches().isEmpty()) {
						lines.add(FoliaMessages.info("(no patches registered)"));
					}
					lines.add(FoliaMessages.rule());
					lines.add(FoliaMessages.info("Global switch: patches.enabled. Fallback = original path."));
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("health")
				.requires(source -> allowed(source, "health"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("FabricFolia Health"));
					lines.add(FoliaMessages.rule());
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled — vanilla execution"));
					} else {
						int worst = 0;
						for (var check : FoliaDiagnostics.healthChecks(engine)) {
							if (check.ok() && !check.warning()) {
								lines.add(FoliaMessages.ok(check.message()));
							} else if (check.warning()) {
								lines.add(FoliaMessages.warn(check.message()));
								worst = Math.max(worst, 1);
							} else {
								lines.add(FoliaMessages.error(check.message()));
								worst = Math.max(worst, 2);
							}
						}
						lines.add(FoliaMessages.rule());
						lines.add(switch (worst) {
							case 0 -> FoliaMessages.ok("Overall: OK");
							case 1 -> FoliaMessages.warn("Overall: WARNING");
							default -> FoliaMessages.error("Overall: ERROR");
						});
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("threads")
				.requires(source -> allowed(source, "threads"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Threads"));
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled — no FabricFolia threads"));
					} else {
						for (Thread thread : Thread.getAllStackTraces().keySet()) {
							String name = thread.getName();
							if (name.startsWith("FabricFolia-")) {
								lines.add(FoliaMessages.toMinecraft("  <aqua>" + name + "</aqua><gray> ("
										+ thread.getState() + ")</gray>"));
							}
						}
						lines.add(FoliaMessages.toMinecraft(
								"<gray>this thread context: " + ThreadOwnership.current().kind() + "</gray>"));
					}
					send(context.getSource(), lines);
					return 1;
				}));

		registerLegacySubcommands(root);
		dispatcher.register(root);
	}

	private static int regions(CommandSourceStack source, boolean verbose) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		List<MutableComponent> lines = new ArrayList<>();
		lines.add(FoliaMessages.header("Regions"));
		if (engine == null) {
			lines.add(FoliaMessages.warn("Engine disabled — no regions exist"));
			send(source, lines);
			return 1;
		}
		var summary = FoliaDiagnostics.regionSummary(engine);
		lines.add(FoliaMessages.row("Total", String.valueOf(summary.total())));
		lines.add(FoliaMessages.row("Active (ready)", String.valueOf(summary.ready())));
		lines.add(FoliaMessages.row("Ticking", String.valueOf(summary.ticking())));
		lines.add(FoliaMessages.row("Transient (merging)", String.valueOf(summary.transientRegions())));
		lines.add(FoliaMessages.row("Due now", String.valueOf(summary.due())));
		lines.add(FoliaMessages.row("Queued tasks", String.valueOf(summary.queuedTasks())));
		lines.add(FoliaMessages.row("Merges / splits", summary.merges() + " / " + summary.splits()));
		if (summary.total() == 0) {
			lines.add(FoliaMessages.info("(regions form when chunks are regionized)"));
		}
		if (verbose) {
			List<String> detail = FoliaDiagnostics.regionDetail(engine, true);
			if (!detail.isEmpty()) {
				lines.add(FoliaMessages.rule());
				for (String line : detail) {
					lines.add(FoliaMessages.toMinecraft("<gray>" + line + "</gray>"));
				}
			}
		}
		send(source, lines);
		return 1;
	}

	private static void registerLegacySubcommands(LiteralArgumentBuilder<CommandSourceStack> root) {
		root.then(Commands.literal("shutdown")
				.requires(source -> allowed(source, "admin"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					if (engine == null) {
						send(context.getSource(), FoliaMessages.warn(
								"Engine disabled (vanilla execution); use /stop."));
						return 0;
					}
					send(context.getSource(), FoliaMessages.info(
							"Stopping the server: region work will drain and worlds will be saved."));
					context.getSource().getServer().halt(false);
					return 1;
				}));

		root.then(Commands.literal("status")
				.requires(source -> allowed(source, "health"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					if (engine == null) {
						send(context.getSource(), FoliaMessages.warn(
								"Fabric Folia is DISABLED (vanilla execution) — check the startup log."));
						return 1;
					}
					var config = engine.config();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Fabric Folia status"));
					lines.add(FoliaMessages.row("Engine", "ACTIVE"));
					lines.add(FoliaMessages.row("Random ticks", randomTickLine(engine)));
					lines.add(FoliaMessages.row("Player path", FabricFoliaMod.playerPathStaging()
							? "staged (owning region)" : "vanilla server thread"));
					lines.add(FoliaMessages.row("Worker threads", String.valueOf(engine.primaryWorkerCount())));
					lines.add(FoliaMessages.row("Thread-check mode", config.threadCheckMode()));
					lines.add(FoliaMessages.row("Region section size", String.valueOf(config.regionSectionSize())));
					lines.add(FoliaMessages.row("Live regions", String.valueOf(engine.regionCount())));
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("entities")
				.requires(source -> allowed(source, "admin"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Entity ownership tracking"));
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled; entity tracking is not running."));
					} else {
						for (String line : engine.entityTrackingLines()) {
							lines.add(FoliaMessages.toMinecraft("<gray>" + line + "</gray>"));
						}
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("metrics")
				.requires(source -> allowed(source, "admin"))
				.executes(context -> {
					FabricFoliaEngine engine = FabricFoliaMod.engine();
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Metrics"));
					if (engine == null) {
						lines.add(FoliaMessages.warn("Engine disabled; no metrics exist."));
					} else {
						for (String line : engine.metricsLines()) {
							lines.add(FoliaMessages.toMinecraft("<gray>  " + line + "</gray>"));
						}
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("compat")
				.requires(source -> allowed(source, "admin"))
				.executes(context -> {
					List<MutableComponent> lines = new ArrayList<>();
					lines.add(FoliaMessages.header("Compatibility scan"));
					var entries = FabricFoliaMod.compatEntries();
					if (entries.isEmpty()) {
						lines.add(FoliaMessages.info("(no scan results — the mod may still be initializing)"));
					}
					for (var entry : entries) {
						lines.add(FoliaMessages.row(entry.id() + " " + entry.version(), String.valueOf(entry.status())));
					}
					send(context.getSource(), lines);
					return 1;
				}));

		root.then(Commands.literal("pin")
				.requires(source -> allowed(source, "admin"))
				.then(Commands.argument("chunkX", IntegerArgumentType.integer())
						.then(Commands.argument("chunkZ", IntegerArgumentType.integer())
								.executes(context -> pin(context.getSource(),
										IntegerArgumentType.getInteger(context, "chunkX"),
										IntegerArgumentType.getInteger(context, "chunkZ"))))));

		root.then(Commands.literal("unpin")
				.requires(source -> allowed(source, "admin"))
				.then(Commands.argument("chunkX", IntegerArgumentType.integer())
						.then(Commands.argument("chunkZ", IntegerArgumentType.integer())
								.executes(context -> unpin(context.getSource(),
										IntegerArgumentType.getInteger(context, "chunkX"),
										IntegerArgumentType.getInteger(context, "chunkZ"))))));
	}

	private static String randomTickLine(FabricFoliaEngine engine) {
		if (!engine.config().regionizedRandomTicks()) {
			return "disabled (vanilla execution)";
		}
		if (engine.randomTickInterceptSuppressed()) {
			return "SUPPRESSED this session (measured mod interaction)";
		}
		return "enabled (per-chunk random ticks on region workers)";
	}

	private static final int PIN_RADIUS = 2;

	private static int pin(CommandSourceStack source, int chunkX, int chunkZ) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			send(source, FoliaMessages.warn("Engine disabled; pinning is unavailable."));
			return 0;
		}
		var level = source.getLevel();
		var pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
		level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, PIN_RADIUS);
		send(source, FoliaMessages.ok("Pinned " + worldLabel(level) + " at chunk [" + chunkX + "," + chunkZ + "]"));
		return 1;
	}

	private static int unpin(CommandSourceStack source, int chunkX, int chunkZ) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			send(source, FoliaMessages.warn("Engine disabled; unpinning is unavailable."));
			return 0;
		}
		var level = source.getLevel();
		level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
				new net.minecraft.world.level.ChunkPos(chunkX, chunkZ), PIN_RADIUS);
		send(source, FoliaMessages.ok("Unpinned chunk [" + chunkX + "," + chunkZ + "]"));
		return 1;
	}

	private static String worldLabel(net.minecraft.server.level.ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
