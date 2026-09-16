/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The administrator-facing console facade.
 *
 * <p><strong>What this is (and is not):</strong> a presentation layer over
 * SLF4J — one consistent brand prefix, severity wording that survives
 * colorless terminals, and a restrained ANSI palette that <em>reinforces</em>
 * meaning rather than replacing it (severity is always carried by the text:
 * {@code WARNING:}, {@code ERROR:}, {@code FATAL:}). It is inspired by the
 * presentation quality of Paper/Folia's console; it copies no Paper or Bukkit
 * code and introduces no dependency on either.</p>
 *
 * <p><strong>Color policy:</strong> colors mark severity and category, never
 * decorate. Info is uncolored; success green; warnings amber; errors and
 * fatal red; compatibility and configuration messages cyan. Normal server
 * operation stays visually quiet.</p>
 *
 * <p><strong>Plain-terminal support:</strong> the level word is always part
 * of the text, so the log is fully understandable when ANSI is disabled.
 * ANSI is enabled by default (the log pipeline renders it); it is disabled
 * by setting the environment variable {@code NO_COLOR} or the system
 * property {@code -Dfabricfolia.console.ansi=false} (both documented in
 * TROUBLESHOOTING.md).</p>
 *
 * <p><strong>Anti-spam:</strong> warnings that would repeat per occurrence
 * use {@link #once} — the first occurrence logs at full strength, repeats
 * are suppressed for the life of the process. This is a startup-quality
 * guarantee: nothing in the normal tick path logs per tick.</p>
 */
public final class Console {

	/** The single brand prefix used by every user-facing line. */
	public static final String PREFIX = "[FabricFolia]";

	private static final Logger LOGGER = LoggerFactory.getLogger("fabricfolia-console");

	/** Message severity/category. The word appears in text for WARN and above. */
	public enum Level {
		INFO(""), SUCCESS("SUCCESS"), WARNING("WARNING"), ERROR("ERROR"),
		FATAL("FATAL"), COMPAT(""), CONFIG(""), PERF(""), SCHED(""), DEBUG("");

		private final String word;

		Level(String word) {
			this.word = word;
		}

		/** The severity word rendered into the text (empty for informational levels). */
		public String word() {
			return word;
		}
	}

	private static final boolean ANSI = detectAnsi();

	/** Restrained palette: severity color + dim brand prefix. */
	private static final Map<Level, String> COLOR = Map.of(
			Level.SUCCESS, "\u001B[32m",   // green
			Level.WARNING, "\u001B[33m",   // amber
			Level.ERROR, "\u001B[31m",     // red
			Level.FATAL, "\u001B[1;31m",   // bold red
			Level.COMPAT, "\u001B[36m",    // cyan
			Level.CONFIG, "\u001B[36m",    // cyan
			Level.PERF, "\u001B[95m",      // bright magenta
			Level.SCHED, "\u001B[34m");    // blue
	private static final String RESET = "\u001B[0m";
	private static final String DIM = "\u001B[2m";

	/** Keys whose once-only message has already been emitted. */
	private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

	private Console() {
	}

	private static boolean detectAnsi() {
		String prop = System.getProperty("fabricfolia.console.ansi");
		if (prop != null) {
			return Boolean.parseBoolean(prop);
		}
		return System.getenv("NO_COLOR") == null;
	}

	/** @return whether ANSI codes are being emitted (diagnostics/tests). */
	public static boolean ansiEnabled() {
		return ANSI;
	}

	// ---------------------------------------------------------------------------
	// Severity entry points — the only API user code should need.
	// ---------------------------------------------------------------------------

	/** Normal informational message. */
	public static void info(String message) {
		log(Level.INFO, message, null);
	}

	/** A subsystem initialized successfully. */
	public static void success(String message) {
		log(Level.SUCCESS, message, null);
	}

	/**
	 * A potential compatibility or configuration problem that does not
	 * necessarily prevent operation. Prefer {@link #once} for anything that
	 * could recur.
	 */
	public static void warning(String message) {
		log(Level.WARNING, message, null);
	}

	/** A specific operation or subsystem failed (human explanation first). */
	public static void error(String message) {
		log(Level.ERROR, message, null);
	}

	/** A specific operation failed; the throwable's stack trace goes to the log. */
	public static void error(String message, Throwable t) {
		log(Level.ERROR, message, t);
	}

	/**
	 * Continuing would be unsafe or impossible. Reserved for genuinely fatal
	 * conditions — not for compatibility findings, which are warnings.
	 */
	public static void fatal(String message) {
		log(Level.FATAL, message, null);
	}

	/** Compatibility detection and classification messages. */
	public static void compat(String message) {
		log(Level.COMPAT, message, null);
	}

	/** Configuration load/summary messages. */
	public static void config(String message) {
		log(Level.CONFIG, message, null);
	}

	/** Performance-relevant diagnostics (profiling, metrics). */
	public static void perf(String message) {
		log(Level.PERF, message, null);
	}

	/** Region scheduler lifecycle messages. */
	public static void sched(String message) {
		log(Level.SCHED, message, null);
	}

	/** Debug-gated detail (see {@code diagnostics.debug-logging}). */
	public static void debug(String message) {
		LOGGER.debug(format(Level.DEBUG, message));
	}

	/**
	 * Logs {@code message} the first time {@code key} is seen; suppresses
	 * repeats for the life of the process (anti-spam policy). Use for any
	 * warning that could otherwise fire per occurrence.
	 */
	public static void once(String key, Level level, String message) {
		if (ONCE.add(key)) {
			log(level, message, null);
		}
	}

	// ---------------------------------------------------------------------------

	private static void log(Level level, String message, Throwable t) {
		String line = format(level, message);
		switch (level) {
			case WARNING -> LOGGER.warn(line);
			case ERROR, FATAL -> {
				if (t != null) {
					LOGGER.error(line, t);
				} else {
					LOGGER.error(line);
				}
			}
			case DEBUG -> LOGGER.debug(line);
			default -> LOGGER.info(line);
		}
	}

	/** Builds the full line: brand prefix, optional severity word, message. */
	public static String format(Level level, String message) {
		String word = level.word();
		String body = word.isEmpty() ? message : word + ": " + message;
		if (!ANSI) {
			return PREFIX + " " + body;
		}
		String color = COLOR.get(level);
		String prefix = DIM + PREFIX + RESET;
		if (word.isEmpty()) {
			return color == null ? prefix + " " + message
					: prefix + " " + color + message + RESET;
		}
		String wordColor = color == null ? "" : color;
		return prefix + " " + wordColor + word + RESET + ": " + message;
	}
}
