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
 * Marks a method that is safe to call from <em>any</em> execution context:
 * region workers, the global context, the network event loop, IO threads, or
 * unknown third-party threads (e.g. another mod's executor).
 *
 * <p><strong>Contract:</strong> the method itself performs any needed context
 * transfer internally — typically by scheduling work into the correct region
 * rather than touching region-owned state directly. Callers must still not
 * assume the work has completed when the method returns, unless the Javadoc
 * says the call blocks until completion.</p>
 *
 * <p>This annotation communicates a real guarantee: every scheduler entry point
 * in this API is {@code @AnyThread} by design, because cross-context scheduling
 * is the whole point of an ownership-based execution model.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface AnyThread {
}
