package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.PageRequest;
import io.github.akaryc1b.approval.connector.model.PageResult;

import java.util.Map;
import java.util.Objects;

public interface FormDataSourceConnector {

    PageResult<Map<String, Object>> query(
        ConnectorContext context,
        DataSourceRequest request,
        PageRequest pageRequest
    );

    record DataSourceRequest(
        String dataSourceCode,
        String keyword,
        Map<String, Object> filters,
        Map<String, Object> formContext
    ) {
        public DataSourceRequest {
            dataSourceCode = requireText(dataSourceCode, "dataSourceCode");
            keyword = keyword == null || keyword.isBlank() ? null : keyword;
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            formContext = formContext == null ? Map.of() : Map.copyOf(formContext);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
