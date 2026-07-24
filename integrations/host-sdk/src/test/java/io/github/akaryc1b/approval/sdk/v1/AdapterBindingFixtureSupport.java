package io.github.akaryc1b.approval.sdk.v1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class AdapterBindingFixtureSupport {
    private AdapterBindingFixtureSupport() {
    }

    static String fixtureJson() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/sdk/v1/fixtures/adapter-binding-v1.json");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate cross-language adapter binding fixture");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture() throws IOException {
        return (Map<String, Object>) CanonicalJson.parse(fixtureJson());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> parent, String field) {
        return (List<Map<String, Object>>) parent.get(field);
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> parent, String field) {
        return (List<String>) parent.get(field);
    }

    static long number(Map<String, Object> parent, String field) {
        return ((Number) parent.get(field)).longValue();
    }
}
