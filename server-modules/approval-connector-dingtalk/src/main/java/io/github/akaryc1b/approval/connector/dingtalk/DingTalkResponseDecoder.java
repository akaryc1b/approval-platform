package io.github.akaryc1b.approval.connector.dingtalk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded decoder for the DingTalk contact responses exercised by captured fixtures.
 */
public final class DingTalkResponseDecoder {

    private final ObjectMapper objectMapper;

    public DingTalkResponseDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public DingTalkUserSearchResult decodeUserSearch(String body) {
        JsonNode root = read(body);
        requireProviderSuccess(root);
        JsonNode list = root.path("list");
        if (!list.isArray()) {
            throw new IllegalArgumentException("DingTalk user-search response is missing list");
        }
        List<String> userIds = new ArrayList<>();
        list.forEach(node -> userIds.add(requireText(node.asText(null), "userId")));
        return new DingTalkUserSearchResult(
            userIds,
            root.path("hasMore").asBoolean(false),
            root.path("totalCount").asLong(userIds.size())
        );
    }

    DingTalkUserDetail decodeUserDetail(String body) {
        JsonNode root = read(body);
        requireProviderSuccess(root);
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            throw new IllegalArgumentException("DingTalk user-detail response is missing result");
        }
        return new DingTalkUserDetail(
            firstText(result, "userid", "userId"),
            optionalText(result, "unionid", "unionId"),
            firstText(result, "name"),
            optionalText(result, "email", "org_email", "orgEmail"),
            optionalText(result, "mobile"),
            result.path("active").asBoolean(false),
            optionalText(result, "manager_userid", "managerUserid", "managerUserId"),
            stringList(result, "dept_id_list", "deptIdList"),
            optionalText(result, "title", "position"),
            optionalText(result, "job_number", "jobNumber", "jobnumber")
        );
    }

    private JsonNode read(String body) {
        Objects.requireNonNull(body, "body must not be null");
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("DingTalk response is not valid JSON", exception);
        }
    }

    private static void requireProviderSuccess(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("DingTalk response root must be an object");
        }
        JsonNode codeNode = root.get("errcode");
        if (codeNode == null || codeNode.isNull()) {
            return;
        }
        long code = codeNode.asLong(Long.MIN_VALUE);
        if (code == Long.MIN_VALUE) {
            throw new IllegalArgumentException("DingTalk errcode is invalid");
        }
        if (code != 0) {
            throw new DingTalkProviderException(
                code,
                optionalText(root, "errmsg", "message")
            );
        }
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        String value = optionalText(node, fieldNames);
        return requireText(value, fieldNames[0]);
    }

    private static String optionalText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode values = node.get(fieldName);
            if (values == null || values.isNull()) {
                continue;
            }
            if (!values.isArray()) {
                throw new IllegalArgumentException(fieldName + " must be an array");
            }
            List<String> result = new ArrayList<>();
            values.forEach(value -> result.add(requireText(value.asText(null), fieldName)));
            return result;
        }
        return List.of();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
