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
import dev.hotreload.protocol.util.ResourceTypeDetector;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class MapperXmlChangeListener implements BulkFileListener {
    private final ProjectFileIndex fileIndex;
    private final HotReloadProjectService sessions;

    public MapperXmlChangeListener(Project project, HotReloadProjectService sessions) {
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.sessions = sessions;
    }

    @Override public void after(List<? extends VFileEvent> events) {
        if (!sessions.isMapperReloadEnabled()) return;
        for (VFileEvent event : events) {
            if (!(event instanceof VFileContentChangeEvent)) continue;
            VirtualFile file = event.getFile();
            if (file == null || !file.isValid() || file.isDirectory()
                    || !"xml".equalsIgnoreCase(file.getExtension()) || !file.isInLocalFileSystem()) {
                continue;
            }
            JpsModuleSourceRootType<?> rootType = fileIndex.getContainingSourceRootType(file);
            if (rootType != JavaResourceRootType.RESOURCE && rootType != JavaResourceRootType.TEST_RESOURCE) {
                continue;
            }
            VirtualFile sourceRoot = fileIndex.getSourceRootForFile(file);
            if (sourceRoot == null || !sourceRoot.isInLocalFileSystem()) {
                sessions.recordWarning("XML_SKIPPED", "source_root_missing");
                continue;
            }
            String resourcePath = VfsUtilCore.getRelativePath(file, sourceRoot, '/');
            try (InputStream content = file.getInputStream()) {
                if (!ResourceTypeDetector.isMapperXml(resourcePath, content)) continue;
            } catch (IOException failure) {
                sessions.recordWarning("XML_SKIPPED", "mapper_detection_failed");
                continue;
            } catch (RuntimeException failure) {
                // A third-party JAXP provider must not escape the VFS callback and abort
                // processing of the remaining file events.
                sessions.recordWarning("XML_SKIPPED", "mapper_detection_failed");
                continue;
            }
            Module module = fileIndex.getModuleForFile(file);
            CompilerModuleExtension compiler = module == null ? null : CompilerModuleExtension.getInstance(module);
            VirtualFile outputRoot = compiler == null ? null
                    : rootType == JavaResourceRootType.TEST_RESOURCE
                    ? compiler.getCompilerOutputPathForTests() : compiler.getCompilerOutputPath();
            if (outputRoot == null || !outputRoot.isInLocalFileSystem()) {
                sessions.recordWarning("XML_SKIPPED", "output_root_missing");
                continue;
            }
            sessions.scheduleMapperReload(sourceRoot.toNioPath(), outputRoot.toNioPath(), file.toNioPath());
        }
    }
}
