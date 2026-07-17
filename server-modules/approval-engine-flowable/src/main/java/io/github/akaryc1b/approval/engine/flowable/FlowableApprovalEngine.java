package io.github.akaryc1b.approval.engine.flowable;

import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Flowable implementation kept entirely behind the product-owned engine SPI.
 */
public final class FlowableApprovalEngine implements ApprovalEngine {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public FlowableApprovalEngine(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService
    ) {
        this.repositoryService = Objects.requireNonNull(
            repositoryService,
            "repositoryService must not be null"
        );
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService must not be null");
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
    }

    @Override
    public DeploymentResult deploy(DeployCommand command) {
        String deploymentName = command.definitionKey()
            + ":dsl-v"
            + command.definitionVersion()
            + ":"
            + command.contentHash();
        Deployment deployment = repositoryService.createDeployment()
            .tenantId(command.tenantId())
            .name(deploymentName)
            .addString(command.resourceName(), command.bpmnXml())
            .deploy();
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .singleResult();
        if (processDefinition == null) {
            throw new EngineOperationException(
                "DEFINITION_DEPLOYMENT_FAILED",
                "deployment did not create a process definition"
            );
        }
        if (!command.definitionKey().equals(processDefinition.getKey())) {
            throw new EngineOperationException(
                "DEFINITION_KEY_MISMATCH",
                "compiled process key does not match the deployment command"
            );
        }
        return new DeploymentResult(
            deployment.getId(),
            processDefinition.getId(),
            processDefinition.getKey(),
            processDefinition.getVersion()
        );
    }

    @Override
    public StartResult start(StartCommand command) {
        Map<String, Object> variables = new LinkedHashMap<>(command.variables());
        variables.putIfAbsent("approvalInitiatorId", command.initiatorId());
        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
            .processDefinitionKey(command.definitionKey())
            .businessKey(command.businessKey())
            .tenantId(command.tenantId())
            .variables(Map.copyOf(variables))
            .start();
        return new StartResult(instance.getProcessInstanceId());
    }

    @Override
    public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
        org.flowable.task.api.TaskQuery flowableQuery = taskService.createTaskQuery()
            .processInstanceId(query.processInstanceId())
            .taskTenantId(query.tenantId())
            .active()
            .orderByTaskCreateTime()
            .asc();
        if (query.assigneeId() != null) {
            flowableQuery.taskAssignee(query.assigneeId());
        }
        return flowableQuery.list().stream().map(FlowableApprovalEngine::snapshot).toList();
    }

    @Override
    public TaskResult complete(CompleteTaskCommand command) {
        Task task = taskService.createTaskQuery().taskId(command.taskId()).singleResult();
        if (task == null || !command.tenantId().equals(task.getTenantId())) {
            throw new EngineOperationException("TASK_NOT_FOUND", "task was not found for the tenant");
        }
        if (!command.operatorId().equals(task.getAssignee())) {
            throw new EngineOperationException(
                "TASK_NOT_ASSIGNED_TO_OPERATOR",
                "task is not assigned to the operator"
            );
        }
        taskService.complete(command.taskId(), command.variables());
        return new TaskResult(command.taskId(), "COMPLETED");
    }

    private static TaskSnapshot snapshot(Task task) {
        return new TaskSnapshot(
            task.getId(),
            task.getProcessInstanceId(),
            task.getTaskDefinitionKey(),
            task.getName(),
            task.getAssignee(),
            task.getCreateTime().toInstant()
        );
    }
}
