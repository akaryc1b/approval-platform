package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateException;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDefinitionJacksonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTemplateManagementJsonCodecTest {

    private ObjectMapper mapper;
    private ProcessTemplateManagementJsonCodec codec;
    private JsonNode templatePackage;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper().findAndRegisterModules();
        ApprovalDefinitionJacksonSupport.configure(mapper);
        codec = new ProcessTemplateManagementJsonCodec(
            mapper,
            new ProcessTemplatePackageJsonCodec(mapper),
            new ApprovalArtifactTransferJsonCodec(mapper)
        );
        try (InputStream input = getClass().getResourceAsStream(
            "/templates/m6c/valid-template-package.json"
        )) {
            templatePackage = mapper.readTree(java.util.Objects.requireNonNull(input));
        }
    }

    @Test
    void previewInjectsTrustedTenantIntoRequestAndBindings() throws Exception {
        ObjectNode root = previewRoot();

        var decoded = codec.decodePreview(mapper.writeValueAsBytes(root), "tenant-authoritative");

        assertEquals("tenant-authoritative", decoded.previewRequest().targetTenantId());
        assertEquals(1, decoded.previewRequest().bindings().size());
        assertEquals(
            "tenant-authoritative",
            decoded.previewRequest().bindings().get(0).targetTenantId()
        );
        assertEquals(BindingKind.FORM_PACKAGE,
            decoded.previewRequest().bindings().get(0).kind());
        assertFalse(root.has("targetTenantId"));
    }

    @Test
    void unknownTopLevelAuthorityFieldIsRejected() throws Exception {
        ObjectNode root = previewRoot();
        root.put("tenantRegistry", "caller-controlled");

        ProcessTemplateException failure = assertThrows(
            ProcessTemplateException.class,
            () -> codec.decodePreview(mapper.writeValueAsBytes(root), "tenant-a")
        );

        assertEquals(
            "management import request contains unknown fields [tenantRegistry]",
            failure.getMessage()
        );
    }

    @Test
    void duplicateJsonKeysAreRejected() throws Exception {
        String packageJson = mapper.writeValueAsString(templatePackage);
        String body = "{\"templatePackage\":" + packageJson
            + ",\"targetDefinitionKey\":\"expenseImported\""
            + ",\"targetDefinitionVersion\":5"
            + ",\"targetDraftName\":\"First\""
            + ",\"targetDraftName\":\"Second\""
            + ",\"bindings\":[]}";

        ProcessTemplateException failure = assertThrows(
            ProcessTemplateException.class,
            () -> codec.decodePreview(body.getBytes(StandardCharsets.UTF_8), "tenant-a")
        );

        assertEquals("management import request is not valid strict JSON", failure.getMessage());
    }

    @Test
    void oversizedManagementRequestIsRejectedBeforeParsing() {
        byte[] body = new byte[ProcessTemplateManagementJsonCodec.MAX_REQUEST_BYTES + 1];

        assertThrows(ProcessTemplateException.PackageTooLarge.class,
            () -> codec.decodePreview(body, "tenant-a"));
    }

    @Test
    void bindingCannotCarryTenantOperatorPermissionOrEvidenceFields() throws Exception {
        ObjectNode root = previewRoot();
        ObjectNode binding = (ObjectNode) root.withArray("bindings").get(0);
        binding.put("targetTenantId", "tenant-b");

        assertThrows(ProcessTemplateException.class,
            () -> codec.decodePreview(mapper.writeValueAsBytes(root), "tenant-a"));
    }

    private ObjectNode previewRoot() {
        ObjectNode root = mapper.createObjectNode();
        root.set("templatePackage", templatePackage);
        root.put("targetDefinitionKey", "expenseImported");
        root.put("targetDefinitionVersion", 5);
        root.put("targetDraftName", "Imported expense");
        ArrayNode bindings = root.putArray("bindings");
        ObjectNode formPackage = bindings.addObject();
        formPackage.put("kind", "FORM_PACKAGE");
        formPackage.put("sourceKey", "source-form");
        formPackage.put("targetResourceKey", "expenseImported");
        formPackage.put("targetVersion", 3);
        return root;
    }
}
