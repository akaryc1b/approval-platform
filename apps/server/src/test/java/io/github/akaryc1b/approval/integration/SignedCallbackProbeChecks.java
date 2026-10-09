package io.github.akaryc1b.approval.integration;

import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** The same dependency-free behavioral checks run locally and through the normal JUnit wrapper. */
public final class SignedCallbackProbeChecks {
    private SignedCallbackProbeChecks() { }

    public static void main(String[] args) throws Exception {
        AtomicInteger verified = new AtomicInteger();
        try (var probe = new SignedCallbackProbe(request -> {
            require("审批".equals(request.body()), "UTF-8 body");
            require("owned".equals(request.headers().get("traceparent")), "trace header");
            verified.incrementAndGet();
        }); HttpClient client = HttpClient.newHttpClient()) {
            probe.failFirst("same-event");
            var request = HttpRequest.newBuilder(probe.uri()).timeout(Duration.ofSeconds(3))
                .header("Idempotency-Key", "same-event").header("traceparent", "owned")
                .POST(HttpRequest.BodyPublishers.ofString("审批")).build();
            require(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 503, "503 attempt");
            require(probe.acceptedCount() == 0, "failure has no side effect");
            require(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 204, "recovery");
            require(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 204, "dedup response");
            require(probe.acceptedCount() == 1 && verified.get() == 3, "exactly one verified side effect");
            require(probe.deliveries().size() == 3, "attempts retained");
            probe.assertHealthy();
        }
        for (String malformed : new String[] {
            "Content-Length: 0\r\ncontent-length: 0\r\n",
            "Content-Length: 65537\r\n",
            "Content-Length: 5\r\n",
            "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n",
            "X-Large: " + "x".repeat(16384) + "\r\nContent-Length: 0\r\n"
        }) {
            AtomicInteger reachedVerifier = new AtomicInteger();
            try (var probe = new SignedCallbackProbe(request -> {
                reachedVerifier.incrementAndGet();
                throw new AssertionError("malformed input reached verifier");
            }); var socket = new Socket("127.0.0.1", probe.uri().getPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("POST /callback HTTP/1.1\r\n" + malformed
                    + "Idempotency-Key: rejected\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.shutdownOutput();
                // A rejected request may close gracefully or reset the unread remainder.
                try { socket.getInputStream().readAllBytes(); } catch (java.net.SocketException rejected) {
                    // The body/header rejection closes a real socket with unread input.
                }
                require(probe.acceptedCount() == 0 && probe.deliveries().isEmpty(), "rejected input has no side effect");
                require(reachedVerifier.get() == 0, "malformed input rejected before verification");
            }
        }
        try (var probe = new SignedCallbackProbe(request -> { throw new IllegalArgumentException("bad signature"); });
             HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(probe.uri()).timeout(Duration.ofSeconds(3))
                .header("Idempotency-Key", "forbidden").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
                throw new AssertionError("unverified request was acknowledged");
            } catch (java.io.IOException expected) {
                // Signature rejection must close the request without acknowledging it.
            }
            require(probe.acceptedCount() == 0, "signature verification precedes side effect");
        }
        System.out.println("SIGNED_CALLBACK_PROBE_CHECKS_PASSED cases=7");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
