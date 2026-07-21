drop index if exists idx_approval_task_completed_assignee_page;

create index idx_approval_task_completed_assignee_page
    on ap_approval_task (
        tenant_id,
        assignee_id,
        completed_at desc,
        task_id desc
    )
    where status = 'COMPLETED';
