package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ApprovalManagementPermission.Requirement;
import io.github.akaryc1b.approval.security.ApprovalEnterpriseRole;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import io.github.akaryc1b.approval.security.ApprovalResourceScope;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityAssignment;
import io.github.akaryc1b.approval.security.ApprovalResponsibilitySourceType;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlledAutomationGovernanceAuthorizationAdversarialTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:30:00Z");
    private static final String SNAPSHOT_PATH =
        "/api/approval/management/ai-governance/snapshot";

    @Test
    void missingReadPermissionNeverReachesGovernanceSource() throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        MockMvc mvc = mvc(sourceCalls);
        ApprovalPrincipal participant = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            null
        );

        mvc.perform(get(SNAPSHOT_PATH)
                .header("X-Tenant-Id", "tenant-a")
                .principal(participant))
            .andExpect(status().isForbidden());

        assertEquals(0, sourceCalls.get());
    }

    @Test
    void departmentScopedResponsibilityCannotReadTenantGovernance() throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        MockMvc mvc = mvc(sourceCalls);
        ApprovalPrincipal departmentAdministrator = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(),
            Set.of(new ApprovalResponsibilityAssignment(
                ApprovalEnterpriseRole.DEPARTMENT_APPROVAL_ADMIN,
                ApprovalResponsibilitySourceType.ROLE,
                "role-department-approval-admin",
                ApprovalResourceScope.departments(Set.of("department-a"))
            )),
            null
        );

        mvc.perform(get(SNAPSHOT_PATH)
                .header("X-Tenant-Id", "tenant-a")
                .principal(departmentAdministrator))
            .andExpect(status().isForbidden());

        assertEquals(0, sourceCalls.get());
    }

    @Test
    void exactTenantReadAuthorityAllowsOneReadOnlySourceCall() throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        MockMvc mvc = mvc(sourceCalls);
        ApprovalPrincipal reader = ApprovalPrincipal.active(
            "tenant-a",
            "operator-a",
            Set.of(Requirement.READ.authority()),
            null
        );

        mvc.perform(get(SNAPSHOT_PATH)
                .header("X-Tenant-Id", "tenant-a")
                .principal(reader))
            .andExpect(status().isOk());

        assertEquals(1, sourceCalls.get());
    }

    private static MockMvc mvc(AtomicInteger sourceCalls) {
        ControlledAutomationGovernanceReadController controller =
            new ControlledAutomationGovernanceReadController(() -> {
                sourceCalls.incrementAndGet();
                return OperationsView.disabled(NOW, inventory());
            });
        ApprovalManagementPermissionInterceptor interceptor =
            new ApprovalManagementPermissionInterceptor(
                true,
                new DefaultApprovalResponsibilityResolver(
                    Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                new SimpleMeterRegistry()
            );
        return MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(interceptor)
            .setControllerAdvice(new ApprovalManagementSecurityApiExceptionHandler())
            .build();
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
}
