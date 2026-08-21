package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySql84ProductionTestServerTest {

    @Test
    void retainsTheExactProductionEquivalentServerPosture() {
        List<String> command = Arrays.asList(
            MySql84ProductionTestServer.command()
        );

        assertTrue(command.contains("--default-time-zone=+00:00"));
        assertTrue(command.contains("--character-set-server=utf8mb4"));
        assertTrue(command.contains("--collation-server=utf8mb4_0900_as_cs"));
        assertTrue(command.contains("--transaction-isolation=READ-COMMITTED"));
        assertTrue(command.contains("--innodb-strict-mode=ON"));
        assertTrue(command.contains("--log-bin-trust-function-creators=ON"));
        assertTrue(command.stream().anyMatch(value -> value.contains(
            "STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
        )));

        String all = String.join(" ", command).toLowerCase(Locale.ROOT);
        assertFalse(all.contains("skip-log-bin"));
        assertFalse(all.contains("disable-log-bin"));
        assertFalse(all.contains("log-bin=off"));
        assertFalse(all.contains("foreign_key_checks"));
    }

    @Test
    void returnsADefensiveCommandCopy() {
        String[] first = MySql84ProductionTestServer.command();
        first[0] = "--tampered";

        assertEquals(
            "--default-time-zone=+00:00",
            MySql84ProductionTestServer.command()[0]
        );
    }
}
