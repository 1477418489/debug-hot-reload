package dev.hotreload.idea.logging;

import dev.hotreload.idea.settings.HotReloadSettings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将内部事件码和字段翻译成中文可读日志。
 */
public final class HotReloadChineseMessages {
    private HotReloadChineseMessages() { }

    public static String eventTitle(String event) {
        if (event == null) return "未知事件";
        switch (event) {
            case "PROJECT_SERVICE_START": return "插件服务启动";
            case "SESSION_PENDING": return "等待调试会话";
            case "PATCH_APPLIED": return "已注入热更新 Agent";
            case "PROCESS_BOUND": return "调试进程已绑定";
            case "HELLO_OK": return "Agent 握手成功";
            case "SESSION_ACTIVE": return "热更新会话已激活";
            case "SESSION_CLOSED": return "热更新会话已关闭";
            case "CLASS_BATCH_SEND": return "发送类热更新";
            case "CLASS_BATCH_RESULT": return "类热更新结果";
            case "CLASS_BATCH_SKIPPED": return "类热更新已跳过";
            case "CLASS_RESTART_REQUIRED": return "Java 类生命周期变更需要重启";
            case "CLASS_STRUCTURE_RELOAD": return "结构热更新";
            case "CLASS_STRUCTURE_FALLBACK": return "结构变更兜底热更新";
            case "IDEA_HOTSWAP_DISABLED": return "已禁用IDEA内置HotSwap";
            case "ENHANCED_RUNTIME_ENABLED": return "已启用增强热更运行时";
            case "IDEA_HOTSWAP_ALREADY_OFF": return "IDEA内置HotSwap本已关闭";
            case "IDEA_HOTSWAP_RESTORED": return "已恢复IDEA内置HotSwap";
            case "IDEA_HOTSWAP_VETO_INSTALLED": return "已拦截IDEA内置HotSwap请求";
            case "IDEA_HOTSWAP_DISABLE_FAILED": return "禁用IDEA内置HotSwap失败";
            case "IDEA_HOTSWAP_RESTORE_FAILED": return "恢复IDEA内置HotSwap失败";
            case "IDEA_HOTSWAP_RESTORE_SKIPPED": return "IDEA内置HotSwap设置已被外部修改，跳过恢复";
            case "IDEA_HOTSWAP_VETO_FAILED": return "安装IDEA HotSwap拦截失败";
            case "CLASS_DEFINE_BEGIN": return "开始定义新类";
            case "CLASS_DEFINE_END": return "新类定义结束";
            case "CLASS_CHANGE_ANALYSIS": return "类变更分析";
            case "XML_RELOAD_SEND": return "发送 Mapper XML 热更新";
            case "XML_RELOAD_RESULT": return "Mapper XML 热更新结果";
            case "XML_RELOAD_SKIPPED": return "Mapper XML 热更新已跳过";
            case "XML_SKIPPED": return "Mapper XML 变更已忽略";
            case "XML_READ_FAILED": return "读取 Mapper XML 失败";
            case "XML_QUEUE_REJECTED": return "Mapper XML 队列已满";
            case "XML_RESTART_REQUIRED": return "Mapper XML 变更需要重启";
            case "XML_RESOURCE_AMBIGUOUS_PREFERRED": return "Mapper 资源多模块同名，已优先当前模块";
            case "XML_OUTPUT_COPY_MISSING_USE_SOURCE": return "输出目录暂无XML副本，已使用源文件内容热更新";
            case "CONFIG_RELOAD_SEND": return "发送配置文件热更新";
            case "CONFIG_RELOAD_RESULT": return "配置文件热更新结果";
            case "CONFIG_RELOAD_SKIPPED": return "配置文件热更新已跳过";
            case "CONFIG_READ_FAILED": return "读取配置文件失败";
            case "CONFIG_RESTART_REQUIRED": return "配置文件生命周期变更需要重启";
            case "STATIC_RESOURCE_SYNCED": return "静态资源已同步到调试输出目录";
            case "STATIC_RESOURCE_REMOVED": return "静态资源已从调试输出目录删除";
            case "STATIC_SYNC_FAILED": return "静态资源同步失败";
            case "STATIC_RELOAD_SEND": return "发送静态资源缓存刷新";
            case "STATIC_RELOAD_RESULT": return "静态资源缓存刷新结果";
            case "STATIC_RELOAD_SKIPPED": return "静态资源热更新已跳过";
            case "STATIC_RESTART_REQUIRED": return "静态资源变更需要重启";
            case "ENVIRONMENT_PROBE": return "运行环境探测";
            case "PROCESS_BIND_UNIDENTIFIED": return "未能识别调试进程";
            default: return event;
        }
    }

