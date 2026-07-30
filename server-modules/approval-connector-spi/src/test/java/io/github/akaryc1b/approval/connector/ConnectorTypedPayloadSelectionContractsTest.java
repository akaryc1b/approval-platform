package io.github.akaryc1b.approval.connector;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationContract;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.BusinessCallbackCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.BusinessCallbackResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.CallbackDeliveryState;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryQuery;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.ExternalTodoCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.ExternalTodoResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.ExternalTodoState;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolutionStatus;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.MessageDeliveryState;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.MessageSendCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.MessageSendResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderBinding;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderRegistry;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelectionRequest;
import io.github.akaryc1b.approval.connector.contract.ConnectorProviderSelectionStatus;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.DeterministicConnectorProviderSelector;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor.ProviderState;
import io.github.akaryc1b.approval.connector.contract.ProviderDescriptor.ProviderType;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import io.github.akaryc1b.approval.connector.testing.DeterministicTypedConnectorPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorTypedPayloadSelectionContractsTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void operationContractsBindClosedOperationsAndExactTypes() {
        assertContract(
            ConnectorOperationPayloads.DIRECTORY_READ,
            ConnectorOperation.ORGANIZATION_READ,
            DirectoryReadCommand.class,
            DirectoryReadResult.class
        );
        assertContract(
            ConnectorOperationPayloads.IDENTITY_RESOLVE,
            ConnectorOperation.IDENTITY_RESOLVE,
            IdentityResolveCommand.class,
            IdentityResolveResult.class
        );
        assertContract(
            ConnectorOperationPayloads.MESSAGE_SEND,
            ConnectorOperation.NOTIFICATION_SEND,
            MessageSendCommand.class,
            MessageSendResult.class
        );
        assertContract(
            ConnectorOperationPayloads.EXTERNAL_TODO_CREATE,
            ConnectorOperation.EXTERNAL_TODO_CREATE,
            ExternalTodoCommand.class,
            ExternalTodoResult.class
        );
        assertContract(
            ConnectorOperationPayloads.EXTERNAL_TODO_UPDATE,
            ConnectorOperation.EXTERNAL_TODO_UPDATE,
            ExternalTodoCommand.class,
            ExternalTodoResult.class
        );
        assertContract(
            ConnectorOperationPayloads.BUSINESS_CALLBACK_DELIVER,
            ConnectorOperation.BUSINESS_CALLBACK_DELIVER,
            BusinessCallbackCommand.class,
            BusinessCallbackResult.class
        );
    }

    @Test
    void directoryPayloadValidatesClosedQueryShapes() {
        ExternalId userId = externalUser("u-1");
        var managerChain = new DirectoryReadCommand(
            DirectoryQuery.MANAGER_CHAIN,
            userId,
            null,
            PageRequest.first(20),
            5,
            Map.of()
        );
        assertTrue(managerChain.canonicalPayloadHash().matches("[0-9a-f]{64}"));

        assertThrows(
            IllegalArgumentException.class,
            () -> new DirectoryReadCommand(
                DirectoryQuery.MANAGER_CHAIN,
                userId,
                null,
                PageRequest.first(20),
                0,
                Map.of()
            )
        );
        assertThrows(
            NullPointerException.class,
            () -> new DirectoryReadCommand(
                DirectoryQuery.ROLE_MEMBERS,
                null,
                null,
                PageRequest.first(20),
                0,
                Map.of()
            )
        );
    }

    @Test
    void canonicalPayloadHashesIgnoreMapAndRecipientOrdering() {
        MessageSendCommand first = message(
            List.of(externalUser("u-2"), externalUser("u-1")),
            Map.of("b", "2", "a", "1")
        );
        MessageSendCommand second = message(
            List.of(externalUser("u-1"), externalUser("u-2")),
            Map.of("a", "1", "b", "2")
        );

        assertEquals(first.canonicalPayloadHash(), second.canonicalPayloadHash());
        assertNotEquals(
            first.canonicalPayloadHash(),
            message(List.of(externalUser("u-1")), Map.of("a", "1")).canonicalPayloadHash()
        );
    }

    @Test
    void payloadMetadataRejectsCredentialLikeKeys() {
        assertThrows(
            IllegalArgumentException.class,
            () -> message(
                List.of(externalUser("u-1")),
                Map.of("accessToken", "must-not-cross")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new BusinessCallbackCommand(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "TASK_COMPLETED",
                "process",
                "instance-1",
                CLOCK.instant(),
                Map.of("authorization", "must-not-cross")
            )
        );
    }

    @Test
    void identityResolutionNeverEstablishesTrustedPlatformIdentity() {
        ExternalId userId = externalUser("u-1");
        var resolved = new IdentityResolveResult(
            IdentityResolutionStatus.RESOLVED,
            userId,
            "mapping-1",
            userSnapshot(userId),
            Map.of()
        );

        assertFalse(resolved.establishesTrustedPlatformIdentity());
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdentityResolveResult(
                IdentityResolutionStatus.NOT_FOUND,
                userId,
                "mapping-1",
                null,
                Map.of()
            )
        );
    }

    @Test
    void todoAndCallbackPayloadsHaveDeterministicEvidence() {
        var todo = new ExternalTodoCommand(
            "task-1",
            externalUser("u-1"),
            "Approve purchase",
            "Review request",
            "/approval/task-1",
            ExternalTodoState.PENDING,
            Instant.parse("2026-07-24T00:00:00Z"),
            Map.of("priority", "normal")
        );
        var callback = new BusinessCallbackCommand(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "TASK_COMPLETED",
            "process",
            "instance-1",
            CLOCK.instant(),
            Map.of("result", "approved")
        );

        assertTrue(todo.canonicalPayloadHash().matches("[0-9a-f]{64}"));
        assertTrue(callback.canonicalPayloadHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void selectionRequiresAnExplicitBoundedAllowList() {
        assertThrows(
            IllegalArgumentException.class,
            () -> selectionRequest(Set.of(), null, "m6-a.v1")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> selectionRequest(Set.of("one"), "two", "m6-a.v1")
        );
    }

    @Test
    void uniqueEligibleProviderIsSelected() {
        ConnectorProviderBinding<MessageSendCommand, MessageSendResult> one =
            messageBinding("one", ProviderState.ENABLED, "m6-a.v1");
        var result = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(one)),
            selectionRequest(Set.of("one"), null, "m6-a.v1")
        );

        assertTrue(result.selected());
        assertEquals("one", result.requireBinding().descriptor().providerKey());
        assertTrue(result.evidence().evidenceHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void multipleEligibleProvidersFailClosedAsAmbiguous() {
        var one = messageBinding("one", ProviderState.ENABLED, "m6-a.v1");
        var two = messageBinding("two", ProviderState.ENABLED, "m6-a.v1");

        var result = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(two, one)),
            selectionRequest(Set.of("two", "one"), null, "m6-a.v1")
        );

        assertEquals(ConnectorProviderSelectionStatus.AMBIGUOUS, result.status());
        assertThrows(IllegalStateException.class, result::requireBinding);
    }

    @Test
    void explicitEligiblePreferredProviderIsSelected() {
        var one = messageBinding("one", ProviderState.ENABLED, "m6-a.v1");
        var two = messageBinding("two", ProviderState.ENABLED, "m6-a.v1");

        var result = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(one, two)),
            selectionRequest(Set.of("one", "two"), "two", "m6-a.v1")
        );

        assertEquals(ConnectorProviderSelectionStatus.SELECTED, result.status());
        assertEquals("two", result.evidence().selectedProviderKey());
    }

    @Test
    void ineligiblePreferredProviderDoesNotFallBack() {
        var one = messageBinding("one", ProviderState.ENABLED, "m6-a.v1");
        var two = messageBinding("two", ProviderState.DISABLED, "m6-a.v1");

        var result = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(one, two)),
            selectionRequest(Set.of("one", "two"), "two", "m6-a.v1")
        );

        assertEquals(
            ConnectorProviderSelectionStatus.PREFERRED_PROVIDER_INELIGIBLE,
            result.status()
        );
        assertEquals(List.of("one"), result.evidence().eligibleProviderKeys());
        assertThrows(IllegalStateException.class, result::requireBinding);
    }

    @Test
    void protocolAndTypeMismatchAreIneligible() {
        var wrongProtocol = messageBinding("one", ProviderState.ENABLED, "m6-a.v2");
        var wrongType = stringMessageBinding("two");

        var result = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(wrongProtocol, wrongType)),
            selectionRequest(Set.of("one", "two"), null, "m6-a.v1")
        );

        assertEquals(ConnectorProviderSelectionStatus.NO_ELIGIBLE_PROVIDER, result.status());
    }

    @Test
    void selectionEvidenceIsStableAcrossRegistrationAndAllowListOrdering() {
        var one = messageBinding("one", ProviderState.ENABLED, "m6-a.v1");
        var two = messageBinding("two", ProviderState.ENABLED, "m6-a.v1");
        var selector = new DeterministicConnectorProviderSelector();

        var first = selector.select(
            new ConnectorProviderRegistry(List.of(one, two)),
            selectionRequest(Set.of("two", "one"), "one", "m6-a.v1")
        );
        var second = selector.select(
            new ConnectorProviderRegistry(List.of(two, one)),
            selectionRequest(Set.of("one", "two"), "one", "m6-a.v1")
        );

        assertEquals(first.evidence().canonicalEvidence(), second.evidence().canonicalEvidence());
        assertEquals(first.evidence().evidenceHash(), second.evidence().evidenceHash());
    }

    @Test
    void selectorPlansOnlyAndInvocationRemainsExplicit() {
        ProviderDescriptor descriptor = descriptor("one", ProviderState.ENABLED, "m6-a.v1");
        var port = new DeterministicTypedConnectorPort<MessageSendCommand, MessageSendResult>(
            descriptor,
            CLOCK,
            command -> new MessageSendResult(
                "message-1",
                MessageDeliveryState.ACCEPTED,
                CLOCK.instant(),
                Map.of()
            )
        );
        var binding = new ConnectorProviderBinding<>(
            descriptor,
            ConnectorOperation.NOTIFICATION_SEND,
            MessageSendCommand.class,
            MessageSendResult.class,
            port,
            null
        );
        var selection = new DeterministicConnectorProviderSelector().select(
            new ConnectorProviderRegistry(List.of(binding)),
            selectionRequest(Set.of("one"), null, "m6-a.v1")
        );

        assertEquals(0, port.invocationCount());

        MessageSendCommand payload = message(List.of(externalUser("u-1")), Map.of());
        ConnectorRequest<MessageSendCommand> request = new ConnectorRequest<>(
            "request-1",
            "trace-1",
            "idem-1",
            ConnectorOperation.NOTIFICATION_SEND,
            payload.canonicalPayloadHash(),
            null,
            payload
        );
        var context = new TrustedConnectorExecutionContext(
            "tenant-1",
            "one",
            new CredentialReference("one", "reference-1"),
            CLOCK.instant()
        );

        MessageSendResult result = selection.requireBinding().execute(context, request).value();

        assertEquals("message-1", result.providerMessageId());
        assertEquals(1, port.invocationCount());
    }

    @Test
    void typedResultContractsRemainBoundedAndClosed() {
        var message = new MessageSendResult(
            "message-1",
            MessageDeliveryState.ACCEPTED,
            CLOCK.instant(),
            Map.of("channel", "test")
        );
        var todo = new ExternalTodoResult(
            "todo-1",
            ExternalTodoState.PENDING,
            CLOCK.instant(),
            Map.of()
        );
        var callback = new BusinessCallbackResult(
            "callback-1",
            CallbackDeliveryState.DELIVERED,
            CLOCK.instant(),
            Map.of()
        );

        assertEquals(MessageDeliveryState.ACCEPTED, message.state());
        assertEquals(ExternalTodoState.PENDING, todo.state());
        assertEquals(CallbackDeliveryState.DELIVERED, callback.state());
    }

    private static <P, R> void assertContract(
        ConnectorOperationContract<P, R> contract,
        ConnectorOperation operation,
        Class<P> requestType,
        Class<R> responseType
    ) {
        assertEquals(operation, contract.operation());
        assertEquals(requestType, contract.requestPayloadType());
        assertEquals(responseType, contract.responseType());
        assertTrue(contract.canonicalValue().contains(contract.contractKey()));
    }

    private static ConnectorProviderSelectionRequest<MessageSendCommand, MessageSendResult>
        selectionRequest(
            Set<String> allowed,
            String preferred,
            String protocol
        ) {
        return new ConnectorProviderSelectionRequest<>(
            ConnectorOperationPayloads.MESSAGE_SEND,
            allowed,
            preferred,
            protocol,
            "policy.v1"
        );
    }

    private static ConnectorProviderBinding<MessageSendCommand, MessageSendResult> messageBinding(
        String providerKey,
        ProviderState state,
        String protocol
    ) {
        ProviderDescriptor descriptor = descriptor(providerKey, state, protocol);
        var port = new DeterministicTypedConnectorPort<MessageSendCommand, MessageSendResult>(
            descriptor,
            CLOCK,
            command -> new MessageSendResult(
                providerKey + "-message",
                MessageDeliveryState.ACCEPTED,
                CLOCK.instant(),
                Map.of()
            )
        );
        return new ConnectorProviderBinding<>(
            descriptor,
            ConnectorOperation.NOTIFICATION_SEND,
            MessageSendCommand.class,
            MessageSendResult.class,
            port,
            null
        );
    }

    private static ConnectorProviderBinding<String, MessageSendResult> stringMessageBinding(
        String providerKey
    ) {
        ProviderDescriptor descriptor = descriptor(
            providerKey,
            ProviderState.ENABLED,
            "m6-a.v1"
        );
        var port = new DeterministicTypedConnectorPort<String, MessageSendResult>(
            descriptor,
            CLOCK,
            ignored -> new MessageSendResult(
                "message",
                MessageDeliveryState.ACCEPTED,
                CLOCK.instant(),
                Map.of()
            )
        );
        return new ConnectorProviderBinding<>(
            descriptor,
            ConnectorOperation.NOTIFICATION_SEND,
            String.class,
            MessageSendResult.class,
            port,
            null
        );
    }

    private static ProviderDescriptor descriptor(
        String providerKey,
        ProviderState state,
        String protocol
    ) {
        return new ProviderDescriptor(
            providerKey,
            ProviderType.TEST,
            protocol,
            Set.of(ConnectorProvider.Capability.NOTIFICATION),
            state,
            Map.of("adapter", "deterministic")
        );
    }

    private static MessageSendCommand message(
        List<ExternalId> recipients,
        Map<String, String> variables
    ) {
        return new MessageSendCommand(
            "approval.card",
            recipients,
            "Approval required",
            "Please review the request",
            "/approval/tasks/1",
            variables
        );
    }

    private static ExternalId externalUser(String value) {
        return new ExternalId("test", "user", value);
    }

    private static UserSnapshot userSnapshot(ExternalId id) {
        return new UserSnapshot(
            id,
            id.value(),
            "Test User",
            null,
            null,
            true,
            List.of(),
            Set.of(),
            Set.of(),
            null,
            Map.of()
        );
    }
}
