package io.github.akaryc1b.approval.ruoyi6.host;

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

    record ExternalIdResponse(String source, String objectType, String value) {
    }

    record UserResponse(
        ExternalIdResponse id,
        String username,
        String displayName,
        String email,
        String mobile,
        boolean active,
        List<ExternalIdResponse> departmentIds,
        Set<String> roleCodes,
        Set<String> positionCodes,
        ExternalIdResponse managerId,
        Map<String, String> attributes
    ) {
    }

    record TenantResponse(
        ExternalIdResponse id,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record DepartmentResponse(
        ExternalIdResponse id,
        String name,
        ExternalIdResponse parentId,
        ExternalIdResponse managerId,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record RoleResponse(
        ExternalIdResponse id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record PositionResponse(
        ExternalIdResponse id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    record IdRequest(ExternalIdRequest id) {
    }

    record ExternalIdRequest(String source, String objectType, String value) {
    }

    record CodeRequest(String code) {
    }

    record UserSearchRequest(UserQuery query, PageRequest page) {
    }

    record UserQuery(
        String keyword,
        ExternalIdRequest departmentId,
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

    record ManagerChainRequest(ExternalIdRequest userId, int maximumLevels) {
    }
}
