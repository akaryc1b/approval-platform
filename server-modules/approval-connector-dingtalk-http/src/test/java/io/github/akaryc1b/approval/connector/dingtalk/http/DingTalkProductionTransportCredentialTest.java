package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.credential.CapturedCredentialBindingPlan;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingDescriptor;
import io.github.akaryc1b.approval.connector.credential.CredentialBindingState;
import io.github.akaryc1b.approval.connector.credential.CredentialMaterialType;
import io.github.akaryc1b.approval.connector.credential.CredentialResolutionException;
import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;
import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.NOW;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.TOKEN_TEXT;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.clock;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.context;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.descriptor;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.openRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.publicPolicy;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.resolver;
import static io.github.akaryc1b.approval.connector.dingtalk.http.DingTalkProductionTransportTestFixtures.transport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkProductionTransportCredentialTest {

    @Test
    void credentialFailureAndPlanMismatchPreventNetwork() throws Exception {
        var revokedSource = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var revokedSender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkProductionTransport revoked = transport(
            descriptor(CredentialBindingState.REVOKED),
            revokedSource,
            revokedSender,
            publicPolicy()
        );
        assertThrows(
            CredentialResolutionException.class,
            () -> revoked.exchange(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                openRequest()
            )
        );
        assertEquals(0, revokedSender.invocationCount);
        assertEquals(0, revokedSource.openCount);

        CredentialBindingDescriptor active = descriptor(CredentialBindingState.ACTIVE);
        var mismatchSource = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            TOKEN_TEXT.getBytes()
        );
        var mismatchSender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkCredentialPlanSource mismatchPlan = (context, operation) ->
            new CapturedCredentialBindingPlan(
                "dingtalk",
                operation,
                CredentialMaterialType.ACCESS_TOKEN,
                active.keyId(),
                active.versionId(),
                active.referenceHash(),
                "b".repeat(64),
                active.policyVersion()
            );
        DingTalkProductionTransport mismatch = new DingTalkProductionTransport(
            resolver(active, mismatchSource),
            mismatchPlan,
            publicPolicy(),
            mismatchSender,
            clock()
        );
        assertThrows(
            DingTalkTransportPolicyException.class,
            () -> mismatch.exchange(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                openRequest()
            )
        );
        assertEquals(0, mismatchSender.invocationCount);
        assertEquals(0, mismatchSource.secretUseCount);
        assertEquals(1, mismatchSource.closeCount);
    }

    @Test
    void timeoutAndUnknownAreSingleAttemptWithoutRetry() throws Exception {
        CredentialBindingDescriptor active = descriptor(CredentialBindingState.ACTIVE);
        for (DingTalkTransportResponse expected : new DingTalkTransportResponse[] {
            DingTalkTransportResponse.timeout(NOW),
            DingTalkTransportResponse.unknown(NOW)
        }) {
            var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
                TOKEN_TEXT.getBytes()
            );
            var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
            sender.response = expected;
            DingTalkProductionTransport production = transport(
                active,
                source,
                sender,
                publicPolicy()
            );

            DingTalkTransportResponse actual = production.exchange(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                openRequest()
            );

            assertEquals(expected.state(), actual.state());
            assertEquals(1, sender.invocationCount);
            assertEquals(1, source.secretUseCount);
        }
    }

    @Test
    void invalidTokenBytesFailClosedAndNeverRenderMaterial() throws Exception {
        CredentialBindingDescriptor active = descriptor(CredentialBindingState.ACTIVE);
        var source = new DingTalkProductionTransportTestFixtures.FixtureMaterialSource(
            "bad\ntoken".getBytes()
        );
        var sender = new DingTalkProductionTransportTestFixtures.RecordingSender();
        DingTalkProductionTransport production = transport(
            active,
            source,
            sender,
            publicPolicy()
        );

        DingTalkTransportPolicyException failure = assertThrows(
            DingTalkTransportPolicyException.class,
            () -> production.exchange(
                context(),
                ConnectorOperation.ORGANIZATION_READ,
                openRequest()
            )
        );

        assertFalse(failure.getMessage().contains("bad"));
        assertFalse(production.toString().contains("bad"));
        assertEquals(0, sender.invocationCount);
        assertEquals(1, source.secretUseCount);
        assertEquals(1, source.closeCount);
        assertTrue(source.materialZeroized);
    }
}
