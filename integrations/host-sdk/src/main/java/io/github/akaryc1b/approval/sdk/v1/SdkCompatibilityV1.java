package io.github.akaryc1b.approval.sdk.v1;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure compatibility negotiation for SDK, event-schema and Webhook protocol versions. */
public final class SdkCompatibilityV1 {
    public static final String MANIFEST_VERSION = "1";
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$"
    );

    private SdkCompatibilityV1() {
    }

    public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {
        public SemanticVersion {
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("Semantic version components cannot be negative");
            }
        }

        public static SemanticVersion parse(String value) {
            String required = required(value, "semantic version");
            Matcher matcher = SEMANTIC_VERSION.matcher(required);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid semantic version: " + value);
            }
            try {
                return new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Semantic version component is too large: " + value, exception);
            }
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            if (minorComparison != 0) {
                return minorComparison;
            }
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    public record CapabilityRequest(String name, boolean required) {
        public CapabilityRequest {
            name = SdkCompatibilityV1.required(name, "capability.name");
        }
    }

    /** Trusted tenant, operator, permission, authority and audit evidence are deliberately absent. */
    public record ClientProfile(
        String manifestVersion,
        String sdkVersion,
        List<String> eventSchemaVersions,
        List<String> webhookProtocolVersions,
        List<CapabilityRequest> capabilities
    ) {
        public ClientProfile {
            requireManifest(manifestVersion);
            SemanticVersion.parse(sdkVersion);
            eventSchemaVersions = uniqueStrings(eventSchemaVersions, "client.eventSchemaVersions");
            webhookProtocolVersions = uniqueStrings(webhookProtocolVersions, "client.webhookProtocolVersions");
            capabilities = immutableCapabilities(capabilities);
        }
    }

    public record DeprecationNotice(
        String capability,
        String deprecatedSince,
        String sunsetAt,
        String replacement
    ) {
        public DeprecationNotice {
            capability = required(capability, "deprecation.capability");
            replacement = required(replacement, "deprecation.replacement");
            Instant deprecated = instant(deprecatedSince, "deprecation.deprecatedSince");
            Instant sunset = instant(sunsetAt, "deprecation.sunsetAt");
            if (!sunset.isAfter(deprecated)) {
                throw new IllegalArgumentException("Deprecation sunset must follow deprecatedSince: " + capability);
            }
        }
    }

    public record ServerProfile(
        String manifestVersion,
        String contractVersion,
        String minimumClientVersion,
        String supportedUntil,
        List<String> eventSchemaVersions,
        List<String> webhookProtocolVersions,
        List<String> capabilities,
        List<DeprecationNotice> deprecations
    ) {
        public ServerProfile {
            requireManifest(manifestVersion);
            SemanticVersion.parse(contractVersion);
            SemanticVersion.parse(minimumClientVersion);
            instant(supportedUntil, "server.supportedUntil");
            eventSchemaVersions = uniqueStrings(eventSchemaVersions, "server.eventSchemaVersions");
            webhookProtocolVersions = uniqueStrings(webhookProtocolVersions, "server.webhookProtocolVersions");
            capabilities = uniqueStrings(capabilities, "server.capabilities");
            deprecations = immutableDeprecations(deprecations);
        }
    }

    public enum Status {
        COMPATIBLE,
        CONTRACT_SUPPORT_EXPIRED,
        CLIENT_UPGRADE_REQUIRED,
        NO_COMMON_EVENT_SCHEMA,
        NO_COMMON_WEBHOOK_PROTOCOL,
        REQUIRED_CAPABILITY_UNAVAILABLE,
        REQUIRED_CAPABILITY_SUNSET
    }

    public record NegotiationResult(
        Status status,
        String contractVersion,
        String eventSchemaVersion,
        String webhookProtocolVersion,
        List<String> enabledCapabilities,
        List<String> warnings
    ) {
        public NegotiationResult {
            status = Objects.requireNonNull(status, "status");
            contractVersion = required(contractVersion, "contractVersion");
            enabledCapabilities = List.copyOf(enabledCapabilities);
            warnings = List.copyOf(warnings);
        }

        public boolean compatible() {
            return status == Status.COMPATIBLE;
        }
    }

    public static final class UnsupportedManifestVersionException extends IllegalArgumentException {
        private final String manifestVersion;

        public UnsupportedManifestVersionException(String manifestVersion) {
            super("Unsupported compatibility manifest version: " + manifestVersion);
            this.manifestVersion = manifestVersion;
        }

        public String manifestVersion() {
            return manifestVersion;
        }
    }

    public static NegotiationResult negotiate(
        ClientProfile client,
        ServerProfile server,
        String evaluatedAt
    ) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(server, "server");
        Instant evaluated = instant(evaluatedAt, "evaluatedAt");
        Instant supportedUntil = instant(server.supportedUntil(), "server.supportedUntil");

        if (!evaluated.isBefore(supportedUntil)) {
            return result(
                Status.CONTRACT_SUPPORT_EXPIRED,
                server,
                null,
                null,
                List.of(),
                List.of("contract support expired at " + server.supportedUntil())
            );
        }
        if (SemanticVersion.parse(client.sdkVersion()).compareTo(
            SemanticVersion.parse(server.minimumClientVersion())
        ) < 0) {
            return result(
                Status.CLIENT_UPGRADE_REQUIRED,
                server,
                null,
                null,
                List.of(),
                List.of("minimum client version is " + server.minimumClientVersion())
            );
        }

        String eventSchema = firstCommon(server.eventSchemaVersions(), client.eventSchemaVersions());
        if (eventSchema == null) {
            return result(Status.NO_COMMON_EVENT_SCHEMA, server, null, null, List.of(), List.of());
        }
        String webhookProtocol = firstCommon(
            server.webhookProtocolVersions(),
            client.webhookProtocolVersions()
        );
        if (webhookProtocol == null) {
            return result(
                Status.NO_COMMON_WEBHOOK_PROTOCOL,
                server,
                eventSchema,
                null,
                List.of(),
                List.of()
            );
        }

        Set<String> available = Set.copyOf(server.capabilities());
        Map<String, DeprecationNotice> deprecations = new HashMap<>();
        for (DeprecationNotice notice : server.deprecations()) {
            deprecations.put(notice.capability(), notice);
        }
        List<String> enabled = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (CapabilityRequest request : client.capabilities()) {
            if (!available.contains(request.name())) {
                if (request.required()) {
                    warnings.add("required capability unavailable: " + request.name());
                    return result(
                        Status.REQUIRED_CAPABILITY_UNAVAILABLE,
                        server,
                        eventSchema,
                        webhookProtocol,
                        enabled,
                        warnings
                    );
                }
                warnings.add("optional capability unavailable: " + request.name());
                continue;
            }

            DeprecationNotice notice = deprecations.get(request.name());
            if (notice != null) {
                Instant deprecatedSince = instant(notice.deprecatedSince(), "deprecation.deprecatedSince");
                Instant sunsetAt = instant(notice.sunsetAt(), "deprecation.sunsetAt");
                if (!evaluated.isBefore(sunsetAt)) {
                    warnings.add(
                        (request.required() ? "required" : "optional")
                            + " capability sunset: " + request.name()
                            + "; sunsetAt=" + notice.sunsetAt()
                            + "; replacement=" + notice.replacement()
                    );
                    if (request.required()) {
                        return result(
                            Status.REQUIRED_CAPABILITY_SUNSET,
                            server,
                            eventSchema,
                            webhookProtocol,
                            enabled,
                            warnings
                        );
                    }
                    continue;
                }
                if (!evaluated.isBefore(deprecatedSince)) {
                    warnings.add(
                        "deprecated capability " + request.name()
                            + "; sunsetAt=" + notice.sunsetAt()
                            + "; replacement=" + notice.replacement()
                    );
                }
            }
            enabled.add(request.name());
        }

        return result(Status.COMPATIBLE, server, eventSchema, webhookProtocol, enabled, warnings);
    }

    private static NegotiationResult result(
        Status status,
        ServerProfile server,
        String eventSchema,
        String webhookProtocol,
        List<String> enabled,
        List<String> warnings
    ) {
        return new NegotiationResult(
            status,
            server.contractVersion(),
            eventSchema,
            webhookProtocol,
            enabled,
            warnings
        );
    }

    private static String firstCommon(List<String> preferred, List<String> accepted) {
        Set<String> acceptedSet = Set.copyOf(accepted);
        for (String value : preferred) {
            if (acceptedSet.contains(value)) {
                return value;
            }
        }
        return null;
    }

    private static List<CapabilityRequest> immutableCapabilities(List<CapabilityRequest> values) {
        Objects.requireNonNull(values, "client.capabilities");
        Set<String> names = new HashSet<>();
        List<CapabilityRequest> output = new ArrayList<>();
        for (CapabilityRequest value : values) {
            CapabilityRequest request = Objects.requireNonNull(value, "client.capabilities entry");
            if (!names.add(request.name())) {
                throw new IllegalArgumentException("Duplicate capability request: " + request.name());
            }
            output.add(request);
        }
        return List.copyOf(output);
    }

    private static List<DeprecationNotice> immutableDeprecations(List<DeprecationNotice> values) {
        Objects.requireNonNull(values, "server.deprecations");
        Set<String> capabilities = new HashSet<>();
        List<DeprecationNotice> output = new ArrayList<>();
        for (DeprecationNotice value : values) {
            DeprecationNotice notice = Objects.requireNonNull(value, "server.deprecations entry");
            if (!capabilities.add(notice.capability())) {
                throw new IllegalArgumentException("Duplicate deprecation notice: " + notice.capability());
            }
            output.add(notice);
        }
        return List.copyOf(output);
    }

    private static List<String> uniqueStrings(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        Set<String> unique = new HashSet<>();
        List<String> output = new ArrayList<>();
        for (String value : values) {
            String required = required(value, field);
            if (!unique.add(required)) {
                throw new IllegalArgumentException(field + " contains duplicate value: " + required);
            }
            output.add(required);
        }
        return List.copyOf(output);
    }

    private static Instant instant(String value, String field) {
        try {
            String required = required(value, field);
            if (!required.endsWith("Z")) {
                throw new IllegalArgumentException(field + " must be an RFC 3339 UTC instant");
            }
            return Instant.parse(required);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an RFC 3339 UTC instant", exception);
        }
    }

    private static void requireManifest(String value) {
        if (!MANIFEST_VERSION.equals(value)) {
            throw new UnsupportedManifestVersionException(value);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }
}
