package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryQuery;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadCommand;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveCommand;
import io.github.akaryc1b.approval.connector.model.ExternalId;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic request mapping for the DingTalk contact APIs covered by M6-A-P1.
 */
public final class DingTalkRequestEncoder {

    public static final String USER_SEARCH_PATH = "/v1.0/contact/users/search";
    public static final String USER_DETAIL_PATH = "/topapi/v2/user/get";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public DingTalkTransportRequest encodeDirectory(DirectoryReadCommand command) {
        return encodeDirectory(command, DEFAULT_TIMEOUT);
    }

    public DingTalkTransportRequest encodeDirectory(
        DirectoryReadCommand command,
        Duration timeout
    ) {
        Objects.requireNonNull(command, "command must not be null");
        return switch (command.query()) {
            case USER_BY_ID -> encodeUserDetail(command.subjectId(), timeout);
            case USER_SEARCH -> encodeUserSearch(command, timeout);
            default -> throw new IllegalArgumentException(
                "DingTalk directory query is outside this transport slice: " + command.query()
            );
        };
    }

    public DingTalkTransportRequest encodeIdentity(IdentityResolveCommand command) {
        return encodeIdentity(command, DEFAULT_TIMEOUT);
    }

    public DingTalkTransportRequest encodeIdentity(
        IdentityResolveCommand command,
        Duration timeout
    ) {
        Objects.requireNonNull(command, "command must not be null");
        if (!DingTalkProviderContract.IDENTITY_NAMESPACE.equals(command.mappingNamespace())) {
            throw new IllegalArgumentException("unsupported DingTalk identity namespace");
        }
        return encodeUserDetail(command.externalUserId(), timeout);
    }

    public DingTalkTransportRequest encodeUserSearch(
        DirectoryReadCommand command,
        Duration timeout
    ) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.query() != DirectoryQuery.USER_SEARCH) {
            throw new IllegalArgumentException("command is not a user search");
        }
        String queryWord = requireText(command.queryValue(), "queryValue", 256);
        if (command.page().size() > 20) {
            throw new IllegalArgumentException("DingTalk user-search size must not exceed 20");
        }
        int offset;
        try {
            offset = Math.multiplyExact(command.page().page(), command.page().size());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("DingTalk user-search offset overflow", exception);
        }
        String body = "{\"queryWord\":" + json(queryWord)
            + ",\"offset\":" + offset
            + ",\"size\":" + command.page().size()
            + "}";
        return request(
            DingTalkTransportRequest.ApiFamily.OPEN_API_V1,
            USER_SEARCH_PATH,
            body,
            timeout
        );
    }

    private static DingTalkTransportRequest encodeUserDetail(
        ExternalId externalUserId,
        Duration timeout
    ) {
        ExternalId userId = DingTalkProviderContract.requireUserId(externalUserId);
        String body = "{\"language\":\"zh_CN\",\"userid\":"
            + json(userId.value())
            + "}";
        return request(
            DingTalkTransportRequest.ApiFamily.LEGACY_OAPI,
            USER_DETAIL_PATH,
            body,
            timeout
        );
    }

    private static DingTalkTransportRequest request(
        DingTalkTransportRequest.ApiFamily family,
        String path,
        String body,
        Duration timeout
    ) {
        return new DingTalkTransportRequest(
            family,
            DingTalkTransportRequest.HttpMethod.POST,
            path,
            Map.of("Content-Type", "application/json"),
            body,
            timeout
        );
    }

    private static String json(String value) {
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
