package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.ReviewPlan;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceIncidentReadinessControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-06T02:15:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);

    @Test
    void endpointReturnsNoStoreTenantIncidentReadiness() {
        IncidentReadinessView expected = disabledView("tenant-a", FROM, NOW);
        AtomicReference<Request> captured = new AtomicReference<>();
        var controller = new ControlledAutomationGovernanceIncidentReadinessController(
            (tenant, from, to) -> {
                captured.set(new Request(tenant, from, to));
                return expected;
            }
        );

        var response = controller.incidentReadiness(
            "tenant-a",
            FROM.toString(),
            NOW.toString()
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
        assertEquals(new Request("tenant-a", FROM, NOW), captured.get());
    }

    @Test
    void endpointRejectsNonCanonicalInputsAndDeclaresOnlyGet() throws Exception {
        var controller = new ControlledAutomationGovernanceIncidentReadinessController(
            ControlledAutomationGovernanceIncidentReadinessControllerTest::disabledView
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> controller.incidentReadiness(
                " tenant-a",
                FROM.toString(),
                NOW.toString()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> controller.incidentReadiness(
                "tenant-a",
                "2026-08-06T01:15:00.000Z",
                NOW.toString()
            )
        );
        assertNotNull(
            ControlledAutomationGovernanceIncidentReadinessController.class
                .getDeclaredMethod(
                    "incidentReadiness",
                    String.class,
                    String.class,
                    String.class
                )
                .getAnnotation(GetMapping.class)
        );
        assertFalse(
            Arrays.stream(
                ControlledAutomationGovernanceIncidentReadinessController.class
                    .getDeclaredMethods()
            ).anyMatch(method -> method.getAnnotation(PostMapping.class) != null)
        );
    }

    @Test
    void endpointRequiresTenantScopedManagementReadPermission() {
        ApprovalManagementPermission permission =
            ControlledAutomationGovernanceIncidentReadinessController.class.getAnnotation(
                ApprovalManagementPermission.class
            );

        assertNotNull(permission);
        assertEquals(ApprovalManagementPermission.Requirement.READ, permission.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            permission.resourceScope()
        );
    }

    private static IncidentReadinessView disabledView(
        String tenant,
        Instant from,
        Instant to
    ) {
        OperationsView snapshot = OperationsView.disabled(NOW, inventory());
        return IncidentReadinessView.from(
            snapshot,
            ControlHealthView.disabled(snapshot),
            UsageView.disabled(snapshot),
            HistoryView.from(
                snapshot,
                HistorySummary.empty(new HistoryWindow(tenant, from, to, NOW))
            ),
            ReviewPlan.preview(Operation.ROLLBACK, snapshot)
        );
    }

    private static List<InventoryEntry> inventory() {
        AiVersionReferences.PolicyVersion policy = new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            OpenAiResponsesProtocol.sha256Utf8(
                "approval-assistance-production-policy/p6-e-v1/advisory-only"
            )
        );
        return List.of(
            entry(AiCapability.APPROVAL_SUMMARY, policy),
            entry(AiCapability.MATERIAL_COMPLETENESS, policy),
            entry(AiCapability.RISK_SIGNALS, policy)
        );
    }

    private static InventoryEntry entry(
        AiCapability capability,
        AiVersionReferences.PolicyVersion policy
    ) {
        return new InventoryEntry(
            capability,
            new AiVersionReferences(
                OpenAiResponsesAdvisoryProvider.providerVersion(),
                OpenAiResponsesAdvisoryProvider.modelVersion(),
                OpenAiResponsesAdvisoryProvider.promptVersion(capability),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                policy,
                OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
            )
        );
    }

    private record Request(String tenantId, Instant fromInclusive, Instant toExclusive) {
    }
}
