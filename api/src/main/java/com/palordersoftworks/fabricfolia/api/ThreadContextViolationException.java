/*
 * Fabric-Folia — regionized multithreaded server execution for vanilla Minecraft
 * under Fabric Loader.
 *
 * Copyright 2026 Palorder Softworks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palordersoftworks.fabricfolia.api;

/**
 * Thrown (STRICT mode) or reported (WARN mode) when code executing in one
 * ownership context directly accesses state owned by a different region.
 *
 * <p>The exception's message is the full actionable diagnostic (spec section 8):
 * the operation attempted, the current execution context, the target's actual
 * ownership, the physical thread name, and what the caller should have done
 * instead. It names the correct scheduler entry point so the fix is obvious:</p>
 *
 * <pre>
 * Fabric-Folia Thread Context Violation
 *
 * Operation: Entity state access
 * Current execution context: Region world:4
 * Target ownership: Region world:5
 * Current thread: Fabric-Folia-Worker-3
 * Expected context: Region world:5
 *
 * This access is unsafe because the target object is owned by another region.
 * Schedule the operation through the appropriate RegionScheduler entry point.
 * </pre>
 */
public class ThreadContextViolationException extends RuntimeException {
	public ThreadContextViolationException(String message) {
		super(message);
	}
}
