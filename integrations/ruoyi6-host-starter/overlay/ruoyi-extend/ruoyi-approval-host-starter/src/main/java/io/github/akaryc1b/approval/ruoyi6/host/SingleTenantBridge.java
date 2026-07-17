package io.github.akaryc1b.approval.ruoyi6.host;

import java.util.Map;
import java.util.Objects;

final class SingleTenantBridge implements Ruoyi6TenantBridge {

    private final ApprovalHostProperties properties;

    SingleTenantBridge(ApprovalHostProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public TenantDescriptor resolve(String requestedTenantId) {
        String configuredTenantId = requireText(properties.getTenantId(), "approval.host.tenant-id");
        if (!configuredTenantId.equals(requestedTenantId)) {
            throw new Ruoyi6HostException(
                403,
                "TENANT_MISMATCH",
                "requested tenant is not available in this RuoYi 6.X host",
                false
            );
        }
        return new TenantDescriptor(
            configuredTenantId,
            requireText(properties.getTenantName(), "approval.host.tenant-name"),
            true,
            Map.of("mode", "single-tenant")
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }
}
