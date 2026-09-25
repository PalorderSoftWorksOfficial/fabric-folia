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

// Distributable jar name: folia-<version>.jar (version from mod_version).
// Everything downstream discovers the jar dynamically (compat harness scans
// fabric/build/libs), so the name is free to carry the brand.
base {
	archivesName = "folia"
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

	// Adventure MiniMessage: the single formatting system for /folia command
	// output (converted to vanilla Components for chat, ANSI for console).
	implementation(libs.adventureApi)
	implementation(libs.adventureMiniMessage)

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
	include(libs.adventureApi)
	include(libs.adventureMiniMessage)

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

// Live verification of the server GUI window icon (logo.png, MinecraftServerGui
// parity with Folia's upstream change). The production server is booted WITHOUT
// nogui by compat/gui_icon_check.py, which asserts the icon-apply log line and a
// clean shutdown. Run from Gradle (not a bare shell) so the server JVM is a
// child of this daemon and inherits the interactive desktop session — a plain
// shell on CI/non-interactive contexts is java.awt-headless and vanilla skips
// the GUI entirely (net.minecraft.server.Main checks isHeadless before showGui).
tasks.register<Exec>("verifyServerGui") {
	group = "verification"
	description = "Boots the assembled baseline server with its GUI and verifies the logo.png window icon is applied."
	workingDir(rootDir)
	commandLine("python", "compat/gui_icon_check.py")
}
