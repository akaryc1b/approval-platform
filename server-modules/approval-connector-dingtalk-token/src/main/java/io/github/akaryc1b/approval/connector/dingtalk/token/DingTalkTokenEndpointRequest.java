package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;

import java.util.Objects;

public record DingTalkTokenEndpointRequest(
    String providerKey,
    ProviderApiFamily apiFamily,
    String tenantEvidenceHash,
    String routePlanHash,
    String credentialReferenceHash,
    String credentialVersionReference,
    String tokenPolicyVersion,
    long requestOrdinal,
    String evidenceHash
) {

    public DingTalkTokenEndpointRequest {
        providerKey = DingTalkTokenSupport.identifier(providerKey, "providerKey");
        apiFamily = Objects.requireNonNull(apiFamily, "apiFamily must not be null");
        tenantEvidenceHash = DingTalkTokenSupport.sha256(
            tenantEvidenceHash,
            "tenantEvidenceHash"
        );
        routePlanHash = DingTalkTokenSupport.sha256(routePlanHash, "routePlanHash");
        credentialReferenceHash = DingTalkTokenSupport.sha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        credentialVersionReference = DingTalkTokenSupport.identifier(
            credentialVersionReference,
            "credentialVersionReference"
        );
        tokenPolicyVersion = DingTalkTokenSupport.identifier(
            tokenPolicyVersion,
            "tokenPolicyVersion"
        );
        if (requestOrdinal < 0) {
            throw new IllegalArgumentException("requestOrdinal must not be negative");
        }
        evidenceHash = DingTalkTokenSupport.sha256(evidenceHash, "evidenceHash");
    }

    public static DingTalkTokenEndpointRequest create(
        DingTalkTokenRequest request,
        long requestOrdinal
    ) {
        String computed = DingTalkTokenSupport.hash(
            request.evidenceHash() + "\n" + request.routePlan().apiFamily().name()
                + "\n" + request.applicationCredentialRequest().credentialReferenceHash()
                + "\n" + request.applicationCredentialRequest().expectedVersion().versionReference()
                + "\n" + requestOrdinal
        );
        return new DingTalkTokenEndpointRequest(
            request.routePlan().providerKey(),
            request.routePlan().apiFamily(),
            request.applicationCredentialRequest().tenantHash(),
            request.routePlan().planHash(),
            request.applicationCredentialRequest().credentialReferenceHash(),
            request.applicationCredentialRequest().expectedVersion().versionReference(),
            request.tokenPolicyVersion(),
            requestOrdinal,
            computed
        );
    }
}
