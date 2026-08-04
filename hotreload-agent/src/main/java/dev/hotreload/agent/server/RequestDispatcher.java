package dev.hotreload.agent.server;

import dev.hotreload.agent.classes.ClassBatchReloader;
import dev.hotreload.agent.classes.EngineCapabilityProbe;
import dev.hotreload.agent.configreload.ConfigResourceReloader;
import dev.hotreload.agent.configreload.StaticResourceReloader;
import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.mybatis.MapperConfigurationReloader;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadRequest;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;
import dev.hotreload.protocol.util.ResourceTypeDetector;

import java.lang.instrument.Instrumentation;
import java.util.Collections;

public final class RequestDispatcher implements AgentServer.MutationHandler {
    private final MapperConfigurationReloader mapperReloader;
    private final ClassBatchReloader classReloader;
    private final ConfigResourceReloader configReloader;
    private final StaticResourceReloader staticReloader;

    public RequestDispatcher(Instrumentation instrumentation, AgentSessionLogger logger) {
        this.mapperReloader = new MapperConfigurationReloader(logger);
        // Capability probed once per session (cached per instrumentation) and injected explicitly.
        this.classReloader = new ClassBatchReloader(instrumentation, logger,
                EngineCapabilityProbe.capability(instrumentation)
                        == EngineCapabilityProbe.Capability.ENHANCED);
        this.configReloader = new ConfigResourceReloader(logger);
        this.staticReloader = new StaticResourceReloader(logger);
    }

    @Override public ReloadResponse handle(ReloadRequest request) {
        if (request instanceof MapperReloadRequest) {
            return mapperReloader.reload((MapperReloadRequest) request);
        }
        if (request instanceof ClassReloadRequest) {
            return classReloader.reload((ClassReloadRequest) request);
        }
        if (request instanceof ResourceReloadRequest) {
            ResourceReloadRequest resReq = (ResourceReloadRequest) request;
            if (ResourceTypeDetector.isStaticResource(resReq.getResourcePath())) {
                return staticReloader.reload(resReq);
            }
            return configReloader.reload(resReq);
        }
        return new ReloadResponse(request.getRequestId(), OperationStatus.FAILED, ReloadErrorCode.INTERNAL_ERROR,
                "", Collections.emptyList());
    }
}
