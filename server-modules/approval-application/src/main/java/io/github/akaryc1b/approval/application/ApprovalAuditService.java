package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityResult;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditPage;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditRecord;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Bounded management queries, integrity verification and server-side audit export. */
public final class ApprovalAuditService {

    public static final int MAX_QUERY_DAYS = 31;
    public static final int MAX_EXPORT_RECORDS = 10_000;

    private static final int EXPORT_PAGE_SIZE = 500;
    private static final String VERIFY_OPERATION = "approval.audit.integrity.verify.v1";
    private static final String EXPORT_OPERATION = "approval.audit.export.v1";
    private static final String REDACTED = "[REDACTED]";

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalAuditStore auditStore;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalAuditService(
        IdempotencyGuard idempotencyGuard,
        ApprovalAuditStore auditStore,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore must not be null");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public AuditPage find(AuditQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        validateRange(query.occurredFrom(), query.occurredTo());
        AuditPage page = auditStore.find(new AuditCriteria(
            query.tenantId(),
            query.operatorId(),
            query.action(),
            query.aggregateType(),
            query.aggregateId(),
            query.requestId(),
            query.traceId(),
            query.occurredFrom(),
            query.occurredTo(),
            query.limit(),
            query.offset()
        ));
        return redact(page);
    }

    public AuditIntegrityReport verify(VerifyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateRange(command.occurredFrom(), command.occurredTo());
        String requestHash = hashValues(
            command.occurredFrom().toString(),
            command.occurredTo().toString()
        );
        return idempotencyGuard.execute(
            command.context(),
            VERIFY_OPERATION,
            requestHash,
            AuditIntegrityReport.class,
            () -> executeVerify(command)
        );
    }

    public AuditExport prepareExport(ExportCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateRange(command.query().occurredFrom(), command.query().occurredTo());
        if (command.maxRecords() < 1 || command.maxRecords() > MAX_EXPORT_RECORDS) {
            throw error(
                400,
                "APPROVAL_AUDIT_EXPORT_LIMIT_INVALID",
                "maxRecords must be between 1 and 10000"
            );
        }
        String requestHash = hashValues(
            command.format().name(),
            Integer.toString(command.maxRecords()),
            queryHash(command.query())
        );
        return idempotencyGuard.execute(
            command.context(),
            EXPORT_OPERATION,
            requestHash,
            AuditExport.class,
            () -> executeExport(command)
        );
    }

