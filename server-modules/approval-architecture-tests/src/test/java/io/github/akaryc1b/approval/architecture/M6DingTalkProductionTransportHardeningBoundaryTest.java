package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class M6DingTalkProductionTransportHardeningBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path ENDPOINT_POLICY = ROOT.resolve(
        "server-modules/approval-connector-dingtalk-http/src/main/java/"
            + "io/github/akaryc1b/approval/connector/dingtalk/http/DingTalkEndpointPolicy.java"
    );
    private static final Path HARDENING_TEST = ROOT.resolve(
        "server-modules/approval-connector-dingtalk-http/src/test/java/"
            + "io/github/akaryc1b/approval/connector/dingtalk/http/"
            + "DingTalkProductionTransportHardeningTest.java"
    );
    private static final Path DOCUMENT = ROOT.resolve(
        "docs/m6/M6_A_DINGTALK_PRODUCTION_TRANSPORT_HARDENING.md"
    );

    @Test
    void endpointPolicyRejectsSpecialPurposeIpv6Families() throws IOException {
        String source = Files.readString(ENDPOINT_POLICY);

        for (String required : new String[] {
            "secondSegment <= 0x01ff",
            "secondSegment == 0x0db8",
            "firstSegment == 0x2002",
            "firstSegment == 0x3ffe",
            "firstSegment != 0x3fff || (secondSegment & 0xf000) != 0"
        }) {
            assertTrue(source.contains(required), "missing IPv6 hardening " + required);
        }
    }

    @Test
    void providerRequestIdCannotRetainCredentialEcho() throws IOException {
        String source = Files.readString(ENDPOINT_POLICY);
        String tests = Files.readString(HARDENING_TEST);

        for (String required : new String[] {
            "redactCredentialEcho",
            "contains(requestIdBytes, accessToken)",
            "containsIgnoreCase(requestId, renderedCredential)",
            "Arrays.fill(requestIdBytes, (byte) 0)"
        }) {
            assertTrue(source.contains(required), "missing credential echo boundary " + required);
        }
        assertTrue(tests.contains("assertNull(response.providerRequestId())"));
        assertTrue(tests.contains("request-test-token%2bone-echo"));
    }

    @Test
    void hardeningDocumentKeepsP3DefaultDisabledAndClosed() throws IOException {
        String document = Files.readString(DOCUMENT);

        for (String required : new String[] {
            "Status: `DINGTALK_PRODUCTION_TRANSPORT_IMPLEMENTED_DEFAULT_DISABLED`",
            "special-purpose IPv6",
            "provider request ID",
            "no Token Acquisition",
            "no Token Refresh",
            "no Tenant Routing",
            "no Persistence",
            "no Worker",
            "no Automatic Retry",
            "no Recovery",
            "no Approval-State Mutation",
            "PR #67 remains Open + Draft"
        }) {
            assertTrue(document.contains(required), "missing P3 hardening text " + required);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                && Files.isDirectory(current.resolve("server-modules"))
                && Files.isDirectory(current.resolve(".github"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("approval-platform repository root was not found");
    }
}
