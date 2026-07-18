package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval/form-packages")
public class ApprovalFormPackageController {

    private static final String TENANT_ID = "X-Tenant-Id";

    private final ApprovalFormDesignService service;

    public ApprovalFormPackageController(ApprovalFormDesignService service) {
        this.service = service;
    }

    @GetMapping("/{formKey}/versions/{packageVersion}")
    public ResponseEntity<FormPackage> find(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable String formKey,
        @PathVariable int packageVersion
    ) {
        return service.findPackage(tenantId, formKey, packageVersion)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
