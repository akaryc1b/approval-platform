package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** MySQL 8.4 current-schema baseline. PostgreSQL V1-V50 remain immutable and separate. */
public final class MySqlV50Baseline implements JavaMigration {

    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("50");
    private static final String DESCRIPTION = "Baseline approval platform";
    private static final int BASELINE_CHECKSUM = -392744552;
    private static final Pattern ADD_COLUMN_IF_NOT_EXISTS = Pattern.compile(
        "\\badd\\s+column\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREATE_UNIQUE_INDEX_IF_NOT_EXISTS = Pattern.compile(
        "\\bcreate\\s+unique\\s+index\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREATE_INDEX_IF_NOT_EXISTS = Pattern.compile(
        "\\bcreate\\s+index\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADD_CONSTRAINT_IF_NOT_EXISTS = Pattern.compile(
        "\\badd\\s+constraint\\s+if\\s+not\\s+exists\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final List<String> BASELINE_RESOURCES = List.of(
        "db/mysqlmigration/baseline-001.b64",
        "db/mysqlmigration/baseline-002.b64",
        "db/mysqlmigration/baseline-003.b64",
        "db/mysqlmigration/baseline-004.b64",
        "db/mysqlmigration/baseline-005.b64",
        "db/mysqlmigration/baseline-006.b64",
        "db/mysqlmigration/baseline-007.b64",
        "db/mysqlmigration/baseline-008.b64",
        "db/mysqlmigration/baseline-009.b64"
    );

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
        List<String> statements = splitStatements(decompressBaseline());
        int index = 0;
        try (Statement statement = context.getConnection().createStatement()) {
            for (String command : statements) {
                index++;
                statement.execute(normalizeForMySql84(command));
            }
        } catch (SQLException exception) {
            throw new FlywayException(
                "MySQL 8.4 baseline statement " + index + " failed",
                exception
            );
        }
    }

    static String normalizeForMySql84(String command) {
        String normalized = ADD_COLUMN_IF_NOT_EXISTS.matcher(command)
            .replaceAll("add column");
        normalized = CREATE_UNIQUE_INDEX_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("create unique index");
        normalized = CREATE_INDEX_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("create index");
        return ADD_CONSTRAINT_IF_NOT_EXISTS.matcher(normalized)
            .replaceAll("add constraint");
    }

    static String decompressBaseline() {
        StringBuilder encoded = new StringBuilder();
        ClassLoader loader = MySqlV50Baseline.class.getClassLoader();
        for (String resource : BASELINE_RESOURCES) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new FlywayException("missing MySQL baseline resource: " + resource);
                }
                encoded.append(new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip());
            } catch (IOException exception) {
                throw new FlywayException("MySQL baseline resource read failed", exception);
            }
        }
        byte[] compressed = Base64.getDecoder().decode(encoded.toString());
        try (GZIPInputStream input = new GZIPInputStream(
            new ByteArrayInputStream(compressed)
        ); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new FlywayException("MySQL baseline decompression failed", exception);
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                if (value == '\n') {
                    lineComment = false;
                    current.append(value);
                }
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!quoted && value == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!quoted && value == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (value == '\'') {
                current.append(value);
                if (quoted && next == '\'') {
                    current.append(next);
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (!quoted && value == ';') {
                String command = current.toString().strip();
                if (!command.isEmpty()) {
                    statements.add(command);
                }
                current.setLength(0);
                continue;
            }
            current.append(value);
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }
}
