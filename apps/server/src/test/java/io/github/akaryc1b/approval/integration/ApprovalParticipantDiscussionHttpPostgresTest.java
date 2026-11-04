package io.github.akaryc1b.approval.integration;

import io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeedState;
import java.util.List;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.security.ApprovalPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/PG with a JVM-owned test authentication adapter, NOT browser SSO acceptance. */
@EnabledIf("databaseAvailable")
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ApprovalParticipantDiscussionHttpPostgresTest.DatabaseCleanup.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Import(ApprovalParticipantDiscussionHttpPostgresTest.IdentityFixture.class)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "approval.security.identity.mode=principal",
        "approval.demo.purchase-payment.enabled=true",
        "approval.demo.purchase-payment.sandbox.enabled=false",
        "approval.connector.generic.enabled=false",
        "flowable.async-executor-activate=false",
        "approval.notifications.delivery-delay-ms=3600000"
    })
class ApprovalParticipantDiscussionHttpPostgresTest {
    private static final String TENANT = "demo-purchase-payment";
    private static PostgreSQLContainer postgres;
    private static final String EXTERNAL_DB = System.getenv("APPROVAL_TEST_DB_URL");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired AtomicReference<ApprovalPrincipal> testPrincipal;
    @Autowired @Qualifier("approvalPersistenceObjectMapper") ObjectMapper mapper;

    static boolean databaseAvailable() {
        return EXTERNAL_DB != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (EXTERNAL_DB != null) {
            if (!EXTERNAL_DB.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("external test PostgreSQL must use explicit IPv4 loopback");
            }
            properties.add("spring.datasource.url", () -> EXTERNAL_DB);
            properties.add("spring.datasource.username", () -> System.getenv("APPROVAL_TEST_DB_USERNAME"));
            properties.add("spring.datasource.password", () -> System.getenv("APPROVAL_TEST_DB_PASSWORD"));
        } else {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("approval_audit_http").withUsername("approval").withPassword("approval");
            postgres.start();
            properties.add("spring.datasource.url", postgres::getJdbcUrl);
            properties.add("spring.datasource.username", postgres::getUsername);
            properties.add("spring.datasource.password", postgres::getPassword);
        }
    }

