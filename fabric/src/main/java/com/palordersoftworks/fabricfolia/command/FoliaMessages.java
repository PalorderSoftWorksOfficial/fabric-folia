/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * The centralized formatting layer (spec 32): every user-facing FabricFolia
 * string is authored once in MiniMessage and rendered to the target
 * medium here — vanilla {@link net.minecraft.network.chat.Component} trees
 * for in-game chat, ANSI for the console. Command code never concatenates
 * color codes by hand.
 *
 * <p><strong>Two output mediums:</strong></p>
 * <ul>
 *   <li>{@link #parse} → Adventure component (in-game via
 *       {@link #toMinecraft});</li>
 *   <li>{@link #toConsoleString} → plain-ish console string honoring the
 *       Console layer's ANSI policy (structured prefix per level, no raw
 *       escapes where unsupported).</li>
 * </ul>
 *
 * <p><strong>Palette</strong> (the semantic colors, used everywhere): header
 * gold, label aqua, value white, ok green, warn yellow, error red, accent
 * dark gray, info gray.</p>
 */
public final class FoliaMessages {

	private static final MiniMessage MM = MiniMessage.miniMessage();

	public static final String HEADER_TAG = "<gold><bold>";
	public static final String RULE = "<gold>" + "─".repeat(38) + "</gold>";

	private FoliaMessages() {
	}

	/** Parses a MiniMessage string into an Adventure component. */
	public static Component parse(String miniMessage) {
		return MM.deserialize(miniMessage);
	}

	/** Parses and converts to a vanilla component (in-game chat). */
	public static MutableComponent toMinecraft(String miniMessage) {
		return ComponentConverter.toMinecraft(parse(miniMessage));
	}

	/**
	 * Renders a MiniMessage string for the console: tags stripped by the
	 * plain serializer, then structured with the Console layer's level
	 * prefix. The Console layer owns ANSI coloring of that prefix; message
	 * bodies stay plain so no raw escapes leak into colorless terminals.
	 */
	public static String toConsoleString(String miniMessage) {
		return MM.stripTags(miniMessage);
	}

	/** Header line (bold gold). */
	public static MutableComponent header(String text) {
		return toMinecraft(HEADER_TAG + text + "</bold></gold>");
	}

	/** Horizontal rule under headers. */
	public static MutableComponent rule() {
		return toMinecraft(RULE);
	}

	/** A "label: value" row. */
	public static MutableComponent row(String label, String value) {
		return toMinecraft("<aqua>" + label + "</aqua><gray>:</gray> <white>" + value + "</white>");
	}

	/** OK status line. */
	public static MutableComponent ok(String text) {
		return toMinecraft("<green>✔ " + text + "</green>");
	}

	/** Warning status line. */
	public static MutableComponent warn(String text) {
		return toMinecraft("<yellow>⚠ " + text + "</yellow>");
	}

	/** Error status line. */
	public static MutableComponent error(String text) {
		return toMinecraft("<red>✘ " + text + "</red>");
	}

	/** Neutral information line. */
	public static MutableComponent info(String text) {
		return toMinecraft("<gray>" + text + "</gray>");
	}

	/** An indented detail row. */
	public static MutableComponent detail(String label, String value) {
		return toMinecraft("  <dark_aqua>" + label + "</dark_aqua><dark_gray> = </dark_gray><white>" + value + "</white>");
	}

	/** @return the MiniMessage markup for a horizontal rule. */
	public static String ruleMarkup() {
		return RULE;
	}
}
