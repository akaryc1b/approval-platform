package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.ApprovalMessage;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.CopiedInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.MessageType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMessageStoreMySqlIntegrationTest
    extends MySqlApprovalProjectionStoreIntegrationSupport {

    private static final Instant MESSAGE_AT = Instant.parse(
        "2026-08-19T08:15:30.123456499Z"
    );
    private static final Instant READ_AT = Instant.parse(
        "2026-08-19T08:16:31.123456500Z"
    );

    private ApprovalMessageStore messages;

    @BeforeEach
    void setUpMessages() {
        seedInstanceWithTasks(
            instance(
                TENANT,
                INSTANCE_ID,
                "engine-instance-message",
                "business-message-alpha"
            ),
            List.of(task(
                TENANT,
                TASK_ID,
                INSTANCE_ID,
                "engine-task-message",
                "Manager-A",
                TaskStatus.PENDING,
                1,
                CREATED_AT
            ))
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        messages = JdbcApprovalMessageStoreFactory.create(
            dataSource,
            mapper,
            new JdbcTransactionManager(dataSource)
        );
    }

    @AfterEach
    void cleanMessages() {
        jdbc.update("delete from ap_approval_message");
    }

    @Test
    void roundTripsMessageCenterReadsDedupReceiptsAndCanonicalTime() {
        assertInstanceOf(JdbcMySqlApprovalMessageStore.class, messages);
        ApprovalMessage urge = message(
            TENANT,
            uuid(9201),
            "Manager-A",
            TASK_ID,
            MessageType.URGE,
            "审批催办",
            "请尽快处理付款审批",
            Map.of("kind", "催办", "precision", "123.4500"),
            "urge:message:1",
            MESSAGE_AT
        );
        ApprovalMessage copy = message(
            TENANT,
            uuid(9202),
            "Finance-Reviewer",
            null,
            MessageType.COPY,
            "审批抄送",
            "请关注采购付款审批",
            Map.of("source", "测试", "businessKey", "business-message-alpha"),
            "copy:message:1",
            MESSAGE_AT.plusSeconds(1)
        );
        ApprovalMessage mention = message(
            TENANT,
            uuid(9203),
            "Finance-A",
            TASK_ID,
            MessageType.MENTION,
            "评论提及",
            "请复核合同附件",
            Map.of("commentId", uuid(9299).toString()),
            "mention:message:1",
            MESSAGE_AT.plusSeconds(2)
        );

        assertEquals(3, messages.append(List.of(urge, copy, mention)));
        assertEquals(0, messages.append(List.of(urge, copy, mention)));
        assertEquals(1, messages.countUnread(new MessageIdentity(TENANT, "Manager-A")));
        assertEquals(1, messages.countUnread(new MessageIdentity(TENANT, "Finance-Reviewer")));

        var managerPage = messages.findMessages(new MessageCriteria(
            TENANT,
            "Manager-A",
            false,
            20,
            0
        ));
        assertEquals(1, managerPage.total());
        assertEquals("123.4500", managerPage.items().getFirst().metadata().get("precision"));
        assertEquals(canonicalInstant(MESSAGE_AT), managerPage.items().getFirst().createdAt());
        assertFalse(managerPage.items().getFirst().read());

        var unreadPage = messages.findMessages(new MessageCriteria(
            TENANT,
            "Manager-A",
            true,
            20,
            0
        ));
        assertEquals(1, unreadPage.total());
        assertEquals(0, messages.findMessages(new MessageCriteria(
            TENANT.toLowerCase(),
            "Manager-A",
            false,
            20,
            0
        )).total());

        var copied = messages.findCopiedInstances(new CopiedInstanceCriteria(
            TENANT,
            "Finance-Reviewer",
            "MESSAGE",
            20,
            0
        ));
        assertEquals(1, copied.total());
        assertEquals("managerApproval", copied.items().getFirst().currentTaskDefinitionKey());
        assertEquals(0, copied.items().getFirst().commentCount());
        assertEquals(0, messages.findCopiedInstances(new CopiedInstanceCriteria(
            TENANT,
            "Finance-Reviewer",
            "missing",
            20,
            0
        )).total());

        var firstRead = messages.markRead(
            TENANT,
            "Manager-A",
            urge.messageId(),
            READ_AT
        ).orElseThrow();
        var replayedRead = messages.markRead(
            TENANT,
            "Manager-A",
            urge.messageId(),
            READ_AT.plusSeconds(1)
        ).orElseThrow();
        assertTrue(firstRead.firstRead());
        assertFalse(replayedRead.firstRead());
        assertEquals(canonicalInstant(READ_AT), firstRead.readAt());
        assertEquals(firstRead.readAt(), replayedRead.readAt());
        assertEquals(0, messages.countUnread(new MessageIdentity(TENANT, "Manager-A")));
        assertTrue(messages.markRead(
            TENANT,
            "Other-Recipient",
            urge.messageId(),
            READ_AT
        ).isEmpty());

        assertEquals(1, messages.markAllRead(
            TENANT,
            "Finance-Reviewer",
            READ_AT.plusSeconds(2)
        ));
        assertEquals(0, messages.markAllRead(
            TENANT,
            "Finance-Reviewer",
            READ_AT.plusSeconds(3)
        ));
        assertTrue(messages.isRecipient(TENANT, "Finance-Reviewer", INSTANCE_ID));
        assertFalse(messages.isRecipient(OTHER_TENANT, "Finance-Reviewer", INSTANCE_ID));

        var receipts = messages.findReceipts(TENANT, INSTANCE_ID);
        assertEquals(3, receipts.size());
        assertEquals(2, receipts.stream().filter(receipt -> receipt.read()).count());
        assertEquals(List.of(
            urge.messageId(),
            copy.messageId(),
            mention.messageId()
        ), receipts.stream().map(receipt -> receipt.messageId()).toList());
    }

    @Test
    void classifiesOnlyExactTenantDedupConflictsAsReplay() {
        ApprovalMessage original = message(
            TENANT,
            uuid(9211),
            "Manager-A",
            TASK_ID,
            MessageType.URGE,
            "original",
            "original body",
            Map.of("revision", "1"),
            "strict-dedup-key",
            MESSAGE_AT
        );
        assertEquals(1, messages.append(List.of(original)));

        ApprovalMessage sameDedupDifferentPayload = message(
            TENANT,
            uuid(9212),
            "Manager-A",
            TASK_ID,
            MessageType.URGE,
            "changed",
            "changed body",
            Map.of("revision", "2"),
            original.dedupKey(),
            MESSAGE_AT.plusSeconds(1)
        );
        assertEquals(0, messages.append(List.of(sameDedupDifferentPayload)));

        ApprovalMessage messageIdCollisionWithoutDedup = message(
            TENANT,
            original.messageId(),
            "Manager-A",
            TASK_ID,
            MessageType.URGE,
            "collision",
            "collision body",
            Map.of(),
            "different-dedup-key",
            MESSAGE_AT.plusSeconds(2)
        );
        assertThrows(
            DuplicateKeyException.class,
            () -> messages.append(List.of(messageIdCollisionWithoutDedup))
        );
        assertEquals(1, messages.findReceipts(TENANT, INSTANCE_ID).size());
    }

    @Test
    void concurrentDedupAndReadAdmissionHaveSingleWinners() throws Exception {
        ApprovalMessage left = message(
            TENANT,
            uuid(9221),
            "Finance-A",
            TASK_ID,
            MessageType.MENTION,
            "left",
            "left body",
            Map.of("side", "left"),
            "concurrent-message-key",
            MESSAGE_AT
        );
        ApprovalMessage right = message(
            TENANT,
            uuid(9222),
            "Finance-A",
            TASK_ID,
            MessageType.MENTION,
            "right",
            "right body",
            Map.of("side", "right"),
            left.dedupKey(),
            MESSAGE_AT.plusNanos(1)
        );
        CountDownLatch appendStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> writes = List.of(
                executor.submit(() -> {
                    appendStart.await();
                    return messages.append(List.of(left));
                }),
                executor.submit(() -> {
                    appendStart.await();
                    return messages.append(List.of(right));
                })
            );
            appendStart.countDown();
            int inserted = 0;
            for (Future<Integer> write : writes) {
                inserted += write.get(20, TimeUnit.SECONDS);
            }
            assertEquals(1, inserted);
        }

        UUID storedMessageId = messages.findMessages(new MessageCriteria(
            TENANT,
            "Finance-A",
            false,
            20,
            0
        )).items().getFirst().messageId();
        Instant leftReadAt = READ_AT.plusSeconds(10);
        Instant rightReadAt = READ_AT.plusSeconds(11);
        CountDownLatch readStart = new CountDownLatch(1);
        List<ApprovalMessageStore.MessageReadResult> reads = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<ApprovalMessageStore.MessageReadResult>> results = List.of(
                executor.submit(() -> {
                    readStart.await();
                    return messages.markRead(
                        TENANT,
                        "Finance-A",
                        storedMessageId,
                        leftReadAt
                    ).orElseThrow();
                }),
                executor.submit(() -> {
                    readStart.await();
                    return messages.markRead(
                        TENANT,
                        "Finance-A",
                        storedMessageId,
                        rightReadAt
                    ).orElseThrow();
                })
            );
            readStart.countDown();
            for (Future<ApprovalMessageStore.MessageReadResult> result : results) {
                reads.add(result.get(20, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, reads.stream().filter(read -> read.firstRead()).count());
        assertEquals(1, reads.stream().map(read -> read.readAt()).distinct().count());
        Instant persisted = reads.getFirst().readAt();
        assertTrue(
            persisted.equals(canonicalInstant(leftReadAt))
                || persisted.equals(canonicalInstant(rightReadAt))
        );
    }

    @Test
    void surroundingTransactionRollbackRestoresAppendAndReadState() {
        ApprovalMessage value = message(
            TENANT,
            uuid(9231),
            "Manager-A",
            TASK_ID,
            MessageType.URGE,
            "rollback",
            "rollback body",
            Map.of(),
            "rollback-message-key",
            MESSAGE_AT
        );

        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            assertEquals(1, messages.append(List.of(value)));
            throw new RollbackMarker();
        }));
        assertEquals(0, messages.findReceipts(TENANT, INSTANCE_ID).size());

        assertEquals(1, messages.append(List.of(value)));
        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            assertTrue(messages.markRead(
                TENANT,
                "Manager-A",
                value.messageId(),
                READ_AT
            ).orElseThrow().firstRead());
            throw new RollbackMarker();
        }));
        var restored = messages.findMessages(new MessageCriteria(
            TENANT,
            "Manager-A",
            false,
            20,
            0
        )).items().getFirst();
        assertFalse(restored.read());
        assertEquals(1, messages.countUnread(new MessageIdentity(TENANT, "Manager-A")));
    }

    private static ApprovalMessage message(
        String tenantId,
        UUID messageId,
        String recipientId,
        UUID taskId,
        MessageType type,
        String title,
        String body,
        Map<String, String> metadata,
        String dedupKey,
        Instant createdAt
    ) {
        return new ApprovalMessage(
            messageId,
            tenantId,
            recipientId,
            "Initiator-A",
            INSTANCE_ID,
            taskId,
            type,
            title,
            body,
            metadata,
            dedupKey,
            createdAt
        );
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString(
            "00000000-0000-0000-0000-" + String.format("%012d", suffix)
        );
    }
}
