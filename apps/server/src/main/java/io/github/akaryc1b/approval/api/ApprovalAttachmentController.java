package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService.DownloadPayload;
import io.github.akaryc1b.approval.application.ApprovalAttachmentService.UploadCommand;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/attachments")
public class ApprovalAttachmentController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalAttachmentService service;

    public ApprovalAttachmentController(ApprovalAttachmentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentSummary upload(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestPart("file") MultipartFile file
    ) throws IOException {
        if (file.getSize() > ApprovalAttachmentService.MAX_FILE_SIZE) {
            throw new IllegalArgumentException("attachment must not exceed 10 MiB");
        }
        return service.upload(new UploadCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes()
        ));
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<AttachmentSummary> metadata(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID attachmentId
    ) {
        return service.findMetadata(tenantId, operatorId, attachmentId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> download(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID attachmentId
    ) {
        return service.download(tenantId, operatorId, attachmentId)
            .map(this::downloadResponse)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> downloadResponse(DownloadPayload payload) {
        AttachmentSummary metadata = payload.metadata();
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(metadata.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(metadata.contentType()))
            .contentLength(metadata.sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-SHA256", metadata.sha256())
            .body(payload.content());
    }
}
