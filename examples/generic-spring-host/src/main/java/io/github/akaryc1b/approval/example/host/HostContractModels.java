package io.github.akaryc1b.approval.example.host;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HostContractModels {

    private HostContractModels() {
    }

    record Envelope<T>(T data) {
    }

    record AuthenticationRequest(
        String credentialType,
        String credential,
        Map<String, String> attributes
    ) {
    }

    record AuthenticationResponse(
        UserResponse principal,
        TenantResponse tenant,
        Set<String> permissions,
        Instant expiresAt,
        Map<String, String> attributes
    ) {
    }

    record ExternalId(String source, String objectType, String value) {
    }

    record UserResponse(
        ExternalId id,
        String username,
        String displayName,
        String email,
        String mobile,
        boolean active,
        List<ExternalId> departmentIds,
        Set<String> roleCodes,
        Set<String> positionCodes,
        ExternalId managerId,
        Map<String, String> attributes
    ) {
    }

    record TenantResponse(
        ExternalId id,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record DepartmentResponse(
        ExternalId id,
        String name,
        ExternalId parentId,
        ExternalId managerId,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record RoleResponse(
        ExternalId id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record PositionResponse(
        ExternalId id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record IdRequest(ExternalId id) {
    }

    record CodeRequest(String code) {
    }

    record UserSearchRequest(UserQuery query, PageRequest page) {
    }

    record UserQuery(
        String keyword,
        ExternalId departmentId,
        String roleCode,
        String positionCode,
        Boolean active
    ) {
    }

    record PageRequest(int page, int size, String cursor) {
    }

    record UserPage(List<UserResponse> items, String nextCursor, long total) {
    }

    record UserItems(List<UserResponse> items) {
    }

    record ManagerChainRequest(ExternalId userId, int maximumLevels) {
    }
}
