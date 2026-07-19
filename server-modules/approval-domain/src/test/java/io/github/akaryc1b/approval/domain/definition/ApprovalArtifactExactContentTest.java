package io.github.akaryc1b.approval.domain.definition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalArtifactExactContentTest {

    private static final String BPMN = "<definitions>\n</definitions>\n";
    private static final String DMN = "<definitions>\n</definitions>\n";
    private static final Instant NOW = Instant.parse("2026-07-19T06:00:00Z");

    @Test
    void preservesCompilerAndReleaseArtifactContentWithoutTrimming() {
        ApprovalCompiledArtifact compiled = new ApprovalCompiledArtifact(
            "tenant-a",
            "approval-a",
            1,
            "1".repeat(64),
            1,
            "2".repeat(64),
            "1.2.0",
            "approval-a.bpmn20.xml",
            BPMN,
            "3".repeat(64),
            "4".repeat(64),
            NOW
        );
        ApprovalReleasePackage release = new ApprovalReleasePackage(
            "tenant-a",
            "approval-a",
            1,
            1,
            "1".repeat(64),
            1,
            "5".repeat(64),
            1,
            "2".repeat(64),
            1,
            "6".repeat(64),
            "1.2.0",
            "approval-a.bpmn20.xml",
            BPMN,
            "3".repeat(64),
            "4".repeat(64),
            DMN,
            "7".repeat(64),
            "8".repeat(64),
            "9".repeat(64),
            new UUID(0, 1),
            "publisher-a",
            NOW
        );

        assertEquals(BPMN, compiled.bpmnXml());
        assertEquals(BPMN, release.bpmnArtifact());
        assertEquals(DMN, release.dmnArtifact());
    }
}
