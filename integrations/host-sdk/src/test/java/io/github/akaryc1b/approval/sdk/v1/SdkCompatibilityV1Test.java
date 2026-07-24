package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkCompatibilityV1Test {
    @Test
    void compatibilityFixtureMatchesTypeScriptContract() throws IOException {
        Map<String, Object> fixture = CompatibilityFixtureSupport.fixture();
        SdkCompatibilityV1.NegotiationResult actual = SdkCompatibilityV1.negotiate(
            client(CompatibilityFixtureSupport.object(fixture, "client")),
            server(CompatibilityFixtureSupport.object(fixture, "server")),
            (String) fixture.get("evaluatedAt")
        );
        Map<String, Object> expected = CompatibilityFixtureSupport.object(fixture, "expectations");

        assertEquals(expected.get("status"), actual.status().name().toLowerCase(Locale.ROOT));
        assertEquals(expected.get("contractVersion"), actual.contractVersion());
        assertEquals(expected.get("eventSchemaVersion"), actual.eventSchemaVersion());
        assertEquals(expected.get("webhookProtocolVersion"), actual.webhookProtocolVersion());
        assertEquals(CompatibilityFixtureSupport.strings(expected, "enabledCapabilities"), actual.enabledCapabilities());
        assertEquals(CompatibilityFixtureSupport.strings(expected, "warnings"), actual.warnings());
        assertTrue(actual.compatible());
    }

    @Test
    void versionSchemaAndProtocolMismatchesFailClosed() throws IOException {
        Map<String, Object> fixture = CompatibilityFixtureSupport.fixture();
        SdkCompatibilityV1.ClientProfile client = client(CompatibilityFixtureSupport.object(fixture, "client"));
        SdkCompatibilityV1.ServerProfile server = server(CompatibilityFixtureSupport.object(fixture, "server"));
        String evaluatedAt = (String) fixture.get("evaluatedAt");

        assertEquals(
            SdkCompatibilityV1.Status.CLIENT_UPGRADE_REQUIRED,
            SdkCompatibilityV1.negotiate(withSdkVersion(client, "0.9.9"), server, evaluatedAt).status()
        );
        assertEquals(
            SdkCompatibilityV1.Status.NO_COMMON_EVENT_SCHEMA,
            SdkCompatibilityV1.negotiate(withEventSchemas(client, List.of("2.0")), server, evaluatedAt).status()
        );
        assertEquals(
            SdkCompatibilityV1.Status.NO_COMMON_WEBHOOK_PROTOCOL,
            SdkCompatibilityV1.negotiate(
                withWebhookProtocols(client, List.of("approval-webhook-v2")),
                server,
                evaluatedAt
            ).status()
        );
    }

    @Test
    void capabilityAvailabilityAndSunsetAreDeterministic() throws IOException {
        Map<String, Object> fixture = CompatibilityFixtureSupport.fixture();
        SdkCompatibilityV1.ClientProfile client = client(CompatibilityFixtureSupport.object(fixture, "client"));
        SdkCompatibilityV1.ServerProfile server = server(CompatibilityFixtureSupport.object(fixture, "server"));
        String evaluatedAt = (String) fixture.get("evaluatedAt");

        assertEquals(
            SdkCompatibilityV1.Status.REQUIRED_CAPABILITY_UNAVAILABLE,
            SdkCompatibilityV1.negotiate(
                withCapabilities(client, List.of(new SdkCompatibilityV1.CapabilityRequest("missing.capability", true))),
                server,
                evaluatedAt
            ).status()
        );
        SdkCompatibilityV1.NegotiationResult optional = SdkCompatibilityV1.negotiate(
            withCapabilities(client, List.of(new SdkCompatibilityV1.CapabilityRequest("missing.capability", false))),
            server,
            evaluatedAt
        );
        assertTrue(optional.compatible());
        assertEquals(List.of(), optional.enabledCapabilities());
        assertEquals(List.of("optional capability unavailable: missing.capability"), optional.warnings());

        SdkCompatibilityV1.ClientProfile requiredLegacy = withCapabilities(
            client,
            List.of(new SdkCompatibilityV1.CapabilityRequest("fixture.legacy-error-shape.v1", true))
        );
        assertEquals(
            SdkCompatibilityV1.Status.REQUIRED_CAPABILITY_SUNSET,
            SdkCompatibilityV1.negotiate(requiredLegacy, server, "2027-01-01T00:00:00Z").status()
        );
    }

    @Test
    void supportWindowAndManifestVersionAreEnforced() throws IOException {
        Map<String, Object> fixture = CompatibilityFixtureSupport.fixture();
        SdkCompatibilityV1.ClientProfile client = client(CompatibilityFixtureSupport.object(fixture, "client"));
        SdkCompatibilityV1.ServerProfile server = server(CompatibilityFixtureSupport.object(fixture, "server"));

        assertEquals(
            SdkCompatibilityV1.Status.CONTRACT_SUPPORT_EXPIRED,
            SdkCompatibilityV1.negotiate(client, server, server.supportedUntil()).status()
        );
        assertThrows(
            SdkCompatibilityV1.UnsupportedManifestVersionException.class,
            () -> new SdkCompatibilityV1.ClientProfile(
                "2",
                client.sdkVersion(),
                client.eventSchemaVersions(),
                client.webhookProtocolVersions(),
                client.capabilities()
            )
        );
    }

    @Test
    void semanticVersionIsStrictAndOrdered() {
        assertEquals("12.3.4", SdkCompatibilityV1.SemanticVersion.parse("12.3.4").toString());
        assertTrue(
            SdkCompatibilityV1.SemanticVersion.parse("1.10.0")
                .compareTo(SdkCompatibilityV1.SemanticVersion.parse("1.9.9")) > 0
        );
        for (String invalid : List.of("1.0", "01.0.0", "1.0.0-beta", "1.0.0.0")) {
            assertThrows(IllegalArgumentException.class, () -> SdkCompatibilityV1.SemanticVersion.parse(invalid));
        }
    }

    @Test
    void compatibilityProfilesContainNoTrustedServerEvidence() {
        List<String> clientFields = Arrays.stream(SdkCompatibilityV1.ClientProfile.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase(Locale.ROOT))
            .toList();
        for (String forbidden : List.of("tenantid", "operator", "permission", "authority", "auditevidence")) {
            assertFalse(clientFields.contains(forbidden));
        }
    }

    private static SdkCompatibilityV1.ClientProfile client(Map<String, Object> value) {
        List<SdkCompatibilityV1.CapabilityRequest> capabilities = CompatibilityFixtureSupport.objects(
            value,
            "capabilities"
        ).stream().map(capability -> new SdkCompatibilityV1.CapabilityRequest(
            (String) capability.get("name"),
            (Boolean) capability.get("required")
        )).toList();
        return new SdkCompatibilityV1.ClientProfile(
            (String) value.get("manifestVersion"),
            (String) value.get("sdkVersion"),
            CompatibilityFixtureSupport.strings(value, "eventSchemaVersions"),
            CompatibilityFixtureSupport.strings(value, "webhookProtocolVersions"),
            capabilities
        );
    }

    private static SdkCompatibilityV1.ServerProfile server(Map<String, Object> value) {
        List<SdkCompatibilityV1.DeprecationNotice> deprecations = CompatibilityFixtureSupport.objects(
            value,
            "deprecations"
        ).stream().map(notice -> new SdkCompatibilityV1.DeprecationNotice(
            (String) notice.get("capability"),
            (String) notice.get("deprecatedSince"),
            (String) notice.get("sunsetAt"),
            (String) notice.get("replacement")
        )).toList();
        return new SdkCompatibilityV1.ServerProfile(
            (String) value.get("manifestVersion"),
            (String) value.get("contractVersion"),
            (String) value.get("minimumClientVersion"),
            (String) value.get("supportedUntil"),
            CompatibilityFixtureSupport.strings(value, "eventSchemaVersions"),
            CompatibilityFixtureSupport.strings(value, "webhookProtocolVersions"),
            CompatibilityFixtureSupport.strings(value, "capabilities"),
            deprecations
        );
    }

    private static SdkCompatibilityV1.ClientProfile withSdkVersion(
        SdkCompatibilityV1.ClientProfile client,
        String version
    ) {
        return new SdkCompatibilityV1.ClientProfile(
            client.manifestVersion(),
            version,
            client.eventSchemaVersions(),
            client.webhookProtocolVersions(),
            client.capabilities()
        );
    }

    private static SdkCompatibilityV1.ClientProfile withEventSchemas(
        SdkCompatibilityV1.ClientProfile client,
        List<String> versions
    ) {
        return new SdkCompatibilityV1.ClientProfile(
            client.manifestVersion(),
            client.sdkVersion(),
            versions,
            client.webhookProtocolVersions(),
            client.capabilities()
        );
    }

    private static SdkCompatibilityV1.ClientProfile withWebhookProtocols(
        SdkCompatibilityV1.ClientProfile client,
        List<String> versions
    ) {
        return new SdkCompatibilityV1.ClientProfile(
            client.manifestVersion(),
            client.sdkVersion(),
            client.eventSchemaVersions(),
            versions,
            client.capabilities()
        );
    }

    private static SdkCompatibilityV1.ClientProfile withCapabilities(
        SdkCompatibilityV1.ClientProfile client,
        List<SdkCompatibilityV1.CapabilityRequest> capabilities
    ) {
        return new SdkCompatibilityV1.ClientProfile(
            client.manifestVersion(),
            client.sdkVersion(),
            client.eventSchemaVersions(),
            client.webhookProtocolVersions(),
            capabilities
        );
    }
}
