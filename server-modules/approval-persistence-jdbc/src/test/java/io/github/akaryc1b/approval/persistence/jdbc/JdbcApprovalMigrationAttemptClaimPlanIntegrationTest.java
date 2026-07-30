package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptClaimPlanIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private static final UUID TARGET_INTENT = UUID.fromString(
        "8a000000-0000-0000-0000-000000000001"
    );

    @Test
    void tenantPrefixedBoundedClaimUsesV40IndexAcrossFiveThousandAttempts() throws Exception {
        seedScaleAttempts();
        jdbc.execute("analyze ap_process_migration_attempt");

        String planJson = jdbc.queryForObject(
            """
            explain (format json)
            select payload_json::text
            from ap_process_migration_attempt
            where tenant_id=? and intent_id=?
              and (status='PENDING' or (status='CLAIMED' and lease_until<=?))
            order by created_at,attempt_id
            limit 10
            for update skip locked
            """,
            (resultSet, rowNumber) -> resultSet.getString(1),
            TENANT,
            TARGET_INTENT,
            NOW.plusSeconds(3_600).atOffset(ZoneOffset.UTC)
        );
        JsonNode plan = new ObjectMapper()
            .findAndRegisterModules()
            .readTree(planJson)
            .path(0)
            .path("Plan");
        String evidence = plan.toString();

        assertEquals("Limit", plan.path("Node Type").asText());
        assertTrue(plan.path("Plan Rows").asLong() <= 10);
        assertTrue(evidence.contains("idx_process_migration_attempt_claim_v40"));
        assertTrue(evidence.contains("tenant_id"));
        assertTrue(evidence.contains("intent_id"));
        assertFalse(evidence.contains("\"Node Type\":\"Seq Scan\""));
        assertEquals(5_000, count("ap_process_migration_attempt"));
    }

    private void seedScaleAttempts() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                try {
                    statement.executeUpdate("""
                        insert into ap_process_migration_attempt (
                          tenant_id,attempt_id,intent_id,approval_instance_id,
                          attempt_number,parent_attempt_id,status,revision,engine_outcome,
                          lease_owner,lease_until,expected_binding_evidence_hash,
                          payload_json,created_at,updated_at,lease_actor,
                          engine_request_reference,failure_class,error_summary
                        )
                        select
                          case when series<=1000 then '%s'
                            else 'tenant-scale-noise-'||mod(series,4)::text end,
                          md5('d2-attempt-'||series::text)::uuid,
                          case when series<=1000 then '%s'::uuid
                            else md5('d2-intent-'||mod(series,100)::text)::uuid end,
                          md5('d2-instance-'||series::text)::uuid,
                          1,null,
                          case when series<=10 then 'PENDING' else 'CLAIMED' end,
                          1,'NOT_REQUESTED',
                          case when series<=10 then null else 'scale-worker' end,
                          case when series<=10 then null
                            else timestamptz '%s' end,
                          repeat('a',64),'{}'::jsonb,
                          timestamptz '%s'+series*interval '1 millisecond',
                          timestamptz '%s'+series*interval '1 millisecond',
                          case when series<=10 then null else 'scale-worker' end,
                          null,'NONE',null
                        from generate_series(1,5000) series
                        """.formatted(
                        TENANT,
                        TARGET_INTENT,
                        NOW.plusSeconds(86_400),
                        NOW,
                        NOW
                    ));
                } finally {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }
}
