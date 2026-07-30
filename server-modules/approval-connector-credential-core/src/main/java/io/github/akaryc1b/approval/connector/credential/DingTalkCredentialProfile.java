package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;

import java.util.Objects;

public final class DingTalkCredentialProfile {

    public static final String PROVIDER_KEY = "dingtalk";

    private DingTalkCredentialProfile() {
    }

    public static CredentialMaterialType requiredMaterialType(ConnectorOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return switch (operation) {
            case ORGANIZATION_READ, IDENTITY_RESOLVE -> CredentialMaterialType.ACCESS_TOKEN;
            default -> throw new IllegalArgumentException(
                "DingTalk credential profile does not allow operation " + operation.name()
            );
        };
    }

    public static CapturedCredentialBindingPlan plan(
        CredentialBindingDescriptor descriptor,
        ConnectorOperation operation
    ) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        CredentialMaterialType requiredType = requiredMaterialType(operation);
        if (!PROVIDER_KEY.equals(descriptor.providerKey())) {
            throw new IllegalArgumentException("credential binding is not owned by DingTalk");
        }
        if (descriptor.credentialType() != requiredType) {
            throw new IllegalArgumentException("credential material type does not match DingTalk profile");
        }
        if (!descriptor.allowedOperations().contains(operation)) {
            throw new IllegalArgumentException("operation is not allowed by credential binding");
        }
        return new CapturedCredentialBindingPlan(
            descriptor.providerKey(),
            operation,
            descriptor.credentialType(),
            descriptor.keyId(),
            descriptor.versionId(),
            descriptor.referenceHash(),
            descriptor.fingerprint(),
            descriptor.policyVersion()
        );
    }
}