    public static String levelTitle(HotReloadLogEvent.Level level) {
        if (level == HotReloadLogEvent.Level.WARN) return "警告";
        return "信息";
    }

    public static String formatDetails(String event, String details) {
        Map<String, String> fields = parseFields(details);
        StringBuilder out = new StringBuilder(256);
        if ("CLASS_BATCH_RESULT".equals(event)) {
            appendClassResult(out, fields);
        } else if ("XML_RELOAD_RESULT".equals(event)) {
            appendXmlResult(out, fields);
        } else if ("CLASS_BATCH_SEND".equals(event)) {
            appendPair(out, "类数量", fields.get("classCount"));
        } else if ("XML_RELOAD_SEND".equals(event)) {
            appendPair(out, "资源", fields.get("resourceId"));
        } else if ("SESSION_ACTIVE".equals(event)) {
            appendPair(out, "目标JDK", fields.get("targetJdk"));
            appendPair(out, "热更引擎", engineCn(fields.get("engine")));
            appendPair(out, "支持类重定义", boolCn(fields.get("classRedefine")));
            appendPair(out, "活跃会话数", fields.get("activeSessions"));
        } else if ("ENHANCED_RUNTIME_ENABLED".equals(event)) {
            appendPair(out, "模式", runtimeModeCn(fields.get("mode")));
            appendPair(out, "启动配置", fields.get("configuration"));
        } else if ("PATCH_APPLIED".equals(event)) {
            appendPair(out, "启动配置", fields.get("configuration"));
            appendPair(out, "Agent", fields.get("agentJar"));
            appendPair(out, "类路径条目", fields.get("classpathEntries"));
        } else if ("CLASS_BATCH_SKIPPED".equals(event) || "XML_RELOAD_SKIPPED".equals(event)
                || "XML_SKIPPED".equals(event)) {
            appendPair(out, "原因", reasonCn(fields.get("reason")));
        } else {
            // generic preferred fields
            appendPair(out, "状态", statusCn(fields.get("status")));
            appendPair(out, "错误码", fields.get("errorCode"));
            appendPair(out, "类/资源", firstNonEmpty(fields.get("itemId"), fields.get("resourceId")));
            appendPair(out, "项目数", fields.get("itemCount"));
            appendPair(out, "成功数", fields.get("successCount"));
            appendPair(out, "跳过数", fields.get("skippedCount"));
            appendPair(out, "失败数", fields.get("failedCount"));
            appendPair(out, "原因", reasonCn(fields.get("reason")));
            appendPair(out, "消息", humanizeTechnical(fields.get("message")));
            appendPair(out, "详情", humanizeTechnical(fields.get("detail")));
        }
        return out.toString().trim();
    }

