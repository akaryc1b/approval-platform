package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationRule;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationStatus;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-authoritative proxy rule lifecycle and deterministic delegate resolution.
 */
public final class ApprovalDelegationService {

    private static final String CREATE_OPERATION = "approval.delegation.create.v1";
    private static final String REVOKE_OPERATION = "approval.delegation.revoke.v1";
    private static final Duration MAXIMUM_DELEGATION_DURATION = Duration.ofDays(366);

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalDelegationStore delegations;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalDelegationService(
        IdempotencyGuard idempotencyGuard,
        ApprovalDelegationStore delegations,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.delegations = Objects.requireNonNull(
            delegations,
            "delegations must not be null"
        );
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public DelegationRule create(CreateDelegationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RequestContext context = command.context();
        String delegateId = requireText(command.delegateId(), "delegateId");
        DelegationScope scope = Objects.requireNonNull(command.scope(), "scope must not be null");
        String definitionKey = normalizeDefinitionKey(scope, command.definitionKey());
        String reason = requireText(command.reason(), "reason");
        Instant validFrom = Objects.requireNonNull(
            command.validFrom(),
            "validFrom must not be null"
        );
        Instant validUntil = Objects.requireNonNull(
            command.validUntil(),
            "validUntil must not be null"
        );
        validateWindow(validFrom, validUntil, clock.instant());
        if (context.operatorId().equals(delegateId)) {
            throw new IllegalArgumentException("delegateId must differ from the current operator");
        }
        String requestHash = hashValues(
            context.operatorId(),
            delegateId,
            scope.name(),
            definitionKey,
            validFrom.toString(),
            validUntil.toString(),
            reason
        );
        return idempotencyGuard.execute(
            context,
            CREATE_OPERATION,
            requestHash,
            DelegationRule.class,
            () -> executeCreate(
                context,
                delegateId,
                scope,
                definitionKey,
                validFrom,
                validUntil,
                reason
            )
        );
    }

    public DelegationRule revoke(RevokeDelegationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String reason = requireText(command.reason(), "reason");
        String requestHash = hashValues(command.ruleId().toString(), reason);
        return idempotencyGuard.execute(
            command.context(),
            REVOKE_OPERATION,
            requestHash,
            DelegationRule.class,
            () -> executeRevoke(command, reason)
        );
    }

    public List<DelegationRule> findMine(
        String tenantId,
        String operatorId,
        boolean includeRevoked
    ) {
        return delegations.findByPrincipal(
            requireText(tenantId, "tenantId"),
            requireText(operatorId, "operatorId"),
            includeRevoked
        );
    }

    public Optional<DelegationResolution> resolve(
        String tenantId,
        String principalId,
        String definitionKey,
        Instant effectiveAt
    ) {
        String normalizedDefinitionKey = requireText(definitionKey, "definitionKey");
        return delegations.resolveEffective(
            requireText(tenantId, "tenantId"),
            requireText(principalId, "principalId"),
            normalizedDefinitionKey,
            Objects.requireNonNull(effectiveAt, "effectiveAt must not be null")
        ).map(rule -> new DelegationResolution(
            rule.ruleId(),
            rule.principalId(),
            rule.delegateId(),
            rule.scope(),
            rule.definitionKey(),
            rule.validFrom(),
            rule.validUntil()
        ));
    }

    private DelegationRule executeCreate(
        RequestContext context,
        String delegateId,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil,
        String reason
    ) {
        Instant now = clock.instant();
        DelegationRule created = delegations.create(new DelegationRule(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            delegateId,
            scope,
            definitionKey,
            validFrom,
            validUntil,
            DelegationStatus.ACTIVE,
            reason,
            context.operatorId(),
            now,
            null,
            null,
            null,
            1
        ));
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("principalId", created.principalId());
        attributes.put("delegateId", created.delegateId());
        attributes.put("scope", created.scope().name());
        attributes.put("validFrom", created.validFrom().toString());
        attributes.put("validUntil", created.validUntil().toString());
        if (created.definitionKey() != null) {
            attributes.put("definitionKey", created.definitionKey());
        }
        appendAudit(
            context,
            "DELEGATION_RULE_CREATED",
            created.ruleId(),
            Map.copyOf(attributes),
            now
        );
        return created;
    }

    private DelegationRule executeRevoke(
        RevokeDelegationCommand command,
        String reason
    ) {
        Instant now = clock.instant();
        DelegationRule revoked = delegations.revoke(
            command.context().tenantId(),
            command.ruleId(),
            command.context().operatorId(),
            command.context().operatorId(),
            reason,
            now
        );
        appendAudit(
            command.context(),
            "DELEGATION_RULE_REVOKED",
            revoked.ruleId(),
            Map.of(
                "principalId", revoked.principalId(),
                "delegateId", revoked.delegateId(),
                "scope", revoked.scope().name()
            ),
            now
        );
        return revoked;
    }

    private void appendAudit(
        RequestContext context,
        String action,
        UUID ruleId,
        Map<String, String> attributes,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            "APPROVAL_DELEGATION_RULE",
            ruleId.toString(),
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
    }

    private static void validateWindow(
        Instant validFrom,
        Instant validUntil,
        Instant now
    ) {
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (!validUntil.isAfter(now)) {
            throw new IllegalArgumentException("validUntil must be in the future");
        }
        Duration duration = Duration.between(validFrom, validUntil);
        if (duration.compareTo(MAXIMUM_DELEGATION_DURATION) > 0) {
            throw new IllegalArgumentException(
                "delegation duration must not exceed 366 days"
            );
        }
    }

    private static String normalizeDefinitionKey(
        DelegationScope scope,
        String definitionKey
    ) {
        String normalized = normalizeOptional(definitionKey);
        if (scope == DelegationScope.ALL && normalized != null) {
            throw new IllegalArgumentException(
                "definitionKey must be absent for an ALL delegation"
            );
        }
        if (scope == DelegationScope.DEFINITION && normalized == null) {
            throw new IllegalArgumentException(
                "definitionKey is required for a DEFINITION delegation"
            );
        }
        return normalized;
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateDelegationCommand(
        RequestContext context,
        String delegateId,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil,
        String reason
    ) {
        public CreateDelegationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
        }
    }

    public record RevokeDelegationCommand(
        RequestContext context,
        UUID ruleId,
        String reason
    ) {
        public RevokeDelegationCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
        }
    }

    public record DelegationResolution(
        UUID ruleId,
        String principalId,
        String delegateId,
        DelegationScope scope,
        String definitionKey,
        Instant validFrom,
        Instant validUntil
    ) {
    }
}
