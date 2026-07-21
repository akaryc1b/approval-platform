package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheck;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureCategory;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.FailureKind;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailure;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailurePage;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Unified operational visibility and governed replay while preserving owning state machines. */
public final class ApprovalOperationalFailureService {

    private static final int MAX_BATCH_SIZE = 50;
    private static final String REPLAY_OPERATION = "approval.operational-failure.replay.v1";
    private static final String BATCH_REPLAY_OPERATION = "approval.operational-failure.replay-batch.v1";

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalOperationalFailureStore failures;
    private final ApprovalNotificationStore notifications;
    private final ApprovalConsistencyService consistency;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalOperationalFailureService(
        IdempotencyGuard idempotencyGuard,
        ApprovalOperationalFailureStore failures,
        ApprovalNotificationStore notifications,
        ApprovalConsistencyService consistency,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.consistency = Objects.requireNonNull(consistency, "consistency must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public OperationalFailurePage findFailures(
        String tenantId,
        FailureCategory category,
        FailureKind failureKind,
        String connectorKey,
        int limit,
        int offset
    ) {
        return failures.findFailures(new OperationalFailureCriteria(
            tenantId,
            category,
            failureKind,
            connectorKey,
            limit,
            offset
        ));
    }

    public List<OperationalFailureAttempt> findAttempts(
        String tenantId,
        FailureCategory category,
        UUID sourceId
    ) {
        return failures.findAttempts(tenantId, category, sourceId);
    }

    public ReplayItemResult replay(ReplayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ReplayItem item = command.item();
        return idempotencyGuard.execute(
            command.context(),
            REPLAY_OPERATION,
            sha256(item.category().name() + ':' + item.sourceId()),
            ReplayItemResult.class,
            () -> replayOne(command.context(), item)
        );
    }

    public BatchReplayResult replayBatch(BatchReplayCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateBatch(command.items());
        return idempotencyGuard.execute(
            command.context(),
            BATCH_REPLAY_OPERATION,
            batchHash(command.items()),
            BatchReplayResult.class,
            () -> executeBatch(command.context(), command.items())
        );
    }

    private BatchReplayResult executeBatch(RequestContext context, List<ReplayItem> items) {
        List<ReplayItemResult> results = new ArrayList<>();
        for (ReplayItem item : items) {
            try {
                results.add(replayOne(context, item));
            } catch (RuntimeException exception) {
                results.add(new ReplayItemResult(
                    item.category(),
                    item.sourceId(),
                    ReplayOutcome.REJECTED,
                    null,
                    clock.instant(),
                    safeMessage(exception)
                ));
            }
        }
        int replayed = (int) results.stream()
            .filter(result -> result.outcome() == ReplayOutcome.REPLAYED)
            .count();
        return new BatchReplayResult(
            List.copyOf(results),
            replayed,
            results.size() - replayed,
            clock.instant()
        );
    }

    private ReplayItemResult replayOne(RequestContext context, ReplayItem item) {
        OperationalFailure failure = failures.findFailure(
            context.tenantId(),
            item.category(),
            item.sourceId()
        ).orElseThrow(() -> new OperationalFailureNotFoundException(
            "operational failure does not exist for the tenant"
        ));
        if (!failure.replayable()) {
            throw new OperationalFailureConflictException("operational failure is not replayable");
        }

        Instant replayedAt = clock.instant();
        UUID replacementSourceId = switch (item.category()) {
            case NOTIFICATION_DELIVERY -> {
                if (failure.recipientId() == null) {
                    throw new OperationalFailureConflictException(
                        "notification failure does not contain a recipient"
                    );
                }
                notifications.replayDeadLetter(
                    context.tenantId(),
                    failure.recipientId(),
                    item.sourceId(),
                    replayedAt
                );
                yield null;
            }
            case BUSINESS_OUTBOX -> {
                boolean replayed = failures.replayOutboxDead(
                    context.tenantId(),
                    item.sourceId(),
                    context.operatorId(),
                    context.requestId(),
                    replayedAt
                );
                if (!replayed) {
                    throw new OperationalFailureConflictException(
                        "only a DEAD business Outbox message can be replayed"
                    );
                }
                yield null;
            }
            case CONSISTENCY_CHECK -> {
                ConsistencyCheck check = consistency.run(new ApprovalConsistencyService.RunCommand(
                    consistencyContext(context, item.sourceId())
                ));
                yield check.checkId();
            }
        };

        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            "OPERATIONAL_FAILURE_REPLAYED",
            "OPERATIONAL_FAILURE",
            item.category().name() + ':' + item.sourceId(),
            context.requestId(),
            context.traceId(),
            replayedAt,
            Map.of(
                "category", item.category().name(),
                "sourceId", item.sourceId().toString(),
                "outcome", ReplayOutcome.REPLAYED.name(),
                "replacementSourceId", replacementSourceId == null
                    ? "NONE"
                    : replacementSourceId.toString()
            )
        ));
        return new ReplayItemResult(
            item.category(),
            item.sourceId(),
            ReplayOutcome.REPLAYED,
            replacementSourceId,
            replayedAt,
            "replay accepted by the owning state machine"
        );
    }

    private static RequestContext consistencyContext(RequestContext context, UUID sourceId) {
        return new RequestContext(
            context.tenantId(),
            context.operatorId(),
            context.requestId(),
            sha256(context.idempotencyKey() + ":consistency:" + sourceId),
            context.traceId()
        );
    }

    private static void validateBatch(List<ReplayItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("batch replay requires at least one item");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch replay supports at most 50 items");
        }
        Set<String> unique = new HashSet<>();
        for (ReplayItem item : items) {
            Objects.requireNonNull(item, "batch replay item must not be null");
            String key = item.category().name() + ':' + item.sourceId();
            if (!unique.add(key)) {
                throw new IllegalArgumentException("batch replay contains duplicate items");
            }
        }
    }

    private static String batchHash(List<ReplayItem> items) {
        StringBuilder canonical = new StringBuilder();
        for (ReplayItem item : items) {
            if (!canonical.isEmpty()) {
                canonical.append('|');
            }
            canonical.append(item.category().name()).append(':').append(item.sourceId());
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    public record ReplayCommand(RequestContext context, ReplayItem item) {
        public ReplayCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            item = Objects.requireNonNull(item, "item must not be null");
        }
    }

    public record BatchReplayCommand(RequestContext context, List<ReplayItem> items) {
        public BatchReplayCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ReplayItem(FailureCategory category, UUID sourceId) {
        public ReplayItem {
            category = Objects.requireNonNull(category, "category must not be null");
            sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        }
    }

    public record ReplayItemResult(
        FailureCategory category,
        UUID sourceId,
        ReplayOutcome outcome,
        UUID replacementSourceId,
        Instant occurredAt,
        String message
    ) {
        public ReplayItemResult {
            category = Objects.requireNonNull(category, "category must not be null");
            sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
        }
    }

    public record BatchReplayResult(
        List<ReplayItemResult> items,
        int replayed,
        int rejected,
        Instant completedAt
    ) {
        public BatchReplayResult {
            items = items == null ? List.of() : List.copyOf(items);
            if (replayed < 0 || rejected < 0 || replayed + rejected != items.size()) {
                throw new IllegalArgumentException("batch replay counts do not match items");
            }
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        }
    }

    public enum ReplayOutcome {
        REPLAYED,
        REJECTED
    }
}
