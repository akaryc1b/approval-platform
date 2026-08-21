package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50TriggerInstallationAuthorityTest {

    @Test
    void acceptsTrustedCreatorsWhenBinaryLoggingIsEnabled() {
        assertDoesNotThrow(() ->
            MySqlV50TriggerInstallationAuthority.require(true, true)
        );
    }

    @Test
    void acceptsUntrustedCreatorsOnlyWhenBinaryLoggingIsDisabled() {
        assertDoesNotThrow(() ->
            MySqlV50TriggerInstallationAuthority.require(false, false)
        );
    }

    @Test
    void rejectsUntrustedCreatorsBeforeFlywayWhenBinaryLoggingIsEnabled() {
        FlywayException failure = assertThrows(
            FlywayException.class,
            () -> MySqlV50TriggerInstallationAuthority.require(true, false)
        );

        assertEquals(
            MySqlV50TriggerInstallationAuthority.requirement(),
            failure.getMessage()
        );
        assertTrue(failure.getMessage().contains(
            "@@GLOBAL.log_bin_trust_function_creators=ON"
        ));
        assertTrue(failure.getMessage().contains("before Flyway"));
    }

    @Test
    void readsOnlyClosedBooleanRepresentations() {
        assertTrue(value(true));
        assertTrue(value(1));
        assertTrue(value(1L));
        assertTrue(value(new BigDecimal("1")));
        assertTrue(value("ON"));
        assertTrue(value(" true "));
        assertFalse(value(false));
        assertFalse(value(0));
        assertFalse(value(0L));
        assertFalse(value(new BigDecimal("0")));
        assertFalse(value("OFF"));
        assertFalse(value(" false "));
    }

    @Test
    void rejectsNullFractionalAndUnknownGlobalVariableValues() {
        assertThrows(FlywayException.class, () -> value(null));
        assertThrows(FlywayException.class, () -> value(2));
        assertThrows(FlywayException.class, () -> value(0.5d));
        assertThrows(FlywayException.class, () -> value("ENABLED"));
        assertThrows(FlywayException.class, () -> value(new Object()));
    }

    @Test
    void authorityProbeIsReadOnlyAndCannotWeakenTheServer() {
        String query = MySqlV50TriggerInstallationAuthority
            .globalVariableQuery()
            .toLowerCase(Locale.ROOT);

        assertTrue(query.startsWith("select"));
        assertTrue(query.contains("@@global.log_bin"));
        assertTrue(query.contains("@@global.log_bin_trust_function_creators"));
        assertFalse(query.contains("set global"));
        assertFalse(query.contains("set persist"));
        assertFalse(query.contains("skip-log-bin"));
        assertFalse(query.contains("disable-log-bin"));
        assertFalse(query.contains("log_bin=off"));
    }

    private static boolean value(Object value) {
        return MySqlV50TriggerInstallationAuthority.booleanValue(
            value,
            "test-variable"
        );
    }
}
