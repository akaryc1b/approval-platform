package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the closed, non-executable default-value protocol on the server. */
public final class FormDefaultValueResolver {

    private final Clock clock;

    public FormDefaultValueResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Map<String, Object> resolve(FormDefinition definition, RequestContext context) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Map<String, Object> values = new LinkedHashMap<>();
        definition.fields().forEach(field -> {
            Object value = resolve(field.defaultValue(), context);
            if (value != null) {
                values.put(field.key(), value);
            }
        });
        return Map.copyOf(values);
    }

    private Object resolve(DefaultValue defaultValue, RequestContext context) {
        DefaultValueType type = defaultValue.type();
        return switch (type) {
            case NONE -> null;
            case LITERAL -> immutable(defaultValue.literal());
            case CURRENT_USER -> context.operatorId();
            case CURRENT_DATE -> LocalDate.now(clock).toString();
            case CURRENT_DATETIME -> clock.instant().toString();
        };
    }

    private static Object immutable(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(FormDefaultValueResolver::immutable).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutable(item)));
            return Map.copyOf(copy);
        }
        return value;
    }

    public static final class DefaultValueResolutionException extends RuntimeException {
        public DefaultValueResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
