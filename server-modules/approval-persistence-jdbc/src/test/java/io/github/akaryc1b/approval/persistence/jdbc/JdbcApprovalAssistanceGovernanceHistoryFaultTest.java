package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalAssistanceGovernanceHistoryQuery
    .HistoryQueryException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalAssistanceGovernanceHistoryFaultTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");

    @Test
    void connectionFailureIsWrappedAsStableHistoryQueryException() {
        CannotCreateTransactionException injected = new CannotCreateTransactionException(
            "p7 injected connection failure"
        );

        HistoryQueryException failure = assertThrows(
            HistoryQueryException.class,
            () -> query(failingTransactions(injected)).summarize(window())
        );

        assertInstanceOf(CannotCreateTransactionException.class, failure.getCause());
    }

    @Test
    void transactionTimeoutIsWrappedAsStableHistoryQueryException() {
        TransactionTimedOutException injected = new TransactionTimedOutException(
            "p7 injected transaction timeout"
        );

        HistoryQueryException failure = assertThrows(
            HistoryQueryException.class,
            () -> query(failingTransactions(injected)).summarize(window())
        );

        assertInstanceOf(TransactionTimedOutException.class, failure.getCause());
    }

    private static JdbcApprovalAssistanceGovernanceHistoryQuery query(
        PlatformTransactionManager transactions
    ) {
        DriverManagerDataSource unused = new DriverManagerDataSource();
        unused.setUrl("jdbc:postgresql://127.0.0.1:1/p7-unused");
        unused.setUsername("unused");
        unused.setPassword("unused");
        return new JdbcApprovalAssistanceGovernanceHistoryQuery(unused, transactions);
    }

    private static PlatformTransactionManager failingTransactions(
        TransactionException injected
    ) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw injected;
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new AssertionError("commit must not run after begin failure");
            }

            @Override
            public void rollback(TransactionStatus status) {
                throw new AssertionError("rollback must not run after begin failure");
            }
        };
    }

    private static HistoryWindow window() {
        return new HistoryWindow(
            "tenant-p7-history-fault",
            NOW.minusSeconds(3_600),
            NOW,
            NOW
        );
    }
}
