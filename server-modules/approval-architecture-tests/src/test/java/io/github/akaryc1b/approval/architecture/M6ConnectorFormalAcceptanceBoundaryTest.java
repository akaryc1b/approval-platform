package io.github.akaryc1b.approval.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M6ConnectorFormalAcceptanceBoundaryTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path ACCEPTANCE = ROOT.resolve(
        "docs/m6/M6_A_CONNECTOR_FOUNDATION_ACCEPTANCE.md"
    );
    private static final Path CONTRACT_ROOT = ROOT.resolve(
        "server-modules/approval-connector-spi/src/main/java/"
            + "io/github/akaryc1b/approval/connector/contract"
    );

    @Test
    void formalAcceptanceIsAnchoredToPermanentEvidence() throws IOException {
        String document = Files.readString(ACCEPTANCE);

        for (String required : List.of(
            "Status: `FORMALLY_ACCEPTED_CONTRACT_FOUNDATION`",
            "reviewed implementation head: `aa181216aa087b2726829247cacdfd41963a861f`",
            "final successful run: `30058999846` / run #477",
            "Maven artifact ID: `8583893853`",
            "1e67d2d007180602beab5ed2f38d9dc1399c75f9396f475595c55671e6c7d573",
            "Maven reactor: 564 tests, 0 failures, 0 errors, 0 skipped",
            "accumulated focused M6-A tests: 87",
            "connector contract tests: 68",
            "permanent boundary tests: 19"
        )) {
            assertTrue(document.contains(required), "missing acceptance evidence " + required);
        }
    }

    @Test
    void formalAcceptanceRetainsEveryProductionSafetyBlock() throws IOException {
        String document = Files.readString(ACCEPTANCE);

        for (String required : List.of(
            "PR #67 remains Open + Draft after this acceptance",
            "real DingTalk, Feishu or other provider adapters and network transport",
            "production credentials, tokens, secrets, secret-store",
            "connector-owned persistence or schema ownership",
            "any V33 or later Flyway migration",
            "connector workers, leases, schedulers, recovery",
            "automatic execution",
            "automatic retry, including retry of uncertain outcomes",
            "health-based routing, fallback, weighted routing or load balancing",
            "direct approval process-state modification",
            "marking PR #67 Ready",
            "enabling auto-merge",
            "merging or closing PR #67",
            "closing Issues #62, #63, #13 or #14"
        )) {
            assertTrue(document.contains(required), "missing safety block " + required);
        }

        assertFalse(document.contains("production enablement is authorized"));
        assertFalse(document.contains("provider execution is authorized"));
        assertFalse(document.contains("automatic retry is authorized"));
    }

    @Test
    void governanceAcceptanceDoesNotCreateRuntimeAuthorityOrAnotherWorkflow()
        throws IOException {
        String evidence = Files.readString(
            CONTRACT_ROOT.resolve("ConnectorFoundationAcceptanceEvidence.java")
        );

        for (String method : List.of(
            "boolean formalAcceptanceGranted()",
            "boolean productionEnabled()",
            "boolean automaticExecutionEnabled()",
            "boolean automaticRetryEnabled()"
        )) {
            assertTrue(evidence.contains(method), "missing runtime safety method " + method);
        }
        assertTrue(
            evidence.lines().filter(line -> line.trim().equals("return false;")).count() >= 4,
            "runtime evidence must not grant governance or production authority"
        );

        Path workflows = ROOT.resolve(".github/workflows");
        List<Path> workflowFiles;
        try (Stream<Path> stream = Files.list(workflows)) {
            workflowFiles = stream.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, workflowFiles.size(), "only one permanent workflow is allowed");
        assertEquals(
            "approval-platform-validation.yml",
            workflowFiles.getFirst().getFileName().toString()
        );
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
