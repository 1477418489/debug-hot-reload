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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监听静态资源的内容和文件生命周期变化并触发热更新。
 * 支持 Spring Boot 静态资源目录：static/、public/、resources/、META-INF/resources/。
 */
public final class StaticResourceChangeListener implements BulkFileListener {
    private static final int MAX_VFS_NODES_PER_EVENT = 4096;

    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;
    private final Map<VFileEvent, List<ResourceLocation>> locationsBeforeChange =
            new IdentityHashMap<VFileEvent, List<ResourceLocation>>();
    private final Set<VFileEvent> admittedLifecycleEvents = Collections.newSetFromMap(
            new IdentityHashMap<VFileEvent, Boolean>());

    public StaticResourceChangeListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override
    public void before(List<? extends VFileEvent> events) {
        synchronized (locationsBeforeChange) {
            locationsBeforeChange.clear();
            admittedLifecycleEvents.clear();
            if (!sessions.isStaticResourceReloadEnabled()) return;
            for (VFileEvent event : events) {
                if (!removesOldLocation(event)) continue;
                admittedLifecycleEvents.add(event);
                List<ResourceLocation> locations = collect(event.getFile());
                if (!locations.isEmpty()) locationsBeforeChange.put(event, locations);
            }
        }
    }

    @Override
    public void after(List<? extends VFileEvent> events) {
        boolean featureEnabled = sessions.isStaticResourceReloadEnabled();
        boolean committedLifecycle = false;
        Map<String, ResourceLocation> removals = new LinkedHashMap<String, ResourceLocation>();
        Map<String, ResourceLocation> synchronizations =
                new LinkedHashMap<String, ResourceLocation>();

        for (VFileEvent event : events) {
            List<ResourceLocation> previous;
            boolean lifecycleAdmitted;
            synchronized (locationsBeforeChange) {
                previous = locationsBeforeChange.remove(event);
                lifecycleAdmitted = admittedLifecycleEvents.remove(event);
            }
            // A move/rename/delete must be accepted in before() so old and new paths remain
            // one lifecycle batch. Enabling the feature halfway through must not install only
            // the new path and leave the old output copy behind.
            if (removesOldLocation(event) && !lifecycleAdmitted) continue;
            if (lifecycleAdmitted) committedLifecycle = true;
            if (!featureEnabled && !lifecycleAdmitted) continue;
            if (previous != null) {
                for (ResourceLocation location : previous) removals.put(location.key(), location);
            }

            VirtualFile changed = fileAfter(event);
            for (ResourceLocation location : collect(changed)) {
                synchronizations.put(location.key(), location);
            }
        }
        synchronized (locationsBeforeChange) {
            locationsBeforeChange.clear();
            admittedLifecycleEvents.clear();
        }

        // All old paths must leave the output before any new path is installed. The project
        // service uses one executor, so this ordering is retained across case-only renames too.
        List<ResourceLocation> removed = new ArrayList<ResourceLocation>(removals.values());
        List<ResourceLocation> synchronizedResources =
                new ArrayList<ResourceLocation>(synchronizations.values());
        if (committedLifecycle) {
            sessions.scheduleCommittedStaticResourceChanges(removed, synchronizedResources);
        } else if (featureEnabled) {
            sessions.scheduleStaticResourceChanges(removed, synchronizedResources);
        }
    }

    private List<ResourceLocation> collect(VirtualFile file) {
        List<ResourceLocation> locations = new ArrayList<ResourceLocation>();
        ScanBudget budget = new ScanBudget();
        collect(file, locations, budget);
        if (budget.truncated) {
            sessions.recordWarning("STATIC_RELOAD_SKIPPED", "file_event_too_large");
            return Collections.emptyList();
        }
        return locations;
    }

