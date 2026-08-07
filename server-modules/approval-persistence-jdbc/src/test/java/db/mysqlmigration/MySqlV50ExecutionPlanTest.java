package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50ExecutionPlanTest {

    @Test
    void skipsOnlyTheGovernedExactActiveIdempotentDuplicate() {
        var plan = new MySqlV50ExecutionPlan();
        String definition = """
            create unique index uk_approval_task_tenant_task
            on ap_approval_task (tenant_id, task_id)
            """;

        assertTrue(plan.prepare(definition, definition).isPresent());
        assertTrue(plan.prepare(definition, definition).isEmpty());
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
    void rejectsUnlistedOrChangedActiveDuplicates() {
        var plan = new MySqlV50ExecutionPlan();
        String ordinary = "create index idx_task on ap_task (tenant_id, task_id)";

        assertTrue(plan.prepare(ordinary, ordinary).isPresent());
        assertThrows(FlywayException.class, () -> plan.prepare(ordinary, ordinary));

        var governedPlan = new MySqlV50ExecutionPlan();
        String governed = "create unique index uk_approval_task_tenant_task "
            + "on ap_approval_task (tenant_id, task_id)";
        String changed = "create unique index uk_approval_task_tenant_task "
            + "on ap_approval_task (tenant_id, task_id, status)";
        assertTrue(governedPlan.prepare(governed, governed).isPresent());
        assertThrows(
            FlywayException.class,
            () -> governedPlan.prepare(changed, changed)
        );
    }
}
