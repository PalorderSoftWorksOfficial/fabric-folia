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

// Root build script for the Fabric-Folia multi-project.
//
// Module layout and dependency direction (spec section 12):
//
//   api      — public, Fabric-native API. No Minecraft types, no Mixin. Other mods
//              compile against this module alone.
//   common   — loader-agnostic engine (regionizer, scheduler, thread context).
//              Depends on api. Pure Java, unit-testable without Loom.
//   fabric   — the mod: Mixins, Fabric API integration, entrypoints, commands.
//              Depends on common and api. Produces the distributable jar.
//
// The common/fabric split is real, not artificial: everything in common is
// reasoned about and tested WITHOUT Minecraft on the classpath (the concurrency
// core must be provable in isolation), while fabric contains the loader- and
// Minecraft-coupled glue that cannot be tested without a runtime.

plugins {
	// Applied here so the version is resolved once for all subprojects; each
	// subproject applies the plugin without re-declaring a version. The version
	// is pinned in gradle/libs.versions.toml (single source of truth).
	alias(libs.plugins.fabricLoom) apply false
	id("java-library")
}

allprojects {
	group = property("maven_group") as String
	version = property("mod_version") as String

	repositories {
		mavenCentral()
	}
}

subprojects {
	apply(plugin = "java-library")

	java {
		// Minecraft 26.2 requires Java 25 (verified: docs.fabricmc.net,
		// fabricmc.net/2026/06/15/262.html). Build toolchain pinned accordingly.
		sourceCompatibility = JavaVersion.VERSION_25
		targetCompatibility = JavaVersion.VERSION_25
	}

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.release = 25
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
		// Concurrency tests can be long-running; give them headroom but never let
		// a hang masquerade as a slow test.
		systemProperty("junit.jupiter.execution.timeout.default", "120s")
		testLogging {
			events("failed", "skipped")
			showExceptions = true
			showCauses = true
		}
	}
}
