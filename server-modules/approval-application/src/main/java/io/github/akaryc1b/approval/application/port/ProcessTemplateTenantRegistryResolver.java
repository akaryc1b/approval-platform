package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;

import java.util.Objects;

/** Server-owned resolver for current tenant capabilities used by template imports. */
@FunctionalInterface
public interface ProcessTemplateTenantRegistryResolver {

    TenantRegistrySnapshot resolve(RegistryResolutionRequest request);

    record RegistryResolutionRequest(
        String tenantId,
        TemplatePackage templatePackage,
        PreviewRequest previewRequest
    ) {
        public RegistryResolutionRequest {
            tenantId = requireText(tenantId, "tenantId");
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            previewRequest = Objects.requireNonNull(previewRequest, "previewRequest");
            if (!tenantId.equals(previewRequest.targetTenantId())) {
                throw new IllegalArgumentException(
                    "registry resolution tenant must match preview target tenant"
                );
            }
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
