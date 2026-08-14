package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/** MySQL 8.4 current-schema baseline. PostgreSQL V1-V50 remain immutable and separate. */
public final class MySqlV50Baseline implements JavaMigration {

    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("50");
    private static final String DESCRIPTION = "Baseline approval platform";
    private static final int PREVIOUS_BASELINE_CHECKSUM = -392744558;
    private static final String D7_APPEND_ONLY_GUARDS = """
        create trigger trg_process_migration_canary_selection_guard_v47
         before update on ap_process_migration_canary_selection
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_canary_selection_delete_guard_v47
         before delete on ap_process_migration_canary_selection
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_run_guard_v47
         before update on ap_process_migration_orchestration_run
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_run_delete_guard_v47
         before delete on ap_process_migration_orchestration_run
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_event_guard_v47
         before update on ap_process_migration_orchestration_event
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_event_delete_guard_v47
         before delete on ap_process_migration_orchestration_event
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_batch_guard_v47
         before update on ap_process_migration_orchestration_batch
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_orchestration_batch_delete_guard_v47
         before delete on ap_process_migration_orchestration_batch
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_kill_switch_observation_guard_v47
         before update on ap_process_migration_kill_switch_observation
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        create trigger trg_process_migration_kill_switch_observation_delete_guard_v47
         before delete on ap_process_migration_kill_switch_observation
         for each row
         signal sqlstate '45000'
          set message_text='M5-D7 evidence is append-only';
        """;
    private static final int BASELINE_CHECKSUM =
        31 * PREVIOUS_BASELINE_CHECKSUM + D7_APPEND_ONLY_GUARDS.hashCode();

    @Override
    public MigrationVersion getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Integer getChecksum() {
        return BASELINE_CHECKSUM;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        MySqlV50TriggerInstallationAuthority.require(context.getConnection());
        List<String> statements = MySqlV50Script.split(baselineScript());
        MySqlV50ExecutionPlan executionPlan = new MySqlV50ExecutionPlan();
        int index = 0;
        try (Statement statement = context.getConnection().createStatement()) {
            for (String command : statements) {
                index++;
                Optional<String> normalized = executableForMySql84(command);
                if (normalized.isEmpty()) {
                    continue;
                }
                Optional<String> executable = executionPlan.prepare(
                    command,
                    normalized.orElseThrow()
                );
                if (executable.isEmpty()) {
                    continue;
                }
                String sql = executable.orElseThrow();
                try {
                    statement.execute(sql);
                } catch (SQLException exception) {
                    if (!isIgnorableCleanBaselineForeignKeyDrop(sql, exception)) {
                        throw exception;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new FlywayException(
                "MySQL 8.4 baseline statement " + index + " failed",
                exception
            );
        }
    }

    static Optional<String> executableForMySql84(String command) {
        return MySqlV50Normalizer.executable(command);
    }

    static boolean isCleanBaselineOnlyHistoricalBackfill(String command) {
        return MySqlV50Normalizer.isHistoricalBackfill(command);
    }

    static String normalizeForMySql84(String command) {
        return MySqlV50Normalizer.normalize(command);
    }

    static boolean isIgnorableCleanBaselineForeignKeyDrop(
        String command,
        SQLException exception
    ) {
        return MySqlV50Normalizer.isIgnorableHistoricalForeignKeyDrop(
            command,
            exception
        );
    }

    static String decompressBaseline() {
        return baselineScript();
    }

    static List<String> splitStatements(String script) {
        return MySqlV50Script.split(script);
    }

    private static String baselineScript() {
        return MySqlV50Script.decompress() + "\n;\n" + D7_APPEND_ONLY_GUARDS;
    }
}
