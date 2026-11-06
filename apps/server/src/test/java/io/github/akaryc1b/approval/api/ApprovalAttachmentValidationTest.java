package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import java.time.Clock;
import java.util.UUID;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalAttachmentValidationTest {
    @Test
    void emptyMultipartIsRejectedAsBadRequestBeforeStorage() throws Exception {
        var service = mock(ApprovalAttachmentService.class);
        performInvalidUpload(service, new byte[0]);
        verifyNoInteractions(service);
    }

    @Test
    void oversizedMultipartIsRejectedAsBadRequestBeforeStorage() throws Exception {
        var service = mock(ApprovalAttachmentService.class);
        performInvalidUpload(service, new byte[(int) ApprovalAttachmentService.MAX_FILE_SIZE + 1]);
        verifyNoInteractions(service);
    }

    @Test
    void missingFilePartIsRejectedBeforeStorage() throws Exception {
        var service = mock(ApprovalAttachmentService.class);
        mvc(service).perform(multipart("/api/approval/attachments")
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                .header("X-Request-Id", "validation-test").header("Idempotency-Key", "validation-test"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void overlongFileNameIsRejectedByRealServiceBeforeStorage() throws Exception {
        assertRealServiceMetadataRejected("a".repeat(256), "text/plain");
    }

    @Test
    void overlongContentTypeIsRejectedByRealServiceBeforeStorage() throws Exception {
        assertRealServiceMetadataRejected("fixture.txt", "application/" + "a".repeat(161));
    }

    private void assertRealServiceMetadataRejected(String fileName, String contentType) throws Exception {
        var idempotency = mock(IdempotencyGuard.class);
        var attachments = mock(ApprovalAttachmentStore.class);
        var service = new ApprovalAttachmentService(idempotency, attachments,
            mock(ApprovalProjectionStore.class), mock(ApprovalMessageStore.class),
            mock(ApprovalCommentStore.class), Clock.systemUTC(), UUID::randomUUID);
        mvc(service).perform(multipart("/api/approval/attachments")
                .file(new MockMultipartFile("file", fileName, contentType, new byte[] { 1 }))
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                .header("X-Request-Id", "validation-test").header("Idempotency-Key", "validation-test"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(idempotency, attachments);
    }

    @Test
    void unexpectedServiceFailureIsNotReclassifiedAsClientValidation() {
        var service = mock(ApprovalAttachmentService.class);
        var failure = new IllegalArgumentException("unexpected internal failure");
        when(service.upload(any())).thenThrow(failure);
        var exception = assertThrows(ServletException.class, () -> mvc(service).perform(
            multipart("/api/approval/attachments")
                .file(new MockMultipartFile("file", "valid.txt", "text/plain", new byte[] { 1 }))
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                .header("X-Request-Id", "validation-test").header("Idempotency-Key", "validation-test")
        ));
        assertSame(failure, exception.getCause());
    }

    private void performInvalidUpload(ApprovalAttachmentService service, byte[] content) throws Exception {
        mvc(service).perform(multipart("/api/approval/attachments")
                .file(new MockMultipartFile("file", "fixture.txt", "text/plain", content))
                .header("X-Tenant-Id", "tenant-a").header("X-Operator-Id", "operator")
                .header("X-Request-Id", "validation-test").header("Idempotency-Key", "validation-test"))
            .andExpect(status().isBadRequest());
    }

    private MockMvc mvc(ApprovalAttachmentService service) {
        return MockMvcBuilders.standaloneSetup(new ApprovalAttachmentController(service)).build();
    }
}
