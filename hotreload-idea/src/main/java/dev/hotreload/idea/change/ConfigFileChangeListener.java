package dev.hotreload.idea.change;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import dev.hotreload.idea.client.HotReloadProjectService;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Watches application* and bootstrap* properties/YAML files under resource roots.
 */
public final class ConfigFileChangeListener implements BulkFileListener {
    private static final int MAX_LIFECYCLE_SCAN_NODES = 4096;
    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;
    private final Set<VFileEvent> configBeforeLifecycle = Collections.newSetFromMap(
            new IdentityHashMap<VFileEvent, Boolean>());

    public ConfigFileChangeListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override public void before(List<? extends VFileEvent> events) {
        synchronized (configBeforeLifecycle) {
            configBeforeLifecycle.clear();
            if (!sessions.isConfigReloadEnabled()) return;
            for (VFileEvent event : events) {
                if (removesOldLocation(event) && containsReloadableConfig(event.getFile())) {
                    configBeforeLifecycle.add(event);
                }
            }
        }
    }

    @Override public void after(List<? extends VFileEvent> events) {
        if (!sessions.isConfigReloadEnabled()) {
            synchronized (configBeforeLifecycle) { configBeforeLifecycle.clear(); }
            return;
        }
        for (VFileEvent event : events) {
            boolean oldConfig;
            synchronized (configBeforeLifecycle) {
                oldConfig = configBeforeLifecycle.remove(event);
            }
            VirtualFile file = fileAfter(event);
            boolean lifecycle = removesOldLocation(event)
                    || event instanceof VFileCreateEvent || event instanceof VFileCopyEvent;
            if (lifecycle) {
                if (oldConfig || containsReloadableConfig(file)) {
                    sessions.recordWarning("CONFIG_RESTART_REQUIRED", "config_lifecycle_change");
                }
                continue;
            }
            if (!(event instanceof VFileContentChangeEvent)) continue;
            scheduleContentReload(file);
        }
        synchronized (configBeforeLifecycle) { configBeforeLifecycle.clear(); }
    }

    private void scheduleContentReload(VirtualFile file) {
            if (file == null || !file.isValid() || file.isDirectory() || !file.isInLocalFileSystem()) return;
            JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
            if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
                return;
            }
            Module module = fileIndex.getModuleForFile(file);
            if (module == null) return;
            VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
            if (sourceRoot == null || !sourceRoot.isInLocalFileSystem()) return;
            String relative = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
            if (!isReloadableConfigPath(relative)) return;
            CompilerModuleExtension compiler = CompilerModuleExtension.getInstance(module);
            VirtualFile outputRoot = compiler == null ? null
                    : rootType == JavaResourceRootType.TEST_RESOURCE
                    ? compiler.getCompilerOutputPathForTests() : compiler.getCompilerOutputPath();
            if (outputRoot == null || !outputRoot.isInLocalFileSystem()) {
                sessions.recordWarning("CONFIG_RELOAD_SKIPPED", "output_root_missing");
                return;
            }
            sessions.scheduleConfigReload(sourceRoot.toNioPath(), outputRoot.toNioPath(),
                    file.toNioPath());
    }

    private boolean isReloadableConfig(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory() || !file.isInLocalFileSystem()) {
            return false;
        }
        JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
        if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
            return false;
        }
        VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
        if (sourceRoot == null) return false;
        return isReloadableConfigPath(VfsUtilCore.getRelativePath(file, sourceRoot, '/'));
    }

    private boolean containsReloadableConfig(VirtualFile root) {
        if (root == null || !root.isValid() || !root.isInLocalFileSystem()
                || !fileIndex.isInContent(root)) return false;
        if (!root.isDirectory()) return isReloadableConfig(root);
        ArrayDeque<VirtualFile> pending = new ArrayDeque<VirtualFile>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty()) {
            VirtualFile file = pending.removeFirst();
            if (++visited > MAX_LIFECYCLE_SCAN_NODES) return true;
            if (!file.isValid() || !file.isInLocalFileSystem()) continue;
            if (file.isDirectory()) {
                VirtualFile[] children = file.getChildren();
                if (children != null) {
                    for (VirtualFile child : children) {
                        if (child == null) continue;
                        if (visited + pending.size() >= MAX_LIFECYCLE_SCAN_NODES) return true;
                        pending.addLast(child);
                    }
                }
            } else if (isReloadableConfig(file)) {
                return true;
            }
        }
        return false;
    }

    private static boolean removesOldLocation(VFileEvent event) {
        return event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent
                || event instanceof VFilePropertyChangeEvent
                && ((VFilePropertyChangeEvent) event).isRename();
    }

    private static VirtualFile fileAfter(VFileEvent event) {
        if (event instanceof VFileDeleteEvent) return null;
        if (event instanceof VFileCopyEvent) {
            VFileCopyEvent copy = (VFileCopyEvent) event;
            VirtualFile created = copy.findCreatedFile();
            return created != null ? created : copy.getNewParent().findChild(copy.getNewChildName());
        }
        return event.getFile();
    }

    static boolean isReloadableConfigPath(String relativePath) {
        return ConfigUpdateReader.isReloadableConfigPath(relativePath);
    }
}
