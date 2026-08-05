package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ActionDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterDefinition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ParameterType;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.ReauthenticationRequirement;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationActionWhitelist.RiskClassification;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.CreationTrigger;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.IdentifierParameter;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterBinding;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ParameterSource;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.PolicyEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.ProposalStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.SourceAdvisoryEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TargetResourceEvidence;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposal.TextParameter;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.CreationDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationProposalFactory.ProposalCreationRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAutomationProposalFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-05T04:00:00Z");
    private static final UUID PROPOSAL_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID SOURCE_EVIDENCE_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000002"
    );

    @Test
    void explicitServerOwnedRequestCreatesOnlyANonExecutableTypedProposal() {
        ControlledAutomationProposalFactory factory = factory(testWhitelist(), () -> PROPOSAL_ID);

        ControlledAutomationProposalFactory.CreationResult result = factory.create(
            request(CreationTrigger.EXPLICIT_USER_ACTION, validParameters(), NOW.plusSeconds(300))
        );

        assertEquals(CreationDisposition.CREATED, result.disposition());
        ControlledAutomationProposal proposal = result.proposal().orElseThrow();
        assertEquals(PROPOSAL_ID, proposal.proposalId());
        assertEquals("TEST_ONLY_NON_EXECUTABLE_ACTION", proposal.canonicalActionType());
        assertEquals(ProposalStatus.PROPOSED, proposal.status());
        assertEquals(
            ControlledAutomationProposal.Authority.NON_EXECUTABLE_PROPOSAL,
            proposal.authority()
        );
        assertTrue(proposal.requiresHumanConfirmation());
        assertEquals(ReauthenticationRequirement.REQUIRED, proposal.reauthenticationRequirement());
        assertEquals("test-whitelist-v1", proposal.whitelistVersion());
        assertEquals("test-policy-v1", proposal.policy().version());
        assertEquals("TEST_RESOURCE", proposal.targetResource().resourceType());
        assertEquals("PENDING", proposal.targetResource().expectedState());
        assertEquals(7L, proposal.targetResource().expectedVersion());
        assertEquals(1, proposal.parameters().size());
        assertEquals(
            ParameterSource.EXPLICIT_USER_INPUT,
            proposal.parameters().get("acknowledgementCode").source()
        );
        assertNotEquals("tenant-a", proposal.tenantEvidenceHash());
        assertNotEquals("operator-a", proposal.operatorEvidenceHash());
        assertEquals(64, proposal.lineageHash().length());
        assertThrows(
            UnsupportedOperationException.class,
            () -> proposal.parameters().put("other", validParameters().values().iterator().next())
        );
    }

    @Test
    void currentEmptyWhitelistReturnsActionNotWhitelistedWithoutAllocatingProposalId() {
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationProposalFactory factory = factory(
            ControlledAutomationActionWhitelist.empty(
                "EMPTY_PENDING_EXISTING_COMMAND_AUDIT"
            ),
            () -> {
                identifiers.incrementAndGet();
                return PROPOSAL_ID;
            }
        );

        ControlledAutomationProposalFactory.CreationResult result = factory.create(
            request(CreationTrigger.EXPLICIT_USER_ACTION, validParameters(), NOW.plusSeconds(300))
        );

        assertEquals(CreationDisposition.ACTION_NOT_WHITELISTED, result.disposition());
        assertTrue(result.proposal().isEmpty());
        assertEquals(0, identifiers.get());
    }

    @Test
    void pageLoadCallbacksPollingSchedulesAndWebhooksCannotCreateProposal() {
        for (CreationTrigger trigger : CreationTrigger.values()) {
            if (trigger == CreationTrigger.EXPLICIT_USER_ACTION) {
                continue;
            }
            ControlledAutomationProposalFactory.CreationResult result = factory(
                testWhitelist(),
                () -> PROPOSAL_ID
            ).create(request(trigger, validParameters(), NOW.plusSeconds(300)));

            assertEquals(CreationDisposition.TRIGGER_NOT_ALLOWED, result.disposition());
            assertTrue(result.proposal().isEmpty());
        }
    }

    @Test
    void parameterSchemaIsClosedAndCannotBeChangedByAdvisoryContent() {
        Map<String, ParameterBinding> changed = Map.of(
            "otherCode",
            new ParameterBinding(
                "otherCode",
                new IdentifierParameter("ACK-1"),
                ParameterSource.EXPLICIT_USER_INPUT
            )
        );

        ControlledAutomationProposalFactory.CreationResult result = factory(
            testWhitelist(),
            () -> PROPOSAL_ID
        ).create(request(CreationTrigger.EXPLICIT_USER_ACTION, changed, NOW.plusSeconds(300)));

        assertEquals(CreationDisposition.PARAMETER_SCHEMA_MISMATCH, result.disposition());
        assertTrue(result.proposal().isEmpty());
    }

    @Test
    void expiredOrOverlongProposalLifetimeFailsBeforeIdentifierAllocation() {
        AtomicInteger identifiers = new AtomicInteger();
        ControlledAutomationProposalFactory factory = factory(testWhitelist(), () -> {
            identifiers.incrementAndGet();
            return PROPOSAL_ID;
        });

        assertEquals(
            CreationDisposition.EXPIRY_NOT_ALLOWED,
            factory.create(
                request(CreationTrigger.EXPLICIT_USER_ACTION, validParameters(), NOW)
            ).disposition()
        );
        assertEquals(
            CreationDisposition.EXPIRY_NOT_ALLOWED,
            factory.create(
                request(
                    CreationTrigger.EXPLICIT_USER_ACTION,
                    validParameters(),
                    NOW.plusSeconds(901)
                )
            ).disposition()
        );
        assertEquals(0, identifiers.get());
    }

    @Test
    void highRiskDefinitionCannotCreateProposalWithoutSeparateGate() {
        ActionDefinition highRisk = new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            testSchema(),
            RiskClassification.HIGH,
            "Test-only high-risk fixture with no command binding.",
            ReauthenticationRequirement.REQUIRED
        );

        ControlledAutomationProposalFactory.CreationResult result = factory(
            whitelist(highRisk),
            () -> PROPOSAL_ID
        ).create(
            request(CreationTrigger.EXPLICIT_USER_ACTION, validParameters(), NOW.plusSeconds(300))
        );

        assertEquals(CreationDisposition.RISK_NOT_ALLOWED, result.disposition());
        assertTrue(result.proposal().isEmpty());
    }

    @Test
    void executableAndCredentialShapedParametersAreRejectedByClosedTypes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ParameterBinding(
                "targetUrl",
                new TextParameter("https://example.test"),
                ParameterSource.EXPLICIT_USER_INPUT
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TextParameter("select * from ap_approval_instance")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TextParameter("${runtimeExpression}")
        );
    }

    @Test
    void proposalContractContainsNoExecutableLifecycleOrCredentialFields() {
        Set<String> fieldNames = Arrays.stream(ControlledAutomationProposal.class.getDeclaredFields())
            .map(Field::getName)
            .collect(java.util.stream.Collectors.toSet());
        for (String forbidden : Set.of(
            "secret",
            "apiKey",
            "bearerToken",
            "sessionCredential",
            "permissionToken",
            "confirmationToken",
            "url",
            "httpMethod",
            "httpBody",
            "sql",
            "script",
            "javaClassName",
            "dynamicModule",
            "rawPrompt",
            "rawProviderOutput"
        )) {
            assertFalse(fieldNames.contains(forbidden));
        }
        Set<String> statuses = Arrays.stream(ProposalStatus.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toSet());
        assertFalse(statuses.contains("EXECUTING"));
        assertFalse(statuses.contains("EXECUTED"));
        assertFalse(statuses.contains("SUCCEEDED"));
        assertFalse(statuses.contains("FAILED"));
        assertFalse(statuses.contains("UNKNOWN"));
    }

    private static ControlledAutomationProposalFactory factory(
        ControlledAutomationActionWhitelist whitelist,
        java.util.function.Supplier<UUID> identifiers
    ) {
        return new ControlledAutomationProposalFactory(
            whitelist,
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers
        );
    }

    private static ControlledAutomationActionWhitelist testWhitelist() {
        return whitelist(new ActionDefinition(
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            "TEST_RESOURCE",
            testSchema(),
            RiskClassification.LOW,
            "Test-only contract fixture with no business side effect.",
            ReauthenticationRequirement.REQUIRED
        ));
    }

    private static ControlledAutomationActionWhitelist whitelist(ActionDefinition definition) {
        return new ControlledAutomationActionWhitelist() {
            @Override
            public String version() {
                return "test-whitelist-v1";
            }

            @Override
            public Optional<ActionDefinition> resolve(String canonicalActionType) {
                return definition.canonicalActionType().equals(canonicalActionType)
                    ? Optional.of(definition)
                    : Optional.empty();
            }
        };
    }

    private static Map<String, ParameterDefinition> testSchema() {
        ParameterDefinition definition = new ParameterDefinition(
            "acknowledgementCode",
            ParameterType.IDENTIFIER,
            Set.of()
        );
        return Map.of(definition.name(), definition);
    }

    private static Map<String, ParameterBinding> validParameters() {
        ParameterBinding binding = new ParameterBinding(
            "acknowledgementCode",
            new IdentifierParameter("ACK-1"),
            ParameterSource.EXPLICIT_USER_INPUT
        );
        return Map.of(binding.name(), binding);
    }

    private static ProposalCreationRequest request(
        CreationTrigger trigger,
        Map<String, ParameterBinding> parameters,
        Instant expiresAt
    ) {
        return new ProposalCreationRequest(
            new AiServerRequestContext("tenant-a", "operator-a", "request-a", "trace-a"),
            trigger,
            sourceEvidence(),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            parameters,
            TargetResourceEvidence.create(
                "TEST_RESOURCE",
                "b".repeat(64),
                "PENDING",
                7,
                NOW.minusSeconds(1)
            ),
            new PolicyEvidence("test-policy-v1", "c".repeat(64)),
            expiresAt
        );
    }

    private static SourceAdvisoryEvidence sourceEvidence() {
        return new SourceAdvisoryEvidence(
            SOURCE_EVIDENCE_ID,
            "a".repeat(64),
            new AiVersionReferences(
                new AiVersionReferences.ProviderVersion("test-provider", "1"),
                new AiVersionReferences.ModelVersion("test-provider", "test-model", "1"),
                new AiVersionReferences.PromptTemplateVersion(
                    "test-prompt",
                    "1",
                    "prompt-hash"
                ),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                new AiVersionReferences.PolicyVersion("test-policy", "1", "policy-hash"),
                new AiVersionReferences.OutputSchemaVersion("test-schema", 1)
            )
        );
    }
}
