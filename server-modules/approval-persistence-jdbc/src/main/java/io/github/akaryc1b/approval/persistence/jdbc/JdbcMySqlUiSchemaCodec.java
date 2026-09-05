package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.ComponentDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed, data-only UI Schema codec that preserves generic Java value types on MySQL JSON.
 */
final class JdbcMySqlUiSchemaCodec {

    static final String JSON_ENCODING = "CANONICAL_UI_SCHEMA_TYPED_JSON_V1";

    private static final String ROOT_PATH = "$";

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper;

    JdbcMySqlUiSchemaCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        this.strictObjectMapper = objectMapper.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    String encode(UiSchemaDefinition definition) {
        UiSchemaDefinition exact = Objects.requireNonNull(
            definition,
            "definition must not be null"
        );
        try {
            ObjectNode payload = requireObjectForEncoding(
                objectMapper.valueToTree(exact),
                ROOT_PATH
            );
            encodeSections(
                requireArrayForEncoding(
                    payload.get("sections"),
                    child(ROOT_PATH, "sections")
                ),
                exact.sections(),
                child(ROOT_PATH, "sections")
            );

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("encoding", JSON_ENCODING);
            envelope.put("payload", objectMapper.writeValueAsString(payload));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "unable to encode MySQL UI Schema envelope",
                exception
            );
        }
    }

    UiSchemaDefinition decode(String json) throws SQLException {
        if (json == null) {
            throw new SQLException("MySQL UI Schema envelope was null");
        }
        try {
            ObjectNode envelope = requireObject(
                strictObjectMapper.readTree(json),
                ROOT_PATH
            );
            if (envelope.size() != 2) {
                throw invalid(ROOT_PATH, "outer envelope must contain exactly two members");
            }
            JsonNode encoding = envelope.get("encoding");
            JsonNode payloadText = envelope.get("payload");
            if (encoding == null
                || !encoding.isTextual()
                || !JSON_ENCODING.equals(encoding.textValue())
                || payloadText == null
                || !payloadText.isTextual()) {
                throw invalid(ROOT_PATH, "invalid or unsupported outer envelope");
            }

            ObjectNode payload = requireObject(
                strictObjectMapper.readTree(payloadText.textValue()),
                child(ROOT_PATH, "payload")
            );
            Map<String, Object> decodedValues = new LinkedHashMap<>();
            decodeSections(
                requireArray(
                    payload.get("sections"),
                    child(ROOT_PATH, "sections")
                ),
                child(ROOT_PATH, "sections"),
                decodedValues
            );

            UiSchemaDefinition parsed = strictObjectMapper.treeToValue(
                payload,
                UiSchemaDefinition.class
            );
            Set<String> consumed = new HashSet<>();
            UiSchemaDefinition restored = restoreDefinition(
                parsed,
                decodedValues,
                consumed
            );
            if (!consumed.equals(decodedValues.keySet())) {
                throw invalid(
                    ROOT_PATH,
                    "typed UI values were not consumed exactly once"
                );
            }
            return restored;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new SQLException(
                "unable to decode MySQL UI Schema envelope",
                exception
            );
        }
    }

    private void encodeSections(
        ArrayNode sectionNodes,
        List<Section> sections,
        String path
    ) {
        if (sectionNodes.size() != sections.size()) {
            throw new IllegalArgumentException(
                "serialized UI section count does not match the domain definition"
            );
        }
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            String sectionPath = child(path, Integer.toString(index));
            ObjectNode sectionNode = requireObjectForEncoding(
                sectionNodes.get(index),
                sectionPath
            );
            ObjectNode visibilityNode = requireObjectForEncoding(
                sectionNode.get("visibility"),
                child(sectionPath, "visibility")
            );
            visibilityNode.set(
                "expectedValue",
                encodeTypedValue(section.visibility().expectedValue())
            );

            ArrayNode fieldNodes = requireArrayForEncoding(
                sectionNode.get("fields"),
                child(sectionPath, "fields")
            );
            if (fieldNodes.size() != section.fields().size()) {
                throw new IllegalArgumentException(
                    "serialized UI field count does not match the domain definition"
                );
            }
            for (int fieldIndex = 0; fieldIndex < section.fields().size(); fieldIndex++) {
                FieldLayout field = section.fields().get(fieldIndex);
                ComponentDefinition component = field.component();
                if (component == null) {
                    continue;
                }
                String fieldPath = child(
                    child(sectionPath, "fields"),
                    Integer.toString(fieldIndex)
                );
                ObjectNode fieldNode = requireObjectForEncoding(
                    fieldNodes.get(fieldIndex),
                    fieldPath
                );
                ObjectNode componentNode = requireObjectForEncoding(
                    fieldNode.get("component"),
                    child(fieldPath, "component")
                );
                ObjectNode propertiesNode = requireObjectForEncoding(
                    componentNode.get("properties"),
                    child(child(fieldPath, "component"), "properties")
                );
                propertiesNode.removeAll();
                component.properties().keySet().stream().sorted().forEach(key ->
                    propertiesNode.set(
                        key,
                        encodeTypedValue(component.properties().get(key))
                    )
                );
            }

            encodeSections(
                requireArrayForEncoding(
                    sectionNode.get("children"),
                    child(sectionPath, "children")
                ),
                section.children(),
                child(sectionPath, "children")
            );
        }
    }

    private void decodeSections(
        ArrayNode sectionNodes,
        String path,
        Map<String, Object> decodedValues
    ) throws SQLException {
        for (int index = 0; index < sectionNodes.size(); index++) {
            String sectionPath = child(path, Integer.toString(index));
            ObjectNode sectionNode = requireObject(
                sectionNodes.get(index),
                sectionPath
            );
            ObjectNode visibilityNode = requireObject(
                sectionNode.get("visibility"),
                child(sectionPath, "visibility")
            );
            String visibilityValuePath = child(
                child(sectionPath, "visibility"),
                "expectedValue"
            );
            Object visibilityValue = decodeTypedValue(
                visibilityNode.get("expectedValue"),
                visibilityValuePath
            );
            registerDecoded(decodedValues, visibilityValuePath, visibilityValue);
            visibilityNode.set(
                "expectedValue",
                objectMapper.valueToTree(visibilityValue)
            );

            ArrayNode fieldNodes = requireArray(
                sectionNode.get("fields"),
                child(sectionPath, "fields")
            );
            for (int fieldIndex = 0; fieldIndex < fieldNodes.size(); fieldIndex++) {
                String fieldPath = child(
                    child(sectionPath, "fields"),
                    Integer.toString(fieldIndex)
                );
                ObjectNode fieldNode = requireObject(
                    fieldNodes.get(fieldIndex),
                    fieldPath
                );
                JsonNode componentValue = fieldNode.get("component");
                if (componentValue == null || componentValue.isNull()) {
                    continue;
                }
                ObjectNode componentNode = requireObject(
                    componentValue,
                    child(fieldPath, "component")
                );
                ObjectNode propertiesNode = requireObject(
                    componentNode.get("properties"),
                    child(child(fieldPath, "component"), "properties")
                );
                List<String> keys = new ArrayList<>();
                propertiesNode.fieldNames().forEachRemaining(keys::add);
                keys.sort(Comparator.naturalOrder());
                for (String key : keys) {
                    String valuePath = child(
                        child(child(fieldPath, "component"), "properties"),
                        key
                    );
                    Object value = decodeTypedValue(
                        propertiesNode.get(key),
                        valuePath
                    );
                    registerDecoded(decodedValues, valuePath, value);
                    propertiesNode.set(key, objectMapper.valueToTree(value));
                }
            }

            decodeSections(
                requireArray(
                    sectionNode.get("children"),
                    child(sectionPath, "children")
                ),
                child(sectionPath, "children"),
                decodedValues
            );
        }
    }

    private UiSchemaDefinition restoreDefinition(
        UiSchemaDefinition definition,
        Map<String, Object> decodedValues,
        Set<String> consumed
    ) throws SQLException {
        UiSchemaDefinition exact = Objects.requireNonNull(
            definition,
            "decoded definition must not be null"
        );
        return new UiSchemaDefinition(
            exact.schemaVersion(),
            exact.formKey(),
            exact.formVersion(),
            exact.version(),
            exact.name(),
            restoreSections(
                exact.sections(),
                child(ROOT_PATH, "sections"),
                decodedValues,
                consumed
            ),
            exact.nodePermissions()
        );
    }

    private List<Section> restoreSections(
        List<Section> sections,
        String path,
        Map<String, Object> decodedValues,
        Set<String> consumed
    ) throws SQLException {
        List<Section> restored = new ArrayList<>(sections.size());
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            String sectionPath = child(path, Integer.toString(index));
            String visibilityPath = child(
                child(sectionPath, "visibility"),
                "expectedValue"
            );
            SectionVisibility visibility = new SectionVisibility(
                section.visibility().mode(),
                section.visibility().fieldKey(),
                decodedValue(decodedValues, consumed, visibilityPath)
            );
            restored.add(new Section(
                section.key(),
                section.title(),
                section.helpText(),
                section.collapsed(),
                restoreFields(
                    section.fields(),
                    child(sectionPath, "fields"),
                    decodedValues,
                    consumed
                ),
                section.order(),
                section.columns(),
                section.collapsible(),
                visibility,
                section.readonlySummary(),
                restoreSections(
                    section.children(),
                    child(sectionPath, "children"),
                    decodedValues,
                    consumed
                )
            ));
        }
        return List.copyOf(restored);
    }

    private List<FieldLayout> restoreFields(
        List<FieldLayout> fields,
        String path,
        Map<String, Object> decodedValues,
        Set<String> consumed
    ) throws SQLException {
        List<FieldLayout> restored = new ArrayList<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            FieldLayout field = fields.get(index);
            String fieldPath = child(path, Integer.toString(index));
            restored.add(new FieldLayout(
                field.fieldKey(),
                field.placeholder(),
                field.helpText(),
                field.span(),
                restoreComponent(
                    field.component(),
                    child(fieldPath, "component"),
                    decodedValues,
                    consumed
                )
            ));
        }
        return List.copyOf(restored);
    }

    private ComponentDefinition restoreComponent(
        ComponentDefinition component,
        String path,
        Map<String, Object> decodedValues,
        Set<String> consumed
    ) throws SQLException {
        if (component == null) {
            return null;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        component.properties().keySet().stream().sorted().forEach(key -> {
            try {
                properties.put(
                    key,
                    decodedValue(
                        decodedValues,
                        consumed,
                        child(child(path, "properties"), key)
                    )
                );
            } catch (SQLException exception) {
                throw new TypedValueRestoreException(exception);
            }
        });
        try {
            return new ComponentDefinition(
                component.componentType(),
                component.componentVersion(),
                properties,
                component.fallbackRenderer()
            );
        } catch (TypedValueRestoreException exception) {
            throw exception.sqlException();
        }
    }

    private ObjectNode encodeTypedValue(Object value) {
        ObjectNode encoded = objectMapper.createObjectNode();
        if (value == null) {
            encoded.put("kind", "NULL");
            return encoded;
        }
        if (value instanceof String text) {
            encoded.put("kind", "STRING");
            encoded.put("value", text);
            return encoded;
        }
        if (value instanceof Boolean flag) {
            encoded.put("kind", "BOOLEAN");
            encoded.put("value", flag);
            return encoded;
        }
        if (value instanceof Number number) {
            NumberToken token = numberToken(number);
            encoded.put("kind", "NUMBER");
            encoded.put("type", token.type());
            encoded.put("value", token.value());
            return encoded;
        }
        if (value instanceof List<?> values) {
            encoded.put("kind", "LIST");
            ArrayNode items = encoded.putArray("values");
            values.forEach(item -> items.add(encodeTypedValue(item)));
            return encoded;
        }
        if (value instanceof Map<?, ?> values) {
            encoded.put("kind", "MAP");
            ArrayNode entries = encoded.putArray("entries");
            List<GenericEntry> ordered = values.entrySet().stream()
                .map(entry -> {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException(
                            "typed UI map key must be a string"
                        );
                    }
                    return new GenericEntry(key, entry.getValue());
                })
                .sorted(Comparator.comparing(GenericEntry::key))
                .toList();
            for (GenericEntry entry : ordered) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("key", entry.key());
                item.set("value", encodeTypedValue(entry.value()));
                entries.add(item);
            }
            return encoded;
        }
        throw new IllegalArgumentException(
            "unsupported generic UI value type: " + value.getClass().getName()
        );
    }

    private Object decodeTypedValue(JsonNode node, String path) throws SQLException {
        ObjectNode encoded = requireObject(node, path);
        JsonNode kindNode = encoded.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            throw invalid(path, "typed UI value kind must be textual");
        }
        return switch (kindNode.textValue()) {
            case "NULL" -> {
                requireMemberCount(encoded, 1, path);
                yield null;
            }
            case "STRING" -> {
                requireMemberCount(encoded, 2, path);
                JsonNode value = encoded.get("value");
                if (value == null || !value.isTextual()) {
                    throw invalid(path, "STRING value must be textual");
                }
                yield value.textValue();
            }
            case "BOOLEAN" -> {
                requireMemberCount(encoded, 2, path);
                JsonNode value = encoded.get("value");
                if (value == null || !value.isBoolean()) {
                    throw invalid(path, "BOOLEAN value must be boolean");
                }
                yield value.booleanValue();
            }
            case "NUMBER" -> {
                requireMemberCount(encoded, 3, path);
                JsonNode type = encoded.get("type");
                JsonNode value = encoded.get("value");
                if (type == null
                    || !type.isTextual()
                    || value == null
                    || !value.isTextual()) {
                    throw invalid(path, "NUMBER type and value must be textual");
                }
                yield decodeNumber(type.textValue(), value.textValue(), path);
            }
            case "LIST" -> {
                requireMemberCount(encoded, 2, path);
                ArrayNode values = requireArray(
                    encoded.get("values"),
                    child(path, "values")
                );
                List<Object> decoded = new ArrayList<>(values.size());
                for (int index = 0; index < values.size(); index++) {
                    decoded.add(decodeTypedValue(
                        values.get(index),
                        child(child(path, "values"), Integer.toString(index))
                    ));
                }
                yield Collections.unmodifiableList(decoded);
            }
            case "MAP" -> {
                requireMemberCount(encoded, 2, path);
                ArrayNode entries = requireArray(
                    encoded.get("entries"),
                    child(path, "entries")
                );
                Map<String, Object> decoded = new LinkedHashMap<>();
                for (int index = 0; index < entries.size(); index++) {
                    String entryPath = child(
                        child(path, "entries"),
                        Integer.toString(index)
                    );
                    ObjectNode entry = requireObject(entries.get(index), entryPath);
                    requireMemberCount(entry, 2, entryPath);
                    JsonNode keyNode = entry.get("key");
                    if (keyNode == null || !keyNode.isTextual()) {
                        throw invalid(entryPath, "MAP key must be textual");
                    }
                    String key = keyNode.textValue();
                    if (decoded.containsKey(key)) {
                        throw invalid(entryPath, "MAP contains a duplicate key");
                    }
                    decoded.put(
                        key,
                        decodeTypedValue(
                            entry.get("value"),
                            child(entryPath, "value")
                        )
                    );
                }
                yield Collections.unmodifiableMap(decoded);
            }
            default -> throw invalid(path, "unknown typed UI value kind");
        };
    }

    private static NumberToken numberToken(Number number) {
        Objects.requireNonNull(number, "number must not be null");
        if (number instanceof Byte value) {
            return new NumberToken("BYTE", value.toString());
        }
        if (number instanceof Short value) {
            return new NumberToken("SHORT", value.toString());
        }
        if (number instanceof Integer value) {
            return new NumberToken("INTEGER", value.toString());
        }
        if (number instanceof Long value) {
            return new NumberToken("LONG", value.toString());
        }
        if (number instanceof BigInteger value) {
            return new NumberToken("BIG_INTEGER", value.toString());
        }
        if (number instanceof Float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("non-finite Float UI value");
            }
            return new NumberToken("FLOAT", value.toString());
        }
        if (number instanceof Double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("non-finite Double UI value");
            }
            return new NumberToken("DOUBLE", value.toString());
        }
        if (number instanceof BigDecimal value) {
            return new NumberToken("BIG_DECIMAL", value.toString());
        }
        throw new IllegalArgumentException(
            "unsupported generic UI number type: " + number.getClass().getName()
        );
    }

    private static Number decodeNumber(
        String type,
        String value,
        String path
    ) throws SQLException {
        try {
            return switch (type) {
                case "BYTE" -> Byte.valueOf(value);
                case "SHORT" -> Short.valueOf(value);
                case "INTEGER" -> Integer.valueOf(value);
                case "LONG" -> Long.valueOf(value);
                case "BIG_INTEGER" -> new BigInteger(value);
                case "FLOAT" -> finite(Float.valueOf(value), path);
                case "DOUBLE" -> finite(Double.valueOf(value), path);
                case "BIG_DECIMAL" -> new BigDecimal(value);
                default -> throw invalid(path, "unknown typed UI number type");
            };
        } catch (NumberFormatException exception) {
            throw new SQLException("invalid typed UI number at " + path, exception);
        }
    }

    private static Float finite(Float value, String path) throws SQLException {
        if (!Float.isFinite(value)) {
            throw invalid(path, "non-finite Float UI value");
        }
        return value;
    }

    private static Double finite(Double value, String path) throws SQLException {
        if (!Double.isFinite(value)) {
            throw invalid(path, "non-finite Double UI value");
        }
        return value;
    }

    private static void registerDecoded(
        Map<String, Object> values,
        String path,
        Object value
    ) throws SQLException {
        if (values.containsKey(path)) {
            throw invalid(path, "typed UI value path was registered twice");
        }
        values.put(path, value);
    }

    private static Object decodedValue(
        Map<String, Object> values,
        Set<String> consumed,
        String path
    ) throws SQLException {
        if (!values.containsKey(path)) {
            throw invalid(path, "typed UI value was not decoded");
        }
        if (!consumed.add(path)) {
            throw invalid(path, "typed UI value was consumed twice");
        }
        return values.get(path);
    }

    private static void requireMemberCount(
        ObjectNode node,
        int expected,
        String path
    ) throws SQLException {
        if (node.size() != expected) {
            throw invalid(path, "typed UI value contains unexpected members");
        }
    }

    private static ObjectNode requireObject(JsonNode node, String path)
        throws SQLException {
        if (!(node instanceof ObjectNode objectNode)) {
            throw invalid(path, "JSON object is required");
        }
        return objectNode;
    }

    private static ArrayNode requireArray(JsonNode node, String path)
        throws SQLException {
        if (!(node instanceof ArrayNode arrayNode)) {
            throw invalid(path, "JSON array is required");
        }
        return arrayNode;
    }

    private static ObjectNode requireObjectForEncoding(
        JsonNode node,
        String path
    ) {
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("JSON object is required at " + path);
        }
        return objectNode;
    }

    private static ArrayNode requireArrayForEncoding(
        JsonNode node,
        String path
    ) {
        if (!(node instanceof ArrayNode arrayNode)) {
            throw new IllegalArgumentException("JSON array is required at " + path);
        }
        return arrayNode;
    }

    private static String child(String path, String segment) {
        return path + '/' + segment.replace("~", "~0").replace("/", "~1");
    }

    private static SQLException invalid(String path, String message) {
        return new SQLException(message + " at " + path);
    }

    private record NumberToken(String type, String value) {
    }

    private record GenericEntry(String key, Object value) {
    }

    private static final class TypedValueRestoreException extends RuntimeException {

        private final SQLException sqlException;

        private TypedValueRestoreException(SQLException sqlException) {
            super(sqlException);
            this.sqlException = sqlException;
        }

        private SQLException sqlException() {
            return sqlException;
        }
    }
}
