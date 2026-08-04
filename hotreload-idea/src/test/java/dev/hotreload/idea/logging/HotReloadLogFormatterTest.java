package dev.hotreload.idea.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadLogFormatterTest {
    @Test
    void formatsChineseSingleLineWithClockTime() {
        HotReloadLogEvent event = new HotReloadLogEvent(
                Instant.parse("2026-07-24T06:02:16Z"),
                HotReloadLogEvent.Level.INFO,
                "SESSION_ACTIVE",
                "924b52bb-aaaa",
                "targetJdk=21 classRedefine=true activeSessions=1");
        String line = HotReloadLogFormatter.format(event, ZoneId.of("UTC"));
        assertTrue(line.startsWith("06:02:16 信息 热更新会话已激活[SESSION_ACTIVE] 会话=924b52bb-aaaa"), line);
        assertTrue(line.contains("目标JDK=21"), line);
        assertTrue(line.contains("支持类重定义=是"), line);
    }

    @Test
    void formatsClassResultInChinese() {
        HotReloadLogEvent event = new HotReloadLogEvent(
                Instant.parse("2026-07-24T06:02:16Z"),
                HotReloadLogEvent.Level.INFO,
                "CLASS_BATCH_RESULT",
                "launch-1",
                "status=SUCCESS errorCode=none itemCount=1 successCount=1 failedCount=0 itemId=com.demo.Foo detail=redefined;spring=contexts=1,mappingRefreshed=1,beansRecreated=1,chain.list.size=3,indexAware=true,genericPatched=2,annotationAspectJ@foo:Log");
        String line = HotReloadLogFormatter.format(event, ZoneId.of("UTC"));
        assertTrue(line.contains("类热更新结果[CLASS_BATCH_RESULT]"), line);
        assertTrue(line.contains("结果=成功"), line);
        assertTrue(line.contains("类名=com.demo.Foo"), line);
        assertTrue(line.contains("拦截链已包含索引感知注解增强") || line.contains("通用注解切面/匹配器已重绑"), line);
    }

    @Test
    void omitsTrailingSpaceWhenDetailsEmpty() {
        HotReloadLogEvent event = new HotReloadLogEvent(
                Instant.parse("2026-07-24T06:02:16Z"),
                HotReloadLogEvent.Level.WARN,
                "PROCESS_BIND_UNIDENTIFIED",
                "none",
                "");
        String line = HotReloadLogFormatter.format(event, ZoneId.of("UTC"));
        assertEquals("06:02:16 警告 未能识别调试进程[PROCESS_BIND_UNIDENTIFIED] 会话=none", line);
    }
}
