package dev.hotreload.agent.configreload;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.spring.SpringContextRegistry;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadItemResult;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

/**
 * Hot reloads classpath properties and a deliberately limited YAML subset into Spring Environment.
 * It does not restart the context and only mutates MutablePropertySources when the required API is available.
 */
public final class ConfigResourceReloader {
    private final AgentSessionLogger logger;

    public ConfigResourceReloader(AgentSessionLogger logger) {
        if (logger == null) throw new NullPointerException("logger");
        this.logger = logger;
    }

    public ReloadResponse reload(ResourceReloadRequest request) {
        long started = System.nanoTime();
        String path = request.getResourcePath();
        logger.log(Level.INFO, "CONFIG_RECEIVED", fields(
                "requestId", request.getRequestId(),
                "resourceId", path,
                "payloadBytes", Integer.toString(request.getContentLength()),
                "contentType", request.getContentType()));
        try {
            Map<String, String> properties = parse(request);
            if (properties == null) {
                return itemResponse(request, OperationStatus.SKIPPED, null, "unsupported_format");
            }
            List<Object> contexts = SpringContextRegistry.snapshot();
            if (contexts.isEmpty()) {
                return itemResponse(request, OperationStatus.FAILED, ReloadErrorCode.BRIDGE_UNAVAILABLE,
                        "spring_context_missing");
            }
            int applied = 0;
            int skipped = 0;
            int failed = 0;
            List<ContextChange> changes = new ArrayList<ContextChange>(contexts.size());
            int failedContext = -1;
            for (int i = 0; i < contexts.size(); i++) {
                ContextChange change = applyToContext(contexts.get(i), path, properties);
                changes.add(change);
                if (change.applied) applied++;
                else if (change.skipped) skipped++;
                else {
                    failed++;
                    failedContext = i;
                    break;
                }
            }
            boolean rollbackFailed = failedContext >= 0
                    && changes.get(failedContext).rollbackFailed;
            if (failedContext >= 0) {
                for (int i = changes.size() - 1; i >= 0; i--) {
                    ContextChange change = changes.get(i);
                    if (change.applied && !change.rollback()) rollbackFailed = true;
                }
            }
            List<ReloadItemResult> items = contextItems(path, contexts.size(), changes, failedContext);
            OperationStatus status = transactionStatus(failed, applied, rollbackFailed);
            ReloadErrorCode code = failed > 0
                    ? rollbackFailed ? ReloadErrorCode.ROLLBACK_FAILED : ReloadErrorCode.INTERNAL_ERROR
                    : null;
            logger.log(failed > 0 ? Level.WARNING : Level.INFO,
                    "CONFIG_RELOAD_RESULT", fields(
                    "requestId", request.getRequestId(),
                    "resourceId", path,
                    "resultCode", status.name(),
                    "detail", "keys=" + properties.size() + ",contexts=" + contexts.size()
                            + ",applied=" + applied + ",skipped=" + skipped
                            + ",failed=" + failed + ",rolledBack="
                            + (failedContext < 0 ? 0 : applied)
                            + ",rollbackFailed=" + rollbackFailed
                            + ",ms=" + ((System.nanoTime() - started) / 1_000_000L)));
            return new ReloadResponse(request.getRequestId(), status, code,
                    status.name(), items);
        } catch (Throwable failure) {
            String diagnostic = failureDiagnostic(failure);
            logger.log(Level.WARNING, "CONFIG_RELOAD_RESULT", fields(
                    "requestId", request.getRequestId(),
                    "resourceId", path,
                    "resultCode", "FAILED",
                    "detail", diagnostic));
            return itemResponse(request, OperationStatus.FAILED, ReloadErrorCode.INTERNAL_ERROR,
                    diagnostic);
        }
    }

    static String failureDiagnostic(Throwable failure) {
        if (failure == null) return "unknown_failure";
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? type : type + ": " + message;
    }

    static OperationStatus transactionStatus(int failed, int applied, boolean rollbackFailed) {
        if (failed > 0) {
            return rollbackFailed ? OperationStatus.RESTART_REQUIRED : OperationStatus.FAILED;
        }
        return applied > 0 ? OperationStatus.SUCCESS : OperationStatus.SKIPPED;
    }