    private static void appendClassResult(StringBuilder out, Map<String, String> fields) {
        String status = fields.get("status");
        boolean failed = "FAILED".equals(status) || "RESTART_REQUIRED".equals(status);
        appendPair(out, "结果", statusCn(status));
        appendPair(out, "类名", fields.get("itemId"));
        appendPair(out, "成功数", fields.get("successCount"));
        appendPair(out, "跳过数", fields.get("skippedCount"));
        appendPair(out, "失败数", fields.get("failedCount"));
        String detail = firstNonEmpty(fields.get("detail"), fields.get("message"));
        if (detail != null) {
            appendPair(out, "说明", humanizeClassDetail(detail));
        }
        if (failed) {
            appendPair(out, "错误码", fields.get("errorCode"));
        }
    }

    private static void appendXmlResult(StringBuilder out, Map<String, String> fields) {
        String status = fields.get("status");
        boolean failed = "FAILED".equals(status) || "RESTART_REQUIRED".equals(status);
        appendPair(out, "结果", statusCn(status));
        appendPair(out, "资源", firstNonEmpty(fields.get("resourceId"), fields.get("itemId")));
        appendPair(out, "成功数", fields.get("successCount"));
        appendPair(out, "跳过数", fields.get("skippedCount"));
        appendPair(out, "失败数", fields.get("failedCount"));
        appendPair(out, "说明", humanizeTechnical(firstNonEmpty(fields.get("detail"), fields.get("message"))));
        if (failed) appendPair(out, "错误码", fields.get("errorCode"));
    }

    public static String humanizeClassDetail(String raw) {
        if (raw == null || raw.isEmpty() || "none".equals(raw)) return "无";
        // Keep underscores: generation tokens use __HrGen and must stay matchable.
        String text = raw;
        StringBuilder out = new StringBuilder();
        if (text.contains("redefined")) out.append("已重定义类字节码");
        if (text.contains("structure:generation") || text.contains("mode=generation")) {
            if (out.length() > 0) out.append("；");
            out.append("结构变更已切换到可赋值子类(Generation __HrGen，assignable=true；请忽略IDEA内置HotSwap提示)");
            String bridgedFields = extractNumber(text, "bridgedFields=");
            String bridgedMethods = extractNumber(text, "bridgedMethods=");
            if (bridgedFields != null || bridgedMethods != null) {
                out.append("；私有成员桥接字段=")
                        .append(bridgedFields == null ? "0" : bridgedFields)
                        .append("/方法=")
                        .append(bridgedMethods == null ? "0" : bridgedMethods);
            }
        }
        if (text.contains("structure:redefined") || text.contains("mode=redefined")
                || text.contains("enhanced_redefine")) {
            if (out.length() > 0) out.append("；");
            out.append("结构变更已就地生效(增强redefine：类身份、Bean实例与运行状态全部保留)");
        }
        if (text.contains("defined:")) {
            if (out.length() > 0) out.append("；");
            out.append("新类已定义");
        }
        if (text.contains("genericAspectJ@") || text.contains("genericMatcher@") || text.contains("annotationAspectJ@") || text.contains("annotationMatcher@") || text.contains("genericPatched=")) {
            if (out.length() > 0) out.append("；");
            out.append("通用注解切面/匹配器已重绑");
        }
        if (text.contains("defined=") && !text.contains("redefined")) {
            if (out.length() > 0) out.append("；");
            out.append("已定义新类");
        }
        if (text.contains("mappingRefresh=skippedNonMappingChange") || text.contains("mappingRefresh=skipNonMapping")) {
            sep(out); out.append("请求映射未变更，已跳过接口映射刷新");
        } else if (text.contains("mappingFallback=") && !text.contains("mappingRefreshed=0")) {
            sep(out); out.append("映射已全量重建RequestMapping(正常恢复路径)");
        } else if (text.contains("structure:generation") || text.contains("mode=generation")) {
            if (text.contains("mappingRefreshed=0") || text.contains("beansRecreated=0")) {
                sep(out); out.append("结构热更后Bean/映射重建可能未生效，请查看beansRecreated与mappingRefreshed");
            }
        }
        appendIfContains(out, text, "mappingRefreshed=", "Spring接口映射已刷新");
        if (text.contains("restoredKeys=")) {
            String restored = extractNumber(text, "restoredKeys=");
            String unregistered = extractNumber(text, "unregistered=");
            if (restored != null) {
                sep(out);
                out.append("映射键恢复=").append(restored);
                if (unregistered != null) out.append("/注销=").append(unregistered);
            }
        }
        appendIfContains(out, text, "beansRecreated=", "相关Bean已重建");
        if (text.contains("refreshedInPlace=")) {
            sep(out); out.append("Bean已原地刷新(实例与状态保留，注入已重做)");
        }
        if (text.contains("proxyRebuilt=")) {
            sep(out); out.append("代理已重建(新方法已纳入拦截链)");
        }
        if (text.contains("jacksonCaches=flushed")) {
            sep(out); out.append("Jackson序列化缓存已刷新");
        }
        if (text.contains("selfCheck=FAILED") || text.contains("selfCheck=routesLost")) {
            sep(out); out.append("警告：路由自检未通过，接口映射可能丢失，请重启Debug会话");
        } else if (text.contains("selfCheck=WARN")) {
            sep(out); out.append("提示：路由自检部分不一致(selfCheck=WARN，详见verbose)");
        }
        if (text.contains("recreateFailed=")) {
            sep(out); out.append("警告：部分Bean重建失败(recreateFailed，开启详细日志查看原因)");
        }
        appendIfContains(out, text, "annotationCachesCleared=", "注解缓存已清理");
        appendIfContains(out, text, "annotationRepatched=", "注解元数据已按字节码回写");
        if (text.contains("indexAware=true")) {
            sep(out); out.append("拦截链已包含索引感知注解增强");
        } else if (text.contains("indexAware=false")) {
            sep(out); out.append("拦截链未包含索引感知注解增强");
        }
        if (text.contains("annotationAspectJ@") || text.contains("annotationMatcher@") || text.contains("genericPatched=")) {
            sep(out); out.append("通用注解切面/匹配器已重绑");
        }
        if (text.contains("match.") && text.contains(".anns=")) {
            sep(out); out.append("方法注解探测已更新");
        }
        if (text.contains("cachesCleared=")) {
            String n = extractNumber(text, "cachesCleared=");
            if (n != null) { sep(out); out.append("AOP方法缓存清理=").append(n); }
        }
        // keep a compact technical tail only when verbose logging is enabled
        if (isVerboseLogsEnabled()) {
            String compact = compactTechnical(text);
            if (!compact.isEmpty()) {
                sep(out);
                out.append("原始摘要=").append(compact);
            }
        }
        return out.length() == 0 ? humanizeTechnical(raw) : out.toString();
    }

