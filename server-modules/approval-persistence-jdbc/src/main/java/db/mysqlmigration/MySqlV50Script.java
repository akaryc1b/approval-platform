package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Loads and splits the governed compressed MySQL V50 schema snapshot. */
final class MySqlV50Script {

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

    private MySqlV50Script() {
    }

    static String decompress() {
        StringBuilder encoded = new StringBuilder();
        ClassLoader loader = MySqlV50Script.class.getClassLoader();
        for (String resource : BASELINE_RESOURCES) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new FlywayException(
                        "missing MySQL baseline resource: " + resource
                    );
                }
                encoded.append(new String(
                    input.readAllBytes(),
                    StandardCharsets.US_ASCII
                ).strip());
            } catch (IOException exception) {
                throw new FlywayException(
                    "MySQL baseline resource read failed",
                    exception
                );
            }
        }
        byte[] compressed = Base64.getDecoder().decode(encoded.toString());
        try (GZIPInputStream input = new GZIPInputStream(
            new ByteArrayInputStream(compressed)
        ); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new FlywayException(
                "MySQL baseline decompression failed",
                exception
            );
        }
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char value = script.charAt(index);
            char next = index + 1 < script.length()
                ? script.charAt(index + 1)
                : '\0';
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
