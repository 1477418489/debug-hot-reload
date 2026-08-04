package dev.hotreload.agent.spring;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationPointcutRebinderCacheTest {
    @Test
    void clearOnClearsAdvisedSupportStyleMethodCache() throws Exception {
        FakeSupport support = new FakeSupport();
        support.methodCache.put("k", "v");
        Method clearOn = AnnotationPointcutRebinder.class.getDeclaredMethod("clearOn", Object.class);
        clearOn.setAccessible(true);
        int cleared = ((Integer) clearOn.invoke(null, support)).intValue();
        assertTrue(cleared >= 1, "expected methodCache clear");
        assertTrue(support.methodCache.isEmpty());
    }

    @Test
    void resolveAdvisedSupportReadsCallbackAdvisedField() throws Exception {
        FakeSupport support = new FakeSupport();
        FakeCallback callback = new FakeCallback();
        callback.advised = support;
        FakeProxy proxy = new FakeProxy();
        Field cb = FakeProxy.class.getDeclaredField("CGLIB$CALLBACK_0");
        cb.setAccessible(true);
        cb.set(proxy, callback);

        Method resolve = AnnotationPointcutRebinder.class.getDeclaredMethod("resolveAdvisedSupport", Object.class);
        resolve.setAccessible(true);
        Object found = resolve.invoke(null, proxy);
        assertEquals(support, found);
    }

    static class FakeSupport {
        final Map methodCache = new HashMap();
    }

    static class FakeCallback {
        Object advised;
    }

    static class FakeProxy {
        Object CGLIB$CALLBACK_0;
    }
}
