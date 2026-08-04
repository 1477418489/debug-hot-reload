package dev.hotreload.bootstrap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResourceMetadata {
    private final String runtimeResource;
    private final String resourceId;
    private final String parserClassName;
    private final String namespace;
    private final Map<String, Map<String, WeakReference<Object>>> ownedObjects;
    private final byte[] sha256;
    private final long version;

    ResourceMetadata(String runtimeResource, String resourceId, String parserClassName, String namespace,
                     Map<String, Map<String, Object>> ownedObjects, byte[] sha256, long version) {
        this(runtimeResource, resourceId, parserClassName, namespace,
                sha256, version, weakIdentities(ownedObjects));
    }

    private ResourceMetadata(String runtimeResource, String resourceId, String parserClassName, String namespace,
                             byte[] sha256, long version,
                             Map<String, Map<String, WeakReference<Object>>> ownedObjects) {
        this.runtimeResource = runtimeResource;
        this.resourceId = resourceId;
        this.parserClassName = parserClassName;
        this.namespace = namespace;
        Map<String, Map<String, WeakReference<Object>>> copied =
                new LinkedHashMap<String, Map<String, WeakReference<Object>>>();
        for (Map.Entry<String, Map<String, WeakReference<Object>>> entry : ownedObjects.entrySet()) {
            copied.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<String, WeakReference<Object>>(entry.getValue())));
        }
        this.ownedObjects = Collections.unmodifiableMap(copied);
        this.sha256 = sha256 == null ? null : sha256.clone();
        this.version = version;
    }

    public String getRuntimeResource() { return runtimeResource; }
    public String getResourceId() { return resourceId; }
    public String getParserClassName() { return parserClassName; }
    public String getNamespace() { return namespace; }
    public List<String> getOwnedIds(String mapName) {
        Map<String, WeakReference<Object>> objects = ownedObjects.get(mapName);
        return objects == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(objects.keySet()));
    }
    public Map<String, List<String>> getOwnedIds() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, Map<String, WeakReference<Object>>> entry : ownedObjects.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue().keySet())));
        }
        return Collections.unmodifiableMap(result);
    }
    public Map<String, Object> getOwnedObjects(String mapName) {
        Map<String, WeakReference<Object>> identities = ownedObjects.get(mapName);
        if (identities == null) return Collections.<String, Object>emptyMap();
        Map<String, Object> objects = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, WeakReference<Object>> entry : identities.entrySet()) {
            objects.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(objects);
    }
    public Map<String, Map<String, Object>> getOwnedObjects() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (String mapName : ownedObjects.keySet()) result.put(mapName, getOwnedObjects(mapName));
        return Collections.unmodifiableMap(result);
    }
    public boolean ownsObject(String mapName, String id) {
        Map<String, WeakReference<Object>> objects = ownedObjects.get(mapName);
        return objects != null && objects.containsKey(id);
    }
    public boolean hasLiveOwnedIdentity(String mapName, String id) {
        Map<String, WeakReference<Object>> objects = ownedObjects.get(mapName);
        WeakReference<Object> identity = objects == null ? null : objects.get(id);
        return identity != null && identity.get() != null;
    }
    public boolean hasCompleteOwnedIdentities() {
        for (Map<String, WeakReference<Object>> identities : ownedObjects.values()) {
            for (WeakReference<Object> identity : identities.values()) {
                if (identity.get() == null) return false;
            }
        }
        return true;
    }
    public Object getOwnedObject(String mapName, String id) {
        Map<String, WeakReference<Object>> objects = ownedObjects.get(mapName);
        WeakReference<Object> identity = objects == null ? null : objects.get(id);
        return identity == null ? null : identity.get();
    }
    public byte[] getSha256() { return sha256 == null ? null : sha256.clone(); }
    public long getVersion() { return version; }

    ResourceMetadata withSha256(byte[] newSha256) {
        return new ResourceMetadata(runtimeResource, resourceId, parserClassName, namespace,
                newSha256, version + 1L, ownedObjects);
    }

    private static Map<String, Map<String, WeakReference<Object>>> weakIdentities(
            Map<String, Map<String, Object>> objects) {
        Map<String, Map<String, WeakReference<Object>>> result =
                new LinkedHashMap<String, Map<String, WeakReference<Object>>>();
        for (Map.Entry<String, Map<String, Object>> map : objects.entrySet()) {
            Map<String, WeakReference<Object>> identities = new LinkedHashMap<String, WeakReference<Object>>();
            for (Map.Entry<String, Object> entry : map.getValue().entrySet()) {
                identities.put(entry.getKey(), new WeakReference<Object>(
                        Objects.requireNonNull(entry.getValue(), "owned object identity")));
            }
            result.put(map.getKey(), identities);
        }
        return result;
    }

}
