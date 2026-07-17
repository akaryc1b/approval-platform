package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.model.ExternalId;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public interface FileConnector {

    UploadSession createUploadSession(ConnectorContext context, UploadRequest request);

    StoredFile completeUpload(ConnectorContext context, CompleteUploadRequest request);

    URI issueDownloadUrl(ConnectorContext context, ExternalId fileId, Duration timeToLive);

    void delete(ConnectorContext context, ExternalId fileId);

    record UploadRequest(
        String fileName,
        String contentType,
        long contentLength,
        String checksum,
        Map<String, String> attributes
    ) {
        public UploadRequest {
            fileName = requireText(fileName, "fileName");
            contentType = requireText(contentType, "contentType");
            if (contentLength < 0) {
                throw new IllegalArgumentException("contentLength must not be negative");
            }
            checksum = normalize(checksum);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record UploadSession(
        String sessionId,
        URI uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
    ) {
        public UploadSession {
            sessionId = requireText(sessionId, "sessionId");
            uploadUrl = Objects.requireNonNull(uploadUrl, "uploadUrl must not be null");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            requiredHeaders = requiredHeaders == null
                ? Map.of()
                : Map.copyOf(requiredHeaders);
        }
    }

    record CompleteUploadRequest(String sessionId, String checksum) {
        public CompleteUploadRequest {
            sessionId = requireText(sessionId, "sessionId");
            checksum = normalize(checksum);
        }
    }

    record StoredFile(
        ExternalId id,
        String fileName,
        String contentType,
        long contentLength,
        String checksum,
        Map<String, String> attributes
    ) {
        public StoredFile {
            id = Objects.requireNonNull(id, "id must not be null");
            fileName = requireText(fileName, "fileName");
            contentType = requireText(contentType, "contentType");
            if (contentLength < 0) {
                throw new IllegalArgumentException("contentLength must not be negative");
            }
            checksum = normalize(checksum);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
