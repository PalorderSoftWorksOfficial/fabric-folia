/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

/**
 * Raised on any GPU-path failure (missing runtime, build error, enqueue
 * error). Every site that can catch it converts it into the documented CPU
 * fallback with the reason visible in metrics — never a silent drop.
 */
public final class GpuException extends RuntimeException {

	public GpuException(String message) {
		super(message);
	}

	public GpuException(String message, Throwable cause) {
		super(message, cause);
	}
}
