package db.mysqlmigration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlV50H8AiEvidenceSchemaContractTest {

    @Test
    void installsExactP4SchemaAuthorityWithoutGlobalOrConstraintBypass() {
        List<String> statements = MySqlV50AiEvidenceGuards.statements();
        String sql = String.join("\n", statements).toLowerCase();

        assertEquals(10, statements.size());
        assertTrue(sql.contains("add column tombstone_hash char(64)"));
        assertTrue(sql.contains("trg_ai_assistance_evidence_update_guard_v49"));
        assertTrue(sql.contains("trg_ai_assistance_evidence_delete_guard_v49"));
        assertTrue(sql.contains("trg_ai_assistance_event_before_insert_v49"));
        assertTrue(sql.contains("trg_ai_assistance_event_after_insert_v49"));
        assertTrue(sql.contains("trg_ai_assistance_state_before_insert_v49"));
        assertTrue(sql.contains("trg_ai_assistance_state_before_update_v49"));
        assertTrue(sql.contains("p4 tombstone event lacks exact active predecessor state"));
        assertTrue(sql.contains("p4 tombstone event evidence is incomplete"));
        assertTrue(sql.contains("p4 tombstone event lost evidence-state cas"));
        assertTrue(sql.contains("p4 evidence state lacks matching stored event"));
        assertTrue(sql.contains("p4 evidence state lacks matching tombstone event"));

        assertFalse(sql.contains("set global"));
        assertFalse(sql.contains("set persist"));
        assertFalse(sql.contains("foreign_key_checks"));
        assertFalse(sql.contains("insert ignore"));
        assertFalse(sql.contains("on duplicate key update"));
        assertFalse(sql.contains("replace into"));
        assertFalse(sql.contains("disable"));
        assertFalse(sql.contains("super privilege"));
    }

    @Test
    void governedV50ChecksumIncludesTheH8Authority() {
        assertEquals(-547102957, new MySqlV50Baseline().getChecksum());
    }
}
