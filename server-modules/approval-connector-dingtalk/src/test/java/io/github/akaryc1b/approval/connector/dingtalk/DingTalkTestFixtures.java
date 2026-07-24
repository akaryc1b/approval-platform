package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryQuery;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorRequest;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;
import io.github.akaryc1b.approval.connector.contract.TrustedConnectorExecutionContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.PageRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class DingTalkTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-24T03:15:00Z");
    static final String USER_ID = "manager4220";

    private DingTalkTestFixtures() {
    }

    static TrustedConnectorExecutionContext context(String providerKey) {
        return new TrustedConnectorExecutionContext(
            "tenant-a",
            providerKey,
            new CredentialReference(providerKey, "credential-ref-a"),
            NOW
        );
    }

    static ConnectorRequest<DirectoryReadCommand> directoryRequest(String userId) {
        DirectoryReadCommand payload = userByIdCommand(userId);
        return request(
            ConnectorOperation.ORGANIZATION_READ,
            payload,
            payload.canonicalPayloadHash()
        );
    }

    static ConnectorRequest<IdentityResolveCommand> identityRequest(
        String userId,
        String namespace
    ) {
        IdentityResolveCommand payload = identityCommand(userId, namespace);
        return request(
            ConnectorOperation.IDENTITY_RESOLVE,
            payload,
            payload.canonicalPayloadHash()
        );
    }

    static <P> ConnectorRequest<P> request(
        ConnectorOperation operation,
        P payload,
        String payloadHash
    ) {
        return new ConnectorRequest<>(
            "request-a",
            "trace-a",
            "idempotency-a",
            operation,
            payloadHash,
            null,
            payload
        );
    }

    static DirectoryReadCommand userByIdCommand(String userId) {
        return new DirectoryReadCommand(
            DirectoryQuery.USER_BY_ID,
            DingTalkProviderContract.userId(userId),
            null,
            PageRequest.first(10),
            0,
            Map.of()
        );
    }

    static DirectoryReadCommand userSearchCommand(
        String query,
        int page,
        int size
    ) {
        return new DirectoryReadCommand(
            DirectoryQuery.USER_SEARCH,
            null,
            query,
            new PageRequest(page, size, null),
            0,
            Map.of()
        );
    }

    static IdentityResolveCommand identityCommand(
        String userId,
        String namespace
    ) {
        return new IdentityResolveCommand(
            new ExternalId("dingtalk", "user", userId),
            namespace,
            Map.of()
        );
    }

    static CapturingTransport responded(int statusCode, String body) {
        return new CapturingTransport(
            DingTalkTransportResponse.responded(
                statusCode,
                "dingtalk-request-a",
                body,
                NOW
            )
        );
    }

    static String detailBody(String userId) {
        return "{"
            + "\"request_id\":\"dingtalk-request-a\","
            + "\"errcode\":0,"
            + "\"errmsg\":\"ok\","
            + "\"result\":{"
            + "\"userid\":\"" + userId + "\","
            + "\"unionid\":\"union-a\","
            + "\"name\":\"Alice\","
            + "\"email\":\"alice@example.com\","
            + "\"mobile\":\"13800000000\","
            + "\"active\":true,"
            + "\"manager_userid\":\"manager1\","
            + "\"dept_id_list\":[1,2],"
            + "\"title\":\"Engineer\","
            + "\"job_number\":\"E-001\""
            + "}}";
    }

    static final class CapturingTransport implements DingTalkTransport {

        private final DingTalkTransportResponse response;
        private final AtomicInteger invocations = new AtomicInteger();
        private DingTalkTransportRequest lastRequest;

        CapturingTransport(DingTalkTransportResponse response) {
            this.response = Objects.requireNonNull(
                response,
                "response must not be null"
            );
        }

        @Override
        public DingTalkTransportResponse exchange(DingTalkTransportRequest request) {
            lastRequest = Objects.requireNonNull(
                request,
                "request must not be null"
            );
            invocations.incrementAndGet();
            return response;
        }

        int invocations() {
            return invocations.get();
        }

        DingTalkTransportRequest lastRequest() {
            return lastRequest;
        }
    }
}
