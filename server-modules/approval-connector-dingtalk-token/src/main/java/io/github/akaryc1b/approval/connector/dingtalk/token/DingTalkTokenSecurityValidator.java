package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.credential.CredentialBindingCatalog;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialAdmission;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

final class DingTalkTokenSecurityValidator {

    private final CredentialBindingCatalog credentialCatalog;
    private final DingTalkTokenRouteGate routeGate;
    private final DingTalkTokenKillSwitch killSwitch;
    private final Runnable requireOpen;
    private final Consumer<String> invalidator;

    DingTalkTokenSecurityValidator(
        CredentialBindingCatalog credentialCatalog,
        DingTalkTokenRouteGate routeGate,
        DingTalkTokenKillSwitch killSwitch,
        Runnable requireOpen,
        Consumer<String> invalidator
    ) {
        this.credentialCatalog = Objects.requireNonNull(
            credentialCatalog,
            "credentialCatalog must not be null"
        );
        this.routeGate = Objects.requireNonNull(routeGate, "routeGate must not be null");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch must not be null");
        this.requireOpen = Objects.requireNonNull(requireOpen, "requireOpen must not be null");
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator must not be null");
    }

    void validate(DingTalkTokenRequest request, Instant evaluatedAt) {
        requireOpen.run();
        validateKillSwitch(request);
        validateRoute(request, evaluatedAt);
        CredentialBindingDescriptor descriptor = descriptor(request);
        validateDescriptorState(request, descriptor, evaluatedAt);
        try {
            CredentialMaterialAdmission.requireAdmitted(
                request.applicationCredentialRequest(),
                descriptor,
                evaluatedAt
            );
        } catch (RuntimeException problem) {
            fail(request, DingTalkTokenFailure.CREDENTIAL_MATERIAL_FAILURE);
        }
    }

    private void validateKillSwitch(DingTalkTokenRequest request) {
        DingTalkTokenKillSwitch.Decision decision;
        try {
            decision = Objects.requireNonNull(
                killSwitch.evaluate(
                    request.trustedTenantId(),
                    request.routePlan().planHash(),
                    request.killSwitchRevision()
                ),
                "kill switch returned null"
            );
        } catch (DingTalkTokenLifecycleException problem) {
            throw problem;
        } catch (RuntimeException problem) {
            fail(request, DingTalkTokenFailure.UNKNOWN);
            return;
        }
        failWhen(
            request,
            !request.killSwitchRevision().equals(decision.revision()),
            DingTalkTokenFailure.KILL_SWITCH_REVISION_DRIFT
        );
        failWhen(
            request,
            !decision.acquisitionAllowed(),
            DingTalkTokenFailure.KILL_SWITCH_DISABLED
        );
    }

    private void validateRoute(DingTalkTokenRequest request, Instant evaluatedAt) {
        DingTalkTokenRouteGate.Result route;
        try {
            route = Objects.requireNonNull(
                routeGate.revalidate(
                    request.trustedTenantId(),
                    request.routePlan(),
                    evaluatedAt
                ),
                "route gate returned null"
            );
        } catch (RuntimeException problem) {
            fail(request, DingTalkTokenFailure.ROUTE_REVALIDATION_FAILED);
            return;
        }
        failWhen(
            request,
            !route.valid(),
            DingTalkTokenFailure.ROUTE_REVALIDATION_FAILED
        );
    }

    private CredentialBindingDescriptor descriptor(DingTalkTokenRequest request) {
        CredentialBindingDescriptor descriptor;
        try {
            descriptor = credentialCatalog.find(
                request.applicationCredentialRequest().credentialReference()
            ).orElse(null);
        } catch (RuntimeException problem) {
            descriptor = null;
        }
        if (descriptor == null) {
            fail(request, DingTalkTokenFailure.CREDENTIAL_NOT_FOUND);
        }
        return descriptor;
    }

    private void validateDescriptorState(
        DingTalkTokenRequest request,
        CredentialBindingDescriptor descriptor,
        Instant evaluatedAt
    ) {
        DingTalkTokenFailure stateFailure = switch (descriptor.state()) {
            case DISABLED -> DingTalkTokenFailure.CREDENTIAL_DISABLED;
            case REVOKED -> DingTalkTokenFailure.CREDENTIAL_REVOKED;
            case NOT_YET_VALID -> DingTalkTokenFailure.CREDENTIAL_NOT_YET_VALID;
            case EXPIRED -> DingTalkTokenFailure.CREDENTIAL_EXPIRED;
            case ROTATION_PENDING -> DingTalkTokenFailure.CREDENTIAL_ROTATION_PENDING;
            case ACTIVE -> DingTalkTokenFailure.NONE;
        };
        failWhen(request, stateFailure != DingTalkTokenFailure.NONE, stateFailure);
        failWhen(
            request,
            !descriptor.providerKey().equals(request.applicationCredentialRequest().providerKey()),
            DingTalkTokenFailure.PROVIDER_MISMATCH
        );
        failWhen(
            request,
            !descriptor.tenantId().equals(request.trustedTenantId()),
            DingTalkTokenFailure.TENANT_MISMATCH
        );
        failWhen(
            request,
            descriptor.credentialType() != CredentialMaterialType.APP_KEY_SECRET,
            DingTalkTokenFailure.CREDENTIAL_MATERIAL_FAILURE
        );
        failWhen(
            request,
            !descriptor.versionId().equals(
                request.applicationCredentialRequest().expectedVersion().versionReference()
            ),
            DingTalkTokenFailure.CREDENTIAL_VERSION_DRIFT
        );
        failWhen(
            request,
            !descriptor.policyVersion().equals(
                request.applicationCredentialRequest().policyRevision()
            ),
            DingTalkTokenFailure.CREDENTIAL_POLICY_DRIFT
        );
        failWhen(
            request,
            !descriptor.fingerprint().equals(
                request.applicationCredentialRequest().credentialBindingHash()
            ),
            DingTalkTokenFailure.CREDENTIAL_BINDING_DRIFT
        );
        failWhen(
            request,
            descriptor.notBefore() != null && evaluatedAt.isBefore(descriptor.notBefore()),
            DingTalkTokenFailure.CREDENTIAL_NOT_YET_VALID
        );
        failWhen(
            request,
            descriptor.expiresAt() != null && !evaluatedAt.isBefore(descriptor.expiresAt()),
            DingTalkTokenFailure.CREDENTIAL_EXPIRED
        );
    }

    private void failWhen(
        DingTalkTokenRequest request,
        boolean condition,
        DingTalkTokenFailure failure
    ) {
        if (condition) {
            fail(request, failure);
        }
    }

    private void fail(DingTalkTokenRequest request, DingTalkTokenFailure failure) {
        invalidator.accept(request.familyHash());
        throw new DingTalkTokenLifecycleException(failure);
    }
}
