package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;
import com.palordersoftworks.fabricfolia.api.annotations.GlobalThread;
import com.palordersoftworks.fabricfolia.api.annotations.IoThread;
import com.palordersoftworks.fabricfolia.api.annotations.NetworkThread;
import com.palordersoftworks.fabricfolia.api.annotations.RegionThread;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiSurfaceTest {

    @Test
    void positionCarriesTheTargetWorldAndChunkCoordinates() {
        RegionScheduler.Position position = RegionScheduler.Position.of("minecraft:the_nether", -3, 7);

        assertEquals("minecraft:the_nether", position.world());
        assertEquals(-3, position.chunkX());
        assertEquals(7, position.chunkZ());
    }

    @Test
    void cancelHandleIsIdempotent() {
        RegionScheduler.CancelHandleView handle = new RegionScheduler.CancelHandleView();

        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
    }

    @Test
    void threadAnnotationsAreDocumentedClassRetainedAndUsableOnMethodsTypesAndConstructors() {
        List<Class<? extends Annotation>> annotations = List.of(
                AnyThread.class,
                GlobalThread.class,
                NetworkThread.class,
                IoThread.class,
                RegionThread.class
        );
        for (Class<? extends Annotation> annotation : annotations) {
            assertEquals(RetentionPolicy.CLASS,
                    annotation.getAnnotation(Retention.class).value(), annotation.getSimpleName());
            assertTrue(annotation.isAnnotationPresent(Documented.class), annotation.getSimpleName());
            List<ElementType> targets = Arrays.asList(annotation.getAnnotation(Target.class).value());
            assertTrue(targets.containsAll(
                            List.of(ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR)),
                    annotation.getSimpleName());
        }
    }

    @Test
    void absenceIsExpressedAsOptionalOnLookupMethods() throws Exception {
        Method ownerOf = RegionScheduler.class.getMethod("ownerOf", RegionScheduler.Position.class);
        Method currentRegion = ThreadContext.class.getMethod("currentRegion");

        assertEquals(Optional.class, ownerOf.getReturnType());
        assertEquals(Optional.class, currentRegion.getReturnType());
    }

    @Test
    void missingRegionPolicyNamesAreStable() {
        assertEquals(List.of("DROP", "RUN_ON_GLOBAL"), Arrays.stream(
                        RegionScheduler.MissingRegionPolicy.values()).map(Enum::name).toList());
    }

    @Test
    void sectionCenterIsDeclaredAsAnIntArray() throws Exception {
        assertEquals(int[].class, RegionInfo.class.getMethod("sectionCenter").getReturnType());
    }
}
