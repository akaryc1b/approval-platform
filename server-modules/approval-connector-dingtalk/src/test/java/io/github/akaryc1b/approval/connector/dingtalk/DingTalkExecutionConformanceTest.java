package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.USER_ID;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.context;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.detailBody;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.directoryRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.identityRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.request;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.responded;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.userSearchCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkExecutionConformanceTest {

    @Test
    void directoryUserByIdMapsCapturedResponse() {
        DingTalkTestFixtures.CapturingTransport transport = responded(200, detailBody(USER_ID));
        DingTalkDirectoryExecutionPort port = new DingTalkDirectoryExecutionPort(transport);

        ConnectorResult<DirectoryReadResult> result = port.execute(
            context("dingtalk"),
            directoryRequest(USER_ID)
        );

        assertEquals(ConnectorOutcome.SUCCESS, result.outcome());
        assertEquals(1, result.value().entries().size());
        assertEquals("Alice", result.value().entries().getFirst().displayName());
        assertEquals(USER_ID, result.value().entries().getFirst().id().value());
        assertTrue(result.value().entries().getFirst().active());
        assertEquals(1, transport.invocations());
        assertEquals("/topapi/v2/user/get", transport.lastRequest().path());
    }

    @Test
    void identityResolutionMapsCapturedUserSnapshotWithoutTrustElevation() {
        DingTalkTestFixtures.CapturingTransport transport = responded(200, detailBody(USER_ID));
        DingTalkIdentityExecutionPort port = new DingTalkIdentityExecutionPort(transport);

        ConnectorResult<IdentityResolveResult> result = port.execute(
            context("dingtalk"),
            identityRequest(USER_ID, "dingtalk-userid")
        );

        assertEquals(ConnectorOutcome.SUCCESS, result.outcome());
        assertEquals(USER_ID, result.value().userSnapshot().username());
        assertEquals("Alice", result.value().userSnapshot().displayName());
        assertEquals(2, result.value().userSnapshot().departmentIds().size());
        assertEquals("manager1", result.value().userSnapshot().managerId().value());
        assertFalse(result.value().establishesTrustedPlatformIdentity());
    }

    @Test
    void wrongProviderContextIsRejectedBeforeTransport() {
        DingTalkTestFixtures.CapturingTransport transport = responded(200, detailBody(USER_ID));
        DingTalkDirectoryExecutionPort port = new DingTalkDirectoryExecutionPort(transport);

        assertThrows(
            IllegalArgumentException.class,
            () -> port.execute(context("feishu"), directoryRequest(USER_ID))
        );
        assertEquals(0, transport.invocations());
    }

    @Test
    void directorySearchIsNotExecutableInThisSingleCallSlice() {
        DingTalkTestFixtures.CapturingTransport transport = responded(200, detailBody(USER_ID));
        DingTalkDirectoryExecutionPort port = new DingTalkDirectoryExecutionPort(transport);
        DirectoryReadCommand payload = userSearchCommand("Alice", 0, 10);

        assertThrows(
            IllegalArgumentException.class,
            () -> port.execute(
                context("dingtalk"),
                request(
                    ConnectorOperation.ORGANIZATION_READ,
                    payload,
                    payload.canonicalPayloadHash()
                )
            )
        );
        assertEquals(0, transport.invocations());
    }

    @Test
    void unsupportedIdentityNamespaceIsRejectedBeforeTransport() {
        DingTalkTestFixtures.CapturingTransport transport = responded(200, detailBody(USER_ID));
        DingTalkIdentityExecutionPort port = new DingTalkIdentityExecutionPort(transport);

        assertThrows(
            IllegalArgumentException.class,
            () -> port.execute(
                context("dingtalk"),
                identityRequest(USER_ID, "dingtalk-unionid")
            )
        );
        assertEquals(0, transport.invocations());
    }
}
