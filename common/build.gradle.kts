/*
 * Fabric-Folia common module build.
 *
 * The common module hosts the loader-agnostic engine:
 *   - regionizer, regions, region sections (spec section 2.2),
 *   - worker-pool scheduler and task queues (spec section 10),
 *   - thread-context diagnostics core (spec section 8),
 *   - the comment-preserving YAML config engine (spec sections 11/13).
 *
 * Everything here is designed to be unit-tested WITHOUT Minecraft on the
 * classpath. Code that must touch Minecraft types belongs in the fabric module.
 */

plugins {
	id("java-library")
}

dependencies {
	api(project(":api"))
	compileOnly(libs.jsr305)

	// Config engine (spec section 13). Apache-2.0, YAML 1.2, actively
	// maintained. Comment round-tripping is implemented via SnakeYAML Engine's
	// LOW-LEVEL Composer/Node API with LoadSettingsBuilder.parseComments(true):
	// the binding API would silently drop administrator comments on save.
	// At runtime in the mod jar this is bundled via Loom include() — see
	// fabric/build.gradle.kts and fabric.mod.json "jars".
	implementation(libs.snakeyamlEngine)

	testImplementation(libs.junitJupiter)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
