package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ActionDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.CreationTrigger;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.SourceAdvisoryEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure server-side factory for typed, non-executable controlled-automation Proposals.
 *
 * <p>The factory performs no Provider, persistence, connector, Flowable or application-command
 * call. Page load, polling, callbacks, listeners, schedules and Webhooks fail closed.</p>
 */
public final class ControlledAutomationProposalFactory {

    private static final Duration MAXIMUM_PROPOSAL_LIFETIME = Duration.ofMinutes(15);

    private final ControlledAutomationActionWhitelist whitelist;
    private final Clock clock;
    private final Supplier<UUID> proposalIdSupplier;

    public ControlledAutomationProposalFactory(
        ControlledAutomationActionWhitelist whitelist,
        Clock clock,
        Supplier<UUID> proposalIdSupplier
    ) {
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.proposalIdSupplier = Objects.requireNonNull(
            proposalIdSupplier,
            "proposalIdSupplier must not be null"
        );
    }

    public CreationResult create(ProposalCreationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.trigger() != CreationTrigger.EXPLICIT_USER_ACTION) {
            return CreationResult.rejected(CreationDisposition.TRIGGER_NOT_ALLOWED);
        }

        Instant createdAt = clock.instant();
        if (!request.expiresAt().isAfter(createdAt)
            || request.expiresAt().isAfter(createdAt.plus(MAXIMUM_PROPOSAL_LIFETIME))) {
            return CreationResult.rejected(CreationDisposition.EXPIRY_NOT_ALLOWED);
        }

        Optional<ActionDefinition> resolved = whitelist.resolve(request.canonicalActionType());
        if (resolved.isEmpty()) {
            return CreationResult.rejected(CreationDisposition.ACTION_NOT_WHITELISTED);
        }
        ActionDefinition action = resolved.orElseThrow();
        if (!action.canonicalActionType().equals(request.canonicalActionType())) {
            return CreationResult.rejected(CreationDisposition.ACTION_NOT_WHITELISTED);
        }
        if (action.riskClassification()
            != ControlledAutomationActionWhitelist.RiskClassification.LOW) {
            return CreationResult.rejected(CreationDisposition.RISK_NOT_ALLOWED);
        }
        if (!action.targetResourceType().equals(request.targetResource().resourceType())) {
            return CreationResult.rejected(CreationDisposition.RESOURCE_TYPE_MISMATCH);
        }
        if (!matchesParameterSchema(action.parameterSchema(), request.parameters())) {
            return CreationResult.rejected(CreationDisposition.PARAMETER_SCHEMA_MISMATCH);
        }

        AiServerRequestContext context = request.requestContext();
        String tenantEvidenceHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-tenant-v1",
            context.tenantId()
        );
        String operatorEvidenceHash = ControlledAutomationProposal.hashTuple(
            "controlled-automation-operator-v1",
            context.tenantId(),
            context.operatorId()
        );
        ControlledAutomationProposal proposal = ControlledAutomationProposal.create(
            Objects.requireNonNull(proposalIdSupplier.get(), "proposalId must not be null"),
            tenantEvidenceHash,
            operatorEvidenceHash,
            request.sourceAdvisory(),
            action.canonicalActionType(),
            request.parameters(),
            request.targetResource(),
            whitelist.version(),
            request.policy(),
            action.riskClassification(),
            action.sideEffectSummary(),
            createdAt,
            request.expiresAt(),
            action.reauthenticationRequirement()
        );
        return CreationResult.created(proposal);
    }

    private static boolean matchesParameterSchema(
        Map<String, ParameterDefinition> schema,
        Map<String, ParameterBinding> parameters
    ) {
        if (!schema.keySet().equals(parameters.keySet())) {
            return false;
        }
        for (Map.Entry<String, ParameterDefinition> entry : schema.entrySet()) {
            ParameterBinding binding = parameters.get(entry.getKey());
            ParameterDefinition definition = entry.getValue();
            if (binding == null || binding.value().type() != definition.type()) {
                return false;
            }
            if (definition.type() == ControlledAutomationActionWhitelist.ParameterType.ENUM
                && !definition.allowedEnumValues().contains(binding.value().canonicalValue())) {
                return false;
            }
        }
        return true;
    }

    public record ProposalCreationRequest(
        AiServerRequestContext requestContext,
        CreationTrigger trigger,
        SourceAdvisoryEvidence sourceAdvisory,
        String canonicalActionType,
        Map<String, ParameterBinding> parameters,
        TargetResourceEvidence targetResource,
        PolicyEvidence policy,
        Instant expiresAt
    ) {
        public ProposalCreationRequest {
            requestContext = Objects.requireNonNull(
                requestContext,
                "requestContext must not be null"
            );
            trigger = Objects.requireNonNull(trigger, "trigger must not be null");
            sourceAdvisory = Objects.requireNonNull(
                sourceAdvisory,
                "sourceAdvisory must not be null"
            );
            canonicalActionType = ControlledAutomationProposal.requireCanonicalActionType(
                canonicalActionType
            );
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            if (parameters.size() > ControlledAutomationProposal.MAXIMUM_PARAMETERS) {
                throw new IllegalArgumentException("parameters exceed the closed limit");
            }
            targetResource = Objects.requireNonNull(
                targetResource,
                "targetResource must not be null"
            );
            policy = Objects.requireNonNull(policy, "policy must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    public enum CreationDisposition {
        CREATED,
        ACTION_NOT_WHITELISTED,
        EXPIRY_NOT_ALLOWED,
        PARAMETER_SCHEMA_MISMATCH,
        RESOURCE_TYPE_MISMATCH,
        RISK_NOT_ALLOWED,
        TRIGGER_NOT_ALLOWED
    }

    public record CreationResult(
        CreationDisposition disposition,
        Optional<ControlledAutomationProposal> proposal
    ) {
        public CreationResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            proposal = Objects.requireNonNull(proposal, "proposal must not be null");
            if ((disposition == CreationDisposition.CREATED) != proposal.isPresent()) {
                throw new IllegalArgumentException(
                    "only CREATED may return a non-executable Proposal"
                );
            }
        }

        private static CreationResult created(ControlledAutomationProposal proposal) {
            return new CreationResult(CreationDisposition.CREATED, Optional.of(proposal));
        }

        private static CreationResult rejected(CreationDisposition disposition) {
            return new CreationResult(disposition, Optional.empty());
        }
    }
}
