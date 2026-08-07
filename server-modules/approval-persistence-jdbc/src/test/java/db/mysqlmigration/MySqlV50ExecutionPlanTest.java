package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50ExecutionPlanTest {

    @Test
    void skipsOnlyAnIdenticalActiveIdempotentIndexDeclaration() {
        var plan = new MySqlV50ExecutionPlan();
        String source = """
            create unique index if not exists uk_task
            on ap_task (tenant_id, task_id)
            """;
        String executable = """
            create unique index uk_task
            on ap_task (tenant_id, task_id)
            """;

        assertTrue(plan.prepare(source, executable).isPresent());
        assertTrue(plan.prepare(source, executable).isEmpty());
    }

    @Test
    void allowsARecreatedIndexAfterAnExplicitDrop() {
        var plan = new MySqlV50ExecutionPlan();
        String source = "create index idx_task on ap_task (tenant_id, task_id)";

        assertTrue(plan.prepare(source, source).isPresent());
        assertTrue(plan.prepare(
            "drop index idx_task on ap_task",
            "drop index idx_task on ap_task"
        ).isPresent());
        assertTrue(plan.prepare(source, source).isPresent());
    }

    @Test
    void rejectsConflictingOrNonIdempotentActiveDuplicates() {
        var plan = new MySqlV50ExecutionPlan();
        String first = "create index idx_task on ap_task (tenant_id, task_id)";
        String changed = "create index idx_task on ap_task (tenant_id, status)";

        assertTrue(plan.prepare(first, first).isPresent());
        assertThrows(FlywayException.class, () -> plan.prepare(first, first));
        assertThrows(FlywayException.class, () -> plan.prepare(
            "create index if not exists idx_task on ap_task (tenant_id, status)",
            changed
        ));
        assertFalse(changed.isBlank());
    }
}
