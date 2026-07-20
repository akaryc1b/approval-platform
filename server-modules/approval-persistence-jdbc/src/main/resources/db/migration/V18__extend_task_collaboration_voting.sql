alter table ap_task_collaboration_policy
    drop constraint if exists chk_task_collaboration_mode;

alter table ap_task_collaboration_policy
    add column approval_threshold integer,
    add column approval_weight_threshold integer;

alter table ap_task_collaboration_participant
    add column participant_weight integer not null default 1;

alter table ap_task_collaboration_policy
    add constraint chk_task_collaboration_mode
        check (collaboration_mode in ('ALL', 'ANY', 'VOTE', 'WEIGHTED')),
    add constraint chk_task_collaboration_threshold_configuration
        check (
            (
                collaboration_mode in ('ALL', 'ANY')
                and approval_threshold is null
                and approval_weight_threshold is null
            )
            or (
                collaboration_mode = 'VOTE'
                and approval_threshold is not null
                and approval_threshold > 0
                and approval_weight_threshold is null
            )
            or (
                collaboration_mode = 'WEIGHTED'
                and approval_threshold is null
                and approval_weight_threshold is not null
                and approval_weight_threshold > 0
            )
        );

alter table ap_task_collaboration_participant
    add constraint chk_task_collaboration_participant_weight
        check (participant_weight > 0);

create index idx_task_collaboration_policy_mode_status
    on ap_task_collaboration_policy (tenant_id, collaboration_mode, status, created_at desc);

create index idx_task_collaboration_participant_policy_status
    on ap_task_collaboration_participant (tenant_id, policy_id, status, added_at);
