package io.github.akaryc1b.approval.persistence.jdbc;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;

final class JdbcApprovalReleaseLifecycleMigrationFixtures {

    static final String TENANT = "tenant-reactivation";
    static final String DEFINITION_KEY = "purchasePayment";
    static final String RELEASE_ONE_HASH = "1".repeat(64);
    static final String RELEASE_TWO_HASH = "2".repeat(64);

    private JdbcApprovalReleaseLifecycleMigrationFixtures() {
    }

    static void seedRollbackHistory(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                statement.execute(releaseSql());
                statement.execute(activationSql());
                statement.execute(effectiveReleaseSql());
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private static String releaseSql() {
        return """
            insert into ap_approval_release_package (
              tenant_id,definition_key,release_version,definition_version,definition_hash,
              form_package_version,form_package_hash,form_version,form_hash,
              ui_schema_version,ui_schema_hash,compiler_version,bpmn_resource_name,bpmn_artifact,
              compiled_artifact_hash,bpmn_hash,dmn_artifact,dmn_hash,deployment_metadata_hash,
              package_hash,source_draft_id,published_by,published_at
            ) values
              ('%s','%s',1,1,'%s',1,'%s',1,'%s',1,'%s','compiler-v1',
               'purchase-payment.bpmn20.xml','<definitions/>','%s','%s',null,null,'%s','%s',
               '20000000-0000-0000-0000-000000000001'::uuid,'publisher',timestamptz '2026-01-01 00:00:00+00'),
              ('%s','%s',2,2,'%s',1,'%s',1,'%s',1,'%s','compiler-v1',
               'purchase-payment.bpmn20.xml','<definitions/>','%s','%s',null,null,'%s','%s',
               '20000000-0000-0000-0000-000000000002'::uuid,'publisher',timestamptz '2026-01-01 00:01:00+00')
            """.formatted(
                TENANT, DEFINITION_KEY, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "e".repeat(64), "f".repeat(64), "0".repeat(64), RELEASE_ONE_HASH,
                TENANT, DEFINITION_KEY, "3".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "4".repeat(64), "5".repeat(64), "6".repeat(64), RELEASE_TWO_HASH
            );
    }

    private static String activationSql() {
        return """
            insert into ap_approval_release_activation_history (
              activation_id,tenant_id,definition_key,release_version,previous_release_version,
              release_package_hash,definition_version,form_package_version,compiler_version,
              engine_deployment_id,engine_definition_id,engine_version,action,revision,
              activated_by,activated_at,change_reason,request_id,trace_id
            ) values
              ('10000000-0000-0000-0000-000000000001','%s','%s',1,null,'%s',1,1,'compiler-v1',
               'engine-deployment-r1','engine-definition-r1',1,'ACTIVATE',1,'operator',
               timestamptz '2026-01-01 00:02:00+00','governed transition','request-1','trace-1'),
              ('10000000-0000-0000-0000-000000000002','%s','%s',2,1,'%s',2,1,'compiler-v1',
               'engine-deployment-r2','engine-definition-r2',2,'ACTIVATE',2,'operator',
               timestamptz '2026-01-01 00:03:00+00','governed transition','request-2','trace-2'),
              ('10000000-0000-0000-0000-000000000003','%s','%s',1,2,'%s',1,1,'compiler-v1',
               'engine-deployment-r1','engine-definition-r1',1,'ROLLBACK',3,'operator',
               timestamptz '2026-01-01 00:04:00+00','governed transition','request-3','trace-3')
            """.formatted(
                TENANT, DEFINITION_KEY, RELEASE_ONE_HASH,
                TENANT, DEFINITION_KEY, RELEASE_TWO_HASH,
                TENANT, DEFINITION_KEY, RELEASE_ONE_HASH
            );
    }

    private static String effectiveReleaseSql() {
        return """
            insert into ap_approval_effective_release (
              tenant_id,definition_key,effective_release_version,previous_release_version,
              release_package_hash,definition_version,definition_hash,
              form_package_version,form_package_hash,form_schema_version,form_schema_hash,
              ui_schema_version,ui_schema_hash,compiler_version,compiled_artifact_hash,
              bpmn_hash,deployment_metadata_hash,engine_deployment_id,engine_definition_id,
              engine_version,status,revision,activated_by,activated_at,change_reason,request_id,trace_id
            ) values (
              '%s','%s',1,2,'%s',1,'%s',1,'%s',1,'%s',1,'%s','compiler-v1','%s','%s','%s',
              'engine-deployment-r1','engine-definition-r1',1,'ACTIVE',3,'operator',
              timestamptz '2026-01-01 00:04:00+00','rollback to release one','request-3','trace-3'
            )
            """.formatted(
                TENANT, DEFINITION_KEY, RELEASE_ONE_HASH, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64), "0".repeat(64)
            );
    }
}
