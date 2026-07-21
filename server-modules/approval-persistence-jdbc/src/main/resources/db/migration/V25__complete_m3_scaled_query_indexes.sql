create index idx_approval_task_pending_assignee_page
    on ap_approval_task (
        tenant_id,
        assignee_id,
        created_at,
        task_id
    )
    where status = 'PENDING';

create index idx_delegation_principal_history
    on ap_delegation_rule (
        tenant_id,
        principal_id,
        created_at desc,
        rule_id desc
    );

create index idx_handover_principal_history
    on ap_principal_handover (
        tenant_id,
        principal_id,
        created_at desc,
        handover_id desc
    );

create index idx_collaboration_participant_pending_page
    on ap_task_collaboration_participant (
        tenant_id,
        participant_user_id,
        added_at,
        participant_id
    )
    where status = 'PENDING';
