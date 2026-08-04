package dev.hotreload.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import dev.hotreload.idea.logging.HotReloadLogBuffer;
import dev.hotreload.idea.logging.HotReloadLogEvent;
import dev.hotreload.idea.logging.HotReloadLogFormatter;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.RenderingHints;
import java.util.List;

public final class HotReloadLogPanel extends JPanel implements HotReloadLogBuffer.Listener {
    private static final String EMPTY_STATE =
            "暂无热更新日志。请先用 Debug 启动应用，再修改类或资源文件查看结果。\n";

    private final HotReloadLogBuffer buffer;
    private final JTextArea textArea = new JTextArea();
    private final JBCheckBox autoScroll = new JBCheckBox("自动滚动", true);

    public HotReloadLogPanel(Project project) {
        super(new BorderLayout());
        HotReloadLogBuffer service = project.getService(HotReloadLogBuffer.class);
        this.buffer = service == null ? new HotReloadLogBuffer() : service;
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setMargin(JBUI.insets(6));

        // 字体配置 - 使用支持中文的字体
        // 优先使用 Microsoft YaHei (微软雅黑) 或系统默认 Dialog 字体
        Font logFont;
        Font testFont = new Font("Microsoft YaHei", Font.PLAIN, 13);
        if ("Microsoft YaHei".equals(testFont.getFamily())) {
            logFont = testFont;
        } else {
            // 回退到系统默认 Dialog 字体（确保支持中文）
            logFont = new Font("Dialog", Font.PLAIN, 13);
        }
        textArea.setFont(logFont);

        // 启用抗锯齿渲染
        textArea.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> buffer.clear());
        toolbar.add(clear);
        toolbar.add(autoScroll);

        add(toolbar, BorderLayout.NORTH);
        add(new JBScrollPane(textArea), BorderLayout.CENTER);

        renderSnapshot(buffer.addListenerAndSnapshot(this));
    }

    public void disposePanel() {
        buffer.removeListener(this);
    }

    @Override public void onAppend(HotReloadLogEvent event) {
        runOnEdt(() -> {
            if (isShowingEmptyState()) {
                textArea.setText("");
            }
            textArea.append(HotReloadLogFormatter.format(event));
            textArea.append("\n");
            if (autoScroll.isSelected()) {
                textArea.setCaretPosition(textArea.getDocument().getLength());
            }
        });
    }

    @Override public void onCleared() {
        runOnEdt(() -> textArea.setText(EMPTY_STATE));
    }

    @Override public void onReset(List<HotReloadLogEvent> events) {
        runOnEdt(() -> renderSnapshot(events));
    }

    private void renderSnapshot(List<HotReloadLogEvent> events) {
        if (events.isEmpty()) {
            textArea.setText(EMPTY_STATE);
            return;
        }
        StringBuilder content = new StringBuilder(events.size() * 96);
        for (HotReloadLogEvent event : events) {
            content.append(HotReloadLogFormatter.format(event)).append('\n');
        }
        textArea.setText(content.toString());
        if (autoScroll.isSelected()) {
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }

    private boolean isShowingEmptyState() {
        return EMPTY_STATE.equals(textArea.getText());
    }

    private static void runOnEdt(Runnable action) {
        if (ApplicationManager.getApplication() != null
                && ApplicationManager.getApplication().isDispatchThread()) {
            action.run();
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
