package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDefinitionJacksonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalArtifactTransferJsonCodecTest {

    private ObjectMapper mapper;
    private ApprovalArtifactTransferJsonCodec codec;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
        ApprovalDefinitionJacksonSupport.configure(mapper);
        codec = new ApprovalArtifactTransferJsonCodec(mapper);
    }

    @Test
    void decodesStrictTransferImport() throws Exception {
        var decoded = codec.decodeImport(validBody());

        assertEquals(ApprovalArtifactTransferService.DSL_FORMAT, decoded.envelope().format());
        assertEquals("purchase-payment", decoded.targetDefinitionKey());
        assertEquals(2, decoded.targetDefinitionVersion());
        assertEquals(1, decoded.targetFormPackageVersion());
        assertEquals("Imported approval", decoded.targetName());
    }

    @Test
    void rejectsDuplicateKeysAndUnknownTopLevelFields() throws Exception {
        String valid = new String(validBody(), StandardCharsets.UTF_8);
        String duplicate = valid.substring(0, valid.length() - 1)
            + ",\"targetName\":\"duplicate\"}";
        ObjectNode unknownNode = (ObjectNode) mapper.readTree(validBody());
        unknownNode.put("unexpected", true);

        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(duplicate.getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(mapper.writeValueAsBytes(unknownNode))
        );
    }

    @Test
    void rejectsUnknownEnvelopeAndPayloadFields() throws Exception {
        ObjectNode unknownEnvelope = (ObjectNode) mapper.readTree(validBody());
        ((ObjectNode) unknownEnvelope.get("envelope")).put("tenantId", "leak");
        ObjectNode unknownPayload = (ObjectNode) mapper.readTree(validBody());
        ((ObjectNode) unknownPayload.get("envelope").get("payload"))
            .put("publishedBy", "leak");

        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(mapper.writeValueAsBytes(unknownEnvelope))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(mapper.writeValueAsBytes(unknownPayload))
        );
    }

    @Test
    void rejectsNullPayloadAndIntegerOverflow() throws Exception {
        ObjectNode nullPayload = (ObjectNode) mapper.readTree(validBody());
        ((ObjectNode) nullPayload.get("envelope")).putNull("payload");
        ObjectNode overflow = (ObjectNode) mapper.readTree(validBody());
        overflow.put(
            "targetDefinitionVersion",
            new BigInteger("999999999999999999999999999")
        );

        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(mapper.writeValueAsBytes(nullPayload))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(mapper.writeValueAsBytes(overflow))
        );
    }

    @Test
    void rejectsOversizedAndDeepJsonBeforeBinding() {
        byte[] oversized = new byte[ApprovalArtifactTransferJsonCodec.MAX_REQUEST_BYTES + 1];
        String deep = "[".repeat(ApprovalArtifactTransferJsonCodec.MAX_JSON_DEPTH + 2)
            + "0"
            + "]".repeat(ApprovalArtifactTransferJsonCodec.MAX_JSON_DEPTH + 2);

        assertThrows(
            ApprovalArtifactTransferExceptions.TooLarge.class,
            () -> codec.decodeImport(oversized)
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> codec.decodeImport(deep.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void jsonPropertyOrderDoesNotChangeDecodedTransferIdentity() throws Exception {
        ObjectNode original = (ObjectNode) mapper.readTree(validBody());
        ObjectNode originalEnvelope = (ObjectNode) original.get("envelope");
        ObjectNode originalPayload = (ObjectNode) originalEnvelope.get("payload");

        ObjectNode reorderedPayload = mapper.createObjectNode();
        java.util.List<String> payloadFields = new java.util.ArrayList<>();
        originalPayload.fieldNames().forEachRemaining(payloadFields::add);
        java.util.Collections.reverse(payloadFields);
        payloadFields.forEach(name -> reorderedPayload.set(name, originalPayload.get(name)));

        ObjectNode reorderedEnvelope = mapper.createObjectNode();
        java.util.List<String> envelopeFields = new java.util.ArrayList<>();
        originalEnvelope.fieldNames().forEachRemaining(envelopeFields::add);
        java.util.Collections.reverse(envelopeFields);
        envelopeFields.forEach(name -> reorderedEnvelope.set(
            name,
            "payload".equals(name) ? reorderedPayload : originalEnvelope.get(name)
        ));

        ObjectNode reordered = mapper.createObjectNode();
        reordered.put("targetName", original.get("targetName").textValue());
        reordered.put(
            "targetFormPackageVersion",
            original.get("targetFormPackageVersion").intValue()
        );
        reordered.put(
            "targetDefinitionVersion",
            original.get("targetDefinitionVersion").intValue()
        );
        reordered.put(
            "targetDefinitionKey",
            original.get("targetDefinitionKey").textValue()
        );
        reordered.set("envelope", reorderedEnvelope);

        assertEquals(
            codec.decodeImport(validBody()),
            codec.decodeImport(mapper.writeValueAsBytes(reordered))
        );
    }

    @Test
    void controllerRejectsOversizedBodyBeforeJsonBinding() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[
            ApprovalArtifactTransferJsonCodec.MAX_REQUEST_BYTES + 1
        ]);

        assertThrows(
            ApprovalArtifactTransferExceptions.TooLarge.class,
            () -> ApprovalArtifactTransferController.readBody(request)
        );
    }

    private byte[] validBody() throws Exception {
        String hash = "a".repeat(64);
        TransferEnvelope envelope = new TransferEnvelope(
            ApprovalArtifactTransferService.DSL_FORMAT,
            ApprovalArtifactTransferService.FORMAT_VERSION,
            ArtifactType.APPROVAL_DSL,
            Instant.parse("2026-07-19T14:00:00Z"),
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            null,
            1,
            hash,
            hash,
            new DefinitionPayload(PurchasePaymentTemplate.processDefinition()),
            hash,
            hash
        );
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("envelope", envelope);
        request.put("targetDefinitionKey", PurchasePaymentTemplate.DEFINITION_KEY);
        request.put("targetDefinitionVersion", 2);
        request.put("targetFormPackageVersion", 1);
        request.put("targetName", "Imported approval");
        return mapper.writeValueAsBytes(request);
    }
}
