package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.AssertionStatus;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.Authority;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed P5 read-only presentation contract for one authorized pending task. */
public final class ApprovalAssistanceReadContracts {

    public static final String PROVIDER_REQUIRED_CODE =
        "AI_ASSISTANCE_P6_PROVIDER_REQUIRED";

    private static final List<UseCase> USE_CASES = List.of(UseCase.values());
    private static final List<Limitation> PROVIDER_REQUIRED_LIMITATIONS = List.of(
        new Limitation(
            "PRODUCTION_PROVIDER_NOT_CONFIGURED",
            "No production Provider is configured before the M6-E P6 gate."
        ),
        new Limitation(
            "NO_ADVISORY_RESULT_AVAILABLE",
            "No advisory content is available and no deterministic mock is used in production."
        ),
        new Limitation(
            "HUMAN_REVIEW_REQUIRED",
            "Any future assistance remains unverified advisory material requiring human review."
        )
    );

    private ApprovalAssistanceReadContracts() {
    }

    public enum Availability {
        PROVIDER_NOT_CONFIGURED
    }

    public record Limitation(String code, String message) {
        public Limitation {
            code = requireText(code, "limitation.code", 96);
            message = requireText(message, "limitation.message", 512);
        }
    }

    public record TaskSnapshot(
        UUID taskId,
        UUID instanceId,
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String compilerVersion,
        String contentHash,
        String taskDefinitionKey,
        Instant instanceUpdatedAt,
        Instant taskUpdatedAt
    ) {
        public TaskSnapshot {
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            definitionKey = requireText(definitionKey, "definitionKey", 160);
            if (definitionVersion < 1) {
                throw new IllegalArgumentException("definitionVersion must be positive");
            }
            formKey = requireText(formKey, "formKey", 160);
            if (formVersion < 1) {
                throw new IllegalArgumentException("formVersion must be positive");
            }
            compilerVersion = requireText(compilerVersion, "compilerVersion", 160);
            contentHash = requireText(contentHash, "contentHash", 256);
            taskDefinitionKey = requireText(
                taskDefinitionKey,
                "taskDefinitionKey",
                160
            );
            instanceUpdatedAt = Objects.requireNonNull(
                instanceUpdatedAt,
                "instanceUpdatedAt must not be null"
            );
            taskUpdatedAt = Objects.requireNonNull(
                taskUpdatedAt,
                "taskUpdatedAt must not be null"
            );
        }

        public static TaskSnapshot from(PendingTaskDetails task) {
            Objects.requireNonNull(task, "task must not be null");
            return new TaskSnapshot(
                task.taskId(),
                task.instanceId(),
                task.definitionKey(),
                task.definitionVersion(),
                task.formKey(),
                task.formVersion(),
                task.compilerVersion(),
                task.contentHash(),
                task.taskDefinitionKey(),
                task.instanceUpdatedAt(),
                task.taskUpdatedAt()
            );
        }
    }

    public record AssistanceView(
        UUID taskId,
        UUID instanceId,
        UseCase requestedUseCase,
        List<UseCase> availableUseCases,
        Availability availability,
        String code,
        Authority authority,
        AssertionStatus assertionStatus,
        boolean needsHumanReview,
        boolean providerInvocationStarted,
        boolean providerSelectable,
        boolean commandAvailable,
        boolean resultAvailable,
        TaskSnapshot taskSnapshot,
        AiAdvisoryResult advisoryResult,
        List<Limitation> limitations
    ) {
        public AssistanceView {
            taskId = Objects.requireNonNull(taskId, "taskId must not be null");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            requestedUseCase = Objects.requireNonNull(
                requestedUseCase,
                "requestedUseCase must not be null"
            );
            availableUseCases = List.copyOf(
                Objects.requireNonNull(
                    availableUseCases,
                    "availableUseCases must not be null"
                )
            );
            availability = Objects.requireNonNull(
                availability,
                "availability must not be null"
            );
            code = requireText(code, "code", 128);
            authority = Objects.requireNonNull(authority, "authority must not be null");
            assertionStatus = Objects.requireNonNull(
                assertionStatus,
                "assertionStatus must not be null"
            );
            taskSnapshot = Objects.requireNonNull(
                taskSnapshot,
                "taskSnapshot must not be null"
            );
            limitations = List.copyOf(
                Objects.requireNonNull(limitations, "limitations must not be null")
            );

            if (!availableUseCases.equals(USE_CASES)
                || !availableUseCases.contains(requestedUseCase)) {
                throw new IllegalArgumentException(
                    "P5 must expose exactly the closed P2 assistance use cases"
                );
            }
            if (!taskId.equals(taskSnapshot.taskId())
                || !instanceId.equals(taskSnapshot.instanceId())) {
                throw new IllegalArgumentException(
                    "task snapshot must match the authorized assistance resource"
                );
            }
            if (availability != Availability.PROVIDER_NOT_CONFIGURED
                || !PROVIDER_REQUIRED_CODE.equals(code)
                || authority != Authority.ADVISORY
                || assertionStatus != AssertionStatus.UNVERIFIED_ADVISORY
                || !needsHumanReview
                || providerInvocationStarted
                || providerSelectable
                || commandAvailable
                || resultAvailable
                || advisoryResult != null
                || !limitations.equals(PROVIDER_REQUIRED_LIMITATIONS)) {
                throw new IllegalArgumentException(
                    "P5 pre-P6 assistance view must remain unavailable and non-authoritative"
                );
            }
        }

        public static AssistanceView providerRequired(
            PendingTaskDetails task,
            UseCase requestedUseCase
        ) {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(
                requestedUseCase,
                "requestedUseCase must not be null"
            );
            return new AssistanceView(
                task.taskId(),
                task.instanceId(),
                requestedUseCase,
                USE_CASES,
                Availability.PROVIDER_NOT_CONFIGURED,
                PROVIDER_REQUIRED_CODE,
                Authority.ADVISORY,
                AssertionStatus.UNVERIFIED_ADVISORY,
                true,
                false,
                false,
                false,
                false,
                TaskSnapshot.from(task),
                null,
                PROVIDER_REQUIRED_LIMITATIONS
            );
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
