package com.palordersoftworks.fabricfolia.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkResidency {

	private static final Map<String, Resident> BY_WORLD = new ConcurrentHashMap<>();

	private ChunkResidency() {
	}

	public static void markResident(String worldKey, int chunkX, int chunkZ) {
		residentFor(worldKey).add(ChunkPos.pack(chunkX, chunkZ));
	}

	public static void markUnresident(String worldKey, int chunkX, int chunkZ) {
		residentFor(worldKey).remove(ChunkPos.pack(chunkX, chunkZ));
	}

	public static boolean isNeighborhoodResident(String worldKey, int chunkX, int chunkZ) {
		Resident resident = BY_WORLD.get(worldKey);
		if (resident == null) {
			return false;
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!resident.contains(ChunkPos.pack(chunkX + dx, chunkZ + dz))) {
					return false;
				}
			}
		}
		return true;
	}

	public static void clearWorld(String worldKey) {
		BY_WORLD.remove(worldKey);
	}

	public static void clearAll() {
		BY_WORLD.clear();
	}

	public static int residentCount(String worldKey) {
		Resident resident = BY_WORLD.get(worldKey);
		return resident == null ? 0 : resident.size();
	}

	private static Resident residentFor(String worldKey) {
		return BY_WORLD.computeIfAbsent(worldKey, key -> new Resident());
	}

	public static final class Resident {

		private volatile LongOpenHashSet snapshot = new LongOpenHashSet();

		synchronized void add(long packedChunkPos) {
			if (snapshot.contains(packedChunkPos)) {
				return;
			}
			LongOpenHashSet updated = snapshot.clone();
			updated.add(packedChunkPos);
			snapshot = updated;
		}

		synchronized void remove(long packedChunkPos) {
			if (!snapshot.contains(packedChunkPos)) {
				return;
			}
			LongOpenHashSet updated = snapshot.clone();
			updated.remove(packedChunkPos);
			snapshot = updated;
		}

		public boolean contains(long packedChunkPos) {
			return snapshot.contains(packedChunkPos);
		}

		public int size() {
			return snapshot.size();
		}
	}
}
