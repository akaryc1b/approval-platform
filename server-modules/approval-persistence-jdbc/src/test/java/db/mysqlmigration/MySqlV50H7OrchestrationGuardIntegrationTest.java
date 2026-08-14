package db.mysqlmigration;

import io.github.akaryc1b.approval.persistence.jdbc.MySqlApprovalProjectionStoreIntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50H7OrchestrationGuardIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    @Test
    void cleanMySql84BaselineInstallsAllD7GuardEvents() {
        List<TriggerFact> triggers = jdbc.query("""
            select trigger_name,event_manipulation,action_timing,action_statement
            from information_schema.triggers
            where trigger_schema=database()
              and trigger_name like 'trg_migration_d7_%_v47'
            order by trigger_name
            """, (row, number) -> new TriggerFact(
                row.getString("trigger_name"),
                row.getString("event_manipulation"),
                row.getString("action_timing"),
                row.getString("action_statement")
            ));

        assertEquals(15, triggers.size());
        for (String tableToken : List.of(
            "canary",
            "run",
            "event",
            "batch",
            "kill_switch"
        )) {
            for (String operation : List.of("insert", "update", "delete")) {
                TriggerFact trigger = triggers.stream()
                    .filter(value -> value.name().contains("_" + tableToken + "_"))
                    .filter(value -> value.event().equalsIgnoreCase(operation))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "missing installed D7 trigger: " + tableToken + '/' + operation
                    ));
                assertEquals("BEFORE", trigger.timing());
                String action = trigger.statement().toLowerCase(Locale.ROOT);
                assertTrue(action.contains("signal sqlstate"));
                if (operation.equals("insert")) {
                    assertTrue(action.contains("json_extract"));
                    assertTrue(action.contains("if not exists"));
                } else {
                    assertTrue(action.contains("m5-d7 evidence is append-only"));
                }
            }
        }
    }

    private record TriggerFact(
        String name,
        String event,
        String timing,
        String statement
    ) {
    }
}
