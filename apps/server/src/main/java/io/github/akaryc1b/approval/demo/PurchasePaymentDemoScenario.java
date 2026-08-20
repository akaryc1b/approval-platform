package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver.AssigneeRules;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, deployment-neutral view of the governed purchase-payment demo scenario.
 */
public final class PurchasePaymentDemoScenario {

    public static final int SCHEMA_VERSION = 1;
    public static final String SCENARIO_MANIFEST_PATH =
        "config/demo/purchase-payment-golden-path.json";

    private final String tenantId;
    private final String tenantDisplayName;
    private final String connectorKey;
    private final String source;
    private final String administratorId;
    private final Map<String, DemoUser> users;
    private final PurchaseRequest request;
    private final AssigneeRules assigneeRules;
    private final List<AttachmentFixture> attachments;

    private PurchasePaymentDemoScenario(
        String tenantId,
        String tenantDisplayName,
        String connectorKey,
        String source,
        String administratorId,
        Map<String, DemoUser> users,
        PurchaseRequest request,
        AssigneeRules assigneeRules,
        List<AttachmentFixture> attachments
    ) {
        this.tenantId = requireText(tenantId, "tenantId");
        this.tenantDisplayName = requireText(tenantDisplayName, "tenantDisplayName");
        this.connectorKey = requireText(connectorKey, "connectorKey");
        this.source = requireText(source, "source");
        this.administratorId = requireText(administratorId, "administratorId");
        this.users = Map.copyOf(users);
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.assigneeRules = Objects.requireNonNull(
            assigneeRules,
            "assigneeRules must not be null"
        );
        this.attachments = List.copyOf(attachments);
        validate();
    }

    public static PurchasePaymentDemoScenario load(
        ObjectMapper objectMapper,
        Resource scenarioManifest,
        Resource seedFixture
    ) throws IOException {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        JsonNode scenario = read(objectMapper, scenarioManifest, "scenario manifest");
        JsonNode fixture = read(objectMapper, seedFixture, "seed fixture");

        requireSchemaVersion(scenario, "scenario manifest");
        requireSchemaVersion(fixture, "seed fixture");

        JsonNode tenant = requiredObject(scenario, "tenant");
        String tenantId = requiredText(tenant, "id");
        String tenantDisplayName = requiredText(tenant, "displayName");

        JsonNode directory = requiredObject(scenario, "directory");
        String connectorKey = requiredText(directory, "connectorKey");
        String source = requiredText(directory, "source");

        Map<String, DemoUser> users = new LinkedHashMap<>();
        for (JsonNode userNode : requiredArray(directory, "users")) {
            DemoUser user = new DemoUser(
                requiredText(userNode, "id"),
                requiredText(userNode, "displayName"),
                textArray(userNode, "roleCodes"),
                textArray(userNode, "positionCodes"),
                optionalText(userNode, "managerId")
            );
            if (users.putIfAbsent(user.id(), user) != null) {
                throw new IllegalArgumentException("demo user IDs must be unique");
            }
        }
        if (users.size() != 6) {
            throw new IllegalArgumentException("demo directory must contain exactly six users");
        }

        String administratorId = singleUserWithRole(users, "APPROVAL_ADMIN");

        JsonNode requestNode = requiredObject(scenario, "request");
        PurchaseRequest request = new PurchaseRequest(
            requiredText(requestNode, "businessKey"),
            new BigDecimal(requiredText(requestNode, "amount")),
            requiredText(requestNode, "supplier"),
            requiredText(requestNode, "purchaseOrderReference"),
            textArray(requestNode, "attachmentIds")
        );

        JsonNode rules = requiredObject(scenario, "assigneeRules");
        AssigneeRules assigneeRules = new AssigneeRules(
            requiredText(rules, "connectorKey"),
            new ExternalId(
                requiredText(requiredObject(rules, "initiatorUserId"), "source"),
                requiredText(requiredObject(rules, "initiatorUserId"), "objectType"),
                requiredText(requiredObject(rules, "initiatorUserId"), "value")
            ),
            requiredText(rules, "financeReviewerRoleCode"),
            requiredText(rules, "financeApproverPositionCode"),
            requiredInt(rules, "maximumFinanceApprovers")
        );

        if (!SCENARIO_MANIFEST_PATH.equals(requiredText(fixture, "scenarioManifest"))) {
            throw new IllegalArgumentException(
                "seed fixture must reference the governed scenario manifest"
            );
        }
        List<AttachmentFixture> attachments = new ArrayList<>();
        for (JsonNode attachment : requiredArray(fixture, "attachments")) {
            attachments.add(new AttachmentFixture(
                requiredText(attachment, "logicalId"),
                UUID.fromString(requiredText(attachment, "attachmentId")),
                requiredText(attachment, "fileName"),
                requiredText(attachment, "contentType"),
                requiredText(attachment, "contentUtf8")
            ));
        }

        return new PurchasePaymentDemoScenario(
            tenantId,
            tenantDisplayName,
            connectorKey,
            source,
            administratorId,
            users,
            request,
            assigneeRules,
            attachments
        );
    }

