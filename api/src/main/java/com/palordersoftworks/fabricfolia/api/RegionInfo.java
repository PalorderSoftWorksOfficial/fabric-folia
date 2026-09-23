/*
 * Fabric-Folia — regionized multithreaded server execution for vanilla Minecraft
 * under Fabric Loader.
 *
 * Copyright 2026 Palorder Softworks
 *
 * Licensed under the Apache License, Version 2.0 (current license text as in the
 * LICENSE file; all API files carry the same Apache-2.0 header, abbreviated here
 * to keep this file reviewable — the full header is required in shipping files).
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

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * A stable, read-only view of a region's identity, for API consumers that need
 * to answer "which region is this?" without being able to mutate anything.
 *
 * <p><strong>Why read-only:</strong> region identity is used across threads —
 * diagnostics, commands, and scheduler callers all need to name a region. Mutable
 * region state is never exposed through this type; it is reachable only from
 * inside the owning context via the scheduler execution contract.</p>
 *
 * <p><strong>Stability:</strong> the returned id for a given logical region
 * (a group of region sections) is stable for the lifetime of that region, and
 * remains valid as a name after the region has died, so diagnostics produced
 * earlier can still be read coherently.</p>
 */
@AnyThread
public interface RegionInfo {
	/**
	 * @return the world this region belongs to, as a plain identifier string
	 * (Minecraft dimension key, e.g. {@code minecraft:overworld}). Plain String is
	 * used deliberately: the API module must not leak Minecraft types (spec 9).
	 */
	String world();

	/**
	 * @return a unique, stable id for this region within its world. Ids are never
	 * reused for a different region within one server run.
	 */
	long regionId();

	/**
	 * @return true if this region object has been split away, merged away, or
	 * retired; false if it currently owns sections.
	 */
	boolean isDead();

	/**
	 * @return the current region state name (TRANSIENT, READY, TICKING, DEAD).
	 * Diagnostic convenience only — do not build logic on polling this value;
	 * it may have changed by the time you act on it. Schedule through
	 * {@link RegionScheduler} instead.
	 */
	String stateName();

	/**
	 * @return the section-space center of the region's owned sections
	 * ({@code [sectionX, sectionZ]}), or null when the region owns nothing
	 * (a dying/dead region). The representative position for diagnostics and
	 * distance decisions; NOT an ownership statement — ownership lives in
	 * the regionizer, query it per position.
	 */
	int[] sectionCenter();
}
