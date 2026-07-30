package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.credential.CredentialMaterialEnvironment;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialRequest;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.DingTalkCredentialProfile;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RoutePlan;

import java.util.Objects;

public record DingTalkTokenRequest(
    String trustedTenantId,
    RoutePlan routePlan,
    CredentialMaterialRequest applicationCredentialRequest,
    String killSwitchRevision,
    String tokenPolicyVersion
) {

    public DingTalkTokenRequest {
        trustedTenantId = DingTalkTokenSupport.identifier(trustedTenantId, "trustedTenantId");
        routePlan = Objects.requireNonNull(routePlan, "routePlan must not be null");
        applicationCredentialRequest = Objects.requireNonNull(
            applicationCredentialRequest,
            "applicationCredentialRequest must not be null"
        );
        killSwitchRevision = DingTalkTokenSupport.identifier(
            killSwitchRevision,
            "killSwitchRevision"
        );
        tokenPolicyVersion = DingTalkTokenSupport.identifier(
            tokenPolicyVersion,
            "tokenPolicyVersion"
        );
        if (!routePlan.hashMatches()) {
            throw new DingTalkTokenLifecycleException(DingTalkTokenFailure.ROUTE_PLAN_INVALID);
        }
        if (!DingTalkCredentialProfile.PROVIDER_KEY.equals(routePlan.providerKey())
            || !DingTalkCredentialProfile.PROVIDER_KEY.equals(
                applicationCredentialRequest.providerKey()
            )) {
            throw new DingTalkTokenLifecycleException(DingTalkTokenFailure.PROVIDER_MISMATCH);
        }
        if (!trustedTenantId.equals(applicationCredentialRequest.tenantId())) {
            throw new DingTalkTokenLifecycleException(DingTalkTokenFailure.TENANT_MISMATCH);
        }
        if (!routePlan.planHash().equals(applicationCredentialRequest.routePlanHash())
            || routePlan.connectorOperation() != applicationCredentialRequest.operation()
            || !routePlan.transportProfile().name().equals(
                applicationCredentialRequest.protocolProfile()
            )
            || !routePlan.capability().name().equals(applicationCredentialRequest.capability())) {
            throw new DingTalkTokenLifecycleException(DingTalkTokenFailure.ROUTE_PLAN_INVALID);
        }
        if (routePlan.credentialMaterialType() != CredentialMaterialType.ACCESS_TOKEN
            || applicationCredentialRequest.materialType()
                != CredentialMaterialType.APP_KEY_SECRET) {
            throw new DingTalkTokenLifecycleException(
                DingTalkTokenFailure.CREDENTIAL_MATERIAL_FAILURE
            );
        }
        if (applicationCredentialRequest.environment()
            == CredentialMaterialEnvironment.PRODUCTION) {
            throw new DingTalkTokenLifecycleException(
                DingTalkTokenFailure.PRODUCTION_NOT_AUTHORIZED
            );
        }
    }

    public String familyHash() {
        return DingTalkTokenSupport.hash(
            applicationCredentialRequest.tenantHash() + "\n"
                + applicationCredentialRequest.providerKey() + "\n"
                + applicationCredentialRequest.credentialReferenceHash() + "\n"
                + routePlan.routeDefinitionHash() + "\n"
                + tokenPolicyVersion
        );
    }

    public String cacheKeyHash() {
        return DingTalkTokenSupport.hash(
            familyHash() + "\n"
                + routePlan.planHash() + "\n"
                + applicationCredentialRequest.credentialBindingHash() + "\n"
                + applicationCredentialRequest.expectedVersion().versionReference() + "\n"
                + applicationCredentialRequest.expectedVersion().versionEvidenceHash() + "\n"
                + killSwitchRevision
        );
    }

    public String canonicalEvidenceJson() {
        return new StringBuilder(768)
            .append('{')
            .append("\"tenantEvidenceHash\":")
            .append(DingTalkTokenSupport.json(applicationCredentialRequest.tenantHash()))
            .append(",\"routePlanHash\":")
            .append(DingTalkTokenSupport.json(routePlan.planHash()))
            .append(",\"routeDefinitionHash\":")
            .append(DingTalkTokenSupport.json(routePlan.routeDefinitionHash()))
            .append(",\"credentialRequestHash\":")
            .append(DingTalkTokenSupport.json(applicationCredentialRequest.evidenceHash()))
            .append(",\"credentialReferenceHash\":")
            .append(DingTalkTokenSupport.json(
                applicationCredentialRequest.credentialReferenceHash()
            ))
            .append(",\"credentialVersionReference\":")
            .append(DingTalkTokenSupport.json(
                applicationCredentialRequest.expectedVersion().versionReference()
            ))
            .append(",\"killSwitchRevision\":")
            .append(DingTalkTokenSupport.json(killSwitchRevision))
            .append(",\"tokenPolicyVersion\":")
            .append(DingTalkTokenSupport.json(tokenPolicyVersion))
            .append(",\"familyHash\":")
            .append(DingTalkTokenSupport.json(familyHash()))
            .append(",\"cacheKeyHash\":")
            .append(DingTalkTokenSupport.json(cacheKeyHash()))
            .append('}')
            .toString();
    }

    public String evidenceHash() {
        return DingTalkTokenSupport.hash(canonicalEvidenceJson());
    }

    @Override
    public String toString() {
        return "DingTalkTokenRequest[routePlanHash=" + routePlan.planHash()
            + ", evidenceHash=" + evidenceHash() + "]";
    }
}
