package io.github.akaryc1b.approval.sdk.v1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class TransportPolicyFixtureSupport {
    private TransportPolicyFixtureSupport() {
    }

    static Map<String, Object> fixture() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/sdk/v1/fixtures/transport-policy-v1.json");
            if (Files.isRegularFile(candidate)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) CanonicalJson.parse(Files.readString(candidate));
                return value;
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate cross-language transport policy fixture");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> parent, String field) {
        return (List<Object>) parent.get(field);
    }
}
