/*
 * Fabric-Folia API module build.
 *
 * The API module is the compile-time contract for other Fabric mods. It must:
 *   - never reference Minecraft or Fabric Loader internals,
 *   - never depend on Mixin,
 *   - depend on as little as possible so third-party builds stay clean.
 *
 * See spec section 12 (Gradle structure) and section 9 (API surface).
 */

plugins {
	id("java-library")
}

dependencies {
	// javax.annotation.Nonnull/Nullable are a compile-time-only documentation
	// dependency (they communicate real guarantees, see spec section 21). They
	// do NOT become a runtime dependency of consumers because it is compileOnly.
	compileOnly(libs.jsr305)

	testImplementation(libs.junitJupiter)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Nothing in this module may accidentally leak a dependency into the public
// surface: if a compile-time need appears here that drags in Minecraft or
// loader internals, that is an API design defect, not a build fix.
