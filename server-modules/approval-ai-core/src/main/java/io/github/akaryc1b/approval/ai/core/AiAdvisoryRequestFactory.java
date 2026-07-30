package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds provider requests from trusted server context and an authorized resource. */
public final class AiAdvisoryRequestFactory {

    private final AiDataMinimizer minimizer;

    public AiAdvisoryRequestFactory(AiDataMinimizer minimizer) {
        this.minimizer = Objects.requireNonNull(minimizer, "minimizer must not be null");
    }

    public AiProviderRequest create(
        AiAdvisoryIntent intent,
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        List<AiSourceField> fields,
        AiVersionReferences versions,
        AiDataMinimizationPolicy policy,
        Duration timeout
    ) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(versions, "versions must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (!context.tenantId().equals(resource.tenantId())) {
            throw new AiPolicyViolationException(
                "AI_CROSS_TENANT_RESOURCE",
                "authorized resource tenant does not match request tenant"
            );
        }
        if (!intent.resourceId().equals(resource.resourceId())) {
            throw new AiPolicyViolationException(
                "AI_RESOURCE_FORGERY",
                "untrusted resource identity does not match authorized resource"
            );
        }
        if (!versions.policy().equals(policy.version())) {
            throw new AiPolicyViolationException(
                "AI_POLICY_VERSION_MISMATCH",
                "request policy version does not match the authoritative policy"
            );
        }

        List<AiProviderRequest.InputField> minimized = minimizer.minimize(
            resource.allowedFieldKeys(),
            fields == null ? List.of() : fields,
            policy
        );
        Set<String> includedFieldKeys = minimized.stream()
            .map(AiProviderRequest.InputField::key)
            .collect(Collectors.toUnmodifiableSet());

        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                context.tenantId(),
                context.operatorId(),
                context.requestId(),
                context.traceId()
            ),
            new AiProviderRequest.AuthorizedResource(
                resource.tenantId(),
                resource.resourceType().name(),
                resource.resourceId(),
                resource.authorizationReference()
            ),
            intent.capability(),
            includedFieldKeys,
            minimized,
            versions,
            timeout
        );
    }
}
