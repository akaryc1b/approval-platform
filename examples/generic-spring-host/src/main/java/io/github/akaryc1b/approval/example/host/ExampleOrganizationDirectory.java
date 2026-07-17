package io.github.akaryc1b.approval.example.host;

import io.github.akaryc1b.approval.example.host.HostContractModels.AuthenticationRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.AuthenticationResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.DepartmentResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.ExternalId;
import io.github.akaryc1b.approval.example.host.HostContractModels.ManagerChainRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.PageRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.PositionResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.RoleResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.TenantResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserPage;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserQuery;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
final class ExampleOrganizationDirectory {

    private final ExampleHostProperties properties;
    private final Clock clock;
    private final Map<String, UserRecord> users;
    private final Map<String, DepartmentRecord> departments;
    private final Map<String, RoleRecord> roles;
    private final Map<String, PositionRecord> positions;

    ExampleOrganizationDirectory(ExampleHostProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.departments = departments();
        this.roles = roles();
        this.positions = positions();
        this.users = users();
    }

    AuthenticationResponse authenticate(AuthenticationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String credentialType = requireText(request.credentialType(), "credentialType");
        if (!"BEARER".equalsIgnoreCase(credentialType)
            && !"SA_TOKEN".equalsIgnoreCase(credentialType)) {
            throw new GenericHostException(
                400,
                "UNSUPPORTED_CREDENTIAL",
                "credentialType must be BEARER or SA_TOKEN",
                false
            );
        }
        verifyToken(requireText(request.credential(), "credential"));
        UserRecord principal = users.get("100");
        return new AuthenticationResponse(
            mapUser(principal),
            tenant(),
            Set.of("approval:access", "approval:initiate", "approval:task:list"),
            clock.instant().plus(Duration.ofHours(1)),
            Map.of("authentication", "fixture-token")
        );
    }

    UserResponse findUser(ExternalId id) {
        String value = externalValue(id, "user");
        UserRecord user = users.get(value);
        return user == null ? null : mapUser(user);
    }

    UserPage searchUsers(UserQuery requestQuery, PageRequest page) {
        UserQuery query = requestQuery == null
            ? new UserQuery(null, null, null, null, null)
            : requestQuery;
        validatePage(page);
        String departmentId = query.departmentId() == null
            ? null
            : externalValue(query.departmentId(), "department");
        String keyword = normalize(query.keyword());

        List<UserRecord> matches = users.values().stream()
            .filter(user -> query.active() == null || query.active() == user.active())
            .filter(user -> departmentId == null || user.departmentIds().contains(departmentId))
            .filter(user -> query.roleCode() == null || user.roleCodes().contains(query.roleCode()))
            .filter(user -> query.positionCode() == null
                || user.positionCodes().contains(query.positionCode()))
            .filter(user -> keyword == null
                || user.username().toLowerCase(Locale.ROOT).contains(keyword)
                || user.displayName().toLowerCase(Locale.ROOT).contains(keyword))
            .toList();

        int from = Math.min(Math.multiplyExact(page.page(), page.size()), matches.size());
        int to = Math.min(from + page.size(), matches.size());
        List<UserResponse> items = matches.subList(from, to).stream().map(this::mapUser).toList();
        return new UserPage(items, null, matches.size());
    }

    DepartmentResponse findDepartment(ExternalId id) {
        DepartmentRecord department = departments.get(externalValue(id, "department"));
        return department == null ? null : mapDepartment(department);
    }

    RoleResponse findRole(String code) {
        RoleRecord role = roles.get(requireText(code, "roleCode"));
        return role == null ? null : mapRole(role);
    }

    PositionResponse findPosition(String code) {
        PositionRecord position = positions.get(requireText(code, "positionCode"));
        return position == null ? null : mapPosition(position);
    }

    List<UserResponse> roleMembers(String roleCode) {
        String code = requireText(roleCode, "roleCode");
        return users.values().stream()
            .filter(user -> user.roleCodes().contains(code))
            .map(this::mapUser)
            .toList();
    }

    List<UserResponse> positionMembers(String positionCode) {
        String code = requireText(positionCode, "positionCode");
        return users.values().stream()
            .filter(user -> user.positionCodes().contains(code))
            .map(this::mapUser)
            .toList();
    }

    List<UserResponse> managerChain(ManagerChainRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.maximumLevels() < 1 || request.maximumLevels() > 100) {
            throw new GenericHostException(
                400,
                "INVALID_REQUEST",
                "maximumLevels must be between 1 and 100",
                false
            );
        }
        UserRecord current = users.get(externalValue(request.userId(), "user"));
        if (current == null) {
            return List.of();
        }

