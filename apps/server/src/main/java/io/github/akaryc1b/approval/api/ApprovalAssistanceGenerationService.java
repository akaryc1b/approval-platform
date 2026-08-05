package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.AiAdvisoryService;
import io.github.akaryc1b.approval.ai.core.AiAuthorizedResource;
import io.github.akaryc1b.approval.ai.core.AiInvocationBudget;
import io.github.akaryc1b.approval.ai.core.AiProviderExecutionPolicy;
import io.github.akaryc1b.approval.ai.core.AiProviderRoute;
import io.github.akaryc1b.approval.ai.core.AiServerRequestContext;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceProductionDurableEvidenceFactory;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceProductionOrchestrator;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Server-owned P6-E application service for explicit advisory generation only. */
public final class ApprovalAssistanceGenerationService
    implements ApprovalAssistanceRuntimeAvailability {

    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration EVIDENCE_RETENTION = Duration.ofDays(365);
    private static final String ROUTE_ID = "openai-responses-production-v1";
    private static final AiVersionReferences.PolicyVersion POLICY_VERSION =
        new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            sha256("approval-assistance-production-policy/p6-e-v1/advisory-only")
        );

    private final ApprovalTaskQuery taskQuery;
    private final ApprovalAssistanceDurableEvidenceStore evidenceStore;
    private final Optional<OpenAiResponsesProductionRuntimeFactory> runtimeFactory;
    private final AiAdvisoryService advisoryService;
    private final Clock clock;
    private final Supplier<UUID> evidenceIdSource;

    public ApprovalAssistanceGenerationService(
        ApprovalTaskQuery taskQuery,
        ApprovalAssistanceDurableEvidenceStore evidenceStore,
        Optional<OpenAiResponsesProductionRuntimeFactory> runtimeFactory,
        AiAdvisoryService advisoryService,
        Clock clock,
        Supplier<UUID> evidenceIdSource
    ) {
        this.taskQuery = Objects.requireNonNull(taskQuery, "taskQuery must not be null");
        this.evidenceStore = Objects.requireNonNull(
            evidenceStore,
            "evidenceStore must not be null"
        );
        this.runtimeFactory = runtimeFactory == null ? Optional.empty() : runtimeFactory;
        this.advisoryService = Objects.requireNonNull(
            advisoryService,
            "advisoryService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.evidenceIdSource = Objects.requireNonNull(
            evidenceIdSource,
            "evidenceIdSource must not be null"
        );
    }

    @Override
    public boolean providerConfigured() {
        return runtimeFactory.isPresent();
    }

    public GenerationOutcome generate(
        String trustedTenantId,
        String trustedOperatorId,
        String requestId,
        String traceId,
        UUID taskId,
        ApprovalAssistanceAdvisoryContract.UseCase useCase
    ) {
        if (runtimeFactory.isEmpty()) {
            return GenerationOutcome.failure(GenerationStatus.DISABLED);
        }
        PendingTaskIdentity identity = new PendingTaskIdentity(
            trustedTenantId,
            trustedOperatorId,
            taskId
        );
        Optional<PendingTaskDetails> initial = taskQuery.findPendingTask(identity);
        if (initial.isEmpty()) {
            return GenerationOutcome.failure(GenerationStatus.NOT_FOUND);
        }
        PendingTaskDetails task = initial.orElseThrow();
        if (!hasTrustedSchemaProvenance(task)) {
            return GenerationOutcome.failure(GenerationStatus.POLICY_BLOCKED);
        }
        ApprovalAssistanceContextProjection projection = projection(
            trustedTenantId,
            trustedOperatorId,
            requestId,
            traceId,
            task,
            useCase
        );
        Optional<PendingTaskDetails> revalidated = taskQuery.findPendingTask(identity);
        if (revalidated.isEmpty() || !task.equals(revalidated.orElseThrow())) {
            return GenerationOutcome.failure(GenerationStatus.STALE_TASK);
        }

        AiVersionReferences versions = versions(useCase.capability());
        Instant requestedAt = latest(
            postgresTimestamp(clock.instant()),
            postgresTimestamp(task.taskUpdatedAt())
        );
        ApprovalAssistanceAdvisoryContract.Request request =
            new ApprovalAssistanceAdvisoryContract.Request(
                projection,
                useCase,
                versions,
                ApprovalAssistanceAdvisoryContract.ResultLimits.conservativeDefaults(),
                ApprovalAssistanceAdvisoryContract.ProjectionProvenance.from(projection),
                requestedAt
            );

        OpenAiResponsesProductionRuntimeFactory.Binding binding;
        try {
            binding = runtimeFactory.orElseThrow().bind(trustedTenantId);
        } catch (RuntimeException unavailable) {
            return GenerationOutcome.failure(GenerationStatus.PROVIDER_UNAVAILABLE);
        }

        ApprovalAssistanceProductionOrchestrator orchestrator;
        try {
            orchestrator = orchestrator(binding, versions, useCase.capability());
        } catch (RuntimeException blocked) {
            return GenerationOutcome.failure(GenerationStatus.POLICY_BLOCKED);
        }

        ApprovalAssistanceProductionOrchestrator.Outcome outcome;
        try {
            outcome = orchestrator.execute(request);
        } catch (ApprovalAssistanceProductionOrchestrator.ProductionAdmissionException blocked) {
            return GenerationOutcome.failure(GenerationStatus.POLICY_BLOCKED);
        } catch (RuntimeException unknown) {
            return GenerationOutcome.failure(GenerationStatus.UNKNOWN);
        }

        Optional<PendingTaskDetails> postInvocation = taskQuery.findPendingTask(identity);
        if (postInvocation.isEmpty() || !task.equals(postInvocation.orElseThrow())) {
            return GenerationOutcome.failure(GenerationStatus.STALE_TASK);
        }

        Instant recordedAt = latest(postgresTimestamp(clock.instant()), requestedAt);
        ApprovalAssistanceDurableEvidence evidence;
        try {
            evidence = ApprovalAssistanceProductionDurableEvidenceFactory.create(
                evidenceIdSource.get(),
                outcome,
                recordedAt,
                recordedAt.plus(EVIDENCE_RETENTION)
            );
        } catch (RuntimeException invalidEvidence) {
            return GenerationOutcome.failure(GenerationStatus.INVALID_OUTPUT);
        }

        ApprovalAssistanceDurableEvidenceStore.StoreResult stored;
        try {
            stored = evidenceStore.store(evidence);
        } catch (RuntimeException unavailable) {
            return GenerationOutcome.failure(GenerationStatus.EVIDENCE_UNAVAILABLE);
        }
        if (stored.disposition()
            == ApprovalAssistanceDurableEvidenceStore.StoreDisposition.CONFLICT) {
            return GenerationOutcome.failure(GenerationStatus.EVIDENCE_CONFLICT);
        }

        GenerationStatus status = status(outcome.coordinated().outcome().classification());
        AiAdvisoryResult advisory = outcome.acceptedResult() == null
            ? null
            : outcome.acceptedResult().advisory();
        return new GenerationOutcome(
            status,
            advisory,
            stored.evidenceId(),
            stored.disposition(),
            outcome.coordinated().outcome().classification()
        );
    }

    private ApprovalAssistanceProductionOrchestrator orchestrator(
        OpenAiResponsesProductionRuntimeFactory.Binding binding,
        AiVersionReferences versions,
        AiCapability capability
    ) {
        AiProviderRoute route = new AiProviderRoute(
            ROUTE_ID,
            0,
            true,
            Set.of(
                AiCapability.APPROVAL_SUMMARY,
                AiCapability.MATERIAL_COMPLETENESS,
                AiCapability.RISK_SIGNALS
            ),
            versions,
            new AiInvocationBudget(PROVIDER_TIMEOUT, 16_000, 8, 0.50d)
        );
        AiProviderExecutionPolicy policy = new AiProviderExecutionPolicy(
            true,
            Set.of(OpenAiResponsesAdvisoryProvider.providerVersion().providerId()),
            Set.of(OpenAiResponsesAdvisoryProvider.modelVersion().authorizationKey()),
            Set.of(
                AiCapability.APPROVAL_SUMMARY,
                AiCapability.MATERIAL_COMPLETENESS,
                AiCapability.RISK_SIGNALS
            ),
            PROVIDER_TIMEOUT,
            0.50d
        );
        ApprovalAssistanceProductionOrchestrator.ProductionControl control =
            new ApprovalAssistanceProductionOrchestrator.ProductionControl(
                true,
                OpenAiResponsesAdvisoryProvider.providerVersion().providerId(),
                OpenAiResponsesAdvisoryProvider.providerVersion().version(),
                OpenAiResponsesAdvisoryProvider.modelVersion().modelId(),
                OpenAiResponsesAdvisoryProvider.modelVersion().version(),
                ROUTE_ID,
                1,
                PROVIDER_TIMEOUT,
                true,
                binding.killSwitchGeneration(),
                binding.killSwitchEvidenceHash(),
                true,
                true,
                true,
                true,
                binding.costPolicyEvidenceHash(),
                binding.secretVersionEvidenceHash(),
                OpenAiResponsesAdvisoryProvider.MAXIMUM_OUTPUT_TOKENS
            );
        return new ApprovalAssistanceProductionOrchestrator(
            advisoryService,
            binding.provider(),
            route,
            policy,
            control
        );
    }

    private static ApprovalAssistanceContextProjection projection(
        String tenantId,
        String operatorId,
        String requestId,
        String traceId,
        PendingTaskDetails task,
        ApprovalAssistanceAdvisoryContract.UseCase useCase
    ) {
        List<AiProviderRequest.InputField> metadataFields = new ArrayList<>();
        add(metadataFields, "definitionKey", "TEXT", task.definitionKey());
        add(metadataFields, "taskName", "TEXT", task.taskName());
        add(metadataFields, "businessKey", "TEXT", task.businessKey());

        List<AiProviderRequest.InputField> formFields = new ArrayList<>();
        add(formFields, "amount", "NUMBER", task.amount());
        add(formFields, "supplier", "TEXT", task.supplier());
        add(
            formFields,
            "purchaseOrderReference",
            "TEXT",
            task.purchaseOrderReference()
        );

        List<AiProviderRequest.InputField> fields = new ArrayList<>(
            metadataFields.size() + formFields.size()
        );
        fields.addAll(metadataFields);
        fields.addAll(formFields);
        Set<String> fieldKeys = fields.stream()
            .map(AiProviderRequest.InputField::key)
            .collect(Collectors.toUnmodifiableSet());
        if (fieldKeys.isEmpty()) {
            throw new IllegalArgumentException("Provider-safe projection must not be empty");
        }
        int omittedFieldCount = task.formSchemaFieldCount() - formFields.size();
        if (omittedFieldCount < 0) {
            throw new IllegalArgumentException(
                "Provider-safe Form fields cannot exceed the trusted Form Schema field count"
            );
        }
        String authorizationReference = sha256(String.join(
            "/",
            "approval-task-participant-v1",
            tenantId,
            operatorId,
            task.taskId().toString()
        ));
        AiVersionReferences.PolicyVersion dataPolicy = POLICY_VERSION;
        Instant taskObservedAt = postgresTimestamp(task.taskUpdatedAt());
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(tenantId, operatorId, requestId, traceId),
            new AiAuthorizedResource(
                tenantId,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                task.taskId().toString(),
                authorizationReference,
                fieldKeys
            ),
            new ApprovalAssistanceContextProjection.ProcessSnapshot(
                task.definitionKey(),
                task.definitionVersion(),
                task.compilerVersion(),
                task.contentHash(),
                task.formKey(),
                task.formVersion(),
                task.releaseVersion(),
                task.releasePackageHash()
            ),
            new ApprovalAssistanceContextProjection.ResourceStateSnapshot(
                tenantId,
                task.instanceId().toString(),
                task.taskId().toString(),
                task.taskDefinitionKey(),
                ApprovalAssistanceContextProjection.ResourceState.TASK_PENDING,
                Math.max(0L, taskObservedAt.toEpochMilli()),
                taskObservedAt
            ),
            new ApprovalAssistanceContextProjection.FormSnapshot(
                task.formKey(),
                task.formVersion(),
                task.formSchemaVersion(),
                task.formContentHash(),
                task.formSchemaFieldCount(),
                task.uiSchemaVersion(),
                task.uiSchemaHash(),
                task.taskDefinitionKey(),
                0
            ),
            fields,
            new ApprovalAssistanceContextProjection.ProviderRequirements(
                Set.of(useCase.capability()),
                8,
                4_000,
                16_000,
                32,
                4,
                true,
                true
            ),
            dataPolicy,
            new ApprovalAssistanceContextProjection.ProjectionEvidence(
                formFields.size(),
                fields.size(),
                formFields.size(),
                0,
                omittedFieldCount,
                0,
                false
            )
        );
    }

    private static boolean hasTrustedSchemaProvenance(PendingTaskDetails task) {
        return task.formPackageVersion() != null
            && task.formPackageHash() != null
            && task.formContentHash() != null
            && task.uiSchemaVersion() != null
            && task.uiSchemaHash() != null
            && task.formSchemaVersion() != null
            && task.formSchemaFieldCount() != null
            && task.formSchemaFieldCount() >= 1
            && task.formSchemaFieldCount() <= 500;
    }

    private static AiVersionReferences versions(AiCapability capability) {
        return new AiVersionReferences(
            OpenAiResponsesAdvisoryProvider.providerVersion(),
            OpenAiResponsesAdvisoryProvider.modelVersion(),
            OpenAiResponsesAdvisoryProvider.promptVersion(capability),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            POLICY_VERSION,
            OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
        );
    }

    private static void add(
        List<AiProviderRequest.InputField> fields,
        String key,
        String type,
        Object value
    ) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        fields.add(new AiProviderRequest.InputField(
            key,
            type,
            value,
            AiProviderRequest.MaskingDisposition.INCLUDED
        ));
    }

    private static GenerationStatus status(AiOutcomeClassification classification) {
        return switch (classification) {
            case SUCCESS -> GenerationStatus.SUCCESS;
            case LOW_CONFIDENCE -> GenerationStatus.LOW_CONFIDENCE;
            case DISABLED -> GenerationStatus.DISABLED;
            case POLICY_BLOCKED, REJECTED, UNSUPPORTED -> GenerationStatus.POLICY_BLOCKED;
            case PROVIDER_UNAVAILABLE -> GenerationStatus.PROVIDER_UNAVAILABLE;
            case TIMEOUT -> GenerationStatus.TIMEOUT;
            case INVALID_OUTPUT -> GenerationStatus.INVALID_OUTPUT;
            case UNKNOWN -> GenerationStatus.UNKNOWN;
        };
    }

    private static Instant latest(Instant left, Instant right) {
        return left.isBefore(right) ? right : left;
    }

    private static Instant postgresTimestamp(Instant value) {
        return Objects.requireNonNull(value, "timestamp must not be null")
            .truncatedTo(ChronoUnit.MICROS);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public enum GenerationStatus {
        SUCCESS,
        LOW_CONFIDENCE,
        DISABLED,
        NOT_FOUND,
        STALE_TASK,
        POLICY_BLOCKED,
        PROVIDER_UNAVAILABLE,
        TIMEOUT,
        INVALID_OUTPUT,
        UNKNOWN,
        EVIDENCE_CONFLICT,
        EVIDENCE_UNAVAILABLE
    }

    public record GenerationOutcome(
        GenerationStatus status,
        AiAdvisoryResult advisory,
        UUID evidenceId,
        ApprovalAssistanceDurableEvidenceStore.StoreDisposition evidenceDisposition,
        AiOutcomeClassification providerClassification
    ) {
        public GenerationOutcome {
            status = Objects.requireNonNull(status, "status must not be null");
            boolean success = status == GenerationStatus.SUCCESS
                || status == GenerationStatus.LOW_CONFIDENCE;
            if (success != (advisory != null)) {
                throw new IllegalArgumentException(
                    "advisory presence must match a successful advisory status"
                );
            }
            if (success && (evidenceId == null || evidenceDisposition == null)) {
                throw new IllegalArgumentException(
                    "successful advisory requires durable evidence"
                );
            }
        }

        public static GenerationOutcome failure(GenerationStatus status) {
            return new GenerationOutcome(status, null, null, null, null);
        }
    }
}
