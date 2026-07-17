package io.github.akaryc1b.approval.ruoyi6.host;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HostContractModels {

    private HostContractModels() {
    }

    public record Envelope<T>(T data) {
    }

    public record AuthenticationRequest(
        String credentialType,
        String credential,
        Map<String, String> attributes
    ) {
    }

    public record AuthenticationResponse(
        UserResponse principal,
        TenantResponse tenant,
        Set<String> permissions,
        Instant expiresAt,
        Map<String, String> attributes
    ) {
    }

    public record ExternalIdResponse(String source, String objectType, String value) {
    }

    public record UserResponse(
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

    public record TenantResponse(
        ExternalIdResponse id,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    public record DepartmentResponse(
        ExternalIdResponse id,
        String name,
        ExternalIdResponse parentId,
        ExternalIdResponse managerId,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    public record RoleResponse(
        ExternalIdResponse id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    public record PositionResponse(
        ExternalIdResponse id,
        String code,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
    }

    public record IdRequest(ExternalIdRequest id) {
    }

    public record ExternalIdRequest(String source, String objectType, String value) {
    }

    public record CodeRequest(String code) {
    }

    public record UserSearchRequest(UserQuery query, PageRequest page) {
    }

    public record UserQuery(
        String keyword,
        ExternalIdRequest departmentId,
        String roleCode,
        String positionCode,
        Boolean active
    ) {
    }

    public record PageRequest(int page, int size, String cursor) {
    }

    public record UserPage(List<UserResponse> items, String nextCursor, long total) {
    }

    public record UserItems(List<UserResponse> items) {
    }

    public record ManagerChainRequest(ExternalIdRequest userId, int maximumLevels) {
    }
}
