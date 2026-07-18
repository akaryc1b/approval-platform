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
        variables.putIfAbsent("initiatorAssignee", command.initiatorId());
        variables.putIfAbsent("approvalDecision", "PENDING");
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
        Task task = requireTask(command.tenantId(), command.taskId());
        if (!command.operatorId().equals(task.getAssignee())) {
            throw new EngineOperationException(
                "TASK_NOT_ASSIGNED_TO_OPERATOR",
                "task is not assigned to the operator"
            );
        }
        taskService.complete(command.taskId(), command.variables());
        return new TaskResult(command.taskId(), "COMPLETED");
    }

    @Override
    public void terminate(TerminateInstanceCommand command) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
            .processInstanceId(command.processInstanceId())
            .singleResult();
        if (instance == null || !command.tenantId().equals(instance.getTenantId())) {
            throw new EngineOperationException(
                "PROCESS_INSTANCE_NOT_FOUND",
                "process instance was not found for the tenant"
            );
        }
        runtimeService.deleteProcessInstance(command.processInstanceId(), command.reason());
    }

    @Override
    public TaskSnapshot transfer(TransferTaskCommand command) {
        Task task = requireTask(command.tenantId(), command.taskId());
        if (!command.currentAssigneeId().equals(task.getAssignee())) {
            throw new EngineOperationException(
                "TASK_NOT_ASSIGNED_TO_OPERATOR",
                "task is not assigned to the current operator"
            );
        }
        taskService.setAssignee(command.taskId(), command.targetAssigneeId());
        return snapshot(requireTask(command.tenantId(), command.taskId()));
    }

    @Override
    public void retrieve(RetrieveTaskCommand command) {
        Task currentTask = requireTask(command.tenantId(), command.currentTaskId());
        if (!command.processInstanceId().equals(currentTask.getProcessInstanceId())) {
            throw new EngineOperationException(
                "TASK_PROCESS_INSTANCE_MISMATCH",
                "task does not belong to the requested process instance"
            );
        }
        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(command.processInstanceId())
            .moveExecutionToActivityId(
                currentTask.getExecutionId(),
                command.targetTaskDefinitionKey()
            )
            .changeState();
    }

    private Task requireTask(String tenantId, String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null || !tenantId.equals(task.getTenantId())) {
            throw new EngineOperationException("TASK_NOT_FOUND", "task was not found for the tenant");
        }
        return task;
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
