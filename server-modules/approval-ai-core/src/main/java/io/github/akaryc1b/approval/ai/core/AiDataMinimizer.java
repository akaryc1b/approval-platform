package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies field permission, visibility, masking, attachment and bounded-input rules. */
public final class AiDataMinimizer {

    private static final List<String> PROMPT_INJECTION_MARKERS = List.of(
        "ignore previous instructions",
        "ignore all previous",
        "system prompt",
        "<system>",
        "developer message",
        "jailbreak",
        "begin prompt",
        "override policy"
    );

    public List<AiProviderRequest.InputField> minimize(
        Set<String> allowedFieldKeys,
        List<AiSourceField> sourceFields,
        AiDataMinimizationPolicy policy
    ) {
        Objects.requireNonNull(allowedFieldKeys, "allowedFieldKeys must not be null");
        Objects.requireNonNull(sourceFields, "sourceFields must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (sourceFields.size() > policy.limits().maximumFields()) {
            throw new AiInputLimitException(
                "AI_INPUT_FIELD_LIMIT",
                "AI input contains too many fields"
            );
        }

        List<AiProviderRequest.InputField> result = new ArrayList<>();
        CharacterBudget budget = new CharacterBudget(
            policy.limits().maximumTotalTextCharacters()
        );
        for (AiSourceField field : sourceFields) {
            if (!allowedFieldKeys.contains(field.key())
                || field.access() == UiSchemaDefinition.FieldAccess.HIDDEN
                || !field.visible()
                || field.value() == null) {
                continue;
            }

            AiDataMinimizationPolicy.FieldRule rule = effectiveRule(field, policy);
            if (rule == AiDataMinimizationPolicy.FieldRule.OMIT) {
                continue;
            }
            if (field.type() == FormDefinition.FieldType.ATTACHMENT) {
                Object attachmentMetadata = sanitizeAttachmentMetadata(field, policy, budget);
                result.add(new AiProviderRequest.InputField(
                    field.key(),
                    field.type().name(),
                    attachmentMetadata,
                    AiProviderRequest.MaskingDisposition.INCLUDED
                ));
                continue;
            }

            if (rule == AiDataMinimizationPolicy.FieldRule.MASK) {
                result.add(new AiProviderRequest.InputField(
                    field.key(),
                    field.type().name(),
                    "***",
                    AiProviderRequest.MaskingDisposition.MASKED
                ));
                budget.add(3);
                continue;
            }

            Object sanitized = sanitizeValue(field.value(), 1, policy, budget);
            result.add(new AiProviderRequest.InputField(
                field.key(),
                field.type().name(),
                sanitized,
                AiProviderRequest.MaskingDisposition.INCLUDED
            ));
        }
        return List.copyOf(result);
    }

    private static AiDataMinimizationPolicy.FieldRule effectiveRule(
        AiSourceField field,
        AiDataMinimizationPolicy policy
    ) {
        AiDataMinimizationPolicy.FieldRule configured = policy.ruleFor(field.key());
        if (configured == AiDataMinimizationPolicy.FieldRule.OMIT) {
            return configured;
        }
        if (field.sensitive()
            && field.access() == UiSchemaDefinition.FieldAccess.READONLY) {
            if (configured == AiDataMinimizationPolicy.FieldRule.MASK) {
                return configured;
            }
            return AiDataMinimizationPolicy.FieldRule.OMIT;
        }
        if (field.sensitive()
            && configured == AiDataMinimizationPolicy.FieldRule.DEFAULT) {
            return AiDataMinimizationPolicy.FieldRule.MASK;
        }
        return configured == AiDataMinimizationPolicy.FieldRule.DEFAULT
            ? AiDataMinimizationPolicy.FieldRule.INCLUDE
            : configured;
    }