    private void collect(VirtualFile file, List<ResourceLocation> locations,
                         ScanBudget budget) {
        if (file == null || !file.isValid() || !file.isInLocalFileSystem()
                || !fileIndex.isInContent(file)) {
            return;
        }
        if (budget.visited >= MAX_VFS_NODES_PER_EVENT) {
            budget.truncated = true;
            return;
        }
        budget.visited++;
        try {
            if (Files.isSymbolicLink(file.toNioPath())) return;
        } catch (RuntimeException ignored) {
            return;
        }
        if (file.isDirectory()) {
            for (VirtualFile child : file.getChildren()) {
                collect(child, locations, budget);
                if (budget.truncated) break;
            }
            return;
        }

        ResourceLocation location = resolve(file);
        if (location != null) locations.add(location);
    }

    private ResourceLocation resolve(VirtualFile file) {
        JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
        if (rootType != JavaResourceRootType.RESOURCE
                && rootType != JavaResourceRootType.TEST_RESOURCE) {
            return null;
        }

        VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
        if (sourceRoot == null || !sourceRoot.isInLocalFileSystem()) {
            sessions.recordWarning("STATIC_RELOAD_SKIPPED", "source_root_missing");
            return null;
        }

        String relative = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
        if (relative == null || relative.isEmpty()
                || !ResourceTypeDetector.isStaticResource(relative)) {
            return null;
        }

        Module module = fileIndex.getModuleForFile(file);
        CompilerModuleExtension compiler = module == null ? null
                : CompilerModuleExtension.getInstance(module);
        VirtualFile outputRoot = compiler == null ? null
                : rootType == JavaResourceRootType.TEST_RESOURCE
                ? compiler.getCompilerOutputPathForTests() : compiler.getCompilerOutputPath();
        if (outputRoot == null || !outputRoot.isInLocalFileSystem()) {
            sessions.recordWarning("STATIC_RELOAD_SKIPPED", "output_root_missing");
            return null;
        }

        try {
            return new ResourceLocation(sourceRoot.toNioPath(), outputRoot.toNioPath(),
                    file.toNioPath(), relative);
        } catch (RuntimeException failure) {
            sessions.recordWarning("STATIC_RELOAD_SKIPPED", "path_unsafe");
            return null;
        }
    }

    private static boolean removesOldLocation(VFileEvent event) {
        return event instanceof VFileDeleteEvent
                || event instanceof VFileMoveEvent
                || event instanceof VFilePropertyChangeEvent
                && ((VFilePropertyChangeEvent) event).isRename();
    }

    private static VirtualFile fileAfter(VFileEvent event) {
        if (event instanceof VFileDeleteEvent) return null;
        if (event instanceof VFileCopyEvent) {
            VFileCopyEvent copy = (VFileCopyEvent) event;
            VirtualFile created = copy.findCreatedFile();
            return created != null ? created
                    : copy.getNewParent().findChild(copy.getNewChildName());
        }
        if (event instanceof VFileCreateEvent || event instanceof VFileContentChangeEvent
                || event instanceof VFileMoveEvent) {
            return event.getFile();
        }
        if (event instanceof VFilePropertyChangeEvent
                && ((VFilePropertyChangeEvent) event).isRename()) {
            return event.getFile();
        }
        return null;
    }

    public static final class ResourceLocation {
        private final java.nio.file.Path sourceRoot;
        private final java.nio.file.Path outputRoot;
        private final java.nio.file.Path sourceFile;
        private final String resourceId;

        private ResourceLocation(java.nio.file.Path sourceRoot, java.nio.file.Path outputRoot,
                                 java.nio.file.Path sourceFile, String resourceId) {
            this.sourceRoot = sourceRoot;
            this.outputRoot = outputRoot;
            this.sourceFile = sourceFile;
            this.resourceId = resourceId;
        }

        private String key() {
            return outputRoot.toAbsolutePath().normalize() + "\n" + resourceId;
        }

        public java.nio.file.Path getSourceRoot() { return sourceRoot; }
        public java.nio.file.Path getOutputRoot() { return outputRoot; }
        public java.nio.file.Path getSourceFile() { return sourceFile; }
        public String getResourceId() { return resourceId; }
    }

    private static final class ScanBudget {
        private int visited;
        private boolean truncated;
    }
}
