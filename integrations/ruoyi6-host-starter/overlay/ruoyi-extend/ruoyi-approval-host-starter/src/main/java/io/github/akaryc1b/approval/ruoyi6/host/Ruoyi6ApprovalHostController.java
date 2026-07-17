package io.github.akaryc1b.approval.ruoyi6.host;

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final ObjectMapper objectMapper;
    private final Ruoyi6TenantBridge tenantBridge;
    private final Ruoyi6ApprovalHostService service;

    Ruoyi6ApprovalHostController(
        HostRequestVerifier requestVerifier,
        ObjectMapper objectMapper,
        Ruoyi6TenantBridge tenantBridge,
        Ruoyi6ApprovalHostService service
    ) {
        this.requestVerifier = requestVerifier;
        this.objectMapper = objectMapper;
        this.tenantBridge = tenantBridge;
        this.service = service;
    }

    @PostMapping(value = "/authenticate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<AuthenticationResponse> authenticate(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("authentication.authenticate.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        AuthenticationRequest request = read(body, AuthenticationRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> new Envelope<>(service.authenticate(tenant, request))
        );
    }

    @PostMapping(value = "/organization/users/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<UserResponse>> findUser(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.users.find.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        IdRequest request = read(body, IdRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> nullable(service.findUser(tenant.id(), request.id()))
        );
    }

    @PostMapping(value = "/organization/users/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserPage> searchUsers(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.users.search.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        UserSearchRequest request = read(body, UserSearchRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> new Envelope<>(service.searchUsers(
                tenant.id(),
                request.query(),
                request.page()
            ))
        );
    }

    @PostMapping(value = "/organization/departments/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<DepartmentResponse>> findDepartment(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.departments.find.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        IdRequest request = read(body, IdRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> nullable(service.findDepartment(request.id()))
        );
    }

    @PostMapping(value = "/organization/roles/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<RoleResponse>> findRole(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.roles.find.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        CodeRequest request = read(body, CodeRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> nullable(service.findRole(request.code()))
        );
    }

    @PostMapping(value = "/organization/positions/find", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<PositionResponse>> findPosition(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.positions.find.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        CodeRequest request = read(body, CodeRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> nullable(service.findPosition(request.code()))
        );
    }

    @PostMapping(value = "/organization/roles/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> roleMembers(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.roles.members.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        CodeRequest request = read(body, CodeRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> new Envelope<>(new UserItems(
                service.roleMembers(tenant.id(), request.code())
            ))
        );
    }

    @PostMapping(value = "/organization/positions/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> positionMembers(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.positions.members.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        CodeRequest request = read(body, CodeRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> new Envelope<>(new UserItems(
                service.positionMembers(tenant.id(), request.code())
            ))
        );
    }

    @PostMapping(value = "/organization/users/manager-chain", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<UserItems> managerChain(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(KEY_ID) String keyId,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(SIGNATURE) String signature,
        @RequestHeader(OPERATION) String operation,
        @RequestHeader(REQUEST_ID) String requestId,
        @RequestBody String body
    ) {
        verify("organization.users.manager-chain.v1", tenantId, keyId, timestamp, nonce, signature,
            operation, requestId, body);
        ManagerChainRequest request = read(body, ManagerChainRequest.class);
        return tenantBridge.execute(
            tenantId,
            tenant -> new Envelope<>(new UserItems(
                service.managerChain(tenant.id(), request)
            ))
        );
    }

    private void verify(
        String expectedOperation,
        String tenantId,
        String keyId,
        String timestamp,
        String nonce,
        String signature,
        String operation,
        String requestId,
        String body
    ) {
        if (!expectedOperation.equals(operation)) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_OPERATION",
                "operation does not match endpoint",
                false
            );
        }
        requestVerifier.verify(new HostRequestVerifier.Request(
            tenantId,
            keyId,
            requestId,
            timestamp,
            nonce,
            body,
            signature
        ));
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException exception) {
            throw new Ruoyi6HostException(
                400,
                "INVALID_JSON",
                "request body is not valid JSON",
                false
            );
        }
    }

    private static <T> ResponseEntity<Envelope<T>> nullable(T value) {
        return value == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(new Envelope<>(value));
    }
}
