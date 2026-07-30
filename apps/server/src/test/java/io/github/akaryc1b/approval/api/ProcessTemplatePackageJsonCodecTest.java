package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ProcessTemplateCanonicalHasher;
import io.github.akaryc1b.approval.application.ProcessTemplateException;
import io.github.akaryc1b.approval.application.ProcessTemplatePackageValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessTemplatePackageJsonCodecTest {

    private final ProcessTemplatePackageJsonCodec codec = new ProcessTemplatePackageJsonCodec(new ObjectMapper());
    private final ProcessTemplatePackageValidator validator = new ProcessTemplatePackageValidator(
        new ProcessTemplateCanonicalHasher());

    @Test
    void decodesAndVerifiesValidFixture() {
        byte[] bytes = fixture("valid-template-package.json");
        var value = codec.decode(bytes);
        validator.validate(value, bytes.length);
        assertEquals("expenseApproval", value.manifest().templateKey());
    }

    @Test
    void rejectsDuplicateJsonKey() {
        rejects("duplicate-json-key.json");
    }

    @Test
    void rejectsUnknownTenantField() {
        rejects("unknown-field.json");
    }

    @Test
    void rejectsMalformedUnicode() {
        rejects("invalid-unicode.json");
    }

    @Test
    void rejectsPathTraversal() {
        rejects("path-traversal.json");
    }

    @Test
    void rejectsScriptAndDynamicImport() {
        rejects("script-dynamic-import.json");
    }

    @Test
    void rejectsTamperedContentHash() {
        rejects("tampered-content-hash.json");
    }

    @Test
    void rejectsDuplicateDependency() {
        rejects("duplicate-dependency.json");
    }

    @Test
    void rejectsExcessiveDepth() {
        String value = "{\"manifest\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}";
        assertThrows(ProcessTemplateException.class, () -> codec.decode(value.getBytes()));
    }

    @Test
    void rejectsExcessiveBytes() {
        assertThrows(ProcessTemplateException.PackageTooLarge.class,
            () -> codec.decode(new byte[2 * 1024 * 1024 + 1]));
    }

    private void rejects(String name) {
        byte[] bytes = fixture(name);
        assertThrows(ProcessTemplateException.class, () -> {
            var value = codec.decode(bytes);
            validator.validate(value, bytes.length);
        });
    }

    private static byte[] fixture(String name) {
        try (InputStream input = ProcessTemplatePackageJsonCodecTest.class.getResourceAsStream(
            "/templates/m6c/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read fixture " + name, exception);
        }
    }
}
