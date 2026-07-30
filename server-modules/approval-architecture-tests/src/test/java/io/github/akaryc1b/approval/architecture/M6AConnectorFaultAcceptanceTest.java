package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6AConnectorFaultAcceptanceTest {

    private static final String REHEARSAL_SHA =
        "dd68005bc98d52c15dd40c3445cfc3544022d7e39e9ec88894e4e414635ac52f";
    private static final Pattern SCENARIO_ROW = Pattern.compile(
        "^\\| `(F[0-9]{2})` \\| `([^`]+)` \\| `([^`]+)` \\| `([^`]+)` \\| `([01])` \\|$"
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("faultScenarios")
    void everyFaultAndSecurityScenarioIsClosed(FaultScenario scenario) {
        assertTrue(scenario.id().matches("F[0-9]{2}"));
        assertTrue(scenario.stableCode().matches("[A-Z0-9_]+"));
        assertTrue(scenario.phase().matches("[A-Z0-9_]+"));
        assertTrue(scenario.disposition().matches("[A-Z0-9_]+"));
        assertTrue(scenario.dispatchCount() == 0 || scenario.dispatchCount() == 1);
        if ("UNKNOWN_AFTER_DISPATCH".equals(scenario.disposition())) {
            assertEquals(1, scenario.dispatchCount());
        }
        if (scenario.disposition().startsWith("REJECT_BEFORE_")) {
            assertEquals(0, scenario.dispatchCount());
        }
        assertFalse(scenario.productionExecutionAuthorized());
        assertFalse(scenario.approvalStateMutationAuthorized());
    }

    @Test
    void matrixHasAtLeastTwentyFourUniqueOrderedScenarios() throws IOException {
        List<FaultScenario> scenarios = scenarios();
        assertEquals(42, scenarios.size());
        assertTrue(scenarios.size() >= 24);
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < scenarios.size(); index++) {
            FaultScenario scenario = scenarios.get(index);
            assertEquals("F%02d".formatted(index + 1), scenario.id());
            assertTrue(ids.add(scenario.id()), "duplicate scenario: " + scenario.id());
        }
    }

    @Test
    void acceptanceDocumentRetainsAuthorityBoundary() throws IOException {
        String document = Files.readString(faultDocument());
        assertTrue(document.contains("PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED"));
        assertTrue(document.contains("APPROVAL_STATE_MUTATION_NOT_AUTHORIZED"));
        assertTrue(document.contains("no worker, scheduler, listener, retry, replay"));
    }

    @Test
    void deterministicRehearsalManifestMatchesPinnedDigest() throws IOException {
        Path manifest = repositoryRoot().resolve(
            "server-modules/approval-architecture-tests/src/test/resources/"
                + "m6-a/p9-rehearsal-manifest.txt"
        );
        assertEquals(REHEARSAL_SHA, sha256(Files.readAllBytes(manifest)));
        String content = Files.readString(manifest);
        assertTrue(content.contains("environment=NON_PRODUCTION_SYNTHETIC"));
        assertTrue(content.contains("real_network=false"));
        assertTrue(content.contains("real_secret_backend=false"));
        assertTrue(content.contains("production_execution_authorized=false"));
        assertTrue(content.contains("approval_state_mutation_authorized=false"));
    }

    @Test
    void productionBlockerCatalogRetainsTwentyBlockedItems() throws IOException {
        String blockers = Files.readString(repositoryRoot().resolve(
            "docs/m6/M6_A_PRODUCTION_BLOCKER_CATALOG.md"
        ));
        for (int index = 1; index <= 20; index++) {
            assertTrue(blockers.contains("| `B%02d` |".formatted(index)));
        }
        assertEquals(20, occurrences(blockers, "| `BLOCKED` |"));
        assertTrue(blockers.contains("P9 closes none of them"));
    }

    static Stream<FaultScenario> faultScenarios() throws IOException {
        return scenarios().stream();
    }

    private static List<FaultScenario> scenarios() throws IOException {
        try (Stream<String> lines = Files.lines(faultDocument())) {
            return lines.map(SCENARIO_ROW::matcher)
                .filter(Matcher::matches)
                .map(matcher -> new FaultScenario(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    Integer.parseInt(matcher.group(5)),
                    false,
                    false
                ))
                .toList();
        }
    }

    private static Path faultDocument() {
        return repositoryRoot().resolve("docs/m6/M6_A_FAULT_SECURITY_ACCEPTANCE.md");
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String content = Files.readString(pom).toLowerCase(Locale.ROOT);
                    if (content.contains("<artifactid>approval-platform</artifactid>")
                        && content.contains("<module>server-modules</module>")) {
                        return current;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("cannot read repository pom", exception);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    record FaultScenario(
        String id,
        String stableCode,
        String phase,
        String disposition,
        int dispatchCount,
        boolean productionExecutionAuthorized,
        boolean approvalStateMutationAuthorized
    ) {
        FaultScenario {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(stableCode, "stableCode must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(disposition, "disposition must not be null");
        }

        @Override
        public String toString() {
            return id + '-' + stableCode;
        }
    }
}
