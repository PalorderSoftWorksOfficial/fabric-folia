/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central ownership-check facade (mandates §7/§8): one place answers
 * "is this world/chunk/position/entity owned by the current execution
 * context?" — with the regionizer as the ONLY authority for ownership.
 *
 * <p><strong>Authority:</strong> every query resolves through
 * {@link WorldRegionizer#ownerOfChunk} at call time; nothing here trusts a
 * cached region reference. The entity overload is the engine's registered
 * resolver (set by the fabric module when entity tracking attaches), so the
 * entity registry's migration protocol is the entity-ownership authority.</p>
 *
 * <p><strong>Failure policy:</strong> violations go through
 * {@link ViolationReporter} — STRICT throws, WARN reports and continues,
 * OFF is free. No check silently returns true to let old code run.</p>
 */
public final class RegionChecks {

	/** Resolves an entity handle to its current owning region, or null. */
	public interface EntityOwnerResolver {
		Region ownerOf(Object entity);
	}

	private static volatile EntityOwnerResolver entityOwnerResolver;
	/** Per-world regionizer authorities, keyed by stable world name. */
	private static final Map<String, WorldRegionizer> AUTHORITIES = new ConcurrentHashMap<>();

	private RegionChecks() {
	}

	/** Installs one world's reverse-lookup authority (engine wiring, per world attach). */
	public static void installAuthority(String worldKey, WorldRegionizer regionizer) {
		AUTHORITIES.put(worldKey, regionizer);
	}

	/** Removes a world's authority (world detach). */
	public static void removeAuthority(String worldKey) {
		AUTHORITIES.remove(worldKey);
	}

	/** Installs the entity-ownership resolver (engine wiring, once). */
	public static void installEntityResolver(EntityOwnerResolver resolver) {
		entityOwnerResolver = resolver;
	}

	/** @return the region owning the chunk right now, or null if none. */
	public static Region ownerOfChunk(String worldKey, int chunkX, int chunkZ) {
		WorldRegionizer regionizer = AUTHORITIES.get(worldKey);
		return regionizer == null ? null : regionizer.ownerOfChunk(chunkX, chunkZ);
	}

	/** @return the region owning the entity right now, or null if unowned/gone. */
	public static Region ownerOfEntity(Object entity) {
		EntityOwnerResolver resolver = entityOwnerResolver;
		return resolver == null ? null : resolver.ownerOf(entity);
	}

	/** @return true when the current context IS {@code region}'s owning context. */
	public static boolean ownsRegion(Region region) {
		ThreadOwnership.Context current = ThreadOwnership.current();
		return current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& current.region() == region;
	}

	/**
	 * Checks chunk ownership against the CURRENT context. A GLOBAL context
	 * (the server thread / global dispatch) is treated as allowed for this
	 * predicate: global owns what no region does, and the per-world
	 * enforcement points decide their own stricter policies — this predicate
	 * answers the ownership question, not the safety question.
	 */
	public static boolean ownsChunk(String worldKey, int chunkX, int chunkZ) {
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.GLOBAL
				|| current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
						&& current.region() == null) {
			return true;
		}
		Region currentOwner = current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				? (Region) current.region()
				: null;
		if (currentOwner != null) {
			Region actual = ownerOfChunk(worldKey, chunkX, chunkZ);
			return actual == currentOwner;
		}
		// ASYNC/NETWORK/IO/UNKNOWN contexts own no region state, ever.
		return false;
	}

	/** @return true when the current context owns the entity (or is global). */
	public static boolean ownsEntity(Object entity) {
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.GLOBAL) {
			return true;
		}
		if (current.kind() != com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
			return false;
		}
		Region owner = ownerOfEntity(entity);
		return owner != null && owner == current.region();
	}

	/**
	 * Enforces chunk ownership for the current context. GLOBAL passes (it is
	 * the sanctioned mutation context for unowned/global state); a region
	 * context passes only when the regionizer says it owns the chunk; ASYNC
	 * and NETWORK contexts fail always. Reports per the configured mode.
	 *
	 * @return true when the access is allowed
	 */
	public static boolean checkChunkAccess(String operation, String worldKey, int chunkX, int chunkZ) {
		if (ownsChunk(worldKey, chunkX, chunkZ)) {
			return true;
		}
		ThreadOwnership.Context current = ThreadOwnership.current();
		Region target = ownerOfChunk(worldKey, chunkX, chunkZ);
		ViolationReporter.renderAndReport(operation, current, target);
		return false;
	}

	/** Enforces entity ownership for the current context. */
	public static boolean checkEntityAccess(String operation, Object entity) {
		if (ownsEntity(entity)) {
			return true;
		}
		ThreadOwnership.Context current = ThreadOwnership.current();
		Region target = ownerOfEntity(entity);
		ViolationReporter.renderAndReport(operation, current, target);
		return false;
	}

	/**
	 * Classifies the current thread as the ASYNC context for the duration of
	 * a task. Async work is explicitly NOT region, global, network, or IO —
	 * every ownership predicate in this class fails for it.
	 *
	 * @return the previous context token to pass to {@link ThreadOwnership#exit}
	 */
	public static ThreadOwnership.Context enterAsync() {
		return ThreadOwnership.enterSide(com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.ASYNC);
	}
}
