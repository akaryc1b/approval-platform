package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads the reviewed MySQL D8 payload-lineage and append-only guards. */
final class MySqlV50D8PlanAggregationGuards {

    private static final List<String> RESOURCES = List.of(
        "db/mysqlguards/d8-001-aggregate-insert.sql",
        "db/mysqlguards/d8-002-aggregate-update.sql",
        "db/mysqlguards/d8-003-aggregate-delete.sql",
        "db/mysqlguards/d8-004-event-insert.sql",
        "db/mysqlguards/d8-005-event-update.sql",
        "db/mysqlguards/d8-006-event-delete.sql",
        "db/mysqlguards/d8-007-completion-insert.sql",
        "db/mysqlguards/d8-008-completion-update.sql",
        "db/mysqlguards/d8-009-completion-delete.sql"
    );

    private MySqlV50D8PlanAggregationGuards() {
    }

    static List<String> statements() {
        List<String> statements = new ArrayList<>();
        ClassLoader loader = MySqlV50D8PlanAggregationGuards.class.getClassLoader();
        for (String resource : RESOURCES) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new FlywayException(
                        "missing MySQL D8 guard resource: " + resource
                    );
                }
                String statement = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
                ).strip();
                if (statement.isEmpty()) {
                    throw new FlywayException(
                        "empty MySQL D8 guard resource: " + resource
                    );
                }
                statements.add(statement);
            } catch (IOException exception) {
                throw new FlywayException(
                    "MySQL D8 guard resource read failed",
                    exception
                );
            }
        }
        if (statements.size() != 9) {
            throw new FlywayException(
                "MySQL D8 guard statement count drift: " + statements.size()
            );
        }
        return List.copyOf(statements);
    }
}
