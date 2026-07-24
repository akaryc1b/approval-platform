package io.github.akaryc1b.approval.sdk.v1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class DiagnosticsAuditFixtureSupport {
    private DiagnosticsAuditFixtureSupport() {
    }

    static String fixtureJson() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/sdk/v1/fixtures/diagnostics-audit-v1.json");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate diagnostics/audit fixture");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture() throws IOException {
        return (Map<String, Object>) CanonicalJson.parse(fixtureJson());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }
}
