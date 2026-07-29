package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenOutcome;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .CompletionClassification;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DurationBucket;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .GateResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .DiagnosticsCriteria;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .InvocationOutcome;
import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts.Sort;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .TransportProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorOperationsDiagnosticsTest {

    private static final Instant NOW = Instant.parse("2026-07-29T07:00:00Z");

    @Test
    void emptyDiagnosticsIsTenantScopedAndNonAuthoritative() {
        Fixture fixture = fixture(8, 4, 262_144);
        var page = fixture.service().query("tenant-a", criteria(50, null));
        assertTrue(page.items().isEmpty());
        assertTrue(page.processLocal());
        assertFalse(page.persistent());
        assertFalse(page.auditSystem());
        assertFalse(page.recoveryMechanism());
        assertFalse(page.productionExecutionAuthorized());
        assertFalse(page.approvalStateMutationAuthorized());
    }

    @Test
    void recordsSecretFreeSuccessEvidence() {
        Fixture fixture = fixture(8, 4, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        var entry = fixture.service().query("tenant-a", criteria(50, null)).items().getFirst();
        assertEquals("dingtalk", entry.provider());
        assertEquals(InvocationOutcome.SUCCEEDED, entry.invocationOutcome());
        assertEquals(1, entry.dispatchCount());
        assertEquals(DingTalkTokenOutcome.ACQUIRED, entry.tokenOutcome());
    }

    @Test
    void earlyBlockedEvidenceCanBeRecordedWithoutRouteOrToken() {
        Fixture fixture = fixture(8, 4, 262_144);
        fixture.store().record(blocked("tenant-a", StableFailureCode.ROUTE_MISSING, 1), NOW);
        var entry = fixture.service().query("tenant-a", criteria(50, null)).items().getFirst();
        assertEquals(InvocationOutcome.REJECTED_BEFORE_DISPATCH, entry.invocationOutcome());
        assertEquals(0, entry.dispatchCount());
        assertEquals(ConnectorOperationsDiagnosticsContracts.RouteState.MISSING, entry.routeState());
    }

    @Test
    void crossTenantQueryReturnsNoResourceEvidence() {
        Fixture fixture = fixture(8, 4, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        assertTrue(fixture.service().query("tenant-b", criteria(50, null)).items().isEmpty());
        assertEquals(0, fixture.service().summarize("tenant-b").total());
    }

    @Test
    void globalCapacityEvictsOldestEntry() {
        Fixture fixture = fixture(2, 2, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-b", 2), NOW);
        fixture.store().record(success("tenant-c", 3), NOW);
        assertEquals(2, fixture.store().size());
        assertTrue(fixture.service().query("tenant-a", criteria(50, null)).items().isEmpty());
    }

    @Test
    void tenantCapacityEvictsOnlyThatTenantsOldestEntry() {
        Fixture fixture = fixture(4, 2, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-b", 2), NOW);
        fixture.store().record(success("tenant-a", 3), NOW);
        fixture.store().record(success("tenant-a", 4), NOW);
        assertEquals(2, fixture.store().tenantSize(hashTenant("tenant-a")));
        assertEquals(1, fixture.store().tenantSize(hashTenant("tenant-b")));
    }

    @Test
    void stableSortIsNewestSequenceFirst() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        var items = fixture.service().query("tenant-a", criteria(50, null)).items();
        assertEquals(hash("evidence-2"), items.getFirst().evidenceHash());
        assertEquals(hash("evidence-1"), items.getLast().evidenceHash());
    }

    @Test
    void paginationIsBoundedAndOpaque() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        fixture.store().record(success("tenant-a", 3), NOW);
        var first = fixture.service().query("tenant-a", criteria(2, null));
        assertEquals(2, first.items().size());
        assertTrue(first.nextPageToken() != null);
        assertFalse(first.nextPageToken().contains("tenant-a"));
        var second = fixture.service().query("tenant-a", criteria(2, first.nextPageToken()));
        assertEquals(1, second.items().size());
        assertTrue(second.nextPageToken() == null);
    }

    @Test
    void highWatermarkExcludesNewEntriesFromLaterPages() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        fixture.store().record(success("tenant-a", 3), NOW);
        var first = fixture.service().query("tenant-a", criteria(2, null));
        fixture.store().record(success("tenant-a", 4), NOW);
        var second = fixture.service().query("tenant-a", criteria(2, first.nextPageToken()));
        assertEquals(1, second.items().size());
    }

    @Test
    void tamperedPageTokenIsRejected() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        var token = fixture.service().query("tenant-a", criteria(1, null)).nextPageToken();
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.InvalidRequest.class,
            () -> fixture.service().query("tenant-a", criteria(1, token + "x"))
        );
    }

    @Test
    void pageTokenCannotCrossTenants() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        var token = fixture.service().query("tenant-a", criteria(1, null)).nextPageToken();
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.InvalidRequest.class,
            () -> fixture.service().query("tenant-b", criteria(1, token))
        );
    }

    @Test
    void pageTokenCannotChangeFilterAuthority() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        var token = fixture.service().query("tenant-a", criteria(1, null)).nextPageToken();
        DiagnosticsCriteria changed = new DiagnosticsCriteria(
            1,
            token,
            "dingtalk",
            null,
            null,
            null,
            null,
            InvocationOutcome.SUCCEEDED,
            null,
            null,
            Sort.CREATED_AT_DESC
        );
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.InvalidRequest.class,
            () -> fixture.service().query("tenant-a", changed)
        );
    }

    @Test
    void exactAllowlistedFiltersDoNotLeakOtherOutcomes() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(blocked("tenant-a", StableFailureCode.KILL_SWITCH_BLOCKED, 2), NOW);
        DiagnosticsCriteria filter = new DiagnosticsCriteria(
            50,
            null,
            "dingtalk",
            null,
            null,
            null,
            null,
            InvocationOutcome.REJECTED_BEFORE_DISPATCH,
            false,
            StableFailureCode.KILL_SWITCH_BLOCKED,
            Sort.CREATED_AT_DESC
        );
        var page = fixture.service().query("tenant-a", filter);
        assertEquals(1, page.items().size());
        assertEquals(StableFailureCode.KILL_SWITCH_BLOCKED, page.items().getFirst().stableFailureCode());
    }

    @Test
    void invalidPageSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> criteria(0, null));
        assertThrows(IllegalArgumentException.class, () -> criteria(101, null));
    }

    @Test
    void responseSizeIsBounded() {
        Fixture fixture = fixture(8, 8, 1_024);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(success("tenant-a", 2), NOW);
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.ResponseTooLarge.class,
            () -> fixture.service().query("tenant-a", criteria(50, null))
        );
    }

    @Test
    void unavailableSourceMapsToStableException() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().setAvailable(false);
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.SourceUnavailable.class,
            () -> fixture.service().query("tenant-a", criteria(50, null))
        );
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.SourceUnavailable.class,
            () -> fixture.service().summarize("tenant-a")
        );
    }

    @Test
    void summaryContainsOnlyTenantLocalLowCardinalityCounts() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        fixture.store().record(blocked("tenant-a", StableFailureCode.ROUTE_DISABLED, 2), NOW);
        fixture.store().record(success("tenant-b", 3), NOW);
        var summary = fixture.service().summarize("tenant-a");
        assertEquals(2, summary.total());
        assertEquals(1L, summary.outcomes().get(InvocationOutcome.SUCCEEDED).longValue());
        assertEquals(1L, summary.failures().get(StableFailureCode.ROUTE_DISABLED).longValue());
        assertFalse(summary.persistent());
        assertFalse(summary.productionExecutionAuthorized());
    }

    @Test
    void canonicalJsonContainsNoRawTenantCredentialTokenOrEndpoint() {
        Fixture fixture = fixture(8, 8, 262_144);
        fixture.store().record(success("tenant-a", 1), NOW);
        String json = fixture.service().query("tenant-a", criteria(50, null)).canonicalJson();
        assertFalse(json.contains("tenant-a"));
        assertFalse(json.contains("credential-reference-a"));
        assertFalse(json.contains("synthetic-token-a"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("Authorization"));
        assertFalse(json.contains("Cookie"));
    }

    @Test
    void pageTokenKeyInputAndClosedKeyAreZeroized() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        ConnectorDiagnosticsPageTokenCodec codec = new ConnectorDiagnosticsPageTokenCodec(key);
        assertTrue(allZero(key));
        codec.close();
        assertTrue(codec.closed());
        assertThrows(
            ConnectorOperationsDiagnosticsExceptions.SourceUnavailable.class,
            () -> codec.encode(new ConnectorOperationsDiagnosticsContracts.PageCursor(
                "0".repeat(64),
                "1".repeat(64),
                1,
                0
            ))
        );
    }

    @Test
    void differentKeysCannotForgePaginationAuthority() {
        byte[] keyA = new byte[32];
        byte[] keyB = new byte[32];
        Arrays.fill(keyA, (byte) 1);
        Arrays.fill(keyB, (byte) 2);
        try (ConnectorDiagnosticsPageTokenCodec a = new ConnectorDiagnosticsPageTokenCodec(keyA);
             ConnectorDiagnosticsPageTokenCodec b = new ConnectorDiagnosticsPageTokenCodec(keyB)) {
            var cursor = new ConnectorOperationsDiagnosticsContracts.PageCursor(
                "0".repeat(64),
                "1".repeat(64),
                10,
                5
            );
            String token = a.encode(cursor);
            assertThrows(
                ConnectorOperationsDiagnosticsExceptions.InvalidRequest.class,
                () -> b.decode(token, cursor.tenantHash(), cursor.filterHash())
            );
        }
    }

    @Test
    void evidenceHashesRemainStableAndDistinct() {
        InvocationEvidence first = success("tenant-a", 1);
        InvocationEvidence second = success("tenant-a", 2);
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
    }

    private static Fixture fixture(int maximum, int perTenant, int responseBytes) {
        BoundedConnectorOperationsDiagnosticsStore store =
            new BoundedConnectorOperationsDiagnosticsStore(maximum, perTenant);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        ConnectorDiagnosticsPageTokenCodec codec = new ConnectorDiagnosticsPageTokenCodec(key);
        ConnectorOperationsDiagnosticsQueryService service =
            new ConnectorOperationsDiagnosticsQueryService(
                store,
                codec,
                Clock.fixed(NOW, ZoneOffset.UTC),
                responseBytes
            );
        return new Fixture(store, codec, service);
    }

    private static DiagnosticsCriteria criteria(int pageSize, String pageToken) {
        return new DiagnosticsCriteria(
            pageSize,
            pageToken,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Sort.CREATED_AT_DESC
        );
    }

    private static InvocationEvidence success(String tenant, int ordinal) {
        return evidence(
            tenant,
            ordinal,
            CompletionClassification.SUCCEEDED,
            StableFailureCode.NONE,
            true,
            1,
            DingTalkTokenOutcome.ACQUIRED
        );
    }

    private static InvocationEvidence blocked(
        String tenant,
        StableFailureCode failure,
        int ordinal
    ) {
        return evidence(
            tenant,
            ordinal,
            CompletionClassification.REJECTED_BEFORE_DISPATCH,
            failure,
            false,
            0,
            null
        );
    }

    private static InvocationEvidence evidence(
        String tenant,
        int ordinal,
        CompletionClassification completion,
        StableFailureCode failure,
        boolean dispatched,
        int dispatchCount,
        DingTalkTokenOutcome tokenOutcome
    ) {
        String tenantHash = hashTenant(tenant);
        String ordinalHash = hash("evidence-" + ordinal);
        boolean routed = failure != StableFailureCode.ROUTE_MISSING
            && failure != StableFailureCode.INVALID_REQUEST;
        return new InvocationEvidence(
            tenantHash,
            hash("request-" + ordinal),
            routed ? hash("plan-" + ordinal) : null,
            routed ? hash("definition-" + ordinal) : null,
            routed ? hash("reference-" + ordinal) : null,
            routed ? hash("binding-" + ordinal) : null,
            routed ? "version-" + ordinal : null,
            routed ? hash("version-" + ordinal) : null,
            tokenOutcome == null ? null : hash("token-evidence-" + ordinal),
            tokenOutcome,
            routed ? TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1 : null,
            routed ? ProviderApiFamily.LEGACY_OAPI : null,
            ConnectorOperation.ORGANIZATION_READ,
            "USER_BY_ID",
            routed ? GateResult.ALLOWED : GateResult.NOT_EVALUATED,
            dispatched,
            dispatchCount,
            completion,
            DurationBucket.LT_10_MS,
            failure,
            ordinalHash
        );
    }

    private static String hashTenant(String tenant) {
        return GovernedConnectorInvocationContracts.tenantHash(tenant);
    }

    private static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    private static boolean allZero(byte[] value) {
        return Arrays.equals(value, new byte[value.length]);
    }

    private record Fixture(
        BoundedConnectorOperationsDiagnosticsStore store,
        ConnectorDiagnosticsPageTokenCodec codec,
        ConnectorOperationsDiagnosticsQueryService service
    ) {
    }
}
