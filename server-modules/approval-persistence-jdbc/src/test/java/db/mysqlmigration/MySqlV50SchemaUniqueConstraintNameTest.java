package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50SchemaUniqueConstraintNameTest {

    @Test
    void keepsBothSlaTimestampChecksWithSchemaUniqueNames() {
        var statements = MySqlV50Baseline.splitStatements(
            MySqlV50Baseline.decompressBaseline()
        );
        String policy = executableContaining(
            statements,
            "create table ap_sla_policy ("
        );
        String version = executableContaining(
            statements,
            "create table ap_sla_policy_version ("
        );

        assertEquals(-392744557, new MySqlV50Baseline().getChecksum());
        assertTrue(policy.contains(
            "constraint chk_sla_policy_timestamps check"
        ));
        assertTrue(version.contains(
            "constraint chk_sla_policy_version_timestamps check"
        ));
        assertFalse(version.contains(
            "constraint chk_sla_policy_timestamps check"
        ));
    }

    private static String executableContaining(
        java.util.List<String> statements,
        String marker
    ) {
        return statements.stream()
            .filter(statement -> statement.contains(marker))
            .map(MySqlV50Baseline::executableForMySql84)
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing MySQL V50 statement: " + marker
            ));
    }
}