    public String tenantId() {
        return tenantId;
    }

    public String tenantDisplayName() {
        return tenantDisplayName;
    }

    public String connectorKey() {
        return connectorKey;
    }

    public String source() {
        return source;
    }

    public String administratorId() {
        return administratorId;
    }

    public PurchaseRequest request() {
        return request;
    }

    public AssigneeRules assigneeRules() {
        return assigneeRules;
    }

    public List<AttachmentFixture> attachments() {
        return attachments;
    }

    public List<DemoUser> users() {
        return List.copyOf(users.values());
    }

    public Optional<DemoUser> findUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    public DemoUser requireUser(String userId) {
        DemoUser user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("unknown demo user: " + userId);
        }
        return user;
    }

    public ExternalId externalUserId(String userId) {
        requireUser(userId);
        return new ExternalId(source, "user", userId);
    }

    public UserSnapshot snapshot(DemoUser user) {
        ExternalId managerId = user.managerId() == null
            ? null
            : externalUserId(user.managerId());
        return new UserSnapshot(
            externalUserId(user.id()),
            user.id(),
            user.displayName(),
            null,
            null,
            true,
            List.of(),
            Set.copyOf(user.roleCodes()),
            Set.copyOf(user.positionCodes()),
            managerId,
            Map.of(
                "demo", "true",
                "tenantId", tenantId,
                "connectorKey", connectorKey
            )
        );
    }

    private void validate() {
        if (!connectorKey.equals(assigneeRules.connectorKey())) {
            throw new IllegalArgumentException(
                "directory connector key must equal assignee connector key"
            );
        }
        if (!source.equals(assigneeRules.initiatorUserId().source())
            || !"user".equals(assigneeRules.initiatorUserId().objectType())) {
            throw new IllegalArgumentException("initiator external ID must use the demo user source");
        }
        DemoUser initiator = requireUser(assigneeRules.initiatorUserId().value());
        if (initiator.managerId() == null) {
            throw new IllegalArgumentException("demo initiator must have a manager");
        }
        requireUser(initiator.managerId());

        long reviewers = users.values().stream()
            .filter(user -> user.roleCodes().contains(assigneeRules.financeReviewerRoleCode()))
            .count();
        if (reviewers != 1) {
            throw new IllegalArgumentException(
                "finance reviewer role must resolve to exactly one demo user"
            );
        }
        long approvers = users.values().stream()
            .filter(user -> user.positionCodes().contains(
                assigneeRules.financeApproverPositionCode()
            ))
            .count();
        if (approvers < 1 || approvers > assigneeRules.maximumFinanceApprovers()) {
            throw new IllegalArgumentException(
                "finance approver fixture must respect the configured maximum"
            );
        }

        List<String> fixtureLogicalIds = attachments.stream()
            .map(AttachmentFixture::logicalId)
            .toList();
        if (!request.attachmentLogicalIds().equals(fixtureLogicalIds)) {
            throw new IllegalArgumentException(
                "seed attachment order must match the governed request attachment IDs"
            );
        }
        Set<UUID> attachmentIds = new HashSet<>();
        for (AttachmentFixture attachment : attachments) {
            if (!attachmentIds.add(attachment.attachmentId())) {
                throw new IllegalArgumentException("seed attachment UUIDs must be unique");
            }
        }
        if (request.amount().compareTo(new BigDecimal("10000.00")) <= 0) {
            throw new IllegalArgumentException(
                "demo purchase amount must exercise the high-value route"
            );
        }
    }

    private static JsonNode read(
        ObjectMapper objectMapper,
        Resource resource,
        String description
    ) throws IOException {
        Objects.requireNonNull(resource, description + " resource must not be null");
        try (InputStream input = resource.getInputStream()) {
            JsonNode value = objectMapper.readTree(input);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException(description + " must be a JSON object");
            }
            return value;
        }
    }

    private static void requireSchemaVersion(JsonNode value, String description) {
        if (requiredInt(value, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                description + " schemaVersion must be " + SCHEMA_VERSION
            );
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return requireText(value.textValue(), field);
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text when present");
        }
        return normalizeOptional(value.textValue());
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static List<String> textArray(JsonNode parent, String field) {
        JsonNode array = requiredArray(parent, field);
        List<String> result = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(field + " entries must be text");
            }
            String value = requireText(item.textValue(), field + " entry");
            if (!distinct.add(value)) {
                throw new IllegalArgumentException(field + " entries must be unique");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static String singleUserWithRole(
        Map<String, DemoUser> users,
        String roleCode
    ) {
        List<String> matches = users.values().stream()
            .filter(user -> user.roleCodes().contains(roleCode))
            .map(DemoUser::id)
            .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                roleCode + " must resolve to exactly one demo user"
            );
        }
        return matches.getFirst();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record DemoUser(
        String id,
        String displayName,
        List<String> roleCodes,
        List<String> positionCodes,
        String managerId
    ) {
        public DemoUser {
            id = requireText(id, "user.id");
            displayName = requireText(displayName, "user.displayName");
            roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
            positionCodes = positionCodes == null ? List.of() : List.copyOf(positionCodes);
            managerId = normalizeOptional(managerId);
        }
    }

    public record PurchaseRequest(
        String businessKey,
        BigDecimal amount,
        String supplier,
        String purchaseOrderReference,
        List<String> attachmentLogicalIds
    ) {
        public PurchaseRequest {
            businessKey = requireText(businessKey, "request.businessKey");
            amount = Objects.requireNonNull(amount, "request.amount must not be null");
            supplier = requireText(supplier, "request.supplier");
            purchaseOrderReference = requireText(
                purchaseOrderReference,
                "request.purchaseOrderReference"
            );
            attachmentLogicalIds = attachmentLogicalIds == null
                ? List.of()
                : List.copyOf(attachmentLogicalIds);
            if (attachmentLogicalIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "request attachment logical IDs must not be empty"
                );
            }
        }
    }

    public record AttachmentFixture(
        String logicalId,
        UUID attachmentId,
        String fileName,
        String contentType,
        String contentUtf8
    ) {
        public AttachmentFixture {
            logicalId = requireText(logicalId, "attachment.logicalId");
            attachmentId = Objects.requireNonNull(
                attachmentId,
                "attachment.attachmentId must not be null"
            );
            fileName = requireText(fileName, "attachment.fileName");
            contentType = requireText(contentType, "attachment.contentType");
            contentUtf8 = requireText(contentUtf8, "attachment.contentUtf8");
        }

        public byte[] content() {
            return contentUtf8.getBytes(StandardCharsets.UTF_8);
        }
    }
}
