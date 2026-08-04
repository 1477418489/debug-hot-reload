package dev.hotreload.idea.run;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import dev.hotreload.idea.client.HotReloadProjectService;

public final class DebugExecutionListener implements ExecutionListener {
    private final HotReloadProjectService sessions;

    public DebugExecutionListener(HotReloadProjectService sessions) {
        this.sessions = sessions;
    }

    @Override public void processStartScheduled(String executorId, ExecutionEnvironment environment) {
        if ("Debug".equals(executorId)) sessions.processStartScheduled(environment.getExecutionId(),
                environment.getRunProfile());
    }

    @Override public void processStarting(String executorId, ExecutionEnvironment environment,
                                          ProcessHandler handler) {
        if ("Debug".equals(executorId)) sessions.bindProcess(environment.getExecutionId(),
                environment.getRunProfile(), handler);
    }

    @Override public void processStarted(String executorId, ExecutionEnvironment environment,
                                         ProcessHandler handler) {
        if ("Debug".equals(executorId)) sessions.bindProcess(environment.getExecutionId(),
                environment.getRunProfile(), handler);
    }

    @Override public void processNotStarted(String executorId, ExecutionEnvironment environment) {
        if ("Debug".equals(executorId)) sessions.processNotStarted(environment.getExecutionId(),
                environment.getRunProfile());
    }

    @Override public void processTerminated(String executorId, ExecutionEnvironment environment,
                                            ProcessHandler handler, int exitCode) {
        if ("Debug".equals(executorId)) sessions.processTerminated(handler);
    }
}
