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

package com.palordersoftworks.fabricfolia.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or type whose calls (or executions) are confined to a thread
 * that is currently executing on behalf of exactly one owning
 * {@link com.palordersoftworks.fabricfolia.api.Region region}.
 *
 * <p><strong>What this guarantees when present:</strong> the annotated code runs
 * inside a region tick or a task dispatched into a region's task queue; region-local
 * state owned by that region may be accessed directly and without locking.</p>
 *
 * <p><strong>What this does NOT guarantee:</strong> which <em>physical</em> thread
 * executes the code. Regions are dispatched onto a bounded shared worker pool, so
 * two consecutive ticks of one region may run on different workers. Ownership is
 * per-execution-context, not per-OS-thread; use
 * {@link com.palordersoftworks.fabricfolia.api.ThreadContext} to query the owning
 * region at runtime rather than comparing thread identities.</p>
 *
 * <p>Methods marked {@code @RegionThread} must not be called from network, IO, or
 * global contexts; doing so is an ownership violation and will be reported by the
 * thread-context diagnostics (STRICT mode throws, WARN mode logs).</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface RegionThread {
}
