package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckScope;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckStatus;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckType;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheck;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheckCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheckPage;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheckRequest;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyFindingCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyFindingPage;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.Severity;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Detect-only consistency administration over platform-owned approval evidence. */
public final class ApprovalConsistencyService {

    private static final String RUN_OPERATION = "approval.consistency.check.run.v1";
    private static final String TENANT_SCOPE_HASH = sha256(CheckScope.TENANT.name());

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalConsistencyStore store;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalConsistencyService(
        IdempotencyGuard idempotencyGuard,
        ApprovalConsistencyStore store,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public ConsistencyCheck run(RunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return idempotencyGuard.execute(
            command.context(),
            RUN_OPERATION,
            TENANT_SCOPE_HASH,
            ConsistencyCheck.class,
            () -> executeRun(command.context())
        );
    }

    public ConsistencyCheckPage findChecks(
        String tenantId,
        CheckStatus status,
        int limit,
        int offset
    ) {
        return store.findChecks(new ConsistencyCheckCriteria(
            tenantId,
            status,
            limit,
            offset
        ));
    }

    public ConsistencyFindingPage findFindings(
        String tenantId,
        UUID checkId,
        CheckType checkType,
        Severity severity,
        int limit,
        int offset
    ) {
        return store.findFindings(new ConsistencyFindingCriteria(
            tenantId,
            checkId,
            checkType,
            severity,
            limit,
            offset
        ));
    }

    private ConsistencyCheck executeRun(RequestContext context) {
        Instant startedAt = clock.instant();
        ConsistencyCheck check = store.run(new ConsistencyCheckRequest(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            context.requestId(),
            context.traceId(),
            CheckScope.TENANT,
            startedAt
        ));
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            "CONSISTENCY_CHECK_EXECUTED",
            "CONSISTENCY_CHECK",
            check.checkId().toString(),
            context.requestId(),
            context.traceId(),
            clock.instant(),
            Map.of(
                "checkId", check.checkId().toString(),
                "scope", check.scope().name(),
                "status", check.status().name(),
                "findingCount", Integer.toString(check.findingCount()),
                "detectOnly", "true"
            )
        ));
        return check;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RunCommand(RequestContext context) {
        public RunCommand {
            context = Objects.requireNonNull(context, "context must not be null");
        }
    }
}
