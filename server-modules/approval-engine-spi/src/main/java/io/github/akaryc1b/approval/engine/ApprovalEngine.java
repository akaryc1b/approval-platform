package io.github.akaryc1b.approval.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Boundary between approval product semantics and the underlying workflow engine.
 * Business modules must not call Flowable services directly.
 */
public interface ApprovalEngine {

    DeploymentResult deploy(DeployCommand command);

    StartResult start(StartCommand command);

    /** Exact immutable release start. Engine adapters should override this method. */
    default StartResult startExact(ExactStartCommand command) {
        return start(new StartCommand(
            command.tenantId(),
            command.definitionKey(),
            command.businessKey(),
            command.initiatorId(),
            command.variables()
        ));
    }

    List<TaskSnapshot> findActiveTasks(TaskQuery query);

    TaskResult complete(CompleteTaskCommand command);

    default void terminate(TerminateInstanceCommand command) {
        throw new EngineOperationException(
            "INSTANCE_TERMINATION_UNSUPPORTED",
            "the approval engine does not support process instance termination"
        );
    }

    default TaskSnapshot transfer(TransferTaskCommand command) {
        throw new EngineOperationException(
            "TASK_TRANSFER_UNSUPPORTED",
            "the approval engine does not support task transfer"
        );
    }

    default void retrieve(RetrieveTaskCommand command) {
        throw new EngineOperationException(
            "TASK_RETRIEVE_UNSUPPORTED",
            "the approval engine does not support task retrieval"
        );
    }

    record DeployCommand(
        String tenantId,
        String definitionKey,
        int definitionVersion,
        String resourceName,
        String bpmnXml,
        String contentHash
    ) {
        public DeployCommand {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (definitionVersion < 1) {
                throw new IllegalArgumentException("definitionVersion must be positive");
            }
            resourceName = requireText(resourceName, "resourceName");
            bpmnXml = requireText(bpmnXml, "bpmnXml");
            contentHash = requireText(contentHash, "contentHash");
        }
    }

    record DeploymentResult(
        String deploymentId,
        String engineDefinitionId,
        String definitionKey,
        int engineVersion
    ) {
        public DeploymentResult {
            deploymentId = requireText(deploymentId, "deploymentId");
            engineDefinitionId = requireText(engineDefinitionId, "engineDefinitionId");
            definitionKey = requireText(definitionKey, "definitionKey");
            if (engineVersion < 1) {
                throw new IllegalArgumentException("engineVersion must be positive");
            }
        }
    }

    record StartCommand(
        String tenantId,
        String definitionKey,
        String businessKey,
        String initiatorId,
        Map<String, Object> variables
    ) {
        public StartCommand {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            businessKey = requireText(businessKey, "businessKey");
            initiatorId = requireText(initiatorId, "initiatorId");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    record ExactStartCommand(
        String tenantId,
        String definitionKey,
        String engineDeploymentId,
        String engineDefinitionId,
        String businessKey,
        String initiatorId,
        int releaseVersion,
        String releasePackageHash,
        int definitionVersion,
        int formPackageVersion,
        String compilerVersion,
        Map<String, Object> variables
    ) {
        public ExactStartCommand {
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            engineDeploymentId = requireText(engineDeploymentId, "engineDeploymentId");
            engineDefinitionId = requireText(engineDefinitionId, "engineDefinitionId");
            businessKey = requireText(businessKey, "businessKey");
            initiatorId = requireText(initiatorId, "initiatorId");
            if (releaseVersion < 1 || definitionVersion < 1 || formPackageVersion < 1) {
                throw new IllegalArgumentException(
                    "release, definition and Form Package versions must be positive"
                );
            }
            releasePackageHash = requireHash(
                releasePackageHash,
                "releasePackageHash"
            );
            compilerVersion = requireText(compilerVersion, "compilerVersion");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    record TaskQuery(
        String tenantId,
        String processInstanceId,
        String assigneeId
    ) {
        public TaskQuery {
            tenantId = requireText(tenantId, "tenantId");
            processInstanceId = requireText(processInstanceId, "processInstanceId");
            assigneeId = normalizeOptional(assigneeId);
        }
    }

    record TaskSnapshot(
        String taskId,
        String processInstanceId,
        String taskDefinitionKey,
        String name,
        String assigneeId,
        Instant createdAt
    ) {
        public TaskSnapshot {
            taskId = requireText(taskId, "taskId");
            processInstanceId = requireText(processInstanceId, "processInstanceId");
            taskDefinitionKey = requireText(taskDefinitionKey, "taskDefinitionKey");
            name = requireText(name, "name");
            assigneeId = normalizeOptional(assigneeId);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    record CompleteTaskCommand(
        String tenantId,
        String taskId,
        String operatorId,
        Map<String, Object> variables
    ) {
        public CompleteTaskCommand {
            tenantId = requireText(tenantId, "tenantId");
            taskId = requireText(taskId, "taskId");
            operatorId = requireText(operatorId, "operatorId");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    record TerminateInstanceCommand(
        String tenantId,
        String processInstanceId,
        String reason
    ) {
        public TerminateInstanceCommand {
            tenantId = requireText(tenantId, "tenantId");
            processInstanceId = requireText(processInstanceId, "processInstanceId");
            reason = requireText(reason, "reason");
        }
    }

    record TransferTaskCommand(
        String tenantId,
        String taskId,
        String currentAssigneeId,
        String targetAssigneeId
    ) {
        public TransferTaskCommand {
            tenantId = requireText(tenantId, "tenantId");
            taskId = requireText(taskId, "taskId");
            currentAssigneeId = requireText(currentAssigneeId, "currentAssigneeId");
            targetAssigneeId = requireText(targetAssigneeId, "targetAssigneeId");
            if (currentAssigneeId.equals(targetAssigneeId)) {
                throw new IllegalArgumentException("targetAssigneeId must differ from currentAssigneeId");
            }
        }
    }

    record RetrieveTaskCommand(
        String tenantId,
        String processInstanceId,
        String currentTaskId,
        String targetTaskDefinitionKey
    ) {
        public RetrieveTaskCommand {
            tenantId = requireText(tenantId, "tenantId");
            processInstanceId = requireText(processInstanceId, "processInstanceId");
            currentTaskId = requireText(currentTaskId, "currentTaskId");
            targetTaskDefinitionKey = requireText(
                targetTaskDefinitionKey,
                "targetTaskDefinitionKey"
            );
        }
    }

    record StartResult(String engineInstanceId) {
        public StartResult {
            engineInstanceId = requireText(engineInstanceId, "engineInstanceId");
        }
    }

    record TaskResult(String taskId, String status) {
        public TaskResult {
            taskId = requireText(taskId, "taskId");
            status = requireText(status, "status");
        }
    }

    final class EngineOperationException extends RuntimeException {

        private final String code;

        public EngineOperationException(String code, String message) {
            super(requireText(message, "message"));
            this.code = requireText(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
