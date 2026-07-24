package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed provider metadata for the captured DingTalk transport conformance slice.
 */
public final class DingTalkProviderContract {

    public static final String PROVIDER_KEY = "dingtalk";
    public static final String PROTOCOL_VERSION = "dingtalk.contact.transport.v1";
    public static final String IDENTITY_NAMESPACE = "dingtalk-userid";
    public static final String EXTERNAL_SOURCE = "dingtalk";
    public static final String USER_OBJECT_TYPE = "user";
    public static final String DEPARTMENT_OBJECT_TYPE = "department";

    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(
        PROVIDER_KEY,
        ProviderDescriptor.ProviderType.OFFICE_PLATFORM,
        PROTOCOL_VERSION,
        Set.of(
            ConnectorProvider.Capability.ORGANIZATION,
            ConnectorProvider.Capability.AUTHENTICATION
        ),
        ProviderDescriptor.ProviderState.ENABLED,
        Map.of(
            "directoryQueries", "USER_BY_ID",
            "identityNamespace", IDENTITY_NAMESPACE,
            "implementationMode", "captured-transport-only",
            "productionNetwork", "false"
        )
    );

    private DingTalkProviderContract() {
    }

    public static ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    public static void requireContext(TrustedConnectorExecutionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!PROVIDER_KEY.equals(context.providerKey())) {
            throw new IllegalArgumentException("trusted context targets another provider");
        }
    }

    public static ExternalId requireUserId(ExternalId id) {
        Objects.requireNonNull(id, "user ID must not be null");
        if (!EXTERNAL_SOURCE.equals(id.source()) || !USER_OBJECT_TYPE.equals(id.objectType())) {
            throw new IllegalArgumentException("DingTalk user ID must use dingtalk:user");
        }
        return id;
    }

    public static ExternalId userId(String value) {
        return new ExternalId(EXTERNAL_SOURCE, USER_OBJECT_TYPE, value);
    }

    public static ExternalId departmentId(String value) {
        return new ExternalId(EXTERNAL_SOURCE, DEPARTMENT_OBJECT_TYPE, value);
    }
}
