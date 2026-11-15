package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;
import com.palordersoftworks.fabricfolia.api.annotations.GlobalThread;
import com.palordersoftworks.fabricfolia.api.annotations.RegionThread;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiContractTest {
    @Test
    void apiContainsNoMinecraftOrFabricRuntimeTypes() throws Exception {
        Set<Class<?>> publicTypes = Set.of(
                FabricFoliaApi.class,
                RegionScheduler.class,
                GlobalScheduler.class,
                AsyncScheduler.class,
                EntityScheduler.class,
                RegionInfo.class,
                ThreadContext.class,
                ValidationMode.class
        );
        for (Class<?> type : publicTypes) {
            assertTrue(Arrays.stream(type.getMethods())
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .noneMatch(parameter -> parameter.getName().startsWith("net.minecraft.")
                            || parameter.getName().startsWith("net.fabricmc.")));
            assertTrue(Arrays.stream(type.getMethods())
                    .map(Method::getReturnType)
                    .noneMatch(result -> result.getName().startsWith("net.minecraft.")
                            || result.getName().startsWith("net.fabricmc.")));
        }
    }

    @Test
    void apiExposesEntrypointAndAllFourSchedulers() throws Exception {
        assertNotNull(FabricFoliaApi.class.getMethod("regionScheduler"));
        assertNotNull(FabricFoliaApi.class.getMethod("entityScheduler"));
        assertNotNull(FabricFoliaApi.class.getMethod("globalScheduler"));
        assertNotNull(FabricFoliaApi.class.getMethod("asyncScheduler"));
        assertNotNull(FabricFoliaApi.Initializer.class.getMethod("onInitialized", FabricFoliaApi.class));
    }

    @Test
    void contextKindsAndValidationModesAreExplicit() {
        assertEquals(Set.of("REGION", "GLOBAL", "NETWORK", "IO", "ASYNC", "UNKNOWN"),
                Arrays.stream(ThreadContext.Kind.values()).map(Enum::name).collect(Collectors.toSet()));
        assertEquals(Set.of("OFF", "WARN", "STRICT"),
                Arrays.stream(ValidationMode.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void publicThreadAnnotationsAreAvailable() {
        assertNotNull(AnyThread.class);
        assertNotNull(GlobalThread.class);
        assertNotNull(RegionThread.class);
    }
}