    private static boolean isVerboseLogsEnabled() {
        try {
            return HotReloadSettings.getInstance().isShowVerboseLogs();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendIfContains(StringBuilder out, String text, String token, String label) {
        if (!text.contains(token)) return;
        String n = extractNumber(text, token);
        sep(out);
        if (n != null) out.append(label).append('=').append(n);
        else out.append(label);
    }

    private static String extractNumber(String text, String token) {
        int idx = text.indexOf(token);
        if (idx < 0) return null;
        int start = idx + token.length();
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c >= '0' && c <= '9') end++;
            else break;
        }
        if (end == start) return null;
        return text.substring(start, end);
    }

    private static String compactTechnical(String text) {
        if (text == null) return "";
        String t = text;
        if (t.length() > 220) t = t.substring(0, 220) + "...";
        return t.replace(' ', '_');
    }

    private static String humanizeTechnical(String value) {
        if (value == null || value.isEmpty() || "none".equals(value)) return null;
        String text = value.replace('_', ' ');
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("configuration property source not loaded")) {
            return "当前 Spring Environment 未加载该配置文件，已安全跳过，避免改变属性优先级";
        }
        if (lower.contains("request queue full")) {
            return "请求队列已满，本次热更新已跳过；调试连接仍保持可用";
        }
        if (lower.contains("add method not implemented")
                || lower.contains("attempted to add a method")
                || lower.contains("class redefinition failed: attempted to add a method")
                || lower.contains("虚拟机不支持的操作: add method")) {
            return "标准JVM不支持直接新增方法；E2增强引擎可就地处理，E3仅在可赋值子类能够表达且直接Spring Bean成功重建时支持，否则需重启Debug会话";
        }
        if (lower.contains("delete method not implemented")
                || lower.contains("attempted to delete a method")
                || lower.contains("class redefinition failed: attempted to delete a method")
                || lower.contains("虚拟机不支持的操作: delete method")
                || lower.contains("删除方法")) {
            return "标准JVM不支持直接删除方法；需由E2增强引擎就地处理，E3无法表达删除操作，否则需重启Debug会话";
        }
        if (lower.contains("add field not implemented")
                || lower.contains("attempted to add a field")) {
            return "标准JVM不支持直接新增字段；E2增强引擎可就地处理，E3仅在可赋值子类能够表达且直接Spring Bean成功重建时支持，否则需重启Debug会话";
        }
        if (lower.contains("delete field not implemented")
                || lower.contains("attempted to delete a field")) {
            return "标准JVM不支持直接删除字段；需由E2增强引擎就地处理，E3无法表达删除操作，否则需重启Debug会话";
        }
        if (lower.contains("generation cannot remove or change methods")) {
            return "E3无法表达方法删除或签名变更；请使用E2增强引擎或重启Debug会话";
        }
        if (lower.contains("generation cannot remove or retype fields")) {
            return "E3无法表达字段删除或类型变更；请使用E2增强引擎或重启Debug会话";
        }
        if (lower.contains("superclass change requires restart")
                || lower.contains("removed interface requires restart")) {
            return "E3无法表达父类变化或既有接口移除；请使用E2增强引擎或重启Debug会话";
        }
        if (lower.contains("conditionalbeanregistrationrequiresrestart")) {
            return "组件包含@Profile/@Conditional条件，插件不会绕过Spring注册条件；请重启Debug会话由Spring重新判定";
        }
        if (lower.contains("dynamicbeanregistrationfailed")
                || lower.contains("dynamicbeanbindfailed")) {
            return "新Spring Bean未能完整注册或创建，为避免假成功请重启Debug会话";
        }
        if (lower.contains("class structure changed") || lower.contains("schema change")
                || lower.contains("not implemented")) {
            return "类结构已变化；E2增强引擎可处理其运行时接受的变更，E3仅支持可表达的新增成员和直接Spring Bean，否则需重启";
        }
        if (lower.contains("class ambiguous") || lower.contains("loadedcount=2")) {
            return "同名类存在多个无法唯一确认的加载实例，已拒绝猜测目标；请消除类加载歧义或重启Debug会话";
        }
        return text;
    }

    private static String engineCn(String engine) {
        if (engine == null) return null;
        if ("enhanced".equals(engine)) return "增强(结构变更原生支持，Bean与状态保留)";
        if ("standard".equals(engine)) return "标准(结构变更走降级路径，建议启用DCEVM/JBR)";
        return engine;
    }

    private static String runtimeModeCn(String mode) {
        if (mode == null) return null;
        if ("dcevm".equals(mode)) return "DCEVM(-XXaltjvm=dcevm)";
        if ("jbr".equals(mode)) return "JBR(-XX:+AllowEnhancedClassRedefinition)";
        return mode;
    }

    private static String statusCn(String status) {
        if (status == null) return null;
        if ("SUCCESS".equals(status)) return "成功";
        if ("FAILED".equals(status)) return "失败";
        if ("SKIPPED".equals(status)) return "已跳过";
        if ("RESTART_REQUIRED".equals(status)) return "需要重启";
        return status;
    }

    private static String boolCn(String value) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value)) return "是";
        if ("false".equalsIgnoreCase(value)) return "否";
        return value;
    }

    private static String reasonCn(String reason) {
        if (reason == null) return null;
        switch (reason) {
            case "no_active_session":
            case "active_session_count":
                return "当前没有活跃调试会话";
            case "debug_session_changed":
                return "调试会话已切换";
            case "debug_session_not_bound_at_save":
                return "保存时调试会话尚未绑定";
            case "resource_not_unique_on_debug_classpath":
                return "资源在调试类路径上不唯一";
            case "output_not_on_debug_classpath":
                return "模块输出目录不在当前Debug类路径";
            case "ok_source_content_fallback":
                return "输出目录暂无XML副本，已使用源文件内容";
            case "resource_missing_in_module_output":
                return "当前模块输出目录没有该配置文件，无法确认运行时加载来源";
            case "launch_state_missing":
                return "调试会话状态已丢失";
            case "path_unsafe":
                return "资源路径不安全";
            case "bad_input":
                return "资源参数无效";
            case "source_root_missing":
                return "缺少源码根目录";
            case "output_root_missing":
                return "缺少输出目录";
            case "queue_full_or_closed":
                return "队列已满或已关闭";
            case "request_queue_full":
                return "请求队列已满，本次热更新已跳过";
            case "configuration_property_source_not_loaded":
                return "当前 Spring Environment 未加载该配置文件";
            case "ambiguous_compile_finish":
                return "编译完成事件不明确";
            case "transport_failed":
                return "通信失败";
            case "resource_install_failed":
                return "静态资源写入调试输出目录失败";
            case "resource_shadowed_on_debug_classpath":
                return "调试类路径中更早的条目遮蔽了该资源";
            case "file_event_too_large":
                return "单次文件事件包含的文件节点过多";
            case "mapper_lifecycle_change":
                return "Mapper XML 已新增、删除、移动或重命名，运行时状态无法安全增量更新，请重启调试会话";
            case "mapper_content_type_changed":
                return "XML 文件已在 Mapper 与非 Mapper 类型之间切换，运行时语句无法安全增量增删，请重启调试会话";
            case "class_source_lifecycle_change":
                return "Java 源文件已删除、移动或重命名，JVM 无法卸载旧类，请重新构建并重启调试会话";
            case "config_lifecycle_change":
                return "配置文件已新增、删除、移动或重命名，Spring 属性源无法安全增量更新，请重启调试会话";
            case "batch_exceeds_queue_capacity":
                return "静态资源批次超过安全队列容量，请重新构建并重启调试会话";
            case "queue_busy_after_retries":
                return "静态资源生命周期批次重试后仍无法进入队列，请重新构建并重启调试会话";
            case "setting_changed_externally":
                return "IDEA内置HotSwap设置已被外部修改";
            case "recovery_state_unavailable":
                return "无法保存HotSwap恢复状态";
            case "recovery_state_clear_failed":
                return "HotSwap已恢复，但恢复状态清理失败";
            default:
                return reason.replace('_', ' ');
        }
    }

    private static void appendPair(StringBuilder out, String key, String value) {
        if (value == null || value.isEmpty() || "none".equals(value) || "null".equals(value)) return;
        if (out.length() > 0) out.append(" | ");
        out.append(key).append('=').append(value);
    }

    private static void sep(StringBuilder out) {
        if (out.length() > 0) out.append("；");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty() && !"none".equals(a)) return a;
        if (b != null && !b.isEmpty() && !"none".equals(b)) return b;
        return null;
    }

    private static Map<String, String> parseFields(String details) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (details == null || details.isEmpty()) return map;
        // PluginSessionDiagnostics keeps pair separators as spaces and sanitizes whitespace
        // inside values to underscores.
        String[] tokens = details.split("\\s+");
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                map.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return map;
    }
}

