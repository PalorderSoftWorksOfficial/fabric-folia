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
 * Marks a method or type whose calls (or executions) are confined to the global
 * execution context — the single context that owns genuinely server-wide state
 * (game rules, world border definition, global schedulers, the regionizer itself).
 *
 * <p><strong>What this guarantees when present:</strong> the annotated code runs on
 * the global context's worker, where global state may be mutated without locking.</p>
 *
 * <p><strong>What this does NOT guarantee:</strong> that a vanilla single "main
 * thread" exists — it does not. Under Fabric-Folia there is no single gameplay
 * thread; the global context is just one more logical execution context with a
 * distinct ownership domain. Global work is scheduled via
 * {@link com.palordersoftworks.fabricfolia.api.GlobalScheduler}.</p>
 *
 * <p>The global context must not become a routing bottleneck for ordinary region
 * gameplay (spec section 4); annotation usage should reflect genuinely global state.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface GlobalThread {
}
