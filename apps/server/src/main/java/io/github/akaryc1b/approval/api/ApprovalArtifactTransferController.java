package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/approval/artifact-transfer")
public class ApprovalArtifactTransferController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalArtifactTransferService service;
    private final ApprovalArtifactTransferJsonCodec codec;

    public ApprovalArtifactTransferController(
        ApprovalArtifactTransferService service,
        ApprovalArtifactTransferJsonCodec codec
    ) {
        this.service = service;
        this.codec = codec;
    }

    @GetMapping("/definition-exports/{definitionKey}/{definitionVersion}")
    public TransferEnvelope exportDefinition(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @PathVariable int definitionVersion
    ) {
        return service.exportDefinition(tenantId, definitionKey, definitionVersion);
    }

    @GetMapping("/release-exports/{definitionKey}/{releaseVersion}")
    public TransferEnvelope exportRelease(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion
    ) {
        return service.exportRelease(tenantId, definitionKey, releaseVersion);
    }

    @PostMapping(
        path = "/imports",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ImportResult> importArtifact(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody byte[] body
    ) {
        var request = codec.decodeImport(body);
        ImportResult result = service.importArtifact(new ImportCommand(
            new RequestContext(tenantId, operatorId, requestId, idempotencyKey, traceId),
            request.envelope(),
            request.targetDefinitionKey(),
            request.targetDefinitionVersion(),
            request.targetFormPackageVersion(),
            request.targetName()
        ));
        return ResponseEntity.created(URI.create(
            "/api/approval/process-design-drafts/" + result.draftId()
        )).eTag("\"revision-" + result.revision() + "\"").body(result);
    }
}
