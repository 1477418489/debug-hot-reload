package dev.hotreload.idea.settings;

import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import dev.hotreload.idea.logging.HotReloadLogBuffer;
import dev.hotreload.idea.client.HotReloadProjectService;
import dev.hotreload.idea.run.DebugLaunchPolicy;
import dev.hotreload.idea.run.EnhancedRuntimeSupport;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HotReloadConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(HotReloadConfigurable.class);

    private final Project project;
    private final HotReloadSettings globalSettings;
    private final ProjectHotReloadSettings projectSettings;

    private JBCheckBox globalEnabled;
    private JBCheckBox globalJavaReload;
    private JBCheckBox globalMapperReload;
    private JBCheckBox globalConfigReload;
    private JBCheckBox globalStaticReload;
    private JBCheckBox globalEnhancedRuntime;
    private JComboBox<LogLevelOption> logLevel;
    private JSpinner logCapacity;

    private JComboBox<ProjectHotReloadSettings.ActivationMode> projectActivation;
    private JBCheckBox projectJavaReload;
    private JBCheckBox projectMapperReload;
    private JBCheckBox projectConfigReload;
    private JBCheckBox projectStaticReload;
    private JBCheckBox projectEnhancedRuntime;
    private JPanel excludedConfigurationsPanel;
    private final Map<String, JBCheckBox> excludedConfigurationBoxes =
            new LinkedHashMap<String, JBCheckBox>();
    private JBTextArea environmentStatus;
    private JPanel panel;

    public HotReloadConfigurable(Project project) {
        this(project, HotReloadSettings.getInstance(),
                ProjectHotReloadSettings.getInstance(project));
    }

    HotReloadConfigurable(Project project, HotReloadSettings globalSettings,
                          ProjectHotReloadSettings projectSettings) {
        this.project = project;
        this.globalSettings = globalSettings;
        this.projectSettings = projectSettings;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Debug Hot Reload";
    }

    @Override public @Nullable JComponent createComponent() {
        createControls();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("全局默认", scrollable(globalPage()));
        tabs.addTab("当前项目", scrollable(projectPage()));

        panel = new JPanel(new BorderLayout());
        panel.add(tabs, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(JBUI.scale(680), JBUI.scale(560)));
        reset();
        return panel;
    }

    private void createControls() {
        globalEnabled = checkBox("默认对项目启用插件", "globalEnabled");
        globalJavaReload = checkBox("Java 类热更新", "globalJavaReload");
        globalMapperReload = checkBox("Mapper XML 热更新", "globalMapperReload");
        globalConfigReload = checkBox("配置文件热更新", "globalConfigReload");
        globalStaticReload = checkBox("静态资源热更新", "globalStaticReload");
        globalEnhancedRuntime = checkBox("自动启用增强运行时", "globalEnhancedRuntime");
        logLevel = new JComboBox<LogLevelOption>(LogLevelOption.values());
        logLevel.setName("logLevel");
        logCapacity = new JSpinner(new SpinnerNumberModel(HotReloadSettings.DEFAULT_LOG_CAPACITY,
                HotReloadSettings.MIN_LOG_CAPACITY, HotReloadSettings.MAX_LOG_CAPACITY, 100));
        logCapacity.setName("logCapacity");

        projectActivation = new JComboBox<ProjectHotReloadSettings.ActivationMode>(
                ProjectHotReloadSettings.ActivationMode.values());
        projectActivation.setName("projectActivation");
        projectJavaReload = checkBox("Java 类热更新", "projectJavaReload");
        projectMapperReload = checkBox("Mapper XML 热更新", "projectMapperReload");
        projectConfigReload = checkBox("配置文件热更新", "projectConfigReload");
        projectStaticReload = checkBox("静态资源热更新", "projectStaticReload");
        projectEnhancedRuntime = checkBox("自动启用增强运行时", "projectEnhancedRuntime");

        excludedConfigurationsPanel = new JPanel();
        excludedConfigurationsPanel.setLayout(new BoxLayout(excludedConfigurationsPanel,
                BoxLayout.Y_AXIS));
        environmentStatus = new JBTextArea(5, 48);
        environmentStatus.setName("environmentStatus");
        environmentStatus.setEditable(false);
        environmentStatus.setOpaque(false);
        environmentStatus.setLineWrap(true);
        environmentStatus.setWrapStyleWord(true);
        environmentStatus.setBorder(JBUI.Borders.empty(2));

        globalEnabled.addActionListener(event -> updateControlEnablement());
        projectActivation.addActionListener(event -> updateControlEnablement());
        globalJavaReload.addActionListener(event -> updateControlEnablement());
        globalEnhancedRuntime.addActionListener(event -> refreshEnvironment());
        projectJavaReload.addActionListener(event -> updateControlEnablement());
        projectEnhancedRuntime.addActionListener(event -> refreshEnvironment());
    }

    private JComponent globalPage() {
        JPanel featureGrid = twoColumnGrid(globalJavaReload, globalMapperReload,
                globalConfigReload, globalStaticReload);
        JPanel logging = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        logging.add(new JBLabel("日志级别"));
        logging.add(logLevel);
        logging.add(Box.createHorizontalStrut(JBUI.scale(12)));
        logging.add(new JBLabel("保留条数"));
        logging.add(logCapacity);

        return verticalPage(
                section("常规", globalEnabled,
                        muted("项目可继承此默认值，也可显式启用或禁用。")),
                section("热更新范围", featureGrid),
                section("增强运行时", globalEnhancedRuntime,
                        muted("仅在确认 DCEVM 或 JBR 支持时注入参数。")),
                section("日志与诊断", logging,
                        muted("诊断级别在下次 Debug 启动时传递给 Agent；保留条数立即生效。")));
    }

    private JComponent projectPage() {
        JPanel activationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        activationRow.add(new JBLabel("启用状态"));
        activationRow.add(projectActivation);

        JPanel featureGrid = twoColumnGrid(projectJavaReload, projectMapperReload,
                projectConfigReload, projectStaticReload);
        JScrollPane exclusions = new JBScrollPane(excludedConfigurationsPanel);
        exclusions.setPreferredSize(new Dimension(JBUI.scale(580), JBUI.scale(140)));
        exclusions.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JButton refreshConfigurations = iconButton(AllIcons.Actions.Refresh,
                "刷新运行配置", "refreshRunConfigurations");
        refreshConfigurations.addActionListener(event -> refreshRunConfigurations(true));
        JPanel exclusionsHeader = new JPanel(new BorderLayout());
        exclusionsHeader.add(muted("选中的配置不会注入 Agent。"), BorderLayout.CENTER);
        exclusionsHeader.add(refreshConfigurations, BorderLayout.EAST);

        JButton refreshEnvironment = iconButton(AllIcons.Actions.Refresh,
                "重新检测运行环境", "refreshEnvironment");
        refreshEnvironment.addActionListener(event -> refreshEnvironment());
        JPanel environmentHeader = new JPanel(new BorderLayout());
        environmentHeader.add(muted("实际参数仍以每次 Debug 使用的 JDK 为准。"), BorderLayout.CENTER);
        environmentHeader.add(refreshEnvironment, BorderLayout.EAST);

        refreshRunConfigurations(false);
        refreshEnvironment();
        return verticalPage(
                section("作用范围", activationRow,
                        muted("继承全局使用上方默认值；启用后使用本项目设置。"),
                        muted("禁用会立即停止发送；Agent 注入范围在下次 Debug 完整生效。")),
                section("本项目热更新范围", featureGrid, projectEnhancedRuntime),
                section("排除运行配置", exclusionsHeader, exclusions),
                section("运行环境", environmentHeader, environmentStatus));
    }

    @Override public boolean isModified() {
        if (panel == null) return false;
        HotReloadSettings.State global = globalSettings.snapshot();
        ProjectHotReloadSettings.State local = projectSettings.snapshot();
        return globalEnabled.isSelected() != global.pluginEnabled
                || globalJavaReload.isSelected() != global.javaReloadEnabled
                || globalMapperReload.isSelected() != global.mapperReloadEnabled
                || globalConfigReload.isSelected() != global.configReloadEnabled
                || globalStaticReload.isSelected() != global.staticResourceReloadEnabled
                || globalEnhancedRuntime.isSelected() != global.enhancedRuntimeEnabled
                || (logLevel.getSelectedItem() == LogLevelOption.DIAGNOSTIC)
                != global.showVerboseLogs
                || isLogCapacityModified(global.logCapacity)
                || projectActivation.getSelectedItem()
                != ProjectHotReloadSettings.parseActivationMode(local.activationMode)
                || projectJavaReload.isSelected() != local.javaReloadEnabled
                || projectMapperReload.isSelected() != local.mapperReloadEnabled
                || projectConfigReload.isSelected() != local.configReloadEnabled
                || projectStaticReload.isSelected() != local.staticResourceReloadEnabled
                || projectEnhancedRuntime.isSelected() != local.enhancedRuntimeEnabled
                || !selectedExcludedConfigurationKeys().equals(
                local.excludedRunConfigurations);
    }

    @Override public void apply() throws ConfigurationException {
        int capacity;
        try {
            logCapacity.commitEdit();
            capacity = validatedLogCapacity();
        } catch (ParseException invalidCapacity) {
            throw new ConfigurationException("日志保留条数必须是整数。");
        }
        boolean verbose = logLevel.getSelectedItem() == LogLevelOption.DIAGNOSTIC;
        ProjectHotReloadSettings.ActivationMode activation =
                (ProjectHotReloadSettings.ActivationMode) projectActivation.getSelectedItem();
        Set<String> exclusions = selectedExcludedConfigurationKeys();
        HotReloadSettings.State previous = globalSettings.snapshot();

        globalSettings.updateUserOptions(globalEnabled.isSelected(),
                globalJavaReload.isSelected(), globalMapperReload.isSelected(),
                globalConfigReload.isSelected(), globalStaticReload.isSelected(),
                globalEnhancedRuntime.isSelected(), verbose, capacity);
        projectSettings.updateUserOptions(activation, projectJavaReload.isSelected(),
                projectMapperReload.isSelected(), projectConfigReload.isSelected(),
                projectStaticReload.isSelected(), projectEnhancedRuntime.isSelected(), exclusions);

        notifyOpenProjects(previous.logCapacity != capacity,
                previous.showVerboseLogs != verbose);
        updateControlEnablement();
    }

    private void notifyOpenProjects(boolean capacityChanged, boolean logPresentationChanged) {
        Project[] projects;
        try {
            projects = ProjectManager.getInstance().getOpenProjects();
        } catch (Throwable unavailable) {
            projects = project == null ? new Project[0] : new Project[]{project};
        }
        for (Project openProject : projects) {
            if (openProject == null || openProject.isDisposed()) continue;
            try {
                HotReloadLogBuffer buffer =
                        openProject.getServiceIfCreated(HotReloadLogBuffer.class);
                if (buffer != null) {
                    if (capacityChanged) buffer.setCapacity(globalSettings.getLogCapacity());
                    if (logPresentationChanged) buffer.refreshListeners();
                }
                HotReloadProjectService service =
                        openProject.getServiceIfCreated(HotReloadProjectService.class);
                if (service != null) service.settingsChanged();
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn("Failed to notify an open project after settings changed", failure);
            }
        }
    }

    private boolean isLogCapacityModified(int configuredCapacity) {
        try {
            logCapacity.commitEdit();
            return validatedLogCapacity()
                    != HotReloadSettings.clampLogCapacity(configuredCapacity);
        } catch (ParseException invalidCapacity) {
            return true;
        } catch (ConfigurationException invalidRange) {
            return true;
        }
    }

    private int validatedLogCapacity() throws ConfigurationException {
        Object value = logCapacity.getValue();
        if (!(value instanceof Number)) {
            throw new ConfigurationException("日志保留条数必须是整数。");
        }
        Number number = (Number) value;
        long capacity = number.longValue();
        double numericValue = number.doubleValue();
        if (!Double.isFinite(numericValue) || numericValue != capacity) {
            throw new ConfigurationException("日志保留条数必须是整数。");
        }
        if (capacity < HotReloadSettings.MIN_LOG_CAPACITY
                || capacity > HotReloadSettings.MAX_LOG_CAPACITY) {
            throw new ConfigurationException("日志保留条数必须在 "
                    + HotReloadSettings.MIN_LOG_CAPACITY + " 到 "
                    + HotReloadSettings.MAX_LOG_CAPACITY + " 之间。");
        }
        return (int) capacity;
    }

    @Override public void reset() {
        if (panel == null) return;
        HotReloadSettings.State global = globalSettings.snapshot();
        ProjectHotReloadSettings.State local = projectSettings.snapshot();
        globalEnabled.setSelected(global.pluginEnabled);
        globalJavaReload.setSelected(global.javaReloadEnabled);
        globalMapperReload.setSelected(global.mapperReloadEnabled);
        globalConfigReload.setSelected(global.configReloadEnabled);
        globalStaticReload.setSelected(global.staticResourceReloadEnabled);
        globalEnhancedRuntime.setSelected(global.enhancedRuntimeEnabled);
        logLevel.setSelectedItem(global.showVerboseLogs
                ? LogLevelOption.DIAGNOSTIC : LogLevelOption.NORMAL);
        logCapacity.setValue(HotReloadSettings.clampLogCapacity(global.logCapacity));

        projectActivation.setSelectedItem(
                ProjectHotReloadSettings.parseActivationMode(local.activationMode));
        projectJavaReload.setSelected(local.javaReloadEnabled);
        projectMapperReload.setSelected(local.mapperReloadEnabled);
        projectConfigReload.setSelected(local.configReloadEnabled);
        projectStaticReload.setSelected(local.staticResourceReloadEnabled);
        projectEnhancedRuntime.setSelected(local.enhancedRuntimeEnabled);
        Set<String> excluded = local.excludedRunConfigurations;
        for (Map.Entry<String, JBCheckBox> entry : excludedConfigurationBoxes.entrySet()) {
            entry.getValue().setSelected(excluded.contains(entry.getKey()));
        }
        updateControlEnablement();
        refreshEnvironment();
    }

    @Override public void disposeUIResources() {
        panel = null;
        globalEnabled = null;
        globalJavaReload = null;
        globalMapperReload = null;
        globalConfigReload = null;
        globalStaticReload = null;
        globalEnhancedRuntime = null;
        logLevel = null;
        logCapacity = null;
        projectActivation = null;
        projectJavaReload = null;
        projectMapperReload = null;
        projectConfigReload = null;
        projectStaticReload = null;
        projectEnhancedRuntime = null;
        excludedConfigurationsPanel = null;
        excludedConfigurationBoxes.clear();
        environmentStatus = null;
    }

    private void updateControlEnablement() {
        boolean globalActive = globalEnabled != null && globalEnabled.isSelected();
        setEnabled(globalActive, globalJavaReload, globalMapperReload, globalConfigReload,
                globalStaticReload);
        globalEnhancedRuntime.setEnabled(globalActive && globalJavaReload.isSelected());

        boolean projectActive = projectActivation != null
                && projectActivation.getSelectedItem()
                == ProjectHotReloadSettings.ActivationMode.ENABLED;
        setEnabled(projectActive, projectJavaReload, projectMapperReload, projectConfigReload,
                projectStaticReload);
        projectEnhancedRuntime.setEnabled(projectActive && projectJavaReload.isSelected());
        boolean exclusionsEnabled = projectActivation != null
                && projectActivation.getSelectedItem()
                != ProjectHotReloadSettings.ActivationMode.DISABLED;
        for (JBCheckBox checkBox : excludedConfigurationBoxes.values()) {
            checkBox.setEnabled(exclusionsEnabled);
        }
        refreshEnvironment();
    }

    private void refreshRunConfigurations(boolean preserveSelection) {
        if (excludedConfigurationsPanel == null) return;
        Set<String> selected = preserveSelection
                ? selectedExcludedConfigurationKeys()
                : projectSettings.getExcludedRunConfigurations();
        excludedConfigurationBoxes.clear();
        excludedConfigurationsPanel.removeAll();
        if (project == null || project.isDisposed()) {
            excludedConfigurationsPanel.add(muted("当前没有可用项目。"));
        } else {
            List<RunConfiguration> configurations = new ArrayList<RunConfiguration>(
                    RunManager.getInstance(project).getAllConfigurationsList());
            configurations.removeIf(configuration -> !DebugLaunchPolicy
                    .isConfigurationTypeSupported(configuration.getType().getId()));
            configurations.sort(Comparator.comparing(RunConfiguration::getName,
                    String.CASE_INSENSITIVE_ORDER).thenComparing(
                    configuration -> configuration.getType().getId()));
            for (RunConfiguration configuration : configurations) {
                String typeId = configuration.getType().getId();
                String key = ProjectHotReloadSettings.configurationKey(typeId,
                        configuration.getName());
                if (excludedConfigurationBoxes.containsKey(key)) continue;
                String fullLabel = configuration.getName() + " ("
                        + configuration.getType().getDisplayName() + ")";
                JBCheckBox checkBox = new JBCheckBox(abbreviate(fullLabel, 80));
                checkBox.setToolTipText(fullLabel);
                checkBox.setSelected(selected.contains(key));
                checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
                excludedConfigurationBoxes.put(key, checkBox);
                excludedConfigurationsPanel.add(checkBox);
            }
            if (excludedConfigurationBoxes.isEmpty()) {
                excludedConfigurationsPanel.add(muted(
                        "未发现 Application 或 Spring Boot 运行配置。"));
            }
        }
        excludedConfigurationsPanel.revalidate();
        excludedConfigurationsPanel.repaint();
        updateControlEnablement();
    }

    private Set<String> selectedExcludedConfigurationKeys() {
        Set<String> selected = new LinkedHashSet<String>();
        for (Map.Entry<String, JBCheckBox> entry : excludedConfigurationBoxes.entrySet()) {
            if (entry.getValue().isSelected()) selected.add(entry.getKey());
        }
        return selected;
    }

    private void refreshEnvironment() {
        if (environmentStatus == null) return;
        Sdk sdk = project == null || project.isDisposed() ? null
                : ProjectRootManager.getInstance(project).getProjectSdk();
        EnhancedRuntimeSupport.Result result = EnhancedRuntimeSupport.inspect(sdk);
        StringBuilder text = new StringBuilder();
        text.append("项目 JDK：");
        if (sdk == null) {
            text.append("未配置");
        } else {
            text.append(sdk.getName());
            if (result.getJdkFeature() != null) {
                text.append(" (JDK ").append(result.getJdkFeature()).append(')');
            }
        }
        if (result.getHome() != null) text.append("\n路径：").append(result.getHome());
        text.append("\n增强运行时：").append(runtimeStatus(result));
        if (result.isAvailable()) {
            if (isEnhancedRuntimeSelected()) {
                text.append("\n预计注入：");
            } else {
                text.append("\n可用参数（当前设置未启用）：");
            }
            text.append(String.join(" ", result.getVmArguments()));
        }
        environmentStatus.setText(text.toString());
        environmentStatus.setCaretPosition(0);
    }

    private static String runtimeStatus(EnhancedRuntimeSupport.Result result) {
        if (result.getMode() == EnhancedRuntimeSupport.Mode.DCEVM) return "DCEVM 可用";
        if (result.getMode() == EnhancedRuntimeSupport.Mode.JBR) return "JBR 增强重定义可用";
        switch (result.getReason()) {
            case SDK_MISSING: return "未配置项目 JDK";
            case HOME_MISSING: return "JDK 路径不可用";
            case JBR_REQUIRES_JDK_17: return "当前 JBR 版本低于 17";
            default: return "未检测到受支持的增强运行时";
        }
    }

    private boolean isEnhancedRuntimeSelected() {
        if (projectActivation == null || globalEnabled == null) return false;
        ProjectHotReloadSettings.ActivationMode mode =
                (ProjectHotReloadSettings.ActivationMode) projectActivation.getSelectedItem();
        if (mode == ProjectHotReloadSettings.ActivationMode.DISABLED) return false;
        if (mode == ProjectHotReloadSettings.ActivationMode.ENABLED) {
            return projectJavaReload.isSelected() && projectEnhancedRuntime.isSelected();
        }
        return globalEnabled.isSelected() && globalJavaReload.isSelected()
                && globalEnhancedRuntime.isSelected();
    }

    private static JPanel verticalPage(JComponent... sections) {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(JBUI.Borders.empty(8));
        for (JComponent section : sections) {
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            page.add(section);
            page.add(Box.createVerticalStrut(JBUI.scale(8)));
        }
        page.add(Box.createVerticalGlue());
        return page;
    }

    private static JPanel section(String title, JComponent... components) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(JBUI.Borders.empty(6, 8));
        for (JComponent component : components) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(component);
            body.add(Box.createVerticalStrut(JBUI.scale(5)));
        }
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(BorderFactory.createTitledBorder(title));
        section.add(body, BorderLayout.CENTER);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                section.getPreferredSize().height));
        return section;
    }

    private static JPanel twoColumnGrid(JComponent... components) {
        JPanel grid = new JPanel(new java.awt.GridLayout(0, 2, JBUI.scale(20), JBUI.scale(4)));
        for (JComponent component : components) grid.add(component);
        return grid;
    }

    private static JScrollPane scrollable(JComponent content) {
        JBScrollPane scrollPane = new JBScrollPane(content);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scrollPane;
    }

    private static JBCheckBox checkBox(String text, String name) {
        JBCheckBox checkBox = new JBCheckBox(text);
        checkBox.setName(name);
        return checkBox;
    }

    private static JBLabel muted(String text) {
        JBLabel label = new JBLabel(text);
        label.setEnabled(false);
        return label;
    }

    private static JButton iconButton(javax.swing.Icon icon, String tooltip, String name) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setName(name);
        button.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(28)));
        return button;
    }

    private static String abbreviate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, Math.max(0, maximumLength - 3)) + "...";
    }

    private static void setEnabled(boolean enabled, JComponent... components) {
        for (JComponent component : components) component.setEnabled(enabled);
    }

    private enum LogLevelOption {
        NORMAL("普通"),
        DIAGNOSTIC("诊断");

        private final String label;

        LogLevelOption(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