    // afterTestClass callbacks run in reverse order: Spring dirties/closes at3000, then DB stops.
    public static class DatabaseCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 2500; }
        @Override public void afterTestClass(TestContext ignored) {
            if (postgres != null) postgres.stop();
        }
    }

    @Autowired PurchasePaymentDemoSeedState seed;
    @AfterEach void clearIdentity() { testPrincipal.set(null); }
    private void actor(String actor) { testPrincipal.set(ApprovalPrincipal.active(TENANT, actor, Set.of(), null)); }

    @Test void discussionRevisionsPrivateAudienceAttachmentsAndInboxStayParticipantScoped() throws Exception {
        String instance = seed.evidence().orElseThrow().instanceId().toString();
        String comments = "/instances/" + instance + "/comments";
        actor("demo-manager");
        JsonNode preferences = get("/notifications/preferences", 200);
        Map<String,Object> preferenceUpdate = Map.of("timezone", "UTC", "quietHoursEnabled", false,
            "emergencyBypass", true, "digestEnabled", false, "expectedVersion", preferences.path("version").asLong(),
            "preferences", List.of(Map.of("eventType", "COMMENT_MENTION", "channel", "IN_APP", "enabled", true)));
        JsonNode savedPreferences = command("PUT", "/notifications/preferences", preferenceUpdate, "prefs", 200);
        assertEquals(preferences.path("version").asLong() + 1, savedPreferences.path("version").asLong());
        JsonNode reloadedPreferences = get("/notifications/preferences", 200);
        assertEquals(savedPreferences.path("version"), reloadedPreferences.path("version"));
        assertEquals("UTC", reloadedPreferences.path("timezone").asText());
        assertEquals(false, reloadedPreferences.path("digestEnabled").asBoolean());
        assertEquals(java.util.stream.StreamSupport.stream(savedPreferences.path("preferences").spliterator(), false)
                .map(JsonNode::toString).collect(java.util.stream.Collectors.toSet()),
            java.util.stream.StreamSupport.stream(reloadedPreferences.path("preferences").spliterator(), false)
                .map(JsonNode::toString).collect(java.util.stream.Collectors.toSet()));
        command("PUT", "/notifications/preferences", preferenceUpdate, "prefs-stale", 409);
        actor("demo-employee");
        JsonNode started = get("/instances/started?keyword=DEMO-PP-0001&limit=1", 200);
        assertTrue(contains(started.path("items"), "instanceId", instance));
        assertEquals(0, count(get("/instances/started?keyword=does-not-exist", 200), "total"));
        assertEquals(0, count(get("/tasks/processed", 200), "total"));
        get(comments + "/options", 200);
        byte[] receipt = "isolated discussion attachment".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded = upload(receipt, "upload-owned");
        String attachment = uploaded.path("attachmentId").asText();
        assertTrue(!attachment.isBlank());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(receipt)),
            uploaded.path("sha256").asText());
        assertEquals(uploaded.path("attachmentId"), upload(receipt, "upload-owned").path("attachmentId"));
        get("/attachments/" + attachment, 200);
        actor("demo-manager");
        get("/attachments/" + attachment, 404);
        command("POST", comments, comment("Foreign attachment", List.of(), List.of(attachment), "PARTICIPANTS"), "foreign-file", 409);
        actor("demo-employee");
        Map<String,Object> original = comment("Discussion original", List.of("demo-manager"), List.of(attachment), "PARTICIPANTS");
        JsonNode created = command("POST", comments, original, "comment-once", 200);
        assertEquals(created, command("POST", comments, original, "comment-once", 200));
        command("POST", comments, comment("Changed body", List.of("demo-manager"), List.of(attachment), "PARTICIPANTS"), "comment-once", 409);
        String commentId = created.path("commentId").asText();
        assertEquals(1, created.path("currentRevision").asInt());
        actor("demo-manager");
        HttpResponse<String> downloaded = raw("GET", "/attachments/" + attachment + "/content", null, null, 200);
        assertEquals(new String(receipt, StandardCharsets.UTF_8), downloaded.body());
        assertEquals(uploaded.path("sha256").asText(), downloaded.headers().firstValue("X-Content-SHA256").orElseThrow());
        assertTrue(contains(get(comments, 200).path("items"), "commentId", commentId));
        Map<String,Object> reply = new LinkedHashMap<>(comment("Manager reply", List.of("demo-employee"), List.of(), "PARTICIPANTS"));
        reply.put("parentCommentId", commentId);
        JsonNode replied = command("POST", comments, reply, "reply-once", 200);
        assertEquals(commentId, replied.path("parentCommentId").asText());
        Map<String,Object> nested = new LinkedHashMap<>(reply); nested.put("parentCommentId", replied.path("commentId").asText());
        command("POST", comments, nested, "nested-rejected", 400);
        get(comments + "/" + commentId + "/revisions", 403);
        Map<String,Object> edit = new LinkedHashMap<>(comment("Discussion edited", List.of("demo-manager"), List.of(attachment), "PARTICIPANTS"));
        edit.put("expectedVersion", created.path("version").asLong()); edit.put("reason", "Correct wording");
        command("PUT", comments + "/" + commentId, edit, "non-author-edit", 403);
        actor("demo-employee");
        JsonNode edited = command("PUT", comments + "/" + commentId, edit, "edit-once", 200);
        assertEquals(2, edited.path("currentRevision").asInt()); assertTrue(edited.path("edited").asBoolean());
        command("PUT", comments + "/" + commentId, edit, "stale-edit", 409);
        JsonNode revisions = get(comments + "/" + commentId + "/revisions", 200);
        assertEquals(2, revisions.size());
        assertTrue(contains(revisions, "body", "Discussion original")); assertTrue(contains(revisions, "body", "Discussion edited"));
        command("POST", comments, comment("Invalid mention", List.of("not-a-participant"), List.of(), "PARTICIPANTS"), "invalid-mention", 400);
        String privateAttachment = upload("private receipt".getBytes(StandardCharsets.UTF_8), "private-upload").path("attachmentId").asText();
        JsonNode privateComment = command("POST", comments, comment("Private manager note", List.of("demo-manager"), List.of(privateAttachment), "MENTIONED_ONLY"), "private-once", 200);
        String privateId = privateComment.path("commentId").asText();
        JsonNode copied = command("POST", "/instances/" + instance + "/copy",
            Map.of("recipientIds", List.of("demo-finance-reviewer"), "comment", "Please review discussion"), "copy-once", 200);
        assertEquals(copied, command("POST", "/instances/" + instance + "/copy",
            Map.of("recipientIds", List.of("demo-finance-reviewer"), "comment", "Please review discussion"), "copy-once", 200));
        actor("demo-finance-reviewer");
        assertTrue(contains(get("/instances/copied?limit=20", 200).path("items"), "instanceId", instance));
        get("/attachments/" + attachment, 200);
        get("/attachments/" + privateAttachment, 404);
        raw("GET", "/attachments/" + privateAttachment + "/content", null, null, 404);
        JsonNode publicView = get(comments, 200);
        assertTrue(contains(publicView.path("items"), "commentId", commentId));
        assertEquals(false, contains(publicView.path("items"), "commentId", privateId));
        get(comments + "/" + privateId + "/revisions", 403);
        actor("demo-manager");
        assertTrue(contains(get(comments, 200).path("items"), "commentId", privateId));
        get("/attachments/" + privateAttachment, 200);
        assertEquals("private receipt", raw("GET", "/attachments/" + privateAttachment + "/content", null, null, 200).body());
        Map<String,Object> expanded = new LinkedHashMap<>(comment("Private reply", List.of("demo-finance-reviewer"), List.of(), "MENTIONED_ONLY"));
        expanded.put("parentCommentId", privateId);
        assertEquals("APPROVAL_COMMENT_PRIVATE_AUDIENCE_EXPANSION",
            command("POST", comments, expanded, "private-expansion", 409).path("code").asText());
        JsonNode history = get("/notifications?limit=100", 200);
        JsonNode notification = java.util.stream.StreamSupport.stream(history.path("items").spliterator(), false)
            .filter(item -> item.path("eventType").asText().equals("COMMENT_MENTION")
                && item.path("channel").asText().equals("IN_APP") && item.path("instanceId").asText().equals(instance))
            .findFirst().orElseThrow();
        String intent = notification.path("intentId").asText();
        assertTrue(count(get("/notifications/unread-count", 200), "unread") > 0);
        assertEquals(0, get("/notifications/" + intent + "/attempts", 200).size());
        command("POST", "/notifications/" + intent + "/replay", null, "not-dead", 409);
        JsonNode read = command("POST", "/notifications/" + intent + "/read", null, "read-once", 200);
        assertTrue(read.hasNonNull("readAt"));
        assertEquals(read.path("readAt"), command("POST", "/notifications/" + intent + "/read", null, "read-again", 200).path("readAt"));
        command("POST", "/notifications/read-all", null, "read-all", 200);
        assertEquals(0, count(get("/notifications/unread-count", 200), "unread"));
        assertEquals(0, get("/notifications?unreadOnly=true", 200).path("items").size());
        actor("demo-employee");
        command("POST", "/notifications/" + intent + "/read", null, "wrong-owner", 404);
        Map<String,Object> deletion = Map.of("expectedVersion", edited.path("version").asLong(), "reason", "Remove obsolete note");
        JsonNode deleted = command("DELETE", comments + "/" + commentId, deletion, "delete-once", 200);
        assertEquals("DELETED", deleted.path("status").asText()); assertEquals(3, deleted.path("currentRevision").asInt());
        assertEquals(deleted, command("DELETE", comments + "/" + commentId, deletion, "delete-once", 200));
        assertEquals(3, get(comments + "/" + commentId + "/revisions", 200).size());
        command("POST", comments, reply, "reply-deleted", 409);
        JsonNode page = get(comments + "?limit=1&offset=0", 200); assertEquals(1, page.path("items").size()); assertTrue(page.path("hasMore").asBoolean());
        assertEquals(0, get(comments + "?limit=1&offset=999", 200).path("items").size());
        actor("not-a-participant"); get(comments, 403); get("/attachments/" + attachment, 404);
        raw("GET", "/attachments/" + attachment + "/content", null, null, 404);
        testPrincipal.set(ApprovalPrincipal.active("other-tenant", "demo-employee", Set.of(), null));
        get(comments, 404); get("/attachments/" + attachment, 404);
        testPrincipal.set(null); get(comments, 401);
    }
    private static Map<String,Object> comment(String body, List<String> mentions, List<String> attachments, String visibility) {
        return Map.of("body", body, "mentionIds", mentions, "attachmentIds", attachments, "visibility", visibility);
    }
    private static long count(JsonNode object, String field) {
        assertTrue(object.path(field).isIntegralNumber(), "missing numeric field: " + field);
        return object.path(field).asLong();
    }
    private static boolean contains(JsonNode items, String field, String value) {
        assertTrue(items.isArray()); for (JsonNode item : items) if (value.equals(item.path(field).asText())) return true; return false;
    }
    private JsonNode get(String path, int expected) throws Exception { return mapper.readTree(raw("GET", path, null, null, expected).body()); }
    private JsonNode command(String method, String path, Object body, String key, int expected) throws Exception {
        return mapper.readTree(raw(method, path, body, key, expected).body());
    }
    private HttpRequest.Builder request(String path) {
        var selected = testPrincipal.get(); String id = UUID.randomUUID().toString();
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/approval" + path))
            .timeout(Duration.ofSeconds(15)).header("X-Tenant-Id", selected == null ? TENANT : selected.tenantId())
            .header("X-Request-Id", id).header("X-Trace-Id", id);
    }
    private HttpResponse<String> raw(String method, String path, Object body, String key, int expected) throws Exception {
        var builder = request(path).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return send(builder.build(), expected);
    }
    private JsonNode upload(byte[] content, String key) throws Exception {
        String boundary = "isolated-upload-boundary";
        String data = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"receipt.txt\"\r\nContent-Type: text/plain\r\n\r\n"
            + new String(content, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n";
        return mapper.readTree(send(request("/attachments").header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(data)).build(), 200).body());
    }
    private HttpResponse<String> send(HttpRequest request, int expected) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("PARTICIPANT_HTTP " + request.method() + " " + request.uri().getPath() + " " + response.statusCode());
        assertEquals(expected, response.statusCode(), response.body()); return response;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityFixture {
        @Bean AtomicReference<ApprovalPrincipal> testPrincipal() { return new AtomicReference<>(); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> testPrincipalFilter(AtomicReference<ApprovalPrincipal> selected) {
            var registration = new FilterRegistrationBean<OncePerRequestFilter>();
            registration.setFilter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                    ApprovalPrincipal fixed = selected.get(); // JVM-only; no header/cookie/body can choose a role.
                    chain.doFilter(new HttpServletRequestWrapper(request) {
                        @Override public Principal getUserPrincipal() { return fixed; }
                    }, response);
                }
            });
            registration.setName("isolatedTestPrincipalFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            registration.addUrlPatterns("/api/approval/*");
            return registration;
        }
    }
}