        List<UserResponse> managers = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        visited.add(current.id());
        for (int level = 0; level < request.maximumLevels(); level++) {
            if (current.managerId() == null || !visited.add(current.managerId())) {
                break;
            }
            UserRecord manager = users.get(current.managerId());
            if (manager == null) {
                break;
            }
            managers.add(mapUser(manager));
            current = manager;
        }
        return List.copyOf(managers);
    }

    TenantResponse tenant() {
        return new TenantResponse(
            id("tenant", properties.getTenantId()),
            properties.getTenantName(),
            true,
            Map.of("mode", "fixture")
        );
    }

    private void verifyToken(String credential) {
        byte[] expected = properties.getBearerToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = credential.getBytes(StandardCharsets.UTF_8);
        try {
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new GenericHostException(
                    401,
                    "INVALID_CREDENTIAL",
                    "credential is invalid",
                    false
                );
            }
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
        }
    }

    private UserResponse mapUser(UserRecord user) {
        return new UserResponse(
            id("user", user.id()),
            user.username(),
            user.displayName(),
            user.email(),
            user.mobile(),
            user.active(),
            user.departmentIds().stream().map(value -> id("department", value)).toList(),
            user.roleCodes(),
            user.positionCodes(),
            user.managerId() == null ? null : id("user", user.managerId()),
            Map.of("tenantId", properties.getTenantId())
        );
    }

    private DepartmentResponse mapDepartment(DepartmentRecord department) {
        return new DepartmentResponse(
            id("department", department.id()),
            department.name(),
            department.parentId() == null ? null : id("department", department.parentId()),
            department.managerId() == null ? null : id("user", department.managerId()),
            department.active(),
            Map.of()
        );
    }

    private RoleResponse mapRole(RoleRecord role) {
        return new RoleResponse(
            id("role", role.id()),
            role.code(),
            role.name(),
            role.active(),
            Map.of()
        );
    }

    private PositionResponse mapPosition(PositionRecord position) {
        return new PositionResponse(
            id("position", position.id()),
            position.code(),
            position.name(),
            position.active(),
            Map.of("departmentId", position.departmentId())
        );
    }

    private ExternalId id(String objectType, String value) {
        return new ExternalId(properties.getSource(), objectType, value);
    }

    private String externalValue(ExternalId id, String expectedObjectType) {
        Objects.requireNonNull(id, "externalId must not be null");
        if (!properties.getSource().equals(id.source())
            || !expectedObjectType.equals(id.objectType())
            || id.value() == null
            || id.value().isBlank()) {
            throw new GenericHostException(
                400,
                "INVALID_EXTERNAL_ID",
                "external ID source, type or value is invalid",
                false
            );
        }
        return id.value();
    }

    private static void validatePage(PageRequest page) {
        Objects.requireNonNull(page, "page must not be null");
        if (page.page() < 0 || page.size() < 1 || page.size() > 500) {
            throw new GenericHostException(
                400,
                "INVALID_PAGE",
                "page must be >= 0 and size between 1 and 500",
                false
            );
        }
        if (page.cursor() != null && !page.cursor().isBlank()) {
            throw new GenericHostException(
                400,
                "CURSOR_UNSUPPORTED",
                "fixture directory does not support cursor paging",
                false
            );
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new GenericHostException(
                400,
                "INVALID_REQUEST",
                name + " must not be blank",
                false
            );
        }
        return value;
    }

    private static Map<String, DepartmentRecord> departments() {
        Map<String, DepartmentRecord> values = new LinkedHashMap<>();
        values.put("10", new DepartmentRecord("10", "Engineering", null, "200", true));
        values.put("20", new DepartmentRecord("20", "Finance", null, "400", true));
        return Map.copyOf(values);
    }

    private static Map<String, RoleRecord> roles() {
        Map<String, RoleRecord> values = new LinkedHashMap<>();
        values.put("employee", new RoleRecord("1", "employee", "Employee", true));
        values.put("engineering-manager", new RoleRecord(
            "2", "engineering-manager", "Engineering Manager", true
        ));
        values.put("finance-manager", new RoleRecord(
            "3", "finance-manager", "Finance Manager", true
        ));
        return Map.copyOf(values);
    }

    private static Map<String, PositionRecord> positions() {
        Map<String, PositionRecord> values = new LinkedHashMap<>();
        values.put("engineer", new PositionRecord("1", "engineer", "Engineer", "10", true));
        values.put("engineering-manager", new PositionRecord(
            "2", "engineering-manager", "Engineering Manager", "10", true
        ));
        values.put("accountant", new PositionRecord(
            "3", "accountant", "Accountant", "20", true
        ));
        values.put("finance-director", new PositionRecord(
            "4", "finance-director", "Finance Director", "20", true
        ));
        return Map.copyOf(values);
    }

    private static Map<String, UserRecord> users() {
        Map<String, UserRecord> values = new LinkedHashMap<>();
        values.put("100", new UserRecord(
            "100", "alice", "Alice Chen", "alice@example.com", "+10000000100", true,
            List.of("10"), Set.of("employee"), Set.of("engineer"), "200"
        ));
        values.put("200", new UserRecord(
            "200", "bob", "Bob Li", "bob@example.com", "+10000000200", true,
            List.of("10"), Set.of("employee", "engineering-manager"),
            Set.of("engineering-manager"), null
        ));
        values.put("300", new UserRecord(
            "300", "carol", "Carol Wang", "carol@example.com", "+10000000300", true,
            List.of("20"), Set.of("employee"), Set.of("accountant"), "400"
        ));
        values.put("400", new UserRecord(
            "400", "dave", "Dave Zhou", "dave@example.com", "+10000000400", true,
            List.of("20"), Set.of("employee", "finance-manager"),
            Set.of("finance-director"), null
        ));
        return Map.copyOf(values);
    }

    private record UserRecord(
        String id,
        String username,
        String displayName,
        String email,
        String mobile,
        boolean active,
        List<String> departmentIds,
        Set<String> roleCodes,
        Set<String> positionCodes,
        String managerId
    ) {
    }

    private record DepartmentRecord(
        String id,
        String name,
        String parentId,
        String managerId,
        boolean active
    ) {
    }

    private record RoleRecord(String id, String code, String name, boolean active) {
    }

    private record PositionRecord(
        String id,
        String code,
        String name,
        String departmentId,
        boolean active
    ) {
    }
}
