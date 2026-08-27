package io.github.akaryc1b.approval.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Loopback-only Spring MVC transport for the local purchase-payment sandbox.
 */
@RestController
@Profile("local")
@ConditionalOnProperty(
    prefix = "approval.demo.purchase-payment.sandbox",
    name = "enabled",
    havingValue = "true"
)
public final class PurchasePaymentDemoPaymentSandboxController {

    private final PurchasePaymentDemoPaymentSandbox sandbox;

    public PurchasePaymentDemoPaymentSandboxController(
        PurchasePaymentDemoPaymentSandbox sandbox
    ) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox must not be null");
    }

    @PostMapping(
        path = PurchasePaymentDemoPaymentSandbox.CALLBACK_PATH,
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    ResponseEntity<String> receive(
        @RequestHeader Map<String, String> headers,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.status(403).body("loopback callback required");
        }
        PurchasePaymentDemoPaymentSandbox.SandboxResponse response = sandbox.handle(
            request.getMethod(),
            request.getRequestURI(),
            headers,
            body
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(
            HttpStatusCode.valueOf(response.status())
        );
        if (response.requestId() != null) {
            builder.header("X-Request-Id", response.requestId());
        }
        return builder.contentType(MediaType.TEXT_PLAIN).body(response.body());
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
            || "0:0:0:0:0:0:0:1".equals(address)
            || "::1".equals(address);
    }
}
