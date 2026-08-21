package io.github.akaryc1b.approval.persistence.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact relational fixture for complete MySQL approval-instance release provenance. */
final class MySqlApprovalProjectionProvenanceFixture {

    static final int DEFINITION_VERSION = 1;
    static final int FORM_VERSION = 1;
    static final int FORM_PACKAGE_VERSION = 3;
    static final int UI_SCHEMA_VERSION = 4;
    static final int RELEASE_VERSION = 2;

    static final String DEFINITION_HASH = "a".repeat(64);
    static final String RELEASE_PACKAGE_HASH = "b".repeat(64);
    static final String FORM_PACKAGE_HASH = "c".repeat(64);
    static final String UI_SCHEMA_HASH = "d".repeat(64);
    static final String FORM_HASH = "f".repeat(64);
    static final String COMPILED_ARTIFACT_HASH = "6".repeat(64);
    static final String BPMN_HASH = "7".repeat(64);
    static final String DEPLOYMENT_METADATA_HASH = "8".repeat(64);
    static final String COMPILER_VERSION = "compiler-1";
    static final String ENGINE_DEFINITION_ID = "engine-definition-release-2";

    private MySqlApprovalProjectionProvenanceFixture() {
    }

    static void reset(JdbcTemplate jdbc, String... tenantIds) {
        Objects.requireNonNull(jdbc, "jdbc must not be null");
        List<String> tenants = List.of(tenantIds);
        if (tenants.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", tenants.stream().map(ignored -> "?").toList());
        Object[] arguments = tenants.toArray();
        for (String table : List.of(
            "ap_approval_release_package",
            "ap_approval_compiled_artifact",
            "ap_approval_definition",
            "ap_approval_design_draft",
            "ap_form_package",
            "ap_form_design_draft",
            "ap_form_ui_schema",
            "ap_form_definition"
        )) {
            jdbc.update(
                "delete from " + table + " where tenant_id in (" + placeholders + ")",
                arguments
            );
        }
    }

    static void seed(
        JdbcTemplate jdbc,
        String tenantId,
        String definitionKey,
        Instant publishedAt
    ) {
        Objects.requireNonNull(jdbc, "jdbc must not be null");
        String exactTenant = requireText(tenantId, "tenantId");
        String exactDefinition = requireText(definitionKey, "definitionKey");
        Timestamp timestamp = Timestamp.from(
            AuditHashCanonicalizer.canonicalInstant(
                Objects.requireNonNull(publishedAt, "publishedAt must not be null")
            )
        );
        String formDraftId = fixtureUuid("form-draft", exactTenant, exactDefinition);
        String approvalDraftId = fixtureUuid("approval-draft", exactTenant, exactDefinition);

        jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, ?, 'fixture-v1', 'Projection fixture form',
                1, cast(? as json), ?, 'Fixture-Publisher', ?)
            """,
            exactTenant,
            exactDefinition,
            FORM_VERSION,
            "{\"fixture\":true}",
            FORM_HASH,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version,
                schema_version, name, section_count, schema_json,
                content_hash, published_by, published_at
            ) values (?, ?, ?, ?, 'fixture-ui-v1', 'Projection fixture UI',
                1, cast(? as json), ?, 'Fixture-Publisher', ?)
            """,
            exactTenant,
            exactDefinition,
            FORM_VERSION,
            UI_SCHEMA_VERSION,
            "{\"sections\":[{\"key\":\"fixture\"}]}",
            UI_SCHEMA_HASH,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name,
                form_version, ui_schema_version,
                form_schema_json, ui_schema_json,
                source_form_version, source_ui_schema_version,
                revision, status, published_package_version,
                created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, 'Projection fixture form draft',
                ?, ?, cast(? as json), cast(? as json),
                null, null, 1, 'DRAFT', null,
                'Fixture-Publisher', 'Fixture-Publisher', ?, ?)
            """,
            exactTenant,
            formDraftId,
            exactDefinition,
            FORM_VERSION,
            UI_SCHEMA_VERSION,
            "{\"fixture\":true}",
            "{\"sections\":[{\"key\":\"fixture\"}]}",
            timestamp,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_form_package (
                tenant_id, form_key, package_version,
                form_version, form_hash,
                ui_schema_version, ui_schema_hash,
                package_hash, source_draft_id,
                published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Fixture-Publisher', ?)
            """,
            exactTenant,
            exactDefinition,
            FORM_PACKAGE_VERSION,
            FORM_VERSION,
            FORM_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            FORM_PACKAGE_HASH,
            formDraftId,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_approval_design_draft (
                tenant_id, draft_id, definition_key, name,
                definition_version, approval_dsl_json,
                form_package_version, form_package_hash,
                source_definition_version, revision, status,
                published_definition_version, published_release_version,
                created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, 'Projection fixture approval draft',
                ?, cast(? as json), ?, ?,
                null, 1, 'DRAFT', null, null,
                'Fixture-Publisher', 'Fixture-Publisher', ?, ?)
            """,
            exactTenant,
            approvalDraftId,
            exactDefinition,
            DEFINITION_VERSION,
            "{\"definitionKey\":\"" + exactDefinition + "\"}",
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            timestamp,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_approval_definition (
                tenant_id, definition_key, definition_version,
                definition_hash, form_package_version, form_package_hash,
                approval_dsl_json, source_draft_id,
                published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as json), ?, 'Fixture-Publisher', ?)
            """,
            exactTenant,
            exactDefinition,
            DEFINITION_VERSION,
            DEFINITION_HASH,
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            "{\"definitionKey\":\"" + exactDefinition + "\"}",
            approvalDraftId,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_approval_compiled_artifact (
                tenant_id, definition_key, definition_version,
                definition_hash, form_version, form_hash,
                compiler_version, resource_name, bpmn_xml,
                compiled_artifact_hash, bpmn_hash, created_at
            ) values (?, ?, ?, ?, ?, ?, ?,
                'projection-fixture.bpmn20.xml', '<definitions/>', ?, ?, ?)
            """,
            exactTenant,
            exactDefinition,
            DEFINITION_VERSION,
            DEFINITION_HASH,
            FORM_VERSION,
            FORM_HASH,
            COMPILER_VERSION,
            COMPILED_ARTIFACT_HASH,
            BPMN_HASH,
            timestamp
        );
        jdbc.update(
            """
            insert into ap_approval_release_package (
                tenant_id, definition_key, release_version,
                definition_version, definition_hash,
                form_package_version, form_package_hash,
                form_version, form_hash,
                ui_schema_version, ui_schema_hash,
                compiler_version, bpmn_resource_name, bpmn_artifact,
                compiled_artifact_hash, bpmn_hash,
                dmn_artifact, dmn_hash,
                deployment_metadata_hash, package_hash,
                source_draft_id, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'projection-fixture.bpmn20.xml', '<definitions/>', ?, ?,
                null, null, ?, ?, ?, 'Fixture-Publisher', ?)
            """,
            exactTenant,
            exactDefinition,
            RELEASE_VERSION,
            DEFINITION_VERSION,
            DEFINITION_HASH,
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            FORM_VERSION,
            FORM_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            COMPILER_VERSION,
            COMPILED_ARTIFACT_HASH,
            BPMN_HASH,
            DEPLOYMENT_METADATA_HASH,
            RELEASE_PACKAGE_HASH,
            approvalDraftId,
            timestamp
        );
    }

    private static String fixtureUuid(
        String namespace,
        String tenantId,
        String definitionKey
    ) {
        return UUID.nameUUIDFromBytes(
            (namespace + ':' + tenantId + ':' + definitionKey)
                .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
