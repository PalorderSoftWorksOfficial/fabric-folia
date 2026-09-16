/*
 * Fabric-Folia fabric module build — the actual mod jar.
 *
 * This module is the only place where Mixins, Fabric API hooks, Minecraft
 * internals, and loader entrypoints live. It depends on common (engine) and
 * api (public contract), and produces the distributable mod jar.
 */

plugins {
	id("java-library")
	alias(libs.plugins.fabricLoom)
}

dependencies {
	// Minecraft itself. 26.2 is unobfuscated (Mojang names ARE the runtime
	// names), so there is no mappings layer to configure. Verified 2026-09-14
	// against the current fabric-example-mod.
	minecraft("com.mojang:minecraft:${property("minecraft_version")}")
	implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

	// Fabric API: used where hooks exist (server lifecycle, command registration).
	// Mixins are reserved for interception Fabric API cannot express (spec 15).
	implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

	// Engine + API modules: compiled against AND bundled jar-in-jar so the
	// mod jar is self-contained (Loader unpacks nested jars declared in
	// fabric.mod.json's implicit nests — Loom generates them from include()).
	implementation(project(":api"))
	implementation(project(":common"))
	include(project(":api"))
	include(project(":common"))

	// SnakeYAML Engine is bundled inside the mod jar (Loom's include() nests
	// it jar-in-jar; declared in fabric.mod.json "jars" so loader unpacks it).
	// The include() mechanism for plain libraries on MC 26.2 is verified during
	// this build; fallback is an Apache-2.0-compatible vendored copy.
	include(libs.snakeyamlEngine)

	compileOnly(libs.jsr305)

	testImplementation(libs.junitJupiter)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
	splitEnvironmentSourceSets()
	// Fabric-Folia is a dedicated-server mod: only the main (server-side)
	// source set is part of the mod; the client source set stays empty.
	mods {
		create("fabricfolia") {
			sourceSet(sourceSets.main.get())
		}
	}
}

val modVersion = property("mod_version") as String

tasks.named<ProcessResources>("processResources") {
	inputs.property("version", modVersion)
	filesMatching("fabric.mod.json") {
		expand("version" to modVersion)
	}
}

tasks.named<Jar>("jar") {
	// Ship the license inside the jar, as the example mod does.
	from(rootProject.file("LICENSE")) {
		rename { "${it}_fabric-folia" }
	}
}
