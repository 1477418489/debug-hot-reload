package dev.hotreload.idea.change;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import dev.hotreload.idea.client.HotReloadProjectService;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.util.List;

/**
 * Watches application* and bootstrap* properties/YAML files under resource roots.
 */
public final class ConfigFileChangeListener implements BulkFileListener {
    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;

    public ConfigFileChangeListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override public void after(List<? extends VFileEvent> events) {
        if (!sessions.isConfigReloadEnabled()) return;
        for (VFileEvent event : events) {
            if (!(event instanceof VFileContentChangeEvent)) continue;
            VirtualFile file = event.getFile();
            if (file == null || !file.isValid() || file.isDirectory() || !file.isInLocalFileSystem()) continue;
            JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
            if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
                continue;
            }
            Module module = fileIndex.getModuleForFile(file);
            if (module == null) continue;
            VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
            if (sourceRoot == null || !sourceRoot.isInLocalFileSystem()) continue;
            String relative = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
            if (!isReloadableConfigPath(relative)) continue;
            CompilerModuleExtension compiler = CompilerModuleExtension.getInstance(module);
            VirtualFile outputRoot = compiler == null ? null
                    : rootType == JavaResourceRootType.TEST_RESOURCE
                    ? compiler.getCompilerOutputPathForTests() : compiler.getCompilerOutputPath();
            if (outputRoot == null || !outputRoot.isInLocalFileSystem()) {
                sessions.recordWarning("CONFIG_RELOAD_SKIPPED", "output_root_missing");
                continue;
            }
            sessions.scheduleConfigReload(sourceRoot.toNioPath(), outputRoot.toNioPath(),
                    file.toNioPath());
        }
    }

    static boolean isReloadableConfigPath(String relativePath) {
        return ConfigUpdateReader.isReloadableConfigPath(relativePath);
    }
}