    private static Object sanitizeAttachmentMetadata(
        AiSourceField field,
        AiDataMinimizationPolicy policy,
        CharacterBudget budget
    ) {
        if (!(field.value() instanceof Collection<?> collection)) {
            throw new AiPolicyViolationException(
                "AI_ATTACHMENT_CONTENT_NOT_ALLOWED",
                "attachment fields must contain metadata collections only"
            );
        }
        if (collection.size() > policy.limits().maximumCollectionSize()) {
            throw new AiInputLimitException(
                "AI_INPUT_COLLECTION_LIMIT",
                "attachment metadata collection is too large"
            );
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Object entry : collection) {
            if (!(entry instanceof AiSourceField.AttachmentMetadata metadata)) {
                throw new AiPolicyViolationException(
                    "AI_ATTACHMENT_CONTENT_NOT_ALLOWED",
                    "raw attachment content is not permitted"
                );
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("attachmentId", metadata.attachmentId());
            value.put("fileName", metadata.fileName());
            value.put("contentType", metadata.contentType());
            value.put("sizeBytes", metadata.sizeBytes());
            value.put("sha256", metadata.sha256());
            budget.add(metadata.attachmentId().length());
            budget.add(metadata.fileName().length());
            budget.add(metadata.contentType().length());
            budget.add(metadata.sha256().length());
            sanitized.add(Map.copyOf(value));
        }
        return List.copyOf(sanitized);
    }

    private static Object sanitizeValue(
        Object value,
        int depth,
        AiDataMinimizationPolicy policy,
        CharacterBudget budget
    ) {
        if (depth > policy.limits().maximumDepth()) {
            throw new AiInputLimitException(
                "AI_INPUT_DEPTH_LIMIT",
                "AI input exceeds maximum nesting depth"
            );
        }
        if (value == null) {
            throw new AiPolicyViolationException(
                "AI_NULL_CONTENT_NOT_ALLOWED",
                "nested null content is not permitted"
            );
        }
        if (value instanceof byte[] || value instanceof char[]) {
            throw new AiPolicyViolationException(
                "AI_BINARY_CONTENT_NOT_ALLOWED",
                "binary or character-array content is not permitted"
            );
        }
        if (value instanceof String text) {
            return sanitizeText(text, policy, budget);
        }
        if (value instanceof Number
            || value instanceof Boolean
            || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > policy.limits().maximumCollectionSize()) {
                throw new AiInputLimitException(
                    "AI_INPUT_COLLECTION_LIMIT",
                    "AI input map is too large"
                );
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new AiPolicyViolationException(
                        "AI_INPUT_KEY_INVALID",
                        "AI input map keys must be strings"
                    );
                }
                sanitized.put(
                    sanitizeText(key, policy, budget),
                    sanitizeValue(entry.getValue(), depth + 1, policy, budget)
                );
            }
            return Map.copyOf(sanitized);
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > policy.limits().maximumCollectionSize()) {
                throw new AiInputLimitException(
                    "AI_INPUT_COLLECTION_LIMIT",
                    "AI input collection is too large"
                );
            }
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeValue(item, depth + 1, policy, budget));
            }
            return List.copyOf(sanitized);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > policy.limits().maximumCollectionSize()) {
                throw new AiInputLimitException(
                    "AI_INPUT_COLLECTION_LIMIT",
                    "AI input array is too large"
                );
            }
            List<Object> sanitized = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeValue(Array.get(value, index), depth + 1, policy, budget));
            }
            return List.copyOf(sanitized);
        }
        throw new AiPolicyViolationException(
            "AI_INPUT_TYPE_UNSUPPORTED",
            "AI input contains an unsupported value type"
        );
    }

    private static String sanitizeText(
        String text,
        AiDataMinimizationPolicy policy,
        CharacterBudget budget
    ) {
        if (text.length() > policy.limits().maximumTextCharactersPerValue()) {
            throw new AiInputLimitException(
                "AI_INPUT_TEXT_LIMIT",
                "AI input text is too long"
            );
        }
        if (policy.blockPromptInjectionMarkers()) {
            String normalized = text.toLowerCase(Locale.ROOT);
            for (String marker : PROMPT_INJECTION_MARKERS) {
                if (normalized.contains(marker)) {
                    throw new AiPolicyViolationException(
                        "AI_PROMPT_INJECTION_MARKER",
                        "AI input contains a blocked prompt-injection marker"
                    );
                }
            }
        }
        budget.add(text.length());
        return text;
    }

    private static final class CharacterBudget {

        private final int maximum;
        private int used;

        private CharacterBudget(int maximum) {
            this.maximum = maximum;
        }

        private void add(int amount) {
            used += amount;
            if (used > maximum) {
                throw new AiInputLimitException(
                    "AI_INPUT_TOTAL_TEXT_LIMIT",
                    "AI input exceeds the total text budget"
                );
            }
        }
    }
}
