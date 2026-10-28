package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.console.Console;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches tick progress from a daemon thread (one int read per park window).
 * Two failure modes, both seen live on the clump world:
 * (1) a GENUINE hang (tickCount frozen) — dumps every thread's stack plus a
 * per-dimension entity-section census (reference counts, identity-duplicates,
 * tick-list sizes) so storage corruption can be told from runaway volume;
 * (2) the watchdog-kill trajectory — tickCount keeps advancing but below
 * ~1.67 TPS, so 26.2's drift-based watchdog (kill iff now−nextTickTime>60s,
 * re-based only every 100 ticks) kills the server with the misleading
 * "A single server tick took…" wording and NO warnings in between; the probe
 * warns up front. Diagnostics only: never touches game state, never
 * suppresses the failure itself.
 */
public final class StallProbe {

	private static final long POLL_MILLIS = 500;
	private static final long STALL_MILLIS = 5000;
	private static final long DUMP_GAP_MILLIS = 8000;
	/**
	 * Watchdog-kill trajectory (26.2 bytecode: kill iff now−nextTickTime > 60s;
	 * nextTickTime re-bases only every 100 ticks): at TPS below ~1.67 the
	 * re-base interval alone exceeds 60s and the watchdog kills WITHOUT a
	 * single hung tick. 2.0 TPS warns before the trajectory is unrecoverable.
	 */
	private static final double CRAWL_KILL_TPS = 2.0;
	private static final long CRAWL_WINDOW_NANOS = 30_000_000_000L;
	private static final int MAX_SECTIONS_REPORTED = 25;

	private static volatile Thread thread;
	private static volatile boolean stop;

	private StallProbe() {
	}

