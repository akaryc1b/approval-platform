package io.github.akaryc1b.approval.ruoyi6.host;

import cn.dev33.satoken.annotation.SaIgnore;
import io.github.akaryc1b.approval.host.security.HostRequestVerifier;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.AuthenticationRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.AuthenticationResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.CodeRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.DepartmentResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.Envelope;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.IdRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.ManagerChainRequest;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.PositionResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.RoleResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserItems;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserPage;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserResponse;
import io.github.akaryc1b.approval.ruoyi6.host.HostContractModels.UserSearchRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@SaIgnore
@RestController
@RequestMapping(
    value = "/api/approval-connector/v1",
    produces = MediaType.APPLICATION_JSON_VALUE
)
public final class Ruoyi6ApprovalHostController {

    private static final String KEY_ID = "X-Approval-Key-Id";
    private static final String TIMESTAMP = "X-Approval-Timestamp";
    private static final String NONCE = "X-Approval-Nonce";
    private static final String SIGNATURE = "X-Approval-Signature";
    private static final String OPERATION = "X-Approval-Operation";
    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String REQUEST_ID = "X-Request-Id";

    private final HostRequestVerifier requestVerifier;
    private final JsonMapper jsonMapper;
    private final Ruoyi6TenantBridge tenantBridge;
    private final Ruoyi6ApprovalHostService service;

    Ruoyi6ApprovalHostController(
        HostRequestVerifier requestVerifier,
        JsonMapper jsonMapper,
        Ruoyi6TenantBridge tenantBridge,
        Ruoyi6ApprovalHostService service
    ) {
        this.requestVerifier = requestVerifier;
        this.jsonMapper = jsonMapper;
        this.tenantBridge = tenantBridge;
        this.service = service;
    }

    @PostMapping(value = "/authenticate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<AuthenticationResponse> authenticate(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("authentication.authenticate.v1", headers, body);
        AuthenticationRequest payload = read(body, AuthenticationRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> new Envelope<>(service.authenticate(tenant, payload))
        );
    }

    @PostMapping(value = "/organization/users/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<UserResponse>> findUser(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.users.find.v1", headers, body);
        IdRequest payload = read(body, IdRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> nullable(service.findUser(tenant.id(), payload.id()))
        );
    }

    @PostMapping(value = "/organization/users/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserPage> searchUsers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.users.search.v1", headers, body);
        UserSearchRequest payload = read(body, UserSearchRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> new Envelope<>(service.searchUsers(
                tenant.id(),
                payload.query(),
                payload.page()
            ))
        );
    }

    @PostMapping(value = "/organization/departments/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<DepartmentResponse>> findDepartment(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.departments.find.v1", headers, body);
        IdRequest payload = read(body, IdRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> nullable(service.findDepartment(payload.id()))
        );
    }

    @PostMapping(value = "/organization/roles/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<RoleResponse>> findRole(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.roles.find.v1", headers, body);
        CodeRequest payload = read(body, CodeRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> nullable(service.findRole(payload.code()))
        );
    }

    @PostMapping(value = "/organization/positions/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<PositionResponse>> findPosition(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.positions.find.v1", headers, body);
        CodeRequest payload = read(body, CodeRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> nullable(service.findPosition(payload.code()))
        );
    }

    @PostMapping(value = "/organization/roles/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> roleMembers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.roles.members.v1", headers, body);
        CodeRequest payload = read(body, CodeRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> new Envelope<>(new UserItems(
                service.roleMembers(tenant.id(), payload.code())
            ))
        );
    }

    @PostMapping(value = "/organization/positions/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> positionMembers(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.positions.members.v1", headers, body);
        CodeRequest payload = read(body, CodeRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> new Envelope<>(new UserItems(
                service.positionMembers(tenant.id(), payload.code())
            ))
        );
    }

    @PostMapping(value = "/organization/users/manager-chain", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> managerChain(
        @RequestHeader HttpHeaders headers,
        @RequestBody String body
    ) {
        SignedRequest request = verify("organization.users.manager-chain.v1", headers, body);
        ManagerChainRequest payload = read(body, ManagerChainRequest.class);
        return tenantBridge.execute(
            request.tenantId(),
            tenant -> new Envelope<>(new UserItems(
                service.managerChain(tenant.id(), payload)
            ))
        );
    }

    private SignedRequest verify(
        String expectedOperation,
        HttpHeaders headers,
        String body
    ) {
        SignedRequest request = new SignedRequest(
            required(headers, TENANT_ID),
            required(headers, KEY_ID),
            required(headers, REQUEST_ID),
            required(headers, TIMESTAMP),
            required(headers, NONCE),
            required(headers, SIGNATURE),
            required(headers, OPERATION)
        );
        if (!expectedOperation.equals(request.operation())) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_OPERATION",
                "operation does not match endpoint",
                false
            );
        }
        requestVerifier.verify(new HostRequestVerifier.Request(
            request.tenantId(),
            request.keyId(),
            request.requestId(),
            request.timestamp(),
            request.nonce(),
            body,
            request.signature()
        ));
        return request;
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return jsonMapper.readValue(body, type);
        } catch (RuntimeException exception) {
            throw new Ruoyi6HostException(
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
            throw new Ruoyi6HostException(
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

    private record SignedRequest(
        String tenantId,
        String keyId,
        String requestId,
        String timestamp,
        String nonce,
        String signature,
        String operation
    ) {
    }
}
