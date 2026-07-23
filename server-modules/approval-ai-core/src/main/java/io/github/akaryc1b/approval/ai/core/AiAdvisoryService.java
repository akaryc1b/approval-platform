package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;
import io.github.akaryc1b.approval.ai.spi.AiCancellation;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Executes advisory providers behind policy, timeout, validation, audit and metrics boundaries. */
public final class AiAdvisoryService implements AutoCloseable {

    private static final List<String> FORBIDDEN_RESULT_MARKERS = List.of(
        "approve this",
        "must approve",
        "reject this",
        "must reject",
        "transfer this",
        "withdraw this",
        "terminate this",
        "migrate this",
        "this is a verified fact",
        "final legal conclusion",
        "final financial conclusion",
        "同意该申请",
        "必须同意",
        "拒绝该申请",
        "必须拒绝",
        "转办该任务",
        "撤回该申请",
        "终止该流程",
        "迁移该实例",
        "退回该申请",
        "已验证事实",
        "最终法律结论",
        "最终财务结论"
    );

    private final ExecutorService executor;
    private final AiAdvisoryAuditSink auditSink;
    private final AiAdvisoryMetrics metrics;
    private final boolean closeExecutor;

    public AiAdvisoryService(
        ExecutorService executor,
        AiAdvisoryAuditSink auditSink,
        AiAdvisoryMetrics metrics
    ) {
        this(executor, auditSink, metrics, false);
    }

