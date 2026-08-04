package dev.hotreload.idea.change;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import dev.hotreload.idea.client.HotReloadProjectService;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reports Java source lifecycle changes that cannot be represented as JVM class unloading. */
public final class JavaSourceLifecycleListener implements BulkFileListener {
    private static final int MAX_LIFECYCLE_SCAN_NODES = 4096;

    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;
    private final Set<VFileEvent> admitted = Collections.newSetFromMap(
            new IdentityHashMap<VFileEvent, Boolean>());

    public JavaSourceLifecycleListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override public void before(List<? extends VFileEvent> events) {
        synchronized (admitted) {
            admitted.clear();
            if (!sessions.isJavaReloadEnabled()) return;
            for (VFileEvent event : events) {
                if (removesOldLocation(event) && containsJavaSource(event.getFile())) {
                    admitted.add(event);
                }
            }
        }
    }

    @Override public void after(List<? extends VFileEvent> events) {
        boolean restartRequired = false;
        synchronized (admitted) {
            for (VFileEvent event : events) restartRequired |= admitted.remove(event);
            admitted.clear();
        }
        if (restartRequired && sessions.isJavaReloadEnabled()) {
            sessions.recordWarning("CLASS_RESTART_REQUIRED", "class_source_lifecycle_change");
        }
    }

    private boolean containsJavaSource(VirtualFile root) {
        if (root == null || !root.isValid() || !root.isInLocalFileSystem()
                || !fileIndex.isInContent(root)) return false;
        if (!root.isDirectory()) return isJavaSource(root);
        ArrayDeque<VirtualFile> pending = new ArrayDeque<VirtualFile>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty()) {
            VirtualFile file = pending.removeFirst();
            if (++visited > MAX_LIFECYCLE_SCAN_NODES) return true;
            if (!file.isValid() || !file.isInLocalFileSystem()) continue;
            if (file.isDirectory()) {
                VirtualFile[] children = file.getChildren();
                if (children == null) continue;
                for (VirtualFile child : children) {
                    if (child == null) continue;
                    if (visited + pending.size() >= MAX_LIFECYCLE_SCAN_NODES) return true;
                    pending.addLast(child);
                }
            } else if (isJavaSource(file)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJavaSource(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()
                || !file.isInLocalFileSystem() || !"java".equalsIgnoreCase(file.getExtension())) {
            return false;
        }
        JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
        return rootType == JavaSourceRootType.SOURCE || rootType == JavaSourceRootType.TEST_SOURCE;
    }

    private static boolean removesOldLocation(VFileEvent event) {
        return event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent
                || event instanceof VFilePropertyChangeEvent
                && ((VFilePropertyChangeEvent) event).isRename();
    }
}
