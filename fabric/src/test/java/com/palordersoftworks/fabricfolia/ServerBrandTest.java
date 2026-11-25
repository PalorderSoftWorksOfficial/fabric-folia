package com.palordersoftworks.fabricfolia;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.mixin.MinecraftServerMixin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: getServerModName() runs during loadLevel, before any engine
 * exists when general.enabled=false (vanilla mode) — the brand injection
 * dereferenced FabricFoliaMod.engine() and crashed the boot with an NPE.
 */
class ServerBrandTest {

	private FoliaConfig savedConfig;
	private FoliaConfig savedRuntimeConfig;

	@BeforeEach
	void save() {
		savedConfig = FabricFoliaMod.config;
		savedRuntimeConfig = FabricFoliaMod.runtimeConfig;
	}

	@AfterEach
	void restore() {
		FabricFoliaMod.config = savedConfig;
		FabricFoliaMod.runtimeConfig = savedRuntimeConfig;
	}

	private static CallbackInfoReturnable<String> invokeBrand() throws Exception {
		Method handler = MinecraftServerMixin.class.getDeclaredMethod("fabricfolia$brand", CallbackInfoReturnable.class);
		handler.setAccessible(true);
		CallbackInfoReturnable<String> cir = new CallbackInfoReturnable<>("getServerModName", true, (String) null);
		handler.invoke(new MinecraftServerMixin(), cir);
		return cir;
	}

	@Test
	void brandInjectionSurvivesMissingEngineAndConfig() throws Exception {
		assertNull(FabricFoliaMod.engine(), "this JVM must run without an engine (general.enabled=false state)");
		FabricFoliaMod.config = null;
		FabricFoliaMod.runtimeConfig = null;

		CallbackInfoReturnable<String> cir = invokeBrand();

		assertFalse(cir.isCancelled(), "vanilla brand must be left untouched");
		assertNull(cir.getReturnValue());
	}

	@Test
	void brandAppliesFromConfigWithoutEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-brand");
		Path file = dir.resolve("fabric-folia.yml");
		Files.writeString(file, "general:\n  server-brand: true\n  server-brand-name: \"folia-test-brand\"\n");
		FoliaConfig config = FoliaConfig.at(file);
		config.load();
		config.freeze();

		FabricFoliaMod.config = config;
		FabricFoliaMod.runtimeConfig = config;

		CallbackInfoReturnable<String> cir = invokeBrand();

		assertTrue(cir.isCancelled(), "configured brand must override vanilla");
		assertEquals("folia-test-brand", cir.getReturnValue());
	}

	@Test
	void liveRuntimeBrandOverridesStartupBrandWithoutChangingEngineGate() throws Exception {
		Path dir = Files.createTempDirectory("folia-brand-runtime");
		Path startupFile = dir.resolve("startup.yml");
		Path runtimeFile = dir.resolve("runtime.yml");
		Files.writeString(startupFile, """
				general:
				  enabled: true
				  server-brand: true
				  server-brand-name: "startup-brand"
				""");
		Files.writeString(runtimeFile, """
				general:
				  enabled: false
				  server-brand: true
				  server-brand-name: "reloaded-brand"
				""");
		FoliaConfig startup = FoliaConfig.at(startupFile);
		startup.load();
		startup.freeze();
		FoliaConfig runtime = FoliaConfig.at(runtimeFile);
		runtime.load();
		runtime.freeze();

		FabricFoliaMod.config = startup;
		FabricFoliaMod.runtimeConfig = runtime;

		CallbackInfoReturnable<String> cir = invokeBrand();

		assertTrue(cir.isCancelled());
		assertEquals("reloaded-brand", cir.getReturnValue());
	}

	@Test
	void masterSwitchOffRestoresVanillaBrandEvenWhenBrandOverrideWasRequested() throws Exception {
		Path dir = Files.createTempDirectory("folia-brand-engine-off");
		Path file = dir.resolve("fabric-folia.yml");
		Files.writeString(file, "general:\n  enabled: false\n  server-brand: true\n  server-brand-name: \"folia-test-brand\"\n");
		FoliaConfig config = FoliaConfig.at(file);
		config.load();
		config.freeze();

		FabricFoliaMod.config = config;
		FabricFoliaMod.runtimeConfig = config;

		CallbackInfoReturnable<String> cir = invokeBrand();

		assertFalse(cir.isCancelled());
	}

	@Test
	void brandFallsBackToVanillaWhenOverrideDisabled() throws Exception {
		Path dir = Files.createTempDirectory("folia-brand-off");
		Path file = dir.resolve("fabric-folia.yml");
		Files.writeString(file, "general:\n  server-brand: false\n");
		FoliaConfig config = FoliaConfig.at(file);
		config.load();
		config.freeze();

		FabricFoliaMod.config = config;
		FabricFoliaMod.runtimeConfig = config;

		CallbackInfoReturnable<String> cir = invokeBrand();

		assertFalse(cir.isCancelled(), "server-brand=false must not modify the brand");
	}
}
