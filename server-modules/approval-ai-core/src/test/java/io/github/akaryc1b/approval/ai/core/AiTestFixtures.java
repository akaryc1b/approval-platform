package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.util.List;
import java.util.Set;

final class AiTestFixtures {

    private AiTestFixtures() {
    }

    static AiVersionReferences versions() {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("deterministic-mock", "1.0.0"),
            new AiVersionReferences.ModelVersion(
                "deterministic-mock",
                "mock-model",
                "2026-07-23"
            ),
            new AiVersionReferences.PromptTemplateVersion(
                "test-template",
                "1",
                "test-template-hash"
            ),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "m6-d-first-slice",
                "1",
                "policy-hash"
            ),
            new AiVersionReferences.OutputSchemaVersion(
                "approval.ai.advisory",
                1
            )
        );
    }

    static AiProviderRequest request() {
        AiVersionReferences versions = versions();
        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a"
            ),
            new AiProviderRequest.AuthorizedResource(
                "tenant-a",
                "APPROVAL_TASK",
                "task-a",
                "authorization-a"
            ),
            AiCapability.APPROVAL_SUMMARY,
            Set.of("amount"),
            List.of(new AiProviderRequest.InputField(
                "amount",
                "MONEY",
                "100.00",
                AiProviderRequest.MaskingDisposition.INCLUDED
            )),
            versions,
            Duration.ofMillis(200)
        );
    }

    static AiProviderExecutionPolicy policy(boolean enabled) {
        AiVersionReferences versions = versions();
        return new AiProviderExecutionPolicy(
            enabled,
            Set.of(versions.provider().providerId()),
            Set.of(versions.model().authorizationKey()),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            Duration.ofSeconds(1),
            0.60d
        );
    }
}
