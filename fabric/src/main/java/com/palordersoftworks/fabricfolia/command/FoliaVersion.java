/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Version/build metadata from one source of truth: the loader's mod
 * containers. No version is ever hardcoded here; when a component's version
 * is not discoverable the display value is "Unknown" (never guessed).
 */
public final class FoliaVersion {

	/** One resolved version row: label + value (value may be "Unknown"). */
	public record Row(String label, String value) {
	}

	private FoliaVersion() {
	}

	/** @return FabricFolia's own version (from its mod container). */
	public static String fabricFolia() {
		return versionOf("fabricfolia");
	}

	/** @return the Minecraft version (the loader's minecraft container). */
	public static String minecraft() {
		return versionOf("minecraft");
	}

	/** @return the Fabric Loader version. */
	public static String loader() {
		try {
			return FabricLoader.getInstance().getModContainer("fabricloader")
					.map(container -> container.getMetadata().getVersion().getFriendlyString())
					.orElse("Unknown");
		} catch (RuntimeException ex) {
			return "Unknown";
		}
	}

	/** @return the Fabric API version, or "not installed". */
	public static String fabricApi() {
		String version = versionOf("fabric-api");
		return version.equals("Unknown") ? "not installed" : version;
	}

	/** @return the Java runtime version. */
	public static String java() {
		return System.getProperty("java.version", "Unknown");
	}

	/** @return the server implementation brand (the brand mixin's value). */
	public static String implementation() {
		return "FabricFolia (regionized)";
	}

	/** @return the build identifier: the mod version (jar-packaged truth). */
	public static String build() {
		return fabricFolia();
	}

	/** @return the display rows for /folia version, in order. */
	public static java.util.List<Row> rows() {
		java.util.List<Row> rows = new java.util.ArrayList<>();
		rows.add(new Row("Minecraft", minecraft()));
		rows.add(new Row("Fabric Loader", loader()));
		rows.add(new Row("Fabric API", fabricApi()));
		rows.add(new Row("FabricFolia", fabricFolia()));
		rows.add(new Row("Java", java()));
		rows.add(new Row("Implementation", implementation()));
		rows.add(new Row("Build", build()));
		return rows;
	}

	private static String versionOf(String modId) {
		try {
			Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);
			return container
					.map(c -> c.getMetadata().getVersion().getFriendlyString())
					.orElse("Unknown");
		} catch (RuntimeException ex) {
			return "Unknown";
		}
	}
}
