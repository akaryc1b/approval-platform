package io.github.akaryc1b.approval.ai.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiExternalSecretResolutionContractTest {

    @Test
    void requestRejectsSecretAndNetworkAuthority() {
        AiVersionReferences.ProviderVersion provider = provider();
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiExternalSecretResolutionRequest(
                provider,
                "provider-auth",
                "v1",
                AiExternalSecretResolutionRequest.Purpose.PROVIDER_AUTHENTICATION,
                "1".repeat(64),
                true,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiExternalSecretResolutionRequest(
                provider,
                "provider-auth",
                "v1",
                AiExternalSecretResolutionRequest.Purpose.PROVIDER_AUTHENTICATION,
                "1".repeat(64),
                false,
                true
            )
        );
    }

    @Test
    void metadataOnlyResolverReturnsNoMaterialOrAuthority() {
        AiExternalSecretResolver resolver = request -> new AiExternalSecretResolutionResult(
            request.providerVersion(),
            request.referenceAuthorizationKey(),
            request.purpose(),
            AiExternalSecretResolutionResult.Status.REFERENCE_AVAILABLE,
            AiExternalSecretResolutionResult.RotationState.CURRENT,
            "2".repeat(64),
            false,
            false,
            false,
            false,
            false
        );
        AiExternalSecretResolutionResult result = resolver.inspectReference(request());

        assertEquals(
            AiExternalSecretResolutionResult.Status.REFERENCE_AVAILABLE,
            result.status()
        );
        assertFalse(result.secretMaterialReturned());
        assertFalse(result.secretResolutionPerformed());
        assertFalse(result.networkCallAttempted());
        assertFalse(result.productionEnablementAuthorized());
    }

    @Test
    void resultRejectsMaterialResolutionAndProductionAuthority() {
        AiExternalSecretResolutionRequest request = request();
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiExternalSecretResolutionResult(
                request.providerVersion(),
                request.referenceAuthorizationKey(),
                request.purpose(),
                AiExternalSecretResolutionResult.Status.REFERENCE_AVAILABLE,
                AiExternalSecretResolutionResult.RotationState.CURRENT,
                "2".repeat(64),
                true,
                false,
                false,
                false,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiExternalSecretResolutionResult(
                request.providerVersion(),
                request.referenceAuthorizationKey(),
                request.purpose(),
                AiExternalSecretResolutionResult.Status.REFERENCE_AVAILABLE,
                AiExternalSecretResolutionResult.RotationState.CURRENT,
                "2".repeat(64),
                false,
                false,
                false,
                false,
                true
            )
        );
    }

    private static AiExternalSecretResolutionRequest request() {
        return new AiExternalSecretResolutionRequest(
            provider(),
            "provider-auth",
            "v1",
            AiExternalSecretResolutionRequest.Purpose.PROVIDER_AUTHENTICATION,
            "1".repeat(64),
            false,
            false
        );
    }

    private static AiVersionReferences.ProviderVersion provider() {
        return new AiVersionReferences.ProviderVersion("provider-a", "1");
    }
}
