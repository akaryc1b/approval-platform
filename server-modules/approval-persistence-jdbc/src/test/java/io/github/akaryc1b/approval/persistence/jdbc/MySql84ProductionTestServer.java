package io.github.akaryc1b.approval.persistence.jdbc;

/** Shared MySQL 8.4 Testcontainers server posture for production-equivalent JDBC suites. */
public final class MySql84ProductionTestServer {

    private static final String[] COMMAND = {
        "--default-time-zone=+00:00",
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_0900_as_cs",
        "--transaction-isolation=READ-COMMITTED",
        "--innodb-strict-mode=ON",
        "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
            + "NO_ENGINE_SUBSTITUTION",
        "--log-bin-trust-function-creators=ON"
    };

    private MySql84ProductionTestServer() {
    }

    public static String[] command() {
        return COMMAND.clone();
    }
}
