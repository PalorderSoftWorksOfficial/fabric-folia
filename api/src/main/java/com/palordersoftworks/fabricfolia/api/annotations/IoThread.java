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
 * Marks a method or type confined to disk-IO worker threads: chunk/entity/player
 * saving and loading.
 *
 * <p><strong>Contract:</strong> IO code must never mutate region-owned state. It
 * consumes immutable snapshots or owner-thread-produced buffers (the persistence
 * mechanism is decided and documented during the saving phase, spec section 25),
 * and hands loaded results back into a region context for integration.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface IoThread {
}
