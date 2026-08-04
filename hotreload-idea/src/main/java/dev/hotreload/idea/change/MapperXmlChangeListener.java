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
import dev.hotreload.protocol.util.ResourceTypeDetector;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class MapperXmlChangeListener implements BulkFileListener {
    private static final int MAX_LIFECYCLE_SCAN_NODES = 4096;
    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;
    private final Set<VFileEvent> mapperBeforeLifecycle = Collections.newSetFromMap(
            new IdentityHashMap<VFileEvent, Boolean>());

    public MapperXmlChangeListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override public void before(List<? extends VFileEvent> events) {
        synchronized (mapperBeforeLifecycle) {
            mapperBeforeLifecycle.clear();
            if (!sessions.isMapperReloadEnabled()) return;
            for (VFileEvent event : events) {
                if ((removesOldLocation(event) || event instanceof VFileContentChangeEvent)
                        && containsMapperResource(event.getFile())) {
                    mapperBeforeLifecycle.add(event);
                }
            }
        }
    }

    @Override public void after(List<? extends VFileEvent> events) {
        if (!sessions.isMapperReloadEnabled()) {
            synchronized (mapperBeforeLifecycle) { mapperBeforeLifecycle.clear(); }
            return;
        }
        for (VFileEvent event : events) {
            boolean oldMapper;
            synchronized (mapperBeforeLifecycle) {
                oldMapper = mapperBeforeLifecycle.remove(event);
            }
            VirtualFile file = fileAfter(event);
            boolean lifecycle = removesOldLocation(event)
                    || event instanceof VFileCreateEvent || event instanceof VFileCopyEvent;
            if (lifecycle) {
                if (oldMapper || containsMapperResource(file)) {
                    sessions.recordWarning("XML_RESTART_REQUIRED", "mapper_lifecycle_change");
                }
                continue;
            }
            if (!(event instanceof VFileContentChangeEvent)) continue;
            boolean newMapper = isMapperResource(file);
            if (oldMapper != newMapper) {
                sessions.recordWarning("XML_RESTART_REQUIRED", "mapper_content_type_changed");
                continue;
            }
            if (newMapper) scheduleContentReload(file);
        }
        synchronized (mapperBeforeLifecycle) { mapperBeforeLifecycle.clear(); }
    }

    private void scheduleContentReload(VirtualFile file) {
            if (file == null || !file.isValid() || file.isDirectory()
                    || !"xml".equalsIgnoreCase(file.getExtension()) || !file.isInLocalFileSystem()) {
                return;
            }
            JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
            if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
                return;
            }
            VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
            if (sourceRoot == null || !sourceRoot.isInLocalFileSystem()) {
                sessions.recordWarning("XML_SKIPPED", "source_root_missing");
                return;
            }
            String resourcePath = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
            try (InputStream content = file.getInputStream()) {
                if (!ResourceTypeDetector.isMapperXml(resourcePath, content)) return;
            } catch (IOException failure) {
                sessions.recordWarning("XML_SKIPPED", "mapper_detection_failed");
                return;
            } catch (RuntimeException failure) {
                // A third-party JAXP provider must not escape the VFS callback and abort
                // processing of the remaining file events.
                sessions.recordWarning("XML_SKIPPED", "mapper_detection_failed");
                return;
            }
            Module module = fileIndex.getModuleForFile(file);
            CompilerModuleExtension compiler = module == null ? null : CompilerModuleExtension.getInstance(module);
            VirtualFile outputRoot = compiler == null ? null
                    : rootType == JavaResourceRootType.TEST_RESOURCE
                    ? compiler.getCompilerOutputPathForTests() : compiler.getCompilerOutputPath();
            if (outputRoot == null || !outputRoot.isInLocalFileSystem()) {
                sessions.recordWarning("XML_SKIPPED", "output_root_missing");
                return;
            }
            sessions.scheduleMapperReload(sourceRoot.toNioPath(), outputRoot.toNioPath(), file.toNioPath());
    }

    private boolean isMapperResource(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()
                || !file.isInLocalFileSystem() || !"xml".equalsIgnoreCase(file.getExtension())) {
            return false;
        }
        JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
        if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
            return false;
        }
        VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
        if (sourceRoot == null) return false;
        String resourcePath = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
        try (InputStream content = file.getInputStream()) {
            return ResourceTypeDetector.isMapperXml(resourcePath, content);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean containsMapperResource(VirtualFile root) {
        if (root == null || !root.isValid() || !root.isInLocalFileSystem()
                || !fileIndex.isInContent(root)) return false;
        if (!root.isDirectory()) return isMapperResource(root);
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
            } else if (isMapperResource(file)) {
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
}
