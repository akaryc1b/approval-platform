package io.github.akaryc1b.approval.engine;

import java.util.Map;
import java.util.Objects;

/**
 * Boundary between approval product semantics and the underlying workflow engine.
 * Business modules must not call Flowable services directly.
 */
public interface ApprovalEngine {

    StartResult start(StartCommand command);

    TaskResult complete(CompleteTaskCommand command);

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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
