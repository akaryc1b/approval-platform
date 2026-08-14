package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Test-only MySQL migration/runtime identity separation for real containers. */
public final class MySqlTestDatabaseAuthority {

    private static final String MIGRATION_USER = "root";

    private MySqlTestDatabaseAuthority() {
    }

    public static FluentConfiguration flyway(
        MySQLContainer mysql,
        DataSource runtimeDataSource
    ) {
        MySQLContainer exactContainer = Objects.requireNonNull(
            mysql,
            "mysql must not be null"
        );
        if (MIGRATION_USER.equalsIgnoreCase(exactContainer.getUsername())) {
            throw new IllegalArgumentException(
                "MySQL test runtime identity must not be the migration identity"
            );
        }
        String jdbcUrl = jdbcUrl(runtimeDataSource);
        return Flyway.configure().dataSource(
            new DriverManagerDataSource(
                jdbcUrl,
                MIGRATION_USER,
                exactContainer.getPassword()
            )
        );
    }

    public static DataSource createLeastPrivilegeRuntimeDataSource(
        MySQLContainer mysql,
        DataSource jdbcUrlSource,
        String runtimeUser,
        String runtimePassword
    ) {
        MySQLContainer exactContainer = Objects.requireNonNull(
            mysql,
            "mysql must not be null"
        );
        String user = accountToken(runtimeUser, "runtimeUser");
        String password = requireText(runtimePassword, "runtimePassword");
        String database = accountToken(
            exactContainer.getDatabaseName(),
            "databaseName"
        );
        String jdbcUrl = jdbcUrl(jdbcUrlSource);
        DataSource migrationDataSource = new DriverManagerDataSource(
            jdbcUrl,
            MIGRATION_USER,
            exactContainer.getPassword()
        );
        String account = sqlLiteral(user) + "@'%'";
        try (Connection connection = migrationDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            List<String> applicationTables = applicationTables(statement, database);
            if (applicationTables.isEmpty()) {
                throw new IllegalStateException(
                    "MySQL migration produced no governed application tables"
                );
            }
            statement.execute("drop user if exists " + account);
            statement.execute(
                "create user " + account + " identified by " + sqlLiteral(password)
            );
            for (String table : applicationTables) {
                statement.execute(
                    "grant select,insert,update,delete on `"
                        + database
                        + "`.`"
                        + table
                        + "` to "
                        + account
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "failed to create least-privilege MySQL runtime identity",
                exception
            );
        }
        return new DriverManagerDataSource(jdbcUrl, user, password);
    }

    private static List<String> applicationTables(
        Statement statement,
        String database
    ) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(
            "select table_name from information_schema.tables "
                + "where table_schema="
                + sqlLiteral(database)
                + " and table_type='BASE TABLE' "
                + "and left(table_name,3)='ap_' order by table_name"
        )) {
            while (result.next()) {
                tables.add(accountToken(result.getString(1), "applicationTable"));
            }
        }
        return List.copyOf(tables);
    }

    private static String jdbcUrl(DataSource dataSource) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "runtimeDataSource must not be null"
        );
        try (Connection connection = source.getConnection()) {
            return requireText(
                connection.getMetaData().getURL(),
                "runtime JDBC URL"
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "failed to resolve MySQL runtime JDBC URL",
                exception
            );
        }
    }

    private static String accountToken(String value, String name) {
        String exact = requireText(value, name);
        if (!exact.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(
                name + " contains unsupported account characters"
            );
        }
        return exact;
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(
            value,
            name + " must not be null"
        ).strip();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
