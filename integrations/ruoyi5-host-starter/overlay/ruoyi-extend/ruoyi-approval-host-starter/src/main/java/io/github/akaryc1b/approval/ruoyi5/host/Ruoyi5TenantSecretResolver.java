package io.github.akaryc1b.approval.ruoyi5.host;

import io.github.akaryc1b.approval.host.security.TenantSecretResolver;

import java.util.Optional;

final class Ruoyi5TenantSecretResolver implements TenantSecretResolver {

    private final ApprovalHostProperties properties;

    Ruoyi5TenantSecretResolver(ApprovalHostProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<SecretMaterial> resolve(String tenantKey, String keyId) {
        ApprovalHostProperties.TenantKey tenant = properties.tenant(tenantKey);
        if (tenant == null || tenant.getKeyId() == null || !tenant.getKeyId().equals(keyId)) {
            return Optional.empty();
        }
        return Optional.of(new SecretMaterial(keyId, tenant.secretBytes()));
    }
}
