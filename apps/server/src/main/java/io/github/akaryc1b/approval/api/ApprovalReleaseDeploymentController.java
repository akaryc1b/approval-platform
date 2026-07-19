package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService.DeployCommand;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService.DeploymentResult;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/approval/release-packages")
public class ApprovalReleaseDeploymentController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String TRACE_ID = "X-Trace-Id";

    private final ApprovalReleaseDeploymentService service;

    public ApprovalReleaseDeploymentController(
        ApprovalReleaseDeploymentService service
    ) {
        this.service = service;
    }

    @PostMapping("/{definitionKey}/{releaseVersion}/deployment")
    public DeploymentResult deploy(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion,
        @RequestBody DeploymentRequest request
    ) {
        return service.deploy(new DeployCommand(
            new RequestContext(
                tenantId,
                operatorId,
                requestId,
                idempotencyKey,
                traceId
            ),
            definitionKey,
            releaseVersion,
            request.deploymentTarget(),
            request.preflightHash(),
            request.acknowledgedWarningCodes()
        ));
    }

    @GetMapping("/{definitionKey}/{releaseVersion}/deployment")
    public ResponseEntity<ApprovalReleaseDeployment> find(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String definitionKey,
        @PathVariable int releaseVersion
    ) {
        return service.find(tenantId, definitionKey, releaseVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record DeploymentRequest(
        String deploymentTarget,
        String preflightHash,
        List<String> acknowledgedWarningCodes
    ) {
    }
}
