package com.palordersoftworks.fabricfolia.util;

import net.minecraft.util.ClassInstanceMultiMap;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConcurrentClassInstanceMultiMap<T> extends ClassInstanceMultiMap<T> {

    private final Class<T> baseClass;
    private final CopyOnWriteArrayList<T> instances = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, Collection<?>> views = new ConcurrentHashMap<>();

    public ConcurrentClassInstanceMultiMap(Class<T> baseClass) {
        super(baseClass);
        this.baseClass = baseClass;
    }

    @Override
    public boolean add(T instance) {
        if (!baseClass.isInstance(instance)) {
            throw new IllegalArgumentException("Element is not an instance of " + baseClass.getName());
        }
        return instances.addIfAbsent(instance);
    }

    @Override
    public boolean remove(Object instance) {
        return instances.remove(instance);
    }

    @Override
    public boolean contains(Object instance) {
        return instances.contains(instance);
    }

    @Override
    public int size() {
        return instances.size();
    }

    @Override
    public Iterator<T> iterator() {
        return instances.iterator();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> Collection<S> find(Class<S> type) {
        return (Collection<S>) views.computeIfAbsent(type, ignored -> new FilteredView<>(type));
    }

    @Override
    public java.util.List<T> getAllInstances() {
        return java.util.List.copyOf(instances);
    }

    private final class FilteredView<S> extends AbstractCollection<S> {
        private final Class<S> type;

        private FilteredView(Class<S> type) {
            this.type = type;
        }

        @Override
        public Iterator<S> iterator() {
            Iterator<T> source = instances.iterator();
            return new Iterator<>() {
                private S next;
                private boolean ready;

                @Override
                public boolean hasNext() {
                    if (ready) {
                        return true;
                    }
                    while (source.hasNext()) {
                        T candidate = source.next();
                        if (type.isInstance(candidate)) {
                            next = type.cast(candidate);
                            ready = true;
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public S next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    S result = next;
                    next = null;
                    ready = false;
                    return result;
                }
            };
        }

        @Override
        public int size() {
            int count = 0;
            for (T instance : instances) {
                if (type.isInstance(instance)) {
                    count++;
                }
            }
            return count;
        }
    }
}
