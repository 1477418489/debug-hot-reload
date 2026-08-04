package dev.hotreload.idea.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadChineseMessagesTest {
    @Test
    void translatesKnownEvents() {
        assertEquals("类热更新结果", HotReloadChineseMessages.eventTitle("CLASS_BATCH_RESULT"));
        assertEquals("Mapper XML 热更新结果", HotReloadChineseMessages.eventTitle("XML_RELOAD_RESULT"));
        assertEquals("静态资源已同步到调试输出目录",
                HotReloadChineseMessages.eventTitle("STATIC_RESOURCE_SYNCED"));
        assertEquals("静态资源已从调试输出目录删除",
                HotReloadChineseMessages.eventTitle("STATIC_RESOURCE_REMOVED"));
        assertTrue(HotReloadChineseMessages.formatDetails("STATIC_RELOAD_SKIPPED",
                "reason=resource_shadowed_on_debug_classpath")
                .contains("调试类路径中更早的条目遮蔽了该资源"));
        assertEquals("静态资源同步失败",
                HotReloadChineseMessages.eventTitle("STATIC_SYNC_FAILED"));
    }

    @Test
    void humanizesGenericAnnotationChainDetails() {
        String details = "status=SUCCESS itemId=com.demo.FooController "
                + "detail=redefined;pointcutRebind=...,match.list.anns=none,"
                + "chain.list.size=2,indexAware=false,genericPatched=1";
        String text = HotReloadChineseMessages.formatDetails("CLASS_BATCH_RESULT", details);
        assertTrue(text.contains("结果=成功"), text);
        assertTrue(text.contains("拦截链未包含索引感知注解增强") || text.contains("通用注解切面/匹配器已重绑"), text);
    }

    @Test
    void rendersSkippedResultsSeparatelyFromSuccess() {
        String classText = HotReloadChineseMessages.formatDetails("CLASS_BATCH_RESULT",
                "status=SKIPPED successCount=0 skippedCount=1 failedCount=0 itemId=com.demo.Foo");
        String xmlText = HotReloadChineseMessages.formatDetails("XML_RELOAD_RESULT",
                "status=SKIPPED successCount=0 skippedCount=1 failedCount=0 itemId=mappers/Foo.xml");

        assertTrue(classText.contains("结果=已跳过"), classText);
        assertTrue(classText.contains("跳过数=1"), classText);
        assertTrue(xmlText.contains("结果=已跳过"), xmlText);
        assertTrue(xmlText.contains("跳过数=1"), xmlText);
    }

    @Test
    void localizesQueueRejectionAndMissingConfigurationSource() {
        String queue = HotReloadChineseMessages.formatDetails("CONFIG_RELOAD_SKIPPED",
                "reason=request_queue_full");
        String missing = HotReloadChineseMessages.formatDetails("CONFIG_RELOAD_RESULT",
                "status=SKIPPED detail=skipped_configuration_property_source_not_loaded");

        assertTrue(queue.contains("请求队列已满"), queue);
        assertTrue(missing.contains("未加载该配置文件"), missing);
    }

    @Test
    void humanizesDeleteMethodJvmErrors() {
        String details = "status=FAILED itemId=com.demo.Foo "
                + "detail=class_redefinition_failed:_虚拟机不支持的操作:_delete_method_not_implemented";
        String text = HotReloadChineseMessages.formatDetails("CLASS_BATCH_RESULT", details);
        assertTrue(text.contains("删除方法") || text.contains("结构热更新") || text.contains("Generation"), text);
    }

    @Test
    void humanizesDeleteFieldJvmErrors() {
        String details = "status=FAILED detail=delete_field_not_implemented";
        String text = HotReloadChineseMessages.formatDetails("CLASS_BATCH_RESULT", details);
        assertTrue(text.contains("字段") || text.contains("结构热更新") || text.contains("Generation") || text.contains("类结构"), text);
    }

    @Test
    void humanizesAddMethodJvmErrors() {
        String details = "status=FAILED detail=attempted_to_add_a_method";
        String text = HotReloadChineseMessages.formatDetails("CLASS_BATCH_RESULT", details);
        assertTrue(text.contains("新增方法") || text.contains("结构热更新") || text.contains("Generation"), text);
    }
}
