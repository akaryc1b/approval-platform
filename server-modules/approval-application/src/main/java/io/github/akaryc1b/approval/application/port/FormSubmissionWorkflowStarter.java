package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Starts the workflow bound to a published Form Schema. */
public interface FormSubmissionWorkflowStarter {

    WorkflowStartResult start(
        RequestContext context,
        String formKey,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    );

    default WorkflowStartResult start(
        RequestContext context,
        String formKey,
        int formVersion,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        return start(context, formKey, businessKey, values, startParameters);
    }

    record WorkflowStartResult(UUID instanceId, String status, Instant startedAt) {
        public WorkflowStartResult {
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }
}
