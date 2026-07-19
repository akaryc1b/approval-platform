package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ReleasePackagePayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferPayload;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Set;

/** Strict, bounded JSON decoder for untrusted Approval artifact transfer requests. */
@Component
final class ApprovalArtifactTransferJsonCodec {

    static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    static final int MAX_JSON_DEPTH = 64;
    static final int MAX_JSON_ELEMENTS = 30_000;
    static final int MAX_JSON_NUMBER_LENGTH = 100;

    private static final Set<String> IMPORT_FIELDS = Set.of(
        "envelope",
        "targetDefinitionKey",
        "targetDefinitionVersion",
        "targetFormPackageVersion",
        "targetName"
    );
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "format",
        "formatVersion",
        "artifactType",
        "exportedAt",
        "definitionKey",
        "definitionVersion",
        "releaseVersion",
        "formPackageVersion",
        "definitionHash",
        "formPackageHash",
        "payload",
        "payloadHash",
        "envelopeHash"
    );
    private static final Set<String> DEFINITION_PAYLOAD_FIELDS = Set.of("definition");
    private static final Set<String> RELEASE_PAYLOAD_FIELDS = Set.of(
        "definition",
        "compilerVersion",
        "bpmnResourceName",
        "bpmnArtifact",
        "bpmnHash",
        "dmnArtifact",
        "dmnHash",
        "compiledArtifactHash",
        "deploymentMetadataHash",
        "releasePackageHash",
        "formSchemaVersion",
        "formSchemaHash",
        "uiSchemaVersion",
        "uiSchemaHash"
    );

    private final ObjectMapper mapper;

    ApprovalArtifactTransferJsonCodec(ObjectMapper objectMapper) {
        mapper = objectMapper.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_JSON_DEPTH)
            .maxStringLength(ApprovalArtifactTransferService.MAX_STRING_LENGTH)
            .maxNumberLength(MAX_JSON_NUMBER_LENGTH)
            .build());
    }

    DecodedImport decodeImport(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid("import request body must not be empty");
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                "import request exceeds the 2 MiB maximum"
            );
        }
        JsonNode root = readTree(body);
        requireObject(root, "import request");
        rejectUnknownFields(root, IMPORT_FIELDS, "import request");
        requireElementLimit(root);
        requireCanonicalLimit(root);
        return new DecodedImport(
            parseEnvelope(required(root, "envelope")),
            text(root, "targetDefinitionKey"),
            integer(root, "targetDefinitionVersion"),
            integer(root, "targetFormPackageVersion"),
            text(root, "targetName")
        );
    }

    private TransferEnvelope parseEnvelope(JsonNode node) {
        requireObject(node, "envelope");
        rejectUnknownFields(node, ENVELOPE_FIELDS, "envelope");
        String format = text(node, "format");
        ArtifactType artifactType = enumValue(
            ArtifactType.class,
            text(node, "artifactType"),
            "artifactType"
        );
        TransferPayload payload = parsePayload(
            format,
            artifactType,
            required(node, "payload")
        );
        return new TransferEnvelope(
            format,
            integer(node, "formatVersion"),
            artifactType,
            instant(node, "exportedAt"),
            text(node, "definitionKey"),
            integer(node, "definitionVersion"),
            optionalInteger(node, "releaseVersion"),
            integer(node, "formPackageVersion"),
            text(node, "definitionHash"),
            text(node, "formPackageHash"),
            payload,
            text(node, "payloadHash"),
            text(node, "envelopeHash")
        );
    }

    private TransferPayload parsePayload(
        String format,
        ArtifactType artifactType,
        JsonNode node
    ) {
        requireObject(node, "payload");
        if (ApprovalArtifactTransferService.DSL_FORMAT.equals(format)
            && artifactType == ArtifactType.APPROVAL_DSL) {
            rejectUnknownFields(node, DEFINITION_PAYLOAD_FIELDS, "DSL payload");
            return new DefinitionPayload(definition(node));
        }
        if (ApprovalArtifactTransferService.RELEASE_FORMAT.equals(format)
            && artifactType == ArtifactType.APPROVAL_RELEASE_PACKAGE) {
            rejectUnknownFields(node, RELEASE_PAYLOAD_FIELDS, "Release Package payload");
            return new ReleasePackagePayload(
                definition(node),
                text(node, "compilerVersion"),
                text(node, "bpmnResourceName"),
                text(node, "bpmnArtifact"),
                text(node, "bpmnHash"),
                optionalText(node, "dmnArtifact"),
                optionalText(node, "dmnHash"),
                text(node, "compiledArtifactHash"),
                text(node, "deploymentMetadataHash"),
                text(node, "releasePackageHash"),
                integer(node, "formSchemaVersion"),
                text(node, "formSchemaHash"),
                integer(node, "uiSchemaVersion"),
                text(node, "uiSchemaHash")
            );
        }
        throw invalid("format and artifactType do not identify a supported payload");
    }

    private ApprovalDefinition definition(JsonNode payload) {
        JsonNode definitionNode = required(payload, "definition");
        requireObject(definitionNode, "payload.definition");
        try {
            return mapper.treeToValue(definitionNode, ApprovalDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "Approval DSL payload is invalid",
                exception
            );
        }
    }

    private JsonNode readTree(byte[] body) {
        try {
            JsonNode value = mapper.readTree(body);
            if (value == null || value.isNull()) {
                throw invalid("import request must be a JSON object");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "import request is not valid strict JSON",
                exception
            );
        } catch (IOException exception) {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "import request could not be read",
                exception
            );
        }
    }

    private void requireCanonicalLimit(JsonNode root) {
        try {
            if (mapper.writeValueAsBytes(root).length > MAX_REQUEST_BYTES) {
                throw new ApprovalArtifactTransferExceptions.TooLarge(
                    "normalized import JSON exceeds the 2 MiB maximum"
                );
            }
        } catch (JsonProcessingException exception) {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "import request could not be normalized safely",
                exception
            );
        }
    }

    private static void requireElementLimit(JsonNode root) {
        if (countElements(root, 0) > MAX_JSON_ELEMENTS) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                "import request exceeds the maximum JSON element count"
            );
        }
    }

    private static int countElements(JsonNode node, int count) {
        int current = count + 1;
        if (current > MAX_JSON_ELEMENTS) {
            return current;
        }
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            current = countElements(children.next(), current);
            if (current > MAX_JSON_ELEMENTS) {
                return current;
            }
        }
        return current;
    }

    private static void rejectUnknownFields(
        JsonNode node,
        Set<String> allowed,
        String subject
    ) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw invalid(subject + " contains unknown field " + field);
            }
        }
    }

    private static JsonNode required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            throw invalid(name + " must not be null");
        }
        return value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(name + " must be a string or null");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static Integer optionalInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be a 32-bit integer or null");
        }
        return value.intValue();
    }

    private static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(text(node, name));
        } catch (DateTimeParseException exception) {
            throw invalid(name + " must be an ISO-8601 instant");
        }
    }

    private static <T extends Enum<T>> T enumValue(
        Class<T> type,
        String value,
        String name
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " is unsupported");
        }
    }

    private static void requireObject(JsonNode node, String name) {
        if (!node.isObject()) {
            throw invalid(name + " must be a JSON object");
        }
    }

    private static ApprovalArtifactTransferExceptions.InvalidFormat invalid(String message) {
        return new ApprovalArtifactTransferExceptions.InvalidFormat(message);
    }

    record DecodedImport(
        TransferEnvelope envelope,
        String targetDefinitionKey,
        int targetDefinitionVersion,
        int targetFormPackageVersion,
        String targetName
    ) {
    }
}
