package io.github.akaryc1b.approval.example.host;

import io.github.akaryc1b.approval.example.host.HostContractModels.AuthenticationRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.AuthenticationResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.CodeRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.DepartmentResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.Envelope;
import io.github.akaryc1b.approval.example.host.HostContractModels.IdRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.ManagerChainRequest;
import io.github.akaryc1b.approval.example.host.HostContractModels.PositionResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.RoleResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserItems;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserPage;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserResponse;
import io.github.akaryc1b.approval.example.host.HostContractModels.UserSearchRequest;
import io.github.akaryc1b.approval.host.security.HostRequestVerifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(
    value = "/api/approval-connector/v1",
    produces = MediaType.APPLICATION_JSON_VALUE
)
final class GenericHostController {

    private static final String KEY_ID = "X-Approval-Key-Id";
    private static final String TIMESTAMP = "X-Approval-Timestamp";
    private static final String NONCE = "X-Approval-Nonce";
    private static final String SIGNATURE = "X-Approval-Signature";
    private static final String OPERATION = "X-Approval-Operation";
    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String REQUEST_ID = "X-Request-Id";

    private final HostRequestVerifier verifier;
    private final JsonMapper jsonMapper;
    private final ExampleOrganizationDirectory directory;

    GenericHostController(
        HostRequestVerifier verifier,
        JsonMapper jsonMapper,
        ExampleOrganizationDirectory directory
    ) {
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.directory = directory;
    }

    @PostMapping(value = "/authenticate", consumes = MediaType.APPLICATION_JSON_VALUE)
    Envelope<AuthenticationResponse> authenticate(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("authentication.authenticate.v1", headers, body);
        return new Envelope<>(directory.authenticate(read(body, AuthenticationRequest.class)));
    }

    @PostMapping(value = "/organization/users/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<UserResponse>> findUser(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.users.find.v1", headers, body);
        return nullable(directory.findUser(read(body, IdRequest.class).id()));
    }

    @PostMapping(value = "/organization/users/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    Envelope<UserPage> searchUsers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.users.search.v1", headers, body);
        UserSearchRequest request = read(body, UserSearchRequest.class);
        return new Envelope<>(directory.searchUsers(request.query(), request.page()));
    }

    @PostMapping(value = "/organization/departments/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<DepartmentResponse>> findDepartment(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.departments.find.v1", headers, body);
        return nullable(directory.findDepartment(read(body, IdRequest.class).id()));
    }

    @PostMapping(value = "/organization/roles/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<RoleResponse>> findRole(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.roles.find.v1", headers, body);
        return nullable(directory.findRole(read(body, CodeRequest.class).code()));
    }

    @PostMapping(value = "/organization/positions/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<PositionResponse>> findPosition(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.positions.find.v1", headers, body);
        return nullable(directory.findPosition(read(body, CodeRequest.class).code()));
    }

    @PostMapping(value = "/organization/roles/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    Envelope<UserItems> roleMembers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.roles.members.v1", headers, body);
        return new Envelope<>(new UserItems(
            directory.roleMembers(read(body, CodeRequest.class).code())
        ));
    }

    @PostMapping(value = "/organization/positions/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    Envelope<UserItems> positionMembers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.positions.members.v1", headers, body);
        return new Envelope<>(new UserItems(
            directory.positionMembers(read(body, CodeRequest.class).code())
        ));
    }

    @PostMapping(value = "/organization/users/manager-chain", consumes = MediaType.APPLICATION_JSON_VALUE)
    Envelope<UserItems> managerChain(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        verify("organization.users.manager-chain.v1", headers, body);
        return new Envelope<>(new UserItems(
            directory.managerChain(read(body, ManagerChainRequest.class))
        ));
    }

    private void verify(String expectedOperation, HttpHeaders headers, String body) {
        String operation = required(headers, OPERATION);
        if (!expectedOperation.equals(operation)) {
            throw new GenericHostException(
                400,
                "INVALID_OPERATION",
                "operation does not match endpoint",
                false
            );
        }
        verifier.verify(new HostRequestVerifier.Request(
            required(headers, TENANT_ID),
            required(headers, KEY_ID),
            required(headers, REQUEST_ID),
            required(headers, TIMESTAMP),
            required(headers, NONCE),
            body,
            required(headers, SIGNATURE)
        ));
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return jsonMapper.readValue(body, type);
        } catch (RuntimeException exception) {
            throw new GenericHostException(
                400,
                "INVALID_JSON",
                "request body is not valid JSON",
                false
            );
        }
    }

    private static String required(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new GenericHostException(
                400,
                "MISSING_HEADER",
                "required connector header is missing",
                false
            );
        }
        return value;
    }

    private static <T> ResponseEntity<Envelope<T>> nullable(T value) {
        return value == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(new Envelope<>(value));
    }
}
