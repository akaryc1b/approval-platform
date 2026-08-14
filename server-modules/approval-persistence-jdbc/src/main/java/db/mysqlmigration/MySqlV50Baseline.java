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
    private static final int BASELINE_SCHEMA_CHECKSUM = -392744558;

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
        return 31 * BASELINE_SCHEMA_CHECKSUM
            + MySqlV50D7OrchestrationGuards.checksum();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<String> statements = MySqlV50Script.split(MySqlV50Script.decompress());
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
            for (String guard : d7GuardStatements()) {
                index++;
                statement.execute(guard);
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
        return MySqlV50Script.decompress();
    }

    static List<String> splitStatements(String script) {
        return MySqlV50Script.split(script);
    }

    static List<String> d7GuardStatements() {
        return MySqlV50D7OrchestrationGuards.statements();
    }
}
