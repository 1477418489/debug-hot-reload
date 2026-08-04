package dev.hotreload.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadBridgeAnnotationIndexTest {
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DemoScope {
        String alias() default "";
    }

    public static class Sample {
        public void listOrders() { }
    }

    @BeforeEach
    void activateBridge() {
        HotReloadBridge.activate();
    }

    @AfterEach
    void keepBridgeActiveForSiblingTests() {
        // Other bootstrap tests assume the default active=true state.
        HotReloadBridge.activate();
    }

    @Test
    void resolveMethodAnnotationHonorsAddAndRemove() throws Exception {
        Method method = Sample.class.getDeclaredMethod("listOrders");
        String className = Sample.class.getName();
        String methodKey = "listOrders()V";

        Map<String, Map<String, String>> classAnns = Collections.emptyMap();
        Map<String, Map<String, Map<String, String>>> methodAnns =
                new LinkedHashMap<String, Map<String, Map<String, String>>>();
        Map<String, Map<String, String>> anns = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("alias", "d");
        anns.put(DemoScope.class.getName(), attrs);
        methodAnns.put(methodKey, anns);

        HotReloadBridge.replaceClassAnnotations(className, classAnns, methodAnns);
        Object present = HotReloadBridge.resolveMethodAnnotation(method, DemoScope.class);
        assertNotNull(present);
        assertFalse(HotReloadBridge.isAbsentMarker(present));
        assertTrue(present instanceof DemoScope);
        assertTrue("d".equals(((DemoScope) present).alias()));

        // Remove annotation from index.
        Map<String, Map<String, Map<String, String>>> emptyMethods =
                new LinkedHashMap<String, Map<String, Map<String, String>>>();
        emptyMethods.put(methodKey, Collections.<String, Map<String, String>>emptyMap());
        HotReloadBridge.replaceClassAnnotations(className, classAnns, emptyMethods);
        Object removed = HotReloadBridge.resolveMethodAnnotation(method, DemoScope.class);
        assertNotNull(removed);
        assertTrue(HotReloadBridge.isAbsentMarker(removed));
        assertNull(HotReloadBridge.consumeAbsentMarker(removed));
    }

    @Test
    void resolveDeclaredAnnotationsHonorsAddAndRemove() throws Exception {
        Method method = Sample.class.getDeclaredMethod("listOrders");
        String className = Sample.class.getName();
        String methodKey = "listOrders()V";

        Map<String, Map<String, String>> classAnns = Collections.emptyMap();
        Map<String, Map<String, Map<String, String>>> methodAnns =
                new LinkedHashMap<String, Map<String, Map<String, String>>>();
        Map<String, Map<String, String>> anns = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("alias", "d");
        anns.put(DemoScope.class.getName(), attrs);
        methodAnns.put(methodKey, anns);
        HotReloadBridge.replaceClassAnnotations(className, classAnns, methodAnns);

        java.lang.annotation.Annotation[] present = HotReloadBridge.resolveDeclaredAnnotations(method);
        assertNotNull(present);
        assertTrue(present.length == 1);
        assertTrue(present[0] instanceof DemoScope);

        Map<String, Map<String, Map<String, String>>> emptyMethods =
                new LinkedHashMap<String, Map<String, Map<String, String>>>();
        emptyMethods.put(methodKey, Collections.<String, Map<String, String>>emptyMap());
        HotReloadBridge.replaceClassAnnotations(className, classAnns, emptyMethods);
        java.lang.annotation.Annotation[] removed = HotReloadBridge.resolveDeclaredAnnotations(method);
        assertNotNull(removed);
        assertTrue(removed.length == 0);
    }
}
