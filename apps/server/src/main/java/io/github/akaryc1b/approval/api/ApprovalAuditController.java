package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalAuditService;
import io.github.akaryc1b.approval.application.ApprovalAuditService.AuditExport;
import io.github.akaryc1b.approval.application.ApprovalAuditService.AuditIntegrityReport;
import io.github.akaryc1b.approval.application.ApprovalAuditService.AuditQuery;
import io.github.akaryc1b.approval.application.ApprovalAuditService.ExportCommand;
import io.github.akaryc1b.approval.application.ApprovalAuditService.ExportFormat;
import io.github.akaryc1b.approval.application.ApprovalAuditService.VerifyCommand;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditPage;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditRecord;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement.AUDIT_EXPORT;
import static io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement.AUDIT_READ;
import static io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement.AUDIT_VERIFY;

@RestController
@RequestMapping("/api/approval/management/audit")
@ApprovalManagementPermission(AUDIT_READ)
public class ApprovalAuditController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalAuditService service;
    private final ObjectMapper objectMapper;

    public ApprovalAuditController(ApprovalAuditService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public AuditPage find(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(required = false) String operatorId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String aggregateType,
        @RequestParam(required = false) String aggregateId,
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String traceId,
        @RequestParam Instant occurredFrom,
        @RequestParam Instant occurredTo,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return service.find(new AuditQuery(
            tenantId,
            operatorId,
            action,
            aggregateType,
            aggregateId,
            requestId,
            traceId,
            occurredFrom,
            occurredTo,
            limit,
            offset
        ));
    }

    @PostMapping("/integrity/verify")
    @ApprovalManagementPermission(AUDIT_VERIFY)
    public AuditIntegrityReport verify(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody IntegrityRequest request
    ) {
        return service.verify(new VerifyCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.occurredFrom(),
            request.occurredTo()
        ));
    }

    @PostMapping("/export")
    @ApprovalManagementPermission(AUDIT_EXPORT)
    public ResponseEntity<byte[]> export(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @Valid @RequestBody ExportRequest request
    ) {
        AuditQuery query = new AuditQuery(
            tenantId,
            request.operatorId(),
            request.action(),
            request.aggregateType(),
            request.aggregateId(),
            request.requestId(),
            request.traceId(),
            request.occurredFrom(),
            request.occurredTo(),
            1_000,
            0
        );
        AuditExport export = service.prepareExport(new ExportCommand(
            context(tenantId, operatorId, requestId, idempotencyKey, traceId),
            query,
            request.format(),
            request.maxRecords()
        ));
        byte[] content = serialize(export);
        String extension = export.format() == ExportFormat.CSV ? "csv" : "json";
        MediaType mediaType = export.format() == ExportFormat.CSV
            ? new MediaType("text", "csv", StandardCharsets.UTF_8)
            : MediaType.APPLICATION_JSON;
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename("approval-audit-" + requestId + '.' + extension)
                    .build()
                    .toString()
            )
            .body(content);
    }

    private byte[] serialize(AuditExport export) {
        if (export.format() == ExportFormat.CSV) {
            return csv(export).getBytes(StandardCharsets.UTF_8);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to serialize audit export", exception);
        }
    }

    private String csv(AuditExport export) {
        StringBuilder csv = new StringBuilder();
        csv.append("tenantSequence,eventId,tenantId,operatorId,action,aggregateType,")
            .append("aggregateId,schemaName,schemaVersion,requestId,traceId,occurredAt,")
            .append("previousHash,payloadHash,currentHash,attributes\n");
        for (AuditRecord record : export.records()) {
            append(csv, Long.toString(record.tenantSequence()));
            append(csv, record.eventId().toString());
            append(csv, record.tenantId());
            append(csv, record.operatorId());
            append(csv, record.action());
            append(csv, record.aggregateType());
            append(csv, record.aggregateId());
            append(csv, record.schemaName());
            append(csv, Integer.toString(record.schemaVersion()));
            append(csv, record.requestId());
            append(csv, record.traceId());
            append(csv, record.occurredAt().toString());
            append(csv, record.previousHash());
            append(csv, record.payloadHash());
            append(csv, record.currentHash());
            append(csv, attributes(record.attributes()), true);
        }
        return csv.toString();
    }

    private String attributes(Map<String, String> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to serialize audit attributes", exception);
        }
    }

    private static void append(StringBuilder csv, String value) {
        append(csv, value, false);
    }

    private static void append(StringBuilder csv, String value, boolean last) {
        String normalized = value == null ? "" : value;
        csv.append('"').append(normalized.replace("\"", "\"\"")).append('"');
        csv.append(last ? '\n' : ',');
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey,
        String traceId
    ) {
        return new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId);
    }

    public record IntegrityRequest(
        @NotNull Instant occurredFrom,
        @NotNull Instant occurredTo
    ) {
    }

    public record ExportRequest(
        @Size(max = 256) String operatorId,
        @Size(max = 256) String action,
        @Size(max = 256) String aggregateType,
        @Size(max = 512) String aggregateId,
        @Size(max = 512) String requestId,
        @Size(max = 512) String traceId,
        @NotNull Instant occurredFrom,
        @NotNull Instant occurredTo,
        @NotNull ExportFormat format,
        @Min(1) @Max(10_000) int maxRecords
    ) {
    }
}