    public AiAdvisoryService(
        ExecutorService executor,
        AiAdvisoryAuditSink auditSink,
        AiAdvisoryMetrics metrics,
        boolean closeExecutor
    ) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.closeExecutor = closeExecutor;
    }

    public AiProviderOutcome advise(
        AiAdvisoryProvider provider,
        AiProviderRequest request,
        AiProviderExecutionPolicy policy
    ) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        AiProviderDescriptor descriptor = Objects.requireNonNull(
            provider.descriptor(),
            "provider descriptor must not be null"
        );

        AiProviderOutcome preflight = preflight(descriptor, request, policy);
        if (preflight != null) {
            record(descriptor, request, preflight, policyResult(preflight.classification()));
            return preflight;
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AiCancellation cancellation = cancelled::get;
        Future<AiProviderOutcome> future = executor.submit(
            () -> provider.advise(request, cancellation)
        );
        AiProviderOutcome outcome;
        try {
            Duration timeout = request.timeout();
            outcome = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            outcome = validateOutcome(request, outcome, policy.minimumConfidence());
        } catch (TimeoutException exception) {
            cancelled.set(true);
            future.cancel(true);
            outcome = AiProviderOutcome.failure(
                AiOutcomeClassification.TIMEOUT,
                "AI_PROVIDER_TIMEOUT",
                "AI advisory provider timed out",
                false
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            future.cancel(true);
            outcome = AiProviderOutcome.failure(
                AiOutcomeClassification.UNKNOWN,
                "AI_PROVIDER_INTERRUPTED",
                "AI advisory provider invocation was interrupted",
                false
            );
        } catch (ExecutionException exception) {
            outcome = AiProviderOutcome.failure(
                AiOutcomeClassification.UNKNOWN,
                "AI_PROVIDER_EXCEPTION",
                "AI advisory provider failed without trusted output",
                false
            );
        } catch (RuntimeException exception) {
            outcome = AiProviderOutcome.failure(
                AiOutcomeClassification.INVALID_OUTPUT,
                "AI_OUTPUT_INVALID",
                "AI advisory provider returned invalid structured output",
                false
            );
        }

        record(descriptor, request, outcome, policyResult(outcome.classification()));
        return outcome;
    }

    private static AiProviderOutcome preflight(
        AiProviderDescriptor descriptor,
        AiProviderRequest request,
        AiProviderExecutionPolicy policy
    ) {
        if (!policy.enabled()) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.DISABLED,
                "AI_PROVIDER_DISABLED",
                "AI advisory is disabled by server policy",
                false
            );
        }
        if (!policy.allowedProviderIds().contains(descriptor.providerId())) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.REJECTED,
                "AI_PROVIDER_NOT_AUTHORIZED",
                "AI provider is not authorized by server policy",
                false
            );
        }
        if (!policy.allowedCapabilities().contains(request.capability())
            || !descriptor.supports(request.capability())) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.UNSUPPORTED,
                "AI_CAPABILITY_UNSUPPORTED",
                "AI capability is not supported",
                false
            );
        }
        if (!descriptor.supports(request.versions().model())
            || !policy.allowsModel(request.versions().model())) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.REJECTED,
                "AI_MODEL_NOT_AUTHORIZED",
                "AI model version is not authorized",
                false
            );
        }
        if (!descriptor.providerVersion().equals(request.versions().provider())) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.REJECTED,
                "AI_PROVIDER_VERSION_MISMATCH",
                "AI provider version does not match the authorized request",
                false
            );
        }
        if (request.versions().knowledgeSource().containsCustomerData()) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_CUSTOMER_KNOWLEDGE_BLOCKED",
                "customer knowledge data is blocked in the M6-D first safe slice",
                false
            );
        }
        if (request.timeout().compareTo(policy.maximumTimeout()) > 0) {
            return AiProviderOutcome.failure(
                AiOutcomeClassification.REJECTED,
                "AI_TIMEOUT_NOT_AUTHORIZED",
                "AI timeout exceeds the server policy maximum",
                false
            );
        }
        return null;
    }

    private static AiProviderOutcome validateOutcome(
        AiProviderRequest request,
        AiProviderOutcome outcome,
        double minimumConfidence
    ) {
        if (outcome == null) {
            return invalidOutput("AI provider returned no outcome");
        }
        if (!outcome.hasAdvisoryResult()) {
            return outcome;
        }
        AiAdvisoryResult result = outcome.result();
        if (!request.versions().equals(result.versions())) {
            return invalidOutput("AI result version references do not match the request");
        }
        if (result.authority() != AiAdvisoryResult.Authority.ADVISORY
            || result.assertionStatus()
                != AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
            || !result.needsHumanReview()) {
            return invalidOutput("AI result attempted to exceed advisory authority");
        }

        Set<String> allowedFieldKeys = request.inputFields().stream()
            .map(AiProviderRequest.InputField::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> evidenceIds = new HashSet<>();
        for (AiAdvisoryResult.EvidenceReference evidence : result.evidenceReferences()) {
            if (!allowedFieldKeys.contains(evidence.fieldKey())) {
                return invalidOutput("AI evidence references an unauthorized field");
            }
            if (!evidenceIds.add(evidence.id())) {
                return invalidOutput("AI evidence reference IDs must be unique");
            }
        }
        Set<String> referencedEvidenceIds = new HashSet<>();
        result.observations().forEach(
            item -> referencedEvidenceIds.addAll(item.evidenceReferenceIds())
        );
        result.riskSignals().forEach(
            item -> referencedEvidenceIds.addAll(item.evidenceReferenceIds())
        );
        result.recommendations().forEach(
            item -> referencedEvidenceIds.addAll(item.evidenceReferenceIds())
        );
        if (!evidenceIds.containsAll(referencedEvidenceIds)) {
            return invalidOutput("AI result references missing evidence IDs");
        }

        Stream<String> resultText = Stream.concat(
            Stream.of(result.summary()),
            Stream.of(
                result.observations().stream().map(AiAdvisoryResult.Observation::text),
                result.riskSignals().stream().map(AiAdvisoryResult.RiskSignal::text),
                result.missingMaterials().stream().map(AiAdvisoryResult.MissingMaterial::reason),
                result.recommendations().stream().map(AiAdvisoryResult.Recommendation::text),
                result.limitations().stream()
            ).flatMap(stream -> stream)
        );
        boolean forbidden = resultText
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(AiAdvisoryService::containsForbiddenResultMarker);
        if (forbidden) {
            return invalidOutput("AI result contains prohibited authoritative or command language");
        }

        if (result.confidence().score() < minimumConfidence
            || outcome.classification() == AiOutcomeClassification.LOW_CONFIDENCE) {
            return AiProviderOutcome.lowConfidence(result);
        }
        return AiProviderOutcome.success(result);
    }

    private static boolean containsForbiddenResultMarker(String text) {
        for (String marker : FORBIDDEN_RESULT_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static AiProviderOutcome invalidOutput(String message) {
        return AiProviderOutcome.failure(
            AiOutcomeClassification.INVALID_OUTPUT,
            "AI_OUTPUT_INVALID",
            message,
            false
        );
    }

    private void record(
        AiProviderDescriptor descriptor,
        AiProviderRequest request,
        AiProviderOutcome outcome,
        AiAdvisoryMetrics.PolicyResult policyResult
    ) {
        AiOutcomeClassification failureClass = outcome.classification()
            == AiOutcomeClassification.SUCCESS
            ? AiOutcomeClassification.SUCCESS
            : outcome.classification();
        metrics.record(new AiAdvisoryMetrics.MetricEvent(
            request.capability(),
            outcome.classification(),
            failureClass,
            descriptor.providerType(),
            policyResult
        ));
        auditSink.record(new AiAuditRecord(
            request.context().requestId(),
            request.context().traceId(),
            request.context().tenantId(),
            request.context().operatorId(),
            request.resource().resourceType(),
            request.resource().resourceId(),
            request.capability(),
            request.versions().policy(),
            request.versions(),
            outcome.classification(),
            null
        ));
    }

    private static AiAdvisoryMetrics.PolicyResult policyResult(
        AiOutcomeClassification classification
    ) {
        return switch (classification) {
            case DISABLED -> AiAdvisoryMetrics.PolicyResult.DISABLED;
            case UNSUPPORTED -> AiAdvisoryMetrics.PolicyResult.UNSUPPORTED;
            case REJECTED -> AiAdvisoryMetrics.PolicyResult.REJECTED;
            case POLICY_BLOCKED -> AiAdvisoryMetrics.PolicyResult.BLOCKED;
            default -> AiAdvisoryMetrics.PolicyResult.ALLOWED;
        };
    }

    @Override
    public void close() {
        if (closeExecutor) {
            executor.close();
        }
    }
}
