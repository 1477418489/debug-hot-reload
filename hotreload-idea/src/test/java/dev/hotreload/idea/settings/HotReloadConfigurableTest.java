package dev.hotreload.idea.settings;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReloadConfigurableTest {
    @Test void resetApplyAndModifiedCoverGlobalAndProjectValues() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                HotReloadSettings global = new HotReloadSettings();
                ProjectHotReloadSettings local = new ProjectHotReloadSettings();
                HotReloadConfigurable configurable =
                        new HotReloadConfigurable(null, global, local);
                JComponent component = configurable.createComponent();
                assertNotNull(component);
                assertFalse(configurable.isModified());

                find(component, "globalJavaReload", JCheckBox.class).setSelected(false);
                find(component, "logLevel", JComboBox.class).setSelectedIndex(1);
                find(component, "logCapacity", JSpinner.class).setValue(2_500);
                find(component, "projectActivation", JComboBox.class).setSelectedItem(
                        ProjectHotReloadSettings.ActivationMode.ENABLED);
                find(component, "projectStaticReload", JCheckBox.class).setSelected(false);
                assertTrue(configurable.isModified());

                configurable.apply();
                assertFalse(global.isJavaReloadEnabled());
                assertTrue(global.isShowVerboseLogs());
                assertEquals(2_500, global.getLogCapacity());
                assertEquals(ProjectHotReloadSettings.ActivationMode.ENABLED,
                        local.getActivationMode());
                assertFalse(local.isStaticResourceReloadEnabled());
                assertFalse(configurable.isModified());

                global.setJavaReloadEnabled(true);
                assertTrue(configurable.isModified());
                configurable.reset();
                assertTrue(find(component, "globalJavaReload", JCheckBox.class).isSelected());
                assertFalse(configurable.isModified());
                configurable.disposeUIResources();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test void invalidCapacityDoesNotPartiallyApplyOtherSettings() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                HotReloadSettings global = new HotReloadSettings();
                ProjectHotReloadSettings local = new ProjectHotReloadSettings();
                HotReloadConfigurable configurable =
                        new HotReloadConfigurable(null, global, local);
                JComponent component = configurable.createComponent();
                find(component, "globalEnabled", JCheckBox.class).setSelected(false);
                find(component, "projectActivation", JComboBox.class).setSelectedItem(
                        ProjectHotReloadSettings.ActivationMode.ENABLED);
                JSpinner spinner = find(component, "logCapacity", JSpinner.class);
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                        .setText("not-an-integer");

                boolean rejected = false;
                try {
                    configurable.apply();
                } catch (com.intellij.openapi.options.ConfigurationException expected) {
                    rejected = true;
                }

                assertTrue(rejected);
                assertTrue(global.isPluginEnabled());
                assertEquals(ProjectHotReloadSettings.ActivationMode.INHERIT,
                        local.getActivationMode());
                configurable.disposeUIResources();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test void invalidCapacityIsReportedAsModifiedBeforeApply() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                HotReloadSettings global = new HotReloadSettings();
                HotReloadConfigurable configurable =
                        new HotReloadConfigurable(null, global, new ProjectHotReloadSettings());
                JComponent component = configurable.createComponent();
                JSpinner spinner = find(component, "logCapacity", JSpinner.class);
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                        .setText("not-an-integer");
                assertTrue(configurable.isModified());
                configurable.disposeUIResources();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test void enhancedRuntimeControlsFollowTheirJavaReloadDependency() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                HotReloadSettings global = new HotReloadSettings();
                global.setJavaReloadEnabled(false);
                ProjectHotReloadSettings local = new ProjectHotReloadSettings();
                local.setActivationMode(ProjectHotReloadSettings.ActivationMode.ENABLED);
                local.setJavaReloadEnabled(false);
                HotReloadConfigurable configurable =
                        new HotReloadConfigurable(null, global, local);
                JComponent component = configurable.createComponent();
                JCheckBox globalJava = find(component, "globalJavaReload", JCheckBox.class);
                JCheckBox globalEnhanced = find(component, "globalEnhancedRuntime", JCheckBox.class);
                JCheckBox projectJava = find(component, "projectJavaReload", JCheckBox.class);
                JCheckBox projectEnhanced = find(component, "projectEnhancedRuntime", JCheckBox.class);

                assertFalse(globalEnhanced.isEnabled());
                assertFalse(projectEnhanced.isEnabled());
                globalJava.doClick();
                projectJava.doClick();
                assertTrue(globalEnhanced.isEnabled());
                assertTrue(projectEnhanced.isEnabled());
                configurable.disposeUIResources();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                T found = findOrNull(child, name, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Component not found: " + name);
    }

    private static <T extends Component> T findOrNull(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                T found = findOrNull(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
