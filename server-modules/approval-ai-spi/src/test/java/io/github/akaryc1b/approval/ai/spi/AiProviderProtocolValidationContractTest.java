package io.github.akaryc1b.approval.ai.spi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderProtocolValidationContractTest {

    @Test
    void profileIsStructuredExactAndCannotAuthorizeInvocation() {
        AiVersionReferences.ProviderVersion provider =
            new AiVersionReferences.ProviderVersion("provider-a", "1");
        AiProviderProtocolProfile profile = new AiProviderProtocolProfile(
            "validator-a",
            "1",
            provider,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            "1".repeat(64),
            "2".repeat(64),
            1_024,
            2_048,
            true,
            true,
            false
        );

        assertEquals("validator-a/1", profile.authorizationKey());
        assertTrue(profile.supports(AiCapability.APPROVAL_SUMMARY));
        assertFalse(profile.providerInvocationAllowed());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderProtocolProfile(
                "validator-a",
                "1",
                provider,
                Set.of(AiCapability.APPROVAL_SUMMARY),
                "1".repeat(64),
                "2".repeat(64),
                1_024,
                2_048,
                true,
                true,
                true
            )
        );
    }

    @Test
    void requestAndResultRemainZeroCallAndNonAuthorizing() {
        AiVersionReferences versions = versions();
        AiProviderProtocolValidationRequest request =
            new AiProviderProtocolValidationRequest(
                versions,
                AiCapability.APPROVAL_SUMMARY,
                "egress/1",
                100,
                1_000,
                true,
                false,
                false,
                false
            );
        AiProviderProtocolValidationResult result =
            AiProviderProtocolValidationResult.valid();

        assertFalse(request.providerInvocationAttempted());
        assertFalse(result.providerInvocationAttempted());
        assertFalse(result.productionEnablementAuthorized());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderProtocolValidationResult(
                AiProviderProtocolValidationResult.Status.VALID,
                List.of(),
                false,
                false,
                false,
                true,
                false
            )
        );
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("provider-a", "1"),
            new AiVersionReferences.ModelVersion("provider-a", "model-a", "1"),
            new AiVersionReferences.PromptTemplateVersion("prompt-a", "1", "hash-a"),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion("policy-a", "1", "hash-b"),
            new AiVersionReferences.OutputSchemaVersion("output-a", 1)
        );
    }
}
