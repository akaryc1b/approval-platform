package io.github.akaryc1b.approval.connector.routing;

import io.github.akaryc1b.approval.connector.ConnectorProvider;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteDefinition;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts.RouteIntent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable startup snapshot with exact-key lookup and deterministic content evidence.
 */
public final class TenantConnectorRouteSnapshot {

    public static final int MAX_ROUTES = 5_000;
    public static final int MAX_ROUTES_PER_TENANT = 16;
    public static final int MAX_CANONICAL_BYTES = 256 * 1024;

    private static final Comparator<RouteDefinition> CANONICAL_ORDER = Comparator
        .comparing(RouteDefinition::tenantId)
        .thenComparing(route -> route.capability().name())
        .thenComparing(route -> route.intent().name())
        .thenComparing(RouteDefinition::providerKey)
        .thenComparing(route -> route.apiFamily().name())
        .thenComparing(RouteDefinition::routeVersion)
        .thenComparing(RouteDefinition::definitionHash);

    private final String configurationVersion;
    private final List<RouteDefinition> definitions;
    private final String snapshotHash;
    private final String computedSnapshotHash;
    private final Map<RouteKey, List<RouteDefinition>> exactIndex;
    private final List<String> integrityIssues;
    private final List<String> configurationIssues;
    private final int canonicalByteCount;

    public TenantConnectorRouteSnapshot(
        String configurationVersion,
        List<RouteDefinition> definitions,
        String snapshotHash
    ) {
        this.configurationVersion = TenantConnectorRouteContracts.version(
            configurationVersion,
            "configurationVersion"
        );
        List<RouteDefinition> copy = definitions == null
            ? List.of()
            : List.copyOf(definitions);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("route definitions must not contain null");
        }
        List<RouteDefinition> sorted = new ArrayList<>(copy);
        sorted.sort(CANONICAL_ORDER);
        this.definitions = Collections.unmodifiableList(sorted);
        this.snapshotHash = TenantConnectorRouteContracts.sha256(
            snapshotHash,
            "snapshotHash"
        );
        this.computedSnapshotHash = computeSnapshotHash(this.configurationVersion, sorted);
        this.canonicalByteCount = canonicalSnapshot(this.configurationVersion, sorted)
            .getBytes(StandardCharsets.UTF_8).length;
        this.exactIndex = buildIndex(sorted);
        this.integrityIssues = integrityIssues(sorted);
        this.configurationIssues = configurationIssues(sorted, exactIndex);
    }

    public static TenantConnectorRouteSnapshot create(
        String configurationVersion,
        List<RouteDefinition> definitions
    ) {
        String normalizedVersion = TenantConnectorRouteContracts.version(
            configurationVersion,
            "configurationVersion"
        );
        List<RouteDefinition> safe = definitions == null ? List.of() : List.copyOf(definitions);
        String computed = computeSnapshotHash(normalizedVersion, safe);
        return new TenantConnectorRouteSnapshot(normalizedVersion, safe, computed);
    }

    public String configurationVersion() {
        return configurationVersion;
    }

    public List<RouteDefinition> definitions() {
        return definitions;
    }

    public String snapshotHash() {
        return snapshotHash;
    }

    public String computedSnapshotHash() {
        return computedSnapshotHash;
    }

    public int canonicalByteCount() {
        return canonicalByteCount;
    }

    public boolean integrityValid() {
        return snapshotHash.equals(computedSnapshotHash) && integrityIssues.isEmpty();
    }

    public boolean configurationValid() {
        return integrityValid() && configurationIssues.isEmpty();
    }

    public List<String> integrityIssues() {
        return integrityIssues;
    }

    public List<String> configurationIssues() {
        return configurationIssues;
    }

    public List<RouteDefinition> exactCandidates(
        String trustedTenantId,
        ConnectorProvider.Capability capability,
        RouteIntent intent
    ) {
        RouteKey key = new RouteKey(
            TenantConnectorRouteContracts.boundedIdentifier(
                trustedTenantId,
                "trustedTenantId",
                TenantConnectorRouteContracts.MAX_IDENTIFIER_LENGTH
            ),
            Objects.requireNonNull(capability, "capability must not be null"),
            Objects.requireNonNull(intent, "intent must not be null")
        );
        return exactIndex.getOrDefault(key, List.of());
    }

    public TenantConnectorRouteSnapshot requireValidConfiguration() {
        if (!configurationValid()) {
            throw new IllegalArgumentException("tenant connector route configuration is invalid");
        }
        return this;
    }

    private List<String> integrityIssues(List<RouteDefinition> routes) {
        List<String> issues = new ArrayList<>();
        if (routes.isEmpty()) {
            issues.add("route_configuration_empty");
        }
        if (routes.size() > MAX_ROUTES) {
            issues.add("route_count_exceeded");
        }
        if (canonicalByteCount > MAX_CANONICAL_BYTES) {
            issues.add("canonical_size_exceeded");
        }
        if (!snapshotHash.equals(computedSnapshotHash)) {
            issues.add("snapshot_hash_mismatch");
        }
        if (routes.stream().anyMatch(route -> !route.hashMatches())) {
            issues.add("definition_hash_mismatch");
        }
        return List.copyOf(issues);
    }

    private static List<String> configurationIssues(
        List<RouteDefinition> routes,
        Map<RouteKey, List<RouteDefinition>> index
    ) {
        Set<String> issues = new HashSet<>();
        Map<String, Integer> perTenant = new HashMap<>();
        Set<String> definitionHashes = new HashSet<>();
        for (RouteDefinition route : routes) {
            perTenant.merge(route.tenantId(), 1, Integer::sum);
            if (!definitionHashes.add(route.definitionHash())) {
                issues.add("duplicate_definition");
            }
            if (!route.supportedByP4()) {
                issues.add("unsupported_route");
            }
        }
        if (perTenant.values().stream().anyMatch(count -> count > MAX_ROUTES_PER_TENANT)) {
            issues.add("tenant_route_count_exceeded");
        }
        if (index.values().stream().anyMatch(matches -> matches.size() > 1)) {
            issues.add("duplicate_tenant_capability_operation");
        }
        return issues.stream().sorted().toList();
    }

    private static Map<RouteKey, List<RouteDefinition>> buildIndex(
        List<RouteDefinition> definitions
    ) {
        Map<RouteKey, List<RouteDefinition>> mutable = new LinkedHashMap<>();
        for (RouteDefinition route : definitions) {
            RouteKey key = new RouteKey(route.tenantId(), route.capability(), route.intent());
            mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(route);
        }
        Map<RouteKey, List<RouteDefinition>> immutable = new LinkedHashMap<>();
        mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(immutable);
    }

    private static String computeSnapshotHash(
        String configurationVersion,
        List<RouteDefinition> definitions
    ) {
        List<RouteDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(CANONICAL_ORDER);
        return TenantConnectorRouteContracts.hash(canonicalSnapshot(configurationVersion, sorted));
    }

    private static String canonicalSnapshot(
        String configurationVersion,
        List<RouteDefinition> definitions
    ) {
        List<String> values = new ArrayList<>(definitions.size() + 2);
        values.add(configurationVersion);
        values.add(Integer.toString(definitions.size()));
        definitions.forEach(route -> values.add(route.definitionHash()));
        return TenantConnectorRouteContracts.canonical(values.toArray(String[]::new));
    }

    private record RouteKey(
        String tenantId,
        ConnectorProvider.Capability capability,
        RouteIntent intent
    ) {
    }
}
