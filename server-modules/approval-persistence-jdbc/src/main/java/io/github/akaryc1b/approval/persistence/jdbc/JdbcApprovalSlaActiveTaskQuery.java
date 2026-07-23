package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActiveTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaMappings.params;

/** Tenant-scoped authoritative task lookup independent from any TASK policy anchor. */
public final class JdbcApprovalSlaActiveTaskQuery
    extends JdbcApprovalSlaStoreSupport
    implements ApprovalSlaActiveTaskQuery {

    public JdbcApprovalSlaActiveTaskQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        super(dataSource, transactionManager);
    }

    @Override
    public List<SlaInstance> findActiveByTask(String tenantId, UUID taskId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        return queryInstances(
            """
            select * from ap_sla_instance
            where tenant_id=:tenantId and task_id=:taskId
              and status in ('ACTIVE','PAUSED')
            order by created_at,sla_instance_id
            """,
            params("tenantId", tenantId, "taskId", taskId)
        );
    }
}
