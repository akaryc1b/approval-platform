package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MySQL 8.4 comment lifecycle, immutable revisions and server-side audience filtering. */
public final class JdbcMySqlApprovalCommentStore implements ApprovalCommentStore {

    private final JdbcMySqlApprovalCommentReadSupport reads;
    private final JdbcMySqlApprovalCommentWriteSupport writes;

    public JdbcMySqlApprovalCommentStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        JdbcMySqlApprovalCommentStoreContext context =
            new JdbcMySqlApprovalCommentStoreContext(
                dataSource,
                objectMapper,
                transactionManager
            );
        this.reads = new JdbcMySqlApprovalCommentReadSupport(context);
        this.writes = new JdbcMySqlApprovalCommentWriteSupport(context, reads);
    }

    @Override
    public StoredCommentItem create(
        ApprovalComment comment,
        CommentRevision revision
    ) {
        return writes.create(comment, revision);
    }

    @Override
    public Optional<StoredCommentItem> findComment(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return reads.findComment(tenantId, instanceId, commentId);
    }

    @Override
    public StoredCommentPage findComments(CommentCriteria criteria) {
        return reads.findComments(criteria);
    }

    @Override
    public List<StoredCommentRevision> findRevisions(
        String tenantId,
        UUID instanceId,
        UUID commentId
    ) {
        return reads.findRevisions(tenantId, instanceId, commentId);
    }

    @Override
    public StoredCommentItem update(
        CommentUpdate update,
        CommentRevision revision
    ) {
        return writes.update(update, revision);
    }

    @Override
    public StoredCommentItem delete(
        CommentDeletion deletion,
        CommentRevision revision
    ) {
        return writes.delete(deletion, revision);
    }

    @Override
    public List<CommentParticipantIdentity> findAdditionalParticipants(
        String tenantId,
        UUID instanceId
    ) {
        return reads.findAdditionalParticipants(tenantId, instanceId);
    }

    @Override
    public CommentAttachmentAccess findAttachmentAccess(
        String tenantId,
        UUID instanceId,
        UUID attachmentId,
        String viewerId
    ) {
        return reads.findAttachmentAccess(
            tenantId,
            instanceId,
            attachmentId,
            viewerId
        );
    }
}