    static Map<String, String> parse(ResourceReloadRequest request) throws Exception {
        String type = request.getContentType() == null ? ""
                : request.getContentType().toLowerCase(Locale.ROOT);
        String path = request.getResourcePath().toLowerCase(Locale.ROOT);
        if (type.contains("properties") || path.endsWith(".properties")) {
            Properties props = new Properties();
            props.load(new InputStreamReader(new ByteArrayInputStream(request.getContent()), StandardCharsets.UTF_8));
            Map<String, String> out = new LinkedHashMap<String, String>();
            for (String name : props.stringPropertyNames()) {
                out.put(name, props.getProperty(name));
            }
            return out;
        }
        if (type.contains("yaml") || type.contains("yml") || path.endsWith(".yml") || path.endsWith(".yaml")) {
            return parseSimpleYaml(new String(request.getContent(), StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * Parses the deliberately small YAML subset that can be represented by a flat String map.
     * Unsupported structures fail the whole reload instead of silently applying a partial file.
     */
    static Map<String, String> parseSimpleYaml(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null || text.isEmpty()) return out;
        List<String> stackKeys = new ArrayList<String>();
        List<Integer> stackIndent = new ArrayList<Integer>();
        List<Integer> stackChildIndent = new ArrayList<Integer>();
        List<Boolean> stackHasScalar = new ArrayList<Boolean>();
        Set<String> seenPaths = new LinkedHashSet<String>();
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        for (String raw : lines) {
            if (raw == null) continue;
            String line = stripComment(raw);
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            if (indent < line.length() && line.charAt(indent) == '\t') {
                throw new IllegalArgumentException("YAML tab indentation is unsupported");
            }
            String body = line.substring(indent).trim();
            if (body.isEmpty()) continue;
            if ("---".equals(body) || "...".equals(body) || body.startsWith("%")) {
                throw new IllegalArgumentException("YAML documents and directives are unsupported");
            }
            if (body.startsWith("-")) {
                throw new IllegalArgumentException("YAML sequences are unsupported");
            }
            int colon = simpleYamlMappingSeparator(body);
            if (colon <= 0) {
                throw new IllegalArgumentException("YAML line is not a simple mapping");
            }
            String key = body.substring(0, colon).trim();
            validateSimpleYamlKey(key);
            String value = body.substring(colon + 1).trim();
            while (!stackIndent.isEmpty() && indent <= stackIndent.get(stackIndent.size() - 1)) {
                popCompletedYamlMapping(stackKeys, stackIndent, stackChildIndent,
                        stackHasScalar);
            }
            if (indent > 0 && stackIndent.isEmpty()) {
                throw new IllegalArgumentException("YAML indentation has no parent mapping");
            }
            if (!stackIndent.isEmpty()) {
                int parent = stackIndent.size() - 1;
                int expectedIndent = stackChildIndent.get(parent);
                if (expectedIndent < 0) {
                    stackChildIndent.set(parent, indent);
                } else if (expectedIndent != indent) {
                    throw new IllegalArgumentException("YAML sibling indentation is inconsistent");
                }
            }
            String propertyName = qualifiedYamlKey(stackKeys, key);
            if (!seenPaths.add(propertyName)) {
                throw new IllegalArgumentException("YAML contains a duplicate property");
            }
            if (value.isEmpty()) {
                stackIndent.add(indent);
                stackKeys.add(key);
                stackChildIndent.add(-1);
                stackHasScalar.add(Boolean.FALSE);
                continue;
            }
            value = parseSimpleYamlScalar(value);
            if (out.containsKey(propertyName)) {
                throw new IllegalArgumentException("YAML contains a duplicate property");
            }
            out.put(propertyName, value);
            for (int i = 0; i < stackHasScalar.size(); i++) {
                stackHasScalar.set(i, Boolean.TRUE);
            }
        }
        while (!stackIndent.isEmpty()) {
            popCompletedYamlMapping(stackKeys, stackIndent, stackChildIndent,
                    stackHasScalar);
        }
        return out;
    }

    private static int simpleYamlMappingSeparator(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == ':'
                    && (i + 1 == body.length() || Character.isWhitespace(body.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static String qualifiedYamlKey(List<String> stackKeys, String key) {
        StringBuilder full = new StringBuilder();
        for (String part : stackKeys) {
            if (full.length() > 0) full.append('.');
            full.append(part);
        }
        if (full.length() > 0) full.append('.');
        return full.append(key).toString();
    }

    private static void popCompletedYamlMapping(List<String> stackKeys,
                                                List<Integer> stackIndent,
                                                List<Integer> stackChildIndent,
                                                List<Boolean> stackHasScalar) {
        int index = stackIndent.size() - 1;
        if (!stackHasScalar.get(index)) {
            throw new IllegalArgumentException("YAML empty mappings and null values are unsupported");
        }
        stackKeys.remove(index);
        stackIndent.remove(index);
        stackChildIndent.remove(index);
        stackHasScalar.remove(index);
    }

    private static void validateSimpleYamlKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("YAML key is empty");
        }
        for (int i = 0; i < key.length(); i++) {
            char value = key.charAt(i);
            if (Character.isWhitespace(value) || value == '\'' || value == '"'
                    || value == '[' || value == ']' || value == '{' || value == '}'
                    || value == '&' || value == '*' || value == '!' || value == '#') {
                throw new IllegalArgumentException("YAML key syntax is unsupported");
            }
        }
    }

    private static String parseSimpleYamlScalar(String value) {
        char first = value.charAt(0);
        if (first == '[' || first == '{' || first == '|' || first == '>'
                || first == '&' || first == '*' || first == '!') {
            throw new IllegalArgumentException("YAML scalar syntax is unsupported");
        }
        if (first == '\'' || first == '"') {
            if (value.length() < 2 || value.charAt(value.length() - 1) != first) {
                throw new IllegalArgumentException("YAML quoted scalar is unterminated");
            }
            for (int i = 1; i < value.length() - 1; i++) {
                char current = value.charAt(i);
                if (current == first || first == '"' && current == '\\') {
                    throw new IllegalArgumentException("YAML escaped scalars are unsupported");
                }
            }
            return value.substring(1, value.length() - 1);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("null".equals(lower) || "~".equals(value)) {
            throw new IllegalArgumentException("YAML null scalars are unsupported");
        }
        return value;
    }

    /**
     * YAML 注释剥离：引号内的 # 不是注释；裸值中的 # 仅在行首或前有空白时才开始注释
     * （password: abc#123 是合法值，password: abc #123 才带注释）。
     */
    static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && i + 1 < line.length()) {
                    i++;
                } else if (quote == '\'' && c == '\'' && i + 1 < line.length()
                        && line.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static ContextChange applyToContext(Object context, String resourcePath,
                                                Map<String, String> properties) {
        try {
            Object env = invoke(context, "getEnvironment");
            if (env == null) return ContextChange.failed("no_environment");
            Object sources = invoke(env, "getPropertySources");
            if (sources == null) return ContextChange.failed("no_property_sources");
            String sourceName = "hotreload:" + resourcePath;
            Object existing = invoke(sources, "get", new Class<?>[]{String.class}, new Object[]{sourceName});
            List<String> originals = originalPropertySourceNames(sources, resourcePath, sourceName);
            Object mapSource = createMapPropertySource(env.getClass().getClassLoader(), sourceName, properties);
            if (mapSource == null) return ContextChange.failed("map_source_unavailable");
            return applyPropertySourceChange(sources, sourceName, mapSource, existing,
                    originals, properties.size());
        } catch (Throwable failure) {
            return ContextChange.failed("error=" + failure.getClass().getSimpleName());
        }
    }

    static String installReloadedPropertySource(Object sources, String sourceName,
                                                Object mapSource, Object existing,
                                                List<String> originals, int propertyCount) {
        if (sources == null || sourceName == null || mapSource == null || originals == null
                || propertyCount < 0) {
            return "property_source_update_unavailable";
        }
        return applyPropertySourceChange(sources, sourceName, mapSource, existing,
                originals, propertyCount).detail;
    }

    private static ContextChange applyPropertySourceChange(Object sources, String sourceName,
                                                           Object mapSource, Object existing,
                                                           List<String> originals,
                                                           int propertyCount) {
        synchronized (sources) {
            PropertySourcesSnapshot before = PropertySourcesSnapshot.capture(sources);
            if (before == null) return ContextChange.failed("property_source_snapshot_unavailable");
            String failure = null;
            try {
                if (existing != null) {
                    if (!installPropertySource(sources, sourceName, mapSource, true)) {
                        failure = "property_source_update_unavailable";
                    } else if (!removePropertySources(sources, originals)) {
                        failure = "original_property_source_remove_unavailable";
                    }
                } else if (originals.isEmpty()) {
                    return ContextChange.skipped("skipped configuration_property_source_not_loaded");
                } else if (!installReplacingPropertySourcesUnsafe(
                        sources, sourceName, mapSource, originals)) {
                    failure = "property_source_update_unavailable";
                }
            } catch (Throwable updateFailure) {
                failure = "error=" + updateFailure.getClass().getSimpleName();
            }
            if (failure != null) {
                boolean rolledBack = before.restore(sources);
                return ContextChange.failed(failure + ",localRollback="
                        + (rolledBack ? "ok" : "failed"), !rolledBack);
            }
            return ContextChange.applied(sources, before,
                    "ok keys=" + propertyCount + " source=" + sourceName
                            + " originals=" + originals.size());
        }
    }

    private static Object createMapPropertySource(ClassLoader loader, String name, Map<String, String> properties) {
        try {
            Class<?> type = Class.forName("org.springframework.core.env.MapPropertySource", false, loader);
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
            return type.getConstructor(String.class, Map.class).newInstance(name, map);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean installPropertySource(Object sources, String sourceName, Object propertySource,
                                         boolean replaceExisting) {
        if (sources == null || sourceName == null || propertySource == null) return false;
        if (replaceExisting) {
            return invokeCompatible(sources, "replace", sourceName, propertySource);
        }
        return invokeCompatible(sources, "addFirst", propertySource);
    }

    static boolean removePropertySource(Object sources, String sourceName) {
        return sources != null && sourceName != null
                && invokeCompatible(sources, "remove", sourceName);
    }

    static List<String> originalPropertySourceNames(Object sources, String resourcePath,
                                                     String hotSourceName) {
        if (!(sources instanceof Iterable) || resourcePath == null) {
            return Collections.emptyList();
        }
        List<String> matches = new ArrayList<String>();
        for (Object source : (Iterable<?>) sources) {
            Object rawName = invoke(source, "getName");
            if (!(rawName instanceof String)) continue;
            String name = (String) rawName;
            if (!name.equals(hotSourceName) && !name.startsWith("hotreload:")
                    && sourceNameContainsResource(name, resourcePath)) {
                matches.add(name);
            }
        }
        return matches;
    }

    static boolean sourceNameContainsResource(String sourceName, String resourcePath) {
        if (sourceName == null || resourcePath == null || resourcePath.isEmpty()) return false;
        String name = sourceName.replace('\\', '/').toLowerCase(Locale.ROOT);
        String resource = resourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= name.length() - resource.length()) {
            int index = name.indexOf(resource, from);
            if (index < 0) return false;
            int end = index + resource.length();
            boolean before = index == 0 || isSourceNameBoundary(name.charAt(index - 1));
            boolean after = end == name.length() || isSourceNameBoundary(name.charAt(end));
            if (before && after && hasClasspathSourceEvidence(name, index)) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean hasClasspathSourceEvidence(String sourceName, int resourceIndex) {
        String prefix = sourceName.substring(0, resourceIndex);
        return hasSourceMarkerSuffix(prefix, "classpath:")
                || hasSourceMarkerSuffix(prefix, "classpath:/")
                || hasSourceMarkerSuffix(prefix, "classpath*:")
                || hasSourceMarkerSuffix(prefix, "classpath*:/")
                || hasSourceMarkerSuffix(prefix, "class path resource [")
                || hasSourceMarkerSuffix(prefix, "class path resource [/");
    }

    private static boolean hasSourceMarkerSuffix(String value, String marker) {
        if (!value.endsWith(marker)) return false;
        int start = value.length() - marker.length();
        return start == 0 || isSourceNameBoundary(value.charAt(start - 1));
    }

    private static boolean isSourceNameBoundary(char value) {
        return value == '/' || value == ':' || value == '[' || value == ']'
                || value == '(' || value == ')' || value == '\'' || value == '"'
                || value == ',' || value == ';' || value == '#' || Character.isWhitespace(value);
    }

    static boolean installReplacingPropertySources(Object sources, String sourceName,
                                                    Object propertySource,
                                                    List<String> originalSourceNames) {
        if (sources == null || sourceName == null || propertySource == null
                || originalSourceNames == null || originalSourceNames.isEmpty()) {
            return false;
        }
        synchronized (sources) {
            PropertySourcesSnapshot before = PropertySourcesSnapshot.capture(sources);
            if (before == null) return false;
            if (installReplacingPropertySourcesUnsafe(
                    sources, sourceName, propertySource, originalSourceNames)) {
                return true;
            }
            before.restore(sources);
            return false;
        }
    }

    private static boolean installReplacingPropertySourcesUnsafe(Object sources, String sourceName,
                                                                  Object propertySource,
                                                                  List<String> originalSourceNames) {
        if (!invokeCompatible(sources, "addBefore", originalSourceNames.get(0), propertySource)) {
            return false;
        }
        return removePropertySources(sources, originalSourceNames);
    }

    private static boolean removePropertySources(Object sources, List<String> sourceNames) {
        if (sourceNames == null) return false;
        boolean removed = true;
        for (String sourceName : sourceNames) {
            removed &= removePropertySource(sources, sourceName);
        }
        return removed;
    }

    private static List<ReloadItemResult> contextItems(String path, int contextCount,
                                                       List<ContextChange> changes,
                                                       int failedContext) {
        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(contextCount);
        for (int i = 0; i < contextCount; i++) {
            String itemId = path + "@ctx" + i;
            if (i >= changes.size()) {
                items.add(new ReloadItemResult(itemId, OperationStatus.SKIPPED, null,
                        "SKIPPED", "transaction_aborted_after_context=" + failedContext));
                continue;
            }
            ContextChange change = changes.get(i);
            if (failedContext >= 0 && change.applied) {
                boolean restored = change.rollbackAttempted && change.rollbackSucceeded;
                ReloadErrorCode code = restored
                        ? ReloadErrorCode.INTERNAL_ERROR : ReloadErrorCode.ROLLBACK_FAILED;
                OperationStatus status = restored
                        ? OperationStatus.FAILED : OperationStatus.RESTART_REQUIRED;
                items.add(new ReloadItemResult(itemId, status, code,
                        status.name(), restored
                        ? "transaction_rolled_back_due_to_context=" + failedContext
                        : "transaction_rollback_failed_due_to_context=" + failedContext));
            } else if (change.applied) {
                items.add(new ReloadItemResult(itemId, OperationStatus.SUCCESS, null,
                        "SUCCESS", change.detail));
            } else if (change.skipped) {
                items.add(new ReloadItemResult(itemId, OperationStatus.SKIPPED, null,
                        "SKIPPED", change.detail));
            } else {
                ReloadErrorCode code = change.rollbackFailed
                        ? ReloadErrorCode.ROLLBACK_FAILED : ReloadErrorCode.INTERNAL_ERROR;
                OperationStatus status = change.rollbackFailed
                        ? OperationStatus.RESTART_REQUIRED : OperationStatus.FAILED;
                items.add(new ReloadItemResult(itemId, status, code,
                        status.name(), change.detail));
            }
        }
        return items;
    }

    private static final class ContextChange {
        private final Object sources;
        private final PropertySourcesSnapshot before;
        private final boolean applied;
        private final boolean skipped;
        private final String detail;
        private final boolean rollbackFailed;
        private boolean rollbackAttempted;
        private boolean rollbackSucceeded;

        private ContextChange(Object sources, PropertySourcesSnapshot before,
                              boolean applied, boolean skipped, String detail,
                              boolean rollbackFailed) {
            this.sources = sources;
            this.before = before;
            this.applied = applied;
            this.skipped = skipped;
            this.detail = detail;
            this.rollbackFailed = rollbackFailed;
        }

        private static ContextChange applied(Object sources, PropertySourcesSnapshot before,
                                             String detail) {
            return new ContextChange(sources, before, true, false, detail, false);
        }

        private static ContextChange skipped(String detail) {
            return new ContextChange(null, null, false, true, detail, false);
        }

        private static ContextChange failed(String detail) {
            return failed(detail, false);
        }

        private static ContextChange failed(String detail, boolean rollbackFailed) {
            return new ContextChange(null, null, false, false, detail, rollbackFailed);
        }

        private boolean rollback() {
            rollbackAttempted = true;
            rollbackSucceeded = before != null && before.restore(sources);
            return rollbackSucceeded;
        }
    }

    private static final class PropertySourcesSnapshot {
        private final List<Object> sources;
        private final List<String> names;

        private PropertySourcesSnapshot(List<Object> sources, List<String> names) {
            this.sources = sources;
            this.names = names;
        }

        private static PropertySourcesSnapshot capture(Object propertySources) {
            if (!(propertySources instanceof Iterable)) return null;
            List<Object> objects = new ArrayList<Object>();
            List<String> names = new ArrayList<String>();
            try {
                for (Object source : (Iterable<?>) propertySources) {
                    Object name = invoke(source, "getName");
                    if (!(name instanceof String)) return null;
                    objects.add(source);
                    names.add((String) name);
                }
                return new PropertySourcesSnapshot(objects, names);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean restore(Object propertySources) {
            if (propertySources == null) return false;
            synchronized (propertySources) {
                PropertySourcesSnapshot current = capture(propertySources);
                if (current == null) return false;
                boolean restored = true;
                for (String name : current.names) {
                    restored &= removePropertySource(propertySources, name);
                }
                for (Object source : sources) {
                    restored &= invokeCompatible(propertySources, "addLast", source);
                }
                PropertySourcesSnapshot after = capture(propertySources);
                return restored && sameAs(after);
            }
        }

        private boolean sameAs(PropertySourcesSnapshot other) {
            if (other == null || sources.size() != other.sources.size()) return false;
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i) != other.sources.get(i)
                        || !names.get(i).equals(other.names.get(i))) return false;
            }
            return true;
        }
    }

    private ReloadResponse itemResponse(ResourceReloadRequest request, OperationStatus status,
                                        ReloadErrorCode code, String diagnostic) {
        ReloadItemResult item = new ReloadItemResult(request.getResourcePath(), status, code,
                status.name(), diagnostic);
        return new ReloadResponse(request.getRequestId(), status, code, status.name(),
                Collections.singletonList(item));
    }

    private static Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = findMethod(target.getClass(), method, types);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeCompatible(Object target, String method, Object... args) {
        if (target == null) return false;
        Method candidate = findCompatibleMethod(target.getClass(), method, args);
        if (candidate == null) return false;
        try {
            candidate.setAccessible(true);
            candidate.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        Class<?> current = type;
        while (current != null) {
            Method candidate = compatibleMethod(current.getDeclaredMethods(), name, args);
            if (candidate != null) return candidate;
            current = current.getSuperclass();
        }
        return compatibleMethod(type.getMethods(), name, args);
    }

    private static Method compatibleMethod(Method[] methods, String name, Object[] args) {
        int argumentCount = args == null ? 0 : args.length;
        for (Method method : methods) {
            if (!method.getName().equals(name)
                    || method.getParameterTypes().length != argumentCount) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < argumentCount; i++) {
                Object argument = args[i];
                if (argument == null ? parameterTypes[i].isPrimitive()
                        : !parameterTypes[i].isAssignableFrom(argument.getClass())) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method;
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            return type.getMethod(name, types);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