    private AuditIntegrityReport executeVerify(VerifyCommand command) {
        AuditIntegrityResult result = auditStore.verify(new AuditIntegrityCriteria(
            command.context().tenantId(),
            command.occurredFrom(),
            command.occurredTo()
        ));
        Instant verifiedAt = clock.instant();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("valid", Boolean.toString(result.valid()));
        attributes.put("checkedCount", Long.toString(result.checkedCount()));
        attributes.put("rangeStart", command.occurredFrom().toString());
        attributes.put("rangeEnd", command.occurredTo().toString());
        attributes.put("chainStateSequence", Long.toString(result.chainStateSequence()));
        attributes.put("chainStateHash", result.chainStateHash());
        if (result.failureCode() != null) {
            attributes.put("failureCode", result.failureCode());
        }
        if (result.firstInvalidEventId() != null) {
            attributes.put("firstInvalidEventId", result.firstInvalidEventId().toString());
        }
        if (result.firstInvalidSequence() != null) {
            attributes.put("firstInvalidSequence", result.firstInvalidSequence().toString());
        }
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            command.context().tenantId(),
            command.context().operatorId(),
            "AUDIT_INTEGRITY_VERIFIED",
            "AUDIT_CHAIN",
            command.context().tenantId(),
            command.context().requestId(),
            command.context().traceId(),
            verifiedAt,
            Map.copyOf(attributes)
        ));
        return new AuditIntegrityReport(
            result.valid(),
            result.checkedCount(),
            result.firstInvalidEventId(),
            result.firstInvalidSequence(),
            result.failureCode(),
            result.chainStateSequence(),
            result.chainStateHash(),
            command.occurredFrom(),
            command.occurredTo(),
            verifiedAt,
            "SHA-256 hash chaining detects changes within the platform audit chain; "
                + "it is not a claim of legal non-repudiation."
        );
    }

    private AuditExport executeExport(ExportCommand command) {
        AuditQuery query = command.query();
        List<AuditRecord> records = new ArrayList<>();
        int offset = 0;
        long total = -1;
        while (records.size() < command.maxRecords()) {
            int remaining = command.maxRecords() - records.size();
            int limit = Math.min(EXPORT_PAGE_SIZE, remaining);
            AuditPage page = find(new AuditQuery(
                query.tenantId(),
                query.operatorId(),
                query.action(),
                query.aggregateType(),
                query.aggregateId(),
                query.requestId(),
                query.traceId(),
                query.occurredFrom(),
                query.occurredTo(),
                limit,
                offset
            ));
            total = page.total();
            records.addAll(page.items());
            if (!page.hasMore()) {
                break;
            }
            offset += page.items().size();
        }
        if (total > command.maxRecords()) {
            throw error(
                409,
                "APPROVAL_AUDIT_EXPORT_LIMIT_EXCEEDED",
                "the export query matches more records than maxRecords"
            );
        }
        Instant exportedAt = clock.instant();
        Map<String, String> attributes = Map.of(
            "format", command.format().name(),
            "recordCount", Integer.toString(records.size()),
            "rangeStart", query.occurredFrom().toString(),
            "rangeEnd", query.occurredTo().toString()
        );
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            command.context().tenantId(),
            command.context().operatorId(),
            "AUDIT_EXPORTED",
            "AUDIT_EXPORT",
            command.context().requestId(),
            command.context().requestId(),
            command.context().traceId(),
            exportedAt,
            attributes
        ));
        return new AuditExport(
            command.format(),
            List.copyOf(records),
            exportedAt,
            query.occurredFrom(),
            query.occurredTo()
        );
    }

    private static AuditPage redact(AuditPage page) {
        List<AuditRecord> records = page.items().stream()
            .map(ApprovalAuditService::redact)
            .toList();
        return new AuditPage(records, page.total(), page.limit(), page.offset(), page.hasMore());
    }

    private static AuditRecord redact(AuditRecord record) {
        Map<String, String> attributes = new LinkedHashMap<>();
        record.attributes().forEach((key, value) ->
            attributes.put(key, sensitive(key) ? REDACTED : value)
        );
        return new AuditRecord(
            record.eventId(),
            record.tenantId(),
            record.tenantSequence(),
            record.operatorId(),
            record.action(),
            record.aggregateType(),
            record.aggregateId(),
            record.schemaName(),
            record.schemaVersion(),
            record.requestId(),
            record.traceId(),
            record.occurredAt(),
            Map.copyOf(attributes),
            record.previousHash(),
            record.payloadHash(),
            record.currentHash()
        );
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("authorization")
            || normalized.contains("cookie")
            || normalized.contains("email")
            || normalized.contains("phone")
            || normalized.equals("body")
            || normalized.equals("commentbody")
            || normalized.equals("content");
    }

    private static void validateRange(Instant from, Instant to) {
        Objects.requireNonNull(from, "occurredFrom must not be null");
        Objects.requireNonNull(to, "occurredTo must not be null");
        if (!from.isBefore(to)) {
            throw error(400, "APPROVAL_AUDIT_RANGE_INVALID", "occurredFrom must be before occurredTo");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_QUERY_DAYS)) > 0) {
            throw error(
                400,
                "APPROVAL_AUDIT_RANGE_TOO_LARGE",
                "audit queries must not exceed 31 days"
            );
        }
    }

    private static String queryHash(AuditQuery query) {
        return String.join(
            "\u001f",
            query.tenantId(),
            optional(query.operatorId()),
            optional(query.action()),
            optional(query.aggregateType()),
            optional(query.aggregateId()),
            optional(query.requestId()),
            optional(query.traceId()),
            query.occurredFrom().toString(),
            query.occurredTo().toString()
        );
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = optional(value).getBytes(StandardCharsets.UTF_8);
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

    private static AuditOperationException error(int status, String code, String message) {
        return new AuditOperationException(status, code, message);
    }

    public enum ExportFormat {
        CSV,
        JSON
    }

    public record AuditQuery(
        String tenantId,
        String operatorId,
        String action,
        String aggregateType,
        String aggregateId,
        String requestId,
        String traceId,
        Instant occurredFrom,
        Instant occurredTo,
        int limit,
        int offset
    ) {
        public AuditQuery {
            tenantId = requireText(tenantId, "tenantId");
            occurredFrom = Objects.requireNonNull(occurredFrom, "occurredFrom must not be null");
            occurredTo = Objects.requireNonNull(occurredTo, "occurredTo must not be null");
            if (limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }
    }

    public record VerifyCommand(
        RequestContext context,
        Instant occurredFrom,
        Instant occurredTo
    ) {
        public VerifyCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            occurredFrom = Objects.requireNonNull(occurredFrom, "occurredFrom must not be null");
            occurredTo = Objects.requireNonNull(occurredTo, "occurredTo must not be null");
        }
    }

    public record ExportCommand(
        RequestContext context,
        AuditQuery query,
        ExportFormat format,
        int maxRecords
    ) {
        public ExportCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            query = Objects.requireNonNull(query, "query must not be null");
            format = Objects.requireNonNull(format, "format must not be null");
            if (!context.tenantId().equals(query.tenantId())) {
                throw new IllegalArgumentException("export query tenant must match request tenant");
            }
        }
    }

    public record AuditIntegrityReport(
        boolean valid,
        long checkedCount,
        UUID firstInvalidEventId,
        Long firstInvalidSequence,
        String failureCode,
        long chainStateSequence,
        String chainStateHash,
        Instant occurredFrom,
        Instant occurredTo,
        Instant verifiedAt,
        String assuranceStatement
    ) {
    }

    public record AuditExport(
        ExportFormat format,
        List<AuditRecord> records,
        Instant exportedAt,
        Instant occurredFrom,
        Instant occurredTo
    ) {
        public AuditExport {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public static final class AuditOperationException extends RuntimeException {
        private final int status;
        private final String code;

        public AuditOperationException(int status, String code, String message) {
            super(requireText(message, "message"));
            if (status < 400 || status > 499) {
                throw new IllegalArgumentException("audit error status must be a 4xx status");
            }
            this.status = status;
            this.code = requireText(code, "code");
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
