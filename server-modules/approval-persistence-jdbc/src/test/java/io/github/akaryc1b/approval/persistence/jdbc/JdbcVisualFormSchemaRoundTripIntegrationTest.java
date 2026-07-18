package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormDefinition.SelectOption;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcVisualFormSchemaRoundTripIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_visual_schema_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcApprovalFormStore store;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("truncate table ap_form_definition cascade");
        store = new JdbcApprovalFormStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void preservesNewFieldTypesAndStaticOptionsInJsonb() {
        FormDefinition definition = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "visual-roundtrip",
            1,
            "Visual roundtrip",
            List.of(
                field("notes", FieldType.TEXTAREA, FieldConstraints.text(2000)),
                field(
                    "quantity",
                    FieldType.NUMBER,
                    FieldConstraints.number(3, BigDecimal.ZERO)
                ),
                field("deliveryDate", FieldType.DATE, FieldConstraints.none()),
                field("meetingAt", FieldType.DATETIME, FieldConstraints.none()),
                field("confirmed", FieldType.BOOLEAN, FieldConstraints.none()),
                new FormField(
                    "category",
                    FieldType.SELECT,
                    "Category",
                    false,
                    FieldConstraints.selection(0, false),
                    FormDefinition.DefaultValue.none(),
                    List.of(
                        new SelectOption("A", "Option A", false),
                        new SelectOption("B", "Option B", true)
                    )
                )
            )
        );
        String contentHash = new FormSchemaHasher().hash(definition);
        store.save(new PublishedForm(
            "tenant-visual",
            definition,
            contentHash,
            "publisher",
            Instant.parse("2026-07-18T14:00:00Z")
        ));

        PublishedForm loaded = store.find("tenant-visual", "visual-roundtrip", 1)
            .orElseThrow();
        assertEquals(contentHash, loaded.contentHash());
        assertEquals(
            List.of(
                FieldType.TEXTAREA,
                FieldType.NUMBER,
                FieldType.DATE,
                FieldType.DATETIME,
                FieldType.BOOLEAN,
                FieldType.SELECT
            ),
            loaded.definition().fields().stream().map(FormField::type).toList()
        );
        FormField select = loaded.definition().fields().get(5);
        assertEquals("A", select.options().get(0).value());
        assertEquals("Option B", select.options().get(1).label());
        assertEquals(true, select.options().get(1).disabled());
    }

    private static FormField field(
        String key,
        FieldType type,
        FieldConstraints constraints
    ) {
        return new FormField(key, type, key, false, constraints);
    }
}
