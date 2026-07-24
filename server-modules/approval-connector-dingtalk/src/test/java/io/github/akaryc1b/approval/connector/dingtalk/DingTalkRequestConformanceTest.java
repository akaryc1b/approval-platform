package io.github.akaryc1b.approval.connector.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.USER_ID;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.identityCommand;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.userByIdCommand;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.userSearchCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkRequestConformanceTest {

    private final DingTalkRequestEncoder encoder = new DingTalkRequestEncoder();

    @Test
    void descriptorIsOfficePlatformWithOnlyReadIdentityCapabilities() {
        ProviderDescriptor descriptor = DingTalkProviderContract.descriptor();

        assertEquals("dingtalk", descriptor.providerKey());
        assertEquals(ProviderDescriptor.ProviderType.OFFICE_PLATFORM, descriptor.providerType());
        assertEquals("dingtalk.contact.transport.v1", descriptor.protocolVersion());
        assertEquals(
            Map.of(
                "directoryQueries", "USER_BY_ID",
                "identityNamespace", "dingtalk-userid",
                "implementationMode", "captured-transport-only",
                "productionNetwork", "false"
            ),
            descriptor.compatibilityMetadata()
        );
        assertEquals(2, descriptor.supportedCapabilities().size());
        assertTrue(
            descriptor.supportedCapabilities().contains(
                ConnectorProvider.Capability.ORGANIZATION
            )
        );
        assertTrue(
            descriptor.supportedCapabilities().contains(
                ConnectorProvider.Capability.AUTHENTICATION
            )
        );
    }

    @Test
    void userSearchEncodingMatchesCurrentDingTalkContactRoute() {
        DingTalkTransportRequest encoded = encoder.encodeDirectory(
            userSearchCommand("engineering", 0, 10)
        );

        assertEquals(DingTalkTransportRequest.ApiFamily.OPEN_API_V1, encoded.apiFamily());
        assertEquals(DingTalkTransportRequest.HttpMethod.POST, encoded.method());
        assertEquals("/v1.0/contact/users/search", encoded.path());
        assertEquals(
            "{\"queryWord\":\"engineering\",\"offset\":0,\"size\":10}",
            encoded.body()
        );
        assertEquals(Map.of("Content-Type", "application/json"), encoded.headers());
    }

    @Test
    void userDetailEncodingMatchesCurrentDingTalkTopApiRoute() {
        DingTalkTransportRequest encoded = encoder.encodeDirectory(
            userByIdCommand(USER_ID)
        );

        assertEquals(DingTalkTransportRequest.ApiFamily.LEGACY_OAPI, encoded.apiFamily());
        assertEquals("/topapi/v2/user/get", encoded.path());
        assertEquals(
            "{\"language\":\"zh_CN\",\"userid\":\"manager4220\"}",
            encoded.body()
        );
    }

    @Test
    void identityEncodingUsesTheSameBoundedUserDetailRoute() {
        DingTalkTransportRequest encoded = encoder.encodeIdentity(
            identityCommand(USER_ID, "dingtalk-userid")
        );

        assertEquals("/topapi/v2/user/get", encoded.path());
        assertEquals(
            "{\"language\":\"zh_CN\",\"userid\":\"manager4220\"}",
            encoded.body()
        );
    }

    @Test
    void capturedRequestContainsNoEndpointOrCredentialMaterial() {
        DingTalkTransportRequest encoded = encoder.encodeDirectory(
            userByIdCommand(USER_ID)
        );

        assertFalse(encoded.credentialMaterialPresent());
        assertFalse(encoded.absoluteEndpointPresent());
        assertTrue(encoded.requiresExternalCredentialBinding());
        assertEquals(64, encoded.requestHash().length());
        assertFalse(encoded.canonicalRequest().contains("api.dingtalk.com"));
        assertFalse(encoded.canonicalRequest().contains("oapi.dingtalk.com"));
        assertFalse(encoded.canonicalRequest().toLowerCase().contains("token"));
    }

    @Test
    void capturedRequestRejectsSensitiveHeaders() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new DingTalkTransportRequest(
                DingTalkTransportRequest.ApiFamily.OPEN_API_V1,
                DingTalkTransportRequest.HttpMethod.POST,
                "/v1.0/contact/users/search",
                Map.of("Authorization", "redacted"),
                "{}",
                Duration.ofSeconds(5)
            )
        );
    }

    @Test
    void searchDecoderParsesIdOnlyResponseWithoutInventingUserDetails() {
        DingTalkUserSearchResult result = new DingTalkResponseDecoder(
            new ObjectMapper()
        ).decodeUserSearch(
            "{\"hasMore\":false,\"totalCount\":2,"
                + "\"list\":[\"user-1\",\"user-2\"]}"
        );

        assertEquals(2, result.userIds().size());
        assertEquals("user-1", result.userIds().getFirst());
        assertFalse(result.hasMore());
        assertEquals(2, result.totalCount());
    }
}
