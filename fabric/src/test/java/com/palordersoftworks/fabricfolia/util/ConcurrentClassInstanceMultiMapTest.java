package com.palordersoftworks.fabricfolia.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentClassInstanceMultiMapTest {

    @Test
    void preservesInsertionOrderAndTypedViews() {
        ConcurrentClassInstanceMultiMap<Object> map = new ConcurrentClassInstanceMultiMap<>(Object.class);
        Object first = "first";
        Object second = 2;
        Object third = "third";

        map.add(first);
        map.add(second);
        map.add(third);

        assertEquals(List.of(first, second, third), map.getAllInstances());
        assertEquals(List.of("first", "third"), new ArrayList<>(map.find(String.class)));
        assertEquals(3, map.size());
        assertFalse(map.add(first));
        assertTrue(map.remove(second));
        assertEquals(List.of(first, third), map.getAllInstances());
    }

    @Test
    void readersCanIterateWhileAnotherThreadMutates() throws InterruptedException {
        ConcurrentClassInstanceMultiMap<Integer> map = new ConcurrentClassInstanceMultiMap<>(Integer.class);
        for (int i = 0; i < 1000; i++) {
            map.add(i);
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int pass = 0; pass < 1000; pass++) {
                    for (Integer value : map) {
                        if (value == null) {
                            throw new AssertionError("Null element observed");
                        }
                    }
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int pass = 0; pass < 1000; pass++) {
                    map.add(1000 + pass);
                    map.remove(pass);
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        reader.start();
        writer.start();
        start.countDown();
        reader.join();
        writer.join();

        assertEquals(null, failure.get());
        assertEquals(1000, map.size());
    }
}
