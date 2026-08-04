package dev.hotreload.idea.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class HotReloadLogToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        HotReloadLogPanel panel = new HotReloadLogPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "热更新日志", false);
        Disposer.register(content, panel::disposePanel);
        toolWindow.getContentManager().addContent(content);
    }
}

