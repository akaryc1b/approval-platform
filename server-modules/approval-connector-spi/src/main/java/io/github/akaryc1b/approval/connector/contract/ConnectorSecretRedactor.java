package io.github.akaryc1b.approval.connector.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConnectorSecretRedactor {

    public static final String REDACTED = "<redacted>";

    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)\\b(authorization|token|secret|password|api[_-]?key)\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern PROVIDER_TOKEN = Pattern.compile(
        "(?i)\\b(?:sk|xox[baprs])-[A-Za-z0-9_-]{8,}"
    );

    private ConnectorSecretRedactor() {
    }

    public static String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1=" + REDACTED);
        return PROVIDER_TOKEN.matcher(redacted).replaceAll(REDACTED);
    }

    public static Map<String, String> redactDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        details.forEach((key, value) -> redacted.put(
            key,
            ConnectorContractSupport.isSensitiveName(key) ? REDACTED : redact(value)
        ));
        return Map.copyOf(redacted);
    }
}
