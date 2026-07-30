package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryEntry;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryEntryType;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.DirectoryReadResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolveResult;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperationPayloads.IdentityResolutionStatus;
import io.github.akaryc1b.approval.connector.model.ExternalId;
import io.github.akaryc1b.approval.connector.model.UserSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DingTalkUserMappings {

    private DingTalkUserMappings() {
    }

    static DirectoryReadResult directoryResult(DingTalkUserDetail detail) {
        ExternalId userId = DingTalkProviderContract.userId(detail.userId());
        return new DirectoryReadResult(
            List.of(
                new DirectoryEntry(
                    userId,
                    DirectoryEntryType.USER,
                    detail.name(),
                    detail.active(),
                    null,
                    attributes(detail)
                )
            ),
            null,
            1
        );
    }

    static IdentityResolveResult identityResult(DingTalkUserDetail detail) {
        ExternalId userId = DingTalkProviderContract.userId(detail.userId());
        List<ExternalId> departmentIds = new ArrayList<>();
        detail.departmentIds().forEach(
            value -> departmentIds.add(DingTalkProviderContract.departmentId(value))
        );
        ExternalId managerId = detail.managerUserId() == null
            ? null
            : DingTalkProviderContract.userId(detail.managerUserId());
        UserSnapshot snapshot = new UserSnapshot(
            userId,
            detail.userId(),
            detail.name(),
            detail.email(),
            detail.mobile(),
            detail.active(),
            departmentIds,
            Set.of(),
            Set.of(),
            managerId,
            attributes(detail)
        );
        return new IdentityResolveResult(
            IdentityResolutionStatus.RESOLVED,
            userId,
            DingTalkProviderContract.IDENTITY_NAMESPACE + ":" + detail.userId(),
            snapshot,
            Map.of("provider", DingTalkProviderContract.PROVIDER_KEY)
        );
    }

    private static Map<String, String> attributes(DingTalkUserDetail detail) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "unionId", detail.unionId());
        put(attributes, "title", detail.title());
        put(attributes, "jobNumber", detail.jobNumber());
        return Map.copyOf(attributes);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
