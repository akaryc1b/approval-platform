#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected D7 protocol test block was not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


service_test = Path(
    "server-modules/approval-application/src/test/java/"
    "io/github/akaryc1b/approval/application/"
    "ApprovalArtifactTransferServiceTest.java"
)
replace_once(
    service_test,
    '''        ReleasePackagePayload unsafeResource = copyReleasePayload(
            original,
            "../approval.bpmn20.xml",
            original.bpmnArtifact(),
            original.bpmnHash()
        );''',
    '''        ReleasePackagePayload validButTampered = copyReleasePayload(
            original,
            original.bpmnResourceName(),
            original.bpmnArtifact() + "\\n",
            original.bpmnHash()
        );
        ReleasePackagePayload unsafeResource = copyReleasePayload(
            original,
            "../approval.bpmn20.xml",
            original.bpmnArtifact(),
            original.bpmnHash()
        );''',
)
replace_once(
    service_test,
    '''        assertThrows(
            ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed.class,
            () -> service.verifyEnvelope(withPayload(envelope, invalidXml))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,''',
    '''        assertThrows(
            ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed.class,
            () -> service.verifyEnvelope(withPayload(envelope, invalidXml))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(withPayload(envelope, validButTampered))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,''',
)

codec_test = Path(
    "apps/server/src/test/java/io/github/akaryc1b/approval/api/"
    "ApprovalArtifactTransferJsonCodecTest.java"
)
replace_once(
    codec_test,
    "import java.nio.charset.StandardCharsets;",
    """import java.math.BigInteger;
import java.nio.charset.StandardCharsets;""",
)
replace_once(
    codec_test,
    '''        overflow.put("targetDefinitionVersion", "999999999999999999999999999");''',
    '''        overflow.put(
            "targetDefinitionVersion",
            new BigInteger("999999999999999999999999999")
        );''',
)
marker = '''    @Test
    void controllerRejectsOversizedBodyBeforeJsonBinding() {'''
order_test = '''    @Test
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

''' + marker
replace_once(codec_test, marker, order_test)
PY
rm -f .github/scripts/apply-pr53-d7-protocol-test-fix.sh
