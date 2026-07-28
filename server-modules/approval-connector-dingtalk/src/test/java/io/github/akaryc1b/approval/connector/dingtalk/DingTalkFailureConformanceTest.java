package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOutcome;
import io.github.akaryc1b.approval.connector.contract.ConnectorResult;
import io.github.akaryc1b.approval.connector.contract.RetryDisposition;
import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.NOW;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.USER_ID;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.context;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.detailBody;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.directoryRequest;
import static io.github.akaryc1b.approval.connector.dingtalk.DingTalkTestFixtures.responded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DingTalkFailureConformanceTest {

    @Test
    void timeoutRequiresReconciliationBeforeRetry() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            new DingTalkTestFixtures.CapturingTransport(
                DingTalkTransportResponse.timeout(NOW)
            )
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.TIMEOUT, result.outcome());
        assertEquals(RetryDisposition.RECONCILE_BEFORE_RETRY, result.retryDisposition());
    }

    @Test
    void unknownTransportOutcomeRequiresReconciliationBeforeRetry() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            new DingTalkTestFixtures.CapturingTransport(
                DingTalkTransportResponse.unknown(NOW)
            )
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.UNKNOWN, result.outcome());
        assertEquals(RetryDisposition.RECONCILE_BEFORE_RETRY, result.retryDisposition());
    }

    @Test
    void http429IsRateLimitedWithoutAutomaticRetry() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(429, "{\"message\":\"limited\"}")
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.RATE_LIMITED, result.outcome());
        assertEquals(RetryDisposition.RETRY_WITH_BACKOFF, result.retryDisposition());
    }

    @Test
    void http503IsRetryableProviderFailure() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(503, "{\"message\":\"unavailable\"}")
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.RETRYABLE_PROVIDER_FAILURE, result.outcome());
    }

    @Test
    void http400IsPermanentProviderFailure() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(400, "{\"message\":\"bad request\"}")
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.PERMANENT_PROVIDER_FAILURE, result.outcome());
        assertEquals(RetryDisposition.DO_NOT_RETRY, result.retryDisposition());
    }

    @Test
    void providerErrcodeIsRejectedWithoutResponseLeakage() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(200, "{\"errcode\":40014,\"errmsg\":\"invalid access\"}")
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.REJECTED, result.outcome());
        assertEquals("DINGTALK_PROVIDER_REJECTED", result.error().code());
        assertEquals("40014", result.error().details().get("providerCode"));
        assertNull(result.value());
    }

    @Test
    void malformedSuccessResponseIsUnknown() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(200, "{\"errcode\":0,\"result\":{}}")
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.UNKNOWN, result.outcome());
        assertEquals(RetryDisposition.RECONCILE_BEFORE_RETRY, result.retryDisposition());
        assertEquals("DINGTALK_RESPONSE_INVALID", result.error().code());
    }

    @Test
    void mismatchedUserIdIsUnknown() {
        ConnectorResult<DirectoryReadResult> result = new DingTalkDirectoryExecutionPort(
            responded(200, detailBody("another-user"))
        ).execute(context("dingtalk"), directoryRequest(USER_ID));

        assertEquals(ConnectorOutcome.UNKNOWN, result.outcome());
        assertEquals("DINGTALK_RESPONSE_ID_MISMATCH", result.error().code());
    }
}
