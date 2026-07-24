package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;
import java.util.zip.CRC32;

/** Deterministic V38 migration assembled from immutable classpath SQL fragments. */
public final class V38__Create_immutable_process_migration_plans
    extends BaseJavaMigration {

    private static final List<String> PARTS = List.of(
        "db/migration/v38/part-01.sqlpart",
        "db/migration/v38/part-02.sqlpart",
        "db/migration/v38/part-03.sqlpart",
        "db/migration/v38/part-04.sqlpart",
        "db/migration/v38/part-05.sqlpart",
        "db/migration/v38/part-06.sqlpart",
        "db/migration/v38/part-07.sqlpart"
    );

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(readSql());
        }
    }

    @Override
    public Integer getChecksum() {
        CRC32 checksum = new CRC32();
        checksum.update(readSql().getBytes(StandardCharsets.UTF_8));
        return (int) checksum.getValue();
    }

    static String readSql() {
        StringBuilder sql = new StringBuilder(32_768);
        ClassLoader loader = V38__Create_immutable_process_migration_plans.class
            .getClassLoader();
        for (String resource : PARTS) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing V38 migration fragment: " + resource);
                }
                sql.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Cannot read V38 migration fragment: " + resource,
                    exception
                );
            }
        }
        return sql.toString();
    }
}
