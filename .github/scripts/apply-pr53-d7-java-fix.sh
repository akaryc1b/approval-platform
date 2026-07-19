#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected D7 source block was not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


jdbc_test = Path(
    "server-modules/approval-persistence-jdbc/src/test/java/"
    "io/github/akaryc1b/approval/persistence/jdbc/"
    "JdbcApprovalArtifactTransferIntegrationTest.java"
)
jdbc_text = jdbc_test.read_text(encoding="utf-8")
jdbc_text = jdbc_text.replace(
    "import static org.junit.jupiter.api.Assertions.assertTrue;\n",
    "",
    1,
)
old_fixture = '''        jdbc.update(
            "delete from ap_form_package where tenant_id = ? and form_key = ?",
            TARGET_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY
        );'''
new_fixture = '''        jdbc.update(
            "update ap_form_package set form_hash = ? "
                + "where tenant_id = ? and form_key = ?",
            "0".repeat(64),
            TARGET_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY
        );'''
if old_fixture not in jdbc_text:
    raise SystemExit("expected target Form Package test fixture was not found")
jdbc_test.write_text(jdbc_text.replace(old_fixture, new_fixture, 1), encoding="utf-8")

controller = Path(
    "apps/server/src/main/java/io/github/akaryc1b/approval/api/"
    "ApprovalArtifactTransferController.java"
)
replace_once(
    controller,
    """import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;""",
    """import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;""",
)
replace_once(
    controller,
    """import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.springframework.http.MediaType;""",
    """import io.github.akaryc1b.approval.domain.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;""",
)
replace_once(
    controller,
    "import org.springframework.web.bind.annotation.RequestBody;\n",
    "",
)
replace_once(
    controller,
    """import java.net.URI;""",
    """import java.io.IOException;
import java.net.URI;""",
)
replace_once(
    controller,
    """        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        @RequestBody byte[] body
    ) {
        var request = codec.decodeImport(body);""",
    """        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
        @RequestHeader(value = TRACE_ID, required = false) String traceId,
        HttpServletRequest servletRequest
    ) {
        var request = codec.decodeImport(readBody(servletRequest));""",
)
replace_once(
    controller,
    """        )).eTag("\\\"revision-" + result.revision() + "\\\"").body(result);
    }
}""",
    """        )).eTag("\\\"revision-" + result.revision() + "\\\"").body(result);
    }

    static byte[] readBody(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(
                ApprovalArtifactTransferJsonCodec.MAX_REQUEST_BYTES + 1
            );
            if (body.length > ApprovalArtifactTransferJsonCodec.MAX_REQUEST_BYTES) {
                throw new ApprovalArtifactTransferExceptions.TooLarge(
                    "import request exceeds the 2 MiB maximum"
                );
            }
            return body;
        } catch (IOException exception) {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "import request could not be read",
                exception
            );
        }
    }
}""",
)

service_test = Path(
    "server-modules/approval-application/src/test/java/"
    "io/github/akaryc1b/approval/application/"
    "ApprovalArtifactTransferServiceTest.java"
)
insert_marker = '''    @Test
    void rejectsTamperedDefinitionAndDeclaredEnvelopeHashes() {'''
insert_tests = '''    @Test
    void rejectsMissingDefinitionAndReleaseForTheTenant() {
        assertThrows(
            ApprovalArtifactTransferExceptions.SourceNotFound.class,
            () -> service.exportDefinition("missing-tenant", KEY, 1)
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.SourceNotFound.class,
            () -> service.exportRelease("missing-tenant", KEY, 1)
        );
    }

    @Test
    void rejectsUnsupportedFormatAndTamperedReleasePackageHash() {
        TransferEnvelope definitionEnvelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        TransferEnvelope unsupported = new TransferEnvelope(
            "APPROVAL_UNKNOWN_EXPORT_V1",
            definitionEnvelope.formatVersion(),
            definitionEnvelope.artifactType(),
            definitionEnvelope.exportedAt(),
            definitionEnvelope.definitionKey(),
            definitionEnvelope.definitionVersion(),
            definitionEnvelope.releaseVersion(),
            definitionEnvelope.formPackageVersion(),
            definitionEnvelope.definitionHash(),
            definitionEnvelope.formPackageHash(),
            definitionEnvelope.payload(),
            definitionEnvelope.payloadHash(),
            definitionEnvelope.envelopeHash()
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> service.verifyEnvelope(unsupported)
        );

        TransferEnvelope releaseEnvelope = service.exportRelease(SOURCE_TENANT, KEY, 1);
        ReleasePackagePayload original = (ReleasePackagePayload) releaseEnvelope.payload();
        ReleasePackagePayload tampered = new ReleasePackagePayload(
            original.definition(),
            original.compilerVersion(),
            original.bpmnResourceName(),
            original.bpmnArtifact(),
            original.bpmnHash(),
            original.dmnArtifact(),
            original.dmnHash(),
            original.compiledArtifactHash(),
            original.deploymentMetadataHash(),
            "f".repeat(64),
            original.formSchemaVersion(),
            original.formSchemaHash(),
            original.uiSchemaVersion(),
            original.uiSchemaHash()
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(withPayload(releaseEnvelope, tampered))
        );
    }

''' + insert_marker
replace_once(service_test, insert_marker, insert_tests)

codec_test = Path(
    "apps/server/src/test/java/io/github/akaryc1b/approval/api/"
    "ApprovalArtifactTransferJsonCodecTest.java"
)
replace_once(
    codec_test,
    """import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;""",
    """import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;""",
)
codec_marker = '''    private byte[] validBody() throws Exception {'''
codec_test_method = '''    @Test
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

''' + codec_marker
replace_once(codec_test, codec_marker, codec_test_method)
PY
rm -f .github/scripts/apply-pr53-d7-java-fix.sh
