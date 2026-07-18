package io.github.akaryc1b.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Produces deterministic hashes for normalized form submissions. */
public final class FormSubmissionHasher {

    private final ObjectMapper objectMapper;

    public FormSubmissionHasher(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
            .copy()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String hash(
        String formKey,
        int formVersion,
        String schemaHash,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("businessKey", businessKey);
        payload.put("formKey", formKey);
        payload.put("formVersion", formVersion);
        payload.put("schemaHash", schemaHash);
        payload.put("startParameters", startParameters == null ? Map.of() : startParameters);
        payload.put("values", values == null ? Map.of() : values);
        try {
            byte[] content = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode form submission", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
