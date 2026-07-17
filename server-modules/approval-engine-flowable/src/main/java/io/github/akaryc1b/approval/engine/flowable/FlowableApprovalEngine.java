package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;

import java.util.Objects;

/**
 * Minimal Flowable adapter. Product-level rejection, countersign, migration and recovery
 * semantics will be added behind the SPI instead of leaking Flowable APIs to callers.
 */
public final class FlowableApprovalEngine implements ApprovalEngine {

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public FlowableApprovalEngine(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
        this.taskService = Objects.requireNonNull(taskService, "taskService");
    }

    @Override
    public StartResult start(StartCommand command) {
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
            .processDefinitionKey(command.definitionKey())
            .businessKey(command.businessKey())
            .tenantId(command.tenantId())
            .variables(command.variables())
            .start();
        return new StartResult(instance.getProcessInstanceId());
    }

    @Override
    public TaskResult complete(CompleteTaskCommand command) {
        taskService.complete(command.taskId(), command.variables());
        return new TaskResult(command.taskId(), "COMPLETED");
    }
}
