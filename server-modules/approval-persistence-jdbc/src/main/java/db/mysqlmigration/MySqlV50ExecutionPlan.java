package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tracks clean-baseline index lifecycle without weakening conflicting declarations. */
final class MySqlV50ExecutionPlan {

    private static final Pattern CREATE_INDEX = Pattern.compile(
        "^\\s*create\\s+(?:unique\\s+)?index\\s+([a-zA-Z0-9_]+)\\s+on\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DROP_INDEX = Pattern.compile(
        "^\\s*drop\\s+index\\s+([a-zA-Z0-9_]+)\\s+on\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern IDEMPOTENT_INDEX = Pattern.compile(
        "^\\s*create\\s+(?:unique\\s+)?index\\s+if\\s+not\\s+exists\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final Map<String, String> activeIndexDefinitions = new LinkedHashMap<>();

    Optional<String> prepare(String source, String executable) {
        Matcher drop = DROP_INDEX.matcher(executable);
        if (drop.find()) {
            activeIndexDefinitions.remove(normalizedName(drop.group(1)));
            return Optional.of(executable);
        }

        Matcher create = CREATE_INDEX.matcher(executable);
        if (!create.find()) {
            return Optional.of(executable);
        }

        String indexName = normalizedName(create.group(1));
        String definition = canonical(executable);
        String previous = activeIndexDefinitions.putIfAbsent(indexName, definition);
        if (previous == null) {
            return Optional.of(executable);
        }
        if (previous.equals(definition) && IDEMPOTENT_INDEX.matcher(source).find()) {
            return Optional.empty();
        }
        throw new FlywayException(
            "conflicting active MySQL baseline index declaration: " + indexName
        );
    }

    private static String normalizedName(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String canonical(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
