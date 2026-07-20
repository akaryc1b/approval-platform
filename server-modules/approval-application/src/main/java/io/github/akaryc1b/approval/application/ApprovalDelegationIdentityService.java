package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationRule;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Exact connector-identity boundary for delegation rule commands. */
public final class ApprovalDelegationIdentityService {

    private final ApprovalIdentityDirectory identities;
    private final ApprovalDelegationService delegations;

    public ApprovalDelegationIdentityService(
        ApprovalIdentityDirectory identities,
        ApprovalDelegationService delegations
    ) {
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.delegations = Objects.requireNonNull(
            delegations,
            "delegations must not be null"
        );
    }

    public DelegationRule create(CreateGovernedDelegationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String delegateId = identities.requireUser(new IdentityLookup(
            command.context().tenantId(),
            command.connectorKey(),
            command.context().requestId(),
            command.context().traceId(),
            command.delegateIdentity(),
            true
        )).userId();
        return delegations.create(new ApprovalDelegationService.CreateDelegationCommand(
            command.context(),
            delegateId,
            command.scope(),
            command.definitionKey(),
            command.validFrom(),
            command.validUntil(),
            command.reason()
        ));
    }

    public DelegationRule revoke(ApprovalDelegationService.RevokeDelegationCommand command) {
        return delegations.revoke(command);
    }

    public List<DelegationRule> findMine(
        String tenantId,
        String operatorId,
        boolean includeRevoked
    ) {
        return delegations.findMine(tenantId, operatorId, includeRevoked);
    }

    public record CreateGovernedDelegationCommand(
        RequestContext context,
        String connectorKey,
        IdentityReference delegateIdentity,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil,
        String reason
    ) {
        public CreateGovernedDelegationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            connectorKey = requireText(connectorKey, "connectorKey");
            delegateIdentity = Objects.requireNonNull(
                delegateIdentity,
                "delegateIdentity must not be null"
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
