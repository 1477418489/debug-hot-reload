package dev.hotreload.agent.mybatis;

import dev.hotreload.bootstrap.ResourceMetadata;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigurationSnapshot {
    private static final String[] MAP_FIELDS = {
            "mappedStatements", "resultMaps", "parameterMaps", "keyGenerators",
            "sqlFragments", "caches", "cacheRefMap"
    };
    private static final String[] COLLECTION_FIELDS = {
            "loadedResources", "incompleteStatements", "incompleteCacheRefs",
            "incompleteResultMaps", "incompleteMethods"
    };

    private final Object configuration;
    private final Map<String, Map<Object, Object>> maps;
    private final Map<String, List<Object>> collections;

    private ConfigurationSnapshot(Object configuration, Map<String, Map<Object, Object>> maps,
                                  Map<String, List<Object>> collections) {
        this.configuration = configuration;
        this.maps = maps;
        this.collections = collections;
    }

    static ConfigurationSnapshot capture(Object configuration) throws Exception {
        Map<String, Map<Object, Object>> maps = new LinkedHashMap<String, Map<Object, Object>>();
        for (String name : MAP_FIELDS) {
            Map<?, ?> source = (Map<?, ?>) fieldValue(configuration, name);
            Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
            copy.putAll(source);
            maps.put(name, copy);
        }
        Map<String, List<Object>> collections = new LinkedHashMap<String, List<Object>>();
        for (String name : COLLECTION_FIELDS) {
            Collection<?> source = (Collection<?>) fieldValue(configuration, name);
            collections.put(name, new ArrayList<Object>(source));
        }
        return new ConfigurationSnapshot(configuration, maps, collections);
    }

    void removeOwned(ResourceMetadata metadata) throws Exception {
        verifyOwned(metadata);
        for (String name : MAP_FIELDS) {
            @SuppressWarnings("unchecked") Map<Object, Object> target = (Map<Object, Object>) fieldValue(configuration, name);
            Map<Object, Object> surviving = canonicalEntries(name, target);
            for (String id : metadata.getOwnedIds(name)) surviving.remove(id);
            rebuild(target, surviving);
        }
        @SuppressWarnings("unchecked") Collection<Object> loaded =
                (Collection<Object>) fieldValue(configuration, "loadedResources");
        loaded.remove(metadata.getRuntimeResource());
    }

    void verifyOwned(ResourceMetadata metadata) throws Exception {
        for (String name : MAP_FIELDS) {
            @SuppressWarnings("unchecked") Map<Object, Object> target =
                    (Map<Object, Object>) fieldValue(configuration, name);
            Map<String, Object> expected = metadata.getOwnedObjects(name);
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
                if (!metadata.hasLiveOwnedIdentity(name, entry.getKey())
                        || !target.containsKey(entry.getKey()) || target.get(entry.getKey()) != entry.getValue()) {
                    throw new OwnershipDriftException(name + ":" + entry.getKey());
                }
            }
        }
    }

    void restore() throws Exception {
        for (String name : MAP_FIELDS) {
            @SuppressWarnings("unchecked") Map<Object, Object> target = (Map<Object, Object>) fieldValue(configuration, name);
            rebuild(target, canonicalEntries(name, maps.get(name)));
        }
        for (String name : COLLECTION_FIELDS) {
            @SuppressWarnings("unchecked") Collection<Object> target =
                    (Collection<Object>) fieldValue(configuration, name);
            target.clear();
            target.addAll(collections.get(name));
        }
    }

    static Object fieldValue(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.isAccessible()) field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Map<Object, Object> canonicalEntries(String mapName, Map<?, ?> source) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String && !"cacheRefMap".equals(mapName)
                    && isStrictMapAlias(source, (String) key, entry.getValue())) continue;
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static boolean isStrictMapAlias(Map<?, ?> source, String key, Object shortValue) {
        if (key.indexOf('.') >= 0) return false;
        String suffix = "." + key;
        boolean foundQualifiedKey = false;
        for (Map.Entry<?, ?> candidate : source.entrySet()) {
            Object candidateKey = candidate.getKey();
            if (!(candidateKey instanceof String)
                    || !((String) candidateKey).endsWith(suffix)) continue;
            foundQualifiedKey = true;
            // StrictMap aliases point at the exact same value as their qualified entry.
            // A legitimate dotless cache namespace may have a different value, even when a
            // qualified cache happens to share its suffix; keep that canonical entry intact.
            if (candidate.getValue() == shortValue) return true;
        }
        if (!foundQualifiedKey || shortValue == null) return false;
        // When two qualified IDs share a short name, MyBatis stores an internal Ambiguity
        // marker under the short key. Do not retain that marker during a StrictMap rebuild.
        return shortValue.getClass().getName().endsWith("$StrictMap$Ambiguity");
    }

    private static void rebuild(Map<Object, Object> target, Map<Object, Object> fullEntries) {
        target.clear();
        for (Map.Entry<Object, Object> entry : fullEntries.entrySet()) {
            target.put(entry.getKey(), entry.getValue());
        }
    }

    static final class OwnershipDriftException extends Exception {
        private OwnershipDriftException(String id) {
            super("Owned MyBatis object changed: " + id);
        }
    }
}
