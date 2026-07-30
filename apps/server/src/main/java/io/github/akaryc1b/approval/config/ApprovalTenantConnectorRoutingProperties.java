package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.TransportProfile;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteSnapshot;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict server-owned startup properties for tenant connector route resolution only.
 */
@ConfigurationProperties(
    prefix = "approval.connector.tenant-routing",
    ignoreUnknownFields = false
)
public final class ApprovalTenantConnectorRoutingProperties {

    private boolean enabled;
    private String configurationVersion;
    private String snapshotHash;
    private List<RouteProperties> routes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfigurationVersion() {
        return configurationVersion;
    }

    public void setConfigurationVersion(String configurationVersion) {
        this.configurationVersion = configurationVersion;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public void setSnapshotHash(String snapshotHash) {
        this.snapshotHash = snapshotHash;
    }

    public List<RouteProperties> getRoutes() {
        return List.copyOf(routes);
    }

    public void setRoutes(List<RouteProperties> routes) {
        this.routes = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
    }

    TenantConnectorRouteSnapshot toSnapshot() {
        List<RouteDefinition> definitions = routes.stream()
            .map(RouteProperties::toDefinition)
            .toList();
        return new TenantConnectorRouteSnapshot(
            configurationVersion,
            definitions,
            snapshotHash
        ).requireValidConfiguration();
    }

    public static final class RouteProperties {

        private String tenantId;
        private String providerKey;
        private String capability;
        private String intent;
        private String apiFamily;
        private String transportProfile;
        private String credentialReference;
        private String credentialMaterialType;
        private String routeVersion;
        private String routePolicyVersion;
        private String credentialPolicyVersion;
        private String credentialDescriptorFingerprint;
        private boolean enabled;
        private String validFrom;
        private String validUntil;
        private String definitionHash;

        RouteDefinition toDefinition() {
            return new RouteDefinition(
                tenantId,
                providerKey,
                parse(ConnectorProvider.Capability.class, capability, "capability"),
                parse(RouteIntent.class, intent, "intent"),
                parse(ProviderApiFamily.class, apiFamily, "apiFamily"),
                parse(TransportProfile.class, transportProfile, "transportProfile"),
                new CredentialReference(providerKey, credentialReference),
                parse(
                    CredentialMaterialType.class,
                    credentialMaterialType,
                    "credentialMaterialType"
                ),
                routeVersion,
                routePolicyVersion,
                credentialPolicyVersion,
                credentialDescriptorFingerprint,
                enabled,
                instant(validFrom, "validFrom"),
                instant(validUntil, "validUntil"),
                definitionHash
            );
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getProviderKey() {
            return providerKey;
        }

        public void setProviderKey(String providerKey) {
            this.providerKey = providerKey;
        }

        public String getCapability() {
            return capability;
        }

        public void setCapability(String capability) {
            this.capability = capability;
        }

        public String getIntent() {
            return intent;
        }

        public void setIntent(String intent) {
            this.intent = intent;
        }

        public String getApiFamily() {
            return apiFamily;
        }

        public void setApiFamily(String apiFamily) {
            this.apiFamily = apiFamily;
        }

        public String getTransportProfile() {
            return transportProfile;
        }

        public void setTransportProfile(String transportProfile) {
            this.transportProfile = transportProfile;
        }

        public String getCredentialReference() {
            return credentialReference;
        }

        public void setCredentialReference(String credentialReference) {
            this.credentialReference = credentialReference;
        }

        public String getCredentialMaterialType() {
            return credentialMaterialType;
        }

        public void setCredentialMaterialType(String credentialMaterialType) {
            this.credentialMaterialType = credentialMaterialType;
        }

        public String getRouteVersion() {
            return routeVersion;
        }

        public void setRouteVersion(String routeVersion) {
            this.routeVersion = routeVersion;
        }

        public String getRoutePolicyVersion() {
            return routePolicyVersion;
        }

        public void setRoutePolicyVersion(String routePolicyVersion) {
            this.routePolicyVersion = routePolicyVersion;
        }

        public String getCredentialPolicyVersion() {
            return credentialPolicyVersion;
        }

        public void setCredentialPolicyVersion(String credentialPolicyVersion) {
            this.credentialPolicyVersion = credentialPolicyVersion;
        }

        public String getCredentialDescriptorFingerprint() {
            return credentialDescriptorFingerprint;
        }

        public void setCredentialDescriptorFingerprint(
            String credentialDescriptorFingerprint
        ) {
            this.credentialDescriptorFingerprint = credentialDescriptorFingerprint;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getValidFrom() {
            return validFrom;
        }

        public void setValidFrom(String validFrom) {
            this.validFrom = validFrom;
        }

        public String getValidUntil() {
            return validUntil;
        }

        public void setValidUntil(String validUntil) {
            this.validUntil = validUntil;
        }

        public String getDefinitionHash() {
            return definitionHash;
        }

        public void setDefinitionHash(String definitionHash) {
            this.definitionHash = definitionHash;
        }

        private static Instant instant(String value, String name) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value.trim());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(name + " must be an RFC 3339 instant", exception);
            }
        }

        private static <E extends Enum<E>> E parse(
            Class<E> type,
            String value,
            String name
        ) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(name + " is unsupported", exception);
            }
        }
    }
}
