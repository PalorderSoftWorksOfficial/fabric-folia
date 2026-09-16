/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

/**
 * Thrown when the configuration file cannot be parsed, contains invalid values,
 * or cannot be migrated. Carries the config file path and the offending key so
 * the operator gets an actionable message instead of a stack trace to nowhere.
 *
 * <p><strong>Fail-closed policy:</strong> a configuration that cannot be validated
 * disables Fabric-Folia entirely (vanilla execution continues) rather than
 * guessing at operator intent. This is deliberate: silently falling back to
 * defaults could leave an operator running with thread-checking or threading
 * behavior they explicitly turned off (or on).</p>
 */
public class ConfigException extends RuntimeException {
	public ConfigException(String message) {
		super(message);
	}

	public ConfigException(String message, Throwable cause) {
		super(message, cause);
	}
}