	public static synchronized void start(MinecraftServer server) {
		if (thread != null) {
			return;
		}
		stop = false;
		Thread t = new Thread(() -> run(server), "FabricFolia-StallProbe");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public static synchronized void stop() {
		stop = true;
		Thread t = thread;
		thread = null;
		if (t != null) {
			t.interrupt();
			try {
				t.join(1500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static void run(MinecraftServer server) {
		Access access;
		try {
			access = new Access();
		} catch (Throwable t) {
			// Version drift in vanilla field names: diagnostics degrade to
			// thread stacks only, never a crash.
			Console.error("StallProbe: entity census unavailable (" + t + "); thread stacks only");
			access = null;
		}
		int lastTick = -1;
		long lastProgress = System.nanoTime();
		long lastDump = 0;
		boolean reported = false;
		int crawlTickBase = -1;
		long crawlWindowStart = 0;
		boolean crawlReported = false;
		while (!stop) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				return;
			}
			try {
				int tick = server.getTickCount();
				long now = System.nanoTime();
				if (crawlTickBase < 0) {
					crawlTickBase = tick;
					crawlWindowStart = now;
				} else if (now - crawlWindowStart >= CRAWL_WINDOW_NANOS) {
					double tps = (tick - crawlTickBase) * 1_000_000_000.0 / (now - crawlWindowStart);
					if (tps < CRAWL_KILL_TPS && !crawlReported) {
						crawlReported = true;
						Console.error("StallProbe: sustained crawl at " + String.format(java.util.Locale.ROOT, "%.2f", tps)
								+ " TPS — the tick loop is PROGRESSING (not hung), but below ~1.67 TPS the "
								+ "watchdog's 100-tick re-base cannot cap its 60s drift limit: a kill is on "
								+ "this trajectory. Reduce the workload (this is the known vanilla O(n^2) "
								+ "entity-clump cost) or enable regionized execution.");
					} else if (tps >= CRAWL_KILL_TPS && crawlReported) {
						crawlReported = false;
						Console.error("StallProbe: tick rate recovered (" + String.format(java.util.Locale.ROOT, "%.2f", tps) + " TPS)");
					}
					crawlTickBase = tick;
					crawlWindowStart = now;
				}
				if (tick != lastTick) {
					if (reported) {
						Console.error("StallProbe: tick progress resumed after "
								+ (now - lastProgress) / 1_000_000 + "ms");
						reported = false;
					}
					lastTick = tick;
					lastProgress = now;
					continue;
				}
				long stalled = (now - lastProgress) / 1_000_000;
				if (stalled >= STALL_MILLIS && now - lastDump >= DUMP_GAP_MILLIS) {
					lastDump = now;
					reported = true;
					Path file = dump(server, access, tick, stalled);
					Console.error("StallProbe: tick stalled " + stalled + "ms without progress; evidence -> "
							+ file);
				}
			} catch (Throwable t) {
				Console.error("StallProbe: probe iteration failed: " + t);
			}
		}
	}

	private static Path dump(MinecraftServer server, Access access, int tick, long stalledMillis) {
		StringBuilder sb = new StringBuilder(64 * 1024);
		sb.append("stall=").append(stalledMillis).append("ms tickCount=").append(tick)
				.append(" at ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
		try {
			Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
			StackTraceElement[] serverStack = null;
			for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
				Thread th = e.getKey();
				if (th.getName().equals("Server thread")) {
					serverStack = e.getValue();
					continue;
				}
				if (th.getState() != Thread.State.RUNNABLE || !th.getName().startsWith("FabricFolia")) {
					continue;
				}
				sb.append("\n--- ").append(th.getName()).append(" (RUNNABLE) ---\n");
				for (StackTraceElement el : e.getValue()) {
					sb.append("    at ").append(el).append('\n');
				}
			}
			if (serverStack != null) {
				sb.append("\n=== SERVER THREAD (the stuck tick) ===\n");
				for (StackTraceElement el : serverStack) {
					sb.append("    at ").append(el).append('\n');
				}
			}
			if (access != null) {
				appendCensus(server, access, sb);
			}
		} catch (Throwable t) {
			sb.append("\ncensus failed: ").append(t).append('\n');
		}
		try {
			Path dir = Path.of("stall-probes");
			Files.createDirectories(dir);
			Path file = dir.resolve("stall-" + LocalDateTime.now()
					.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".log");
			Files.writeString(file, sb, StandardCharsets.UTF_8);
			return file;
		} catch (IOException e) {
			Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."))
					.resolve("fabric-folia-stall-" + System.nanoTime() + ".log");
			try {
				Files.writeString(tmp, sb, StandardCharsets.UTF_8);
				return tmp;
			} catch (IOException e2) {
				return tmp;
			}
		}
	}

	private static void appendCensus(MinecraftServer server, Access access, StringBuilder sb) {
		for (ServerLevel level : server.getAllLevels()) {
			sb.append("\n=== dimension ").append(level.dimension().identifier()).append(" ===\n");
			try {
				Object entityManager = access.entityManager.get(level);
				@SuppressWarnings("unchecked")
				PersistentEntitySectionManager<Entity> manager =
						(PersistentEntitySectionManager<Entity>) entityManager;
				Object sectionStorage = access.sectionStorage.get(manager);
				@SuppressWarnings("unchecked")
				EntitySectionStorage<Entity> storage = (EntitySectionStorage<Entity>) sectionStorage;
				Object sectionsRaw = access.sections.get(storage);
				@SuppressWarnings("unchecked")
				Map<Long, EntitySection<Entity>> sections = (Map<Long, EntitySection<Entity>>) sectionsRaw;
				EntityTickList tickList = (EntityTickList) access.entityTickList.get(level);

				long totalRefs = 0;
				long duplicateRefs = 0;
				int distinct = 0;
				IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
				List<long[]> biggest = new ArrayList<>();
				try {
					Object active = access.tickActive.get(tickList);
					Object passive = access.tickPassive.get(tickList);
					Object iterated = access.tickIterated.get(tickList);
					sb.append("tickList active=").append(access.sizeOf(active))
							.append(" passive=").append(access.sizeOf(passive))
							.append(" iterated=").append(access.sizeOf(iterated)).append('\n');
				} catch (Throwable t) {
					sb.append("tickList census unavailable: ").append(t).append('\n');
				}
				for (Map.Entry<Long, EntitySection<Entity>> e : sections.entrySet()) {
					EntitySection<Entity> section = e.getValue();
					List<Entity> instances = access.instancesOf(section);
					int size = instances.size();
					if (size == 0) {
						continue;
					}
					totalRefs += size;
					int localDup = 0;
					IdentityHashMap<Object, Boolean> local = size > 1 ? new IdentityHashMap<>() : null;
					String sample = null;
					for (int i = 0; i < size; i++) {
						Entity ent = instances.get(i);
						if (local != null && local.put(ent, Boolean.TRUE) != null) {
							localDup++;
						}
						if (seen.put(ent, Boolean.TRUE) == null) {
							distinct++;
						}
						if (sample == null && ent != null) {
							sample = describe(ent);
						}
					}
					duplicateRefs += localDup;
					if (size > 100) {
						biggest.add(new long[] {e.getKey(), size, localDup});
						if (biggest.size() == 1) {
							sb.append("largest section key=").append(e.getKey())
									.append(" size=").append(size)
									.append(" intra-dups=").append(localDup)
									.append(" sample: ").append(sample).append('\n');
						}
					}
				}
				int known = access.knownUuids(manager).size();
				sb.append("sections=").append(sections.size())
						.append(" totalRefs=").append(totalRefs)
						.append(" distinctEntities=").append(distinct)
						.append(" intraSectionDuplicateRefs=").append(duplicateRefs)
						.append(" knownUuids=").append(known).append('\n');
				biggest.sort((a, b) -> Long.compare(b[1], a[1]));
				for (int i = 0; i < Math.min(biggest.size(), MAX_SECTIONS_REPORTED); i++) {
					long[] row = biggest.get(i);
					sb.append("  section key=").append(row[0])
							.append(" size=").append(row[1])
							.append(" intra-dups=").append(row[2]).append('\n');
				}
			} catch (Throwable t) {
				sb.append("dimension census failed: ").append(t).append('\n');
			}
		}
	}

	private static String describe(Entity ent) {
		try {
			return ent.getType().getDescription().getString() + " id=" + ent.getId()
					+ " pos=(" + ent.getX() + "," + ent.getY() + "," + ent.getZ() + ")"
					+ " removed=" + ent.isRemoved();
		} catch (Throwable t) {
			return "entity describe failed: " + t;
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static final class Access {
		final Field entityManager;
		final Field sectionStorage;
		final Field sections;
		final Field entityTickList;
		final Field sectionStorageField;
		final Field sectionInstances;
		final Field tickActive;
		final Field tickPassive;
		final Field tickIterated;
		final Field knownUuids;

		Access() throws ReflectiveOperationException {
			entityManager = field(ServerLevel.class, "entityManager");
			sectionStorage = field(PersistentEntitySectionManager.class, "sectionStorage");
			sections = field(EntitySectionStorage.class, "sections");
			entityTickList = field(ServerLevel.class, "entityTickList");
			sectionStorageField = field(EntitySection.class, "storage");
			Class<?> multiMap = Class.forName("net.minecraft.util.ClassInstanceMultiMap");
			sectionInstances = field(multiMap, "allInstances");
			tickActive = field(EntityTickList.class, "active");
			tickPassive = field(EntityTickList.class, "passive");
			tickIterated = field(EntityTickList.class, "iterated");
			knownUuids = field(PersistentEntitySectionManager.class, "knownUuids");
		}

		@SuppressWarnings("unchecked")
		List<Entity> instancesOf(EntitySection<Entity> section) throws IllegalAccessException {
			Object storage = sectionStorageField.get(section);
			return (List<Entity>) sectionInstances.get(storage);
		}

		@SuppressWarnings("unchecked")
		java.util.Set<java.util.UUID> knownUuids(Object manager) throws IllegalAccessException {
			return (java.util.Set<java.util.UUID>) knownUuids.get(manager);
		}

		int sizeOf(Object map) {
			if (map instanceof java.util.Map<?, ?> m) {
				return m.size();
			}
			if (map instanceof it.unimi.dsi.fastutil.objects.Object2IntMap<?> m) {
				return m.size();
			}
			if (map instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> m) {
				return m.size();
			}
			return -1;
		}
	}
}
