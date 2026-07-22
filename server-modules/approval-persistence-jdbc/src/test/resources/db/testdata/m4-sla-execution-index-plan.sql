insert into ap_sla_policy (
    policy_id, tenant_id, policy_key, display_name, status, active_version,
    created_by, created_at, updated_at, version
) values (
    md5('execution-policy')::uuid, 'tenant-execution-index-plan',
    'execution-policy', 'Execution policy', 'DRAFT', null, 'planner-seed',
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-01 00:00:00+00', 1
);
insert into ap_sla_policy_version (
    policy_id, tenant_id, policy_version, definition_key, release_version,
    task_definition_key, target_type, duration_mode, duration_millis,
    calendar_id, calendar_version, calendar_content_hash, time_zone,
    first_reminder_offset_millis, repeat_reminder_interval_millis,
    maximum_reminder_count, overdue_offset_millis, escalation_strategy,
    escalation_target, automatic_action_policy, pause_rules_json, content_hash,
    status, immutable, published_by, published_at, created_at, updated_at
) values (
    md5('execution-policy')::uuid, 'tenant-execution-index-plan', 1,
    'purchasePayment', 1, 'managerApproval', 'TASK', 'NATURAL_TIME', 7200000,
    null, null, null, 'UTC', 1800000, null, 1, 900000, null, null, 'NONE',
    '{}'::jsonb, repeat('c', 64), 'ACTIVE', true, 'planner-publisher',
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-01 00:00:00+00'
);
update ap_sla_policy
set status='ACTIVE', active_version=1, version=2
where tenant_id='tenant-execution-index-plan' and policy_key='execution-policy';
insert into ap_definition_version (
    tenant_id, definition_key, definition_version, form_key, form_version,
    compiler_version, content_hash, deployment_id, engine_definition_id,
    engine_version, published_by, published_at
) values (
    'tenant-execution-index-plan', 'purchasePayment', 1, 'purchasePayment', 1,
    'compiler-plan', repeat('a', 64), 'deployment-execution-plan',
    'engine-execution-plan', 1, 'planner-publisher',
    timestamptz '2026-01-01 00:00:00+00'
);
insert into ap_approval_instance (
    instance_id, tenant_id, business_key, engine_instance_id,
    definition_key, definition_version, form_key, form_version,
    compiler_version, content_hash, initiator_id, amount, supplier,
    purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
    request_hash, status, version, created_at, updated_at
)
select md5('approval-execution-' || g)::uuid, 'tenant-execution-index-plan',
    'EXECUTION-' || g, 'engine-execution-' || g, 'purchasePayment', 1,
    'purchasePayment', 1, 'compiler-plan', repeat('a', 64), 'initiator', g,
    'supplier', 'PO-' || g, '[]'::jsonb, '{}'::jsonb,
    repeat(md5('approval-execution-request-' || g), 2), 'RUNNING', 1,
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second'
from generate_series(1, 40000) g;
insert into ap_approval_task (
    task_id, instance_id, tenant_id, engine_task_id, task_definition_key,
    task_name, assignee_id, status, version, created_at, updated_at, completed_at
)
select md5('task-execution-' || g)::uuid,
    md5('approval-execution-' || g)::uuid, 'tenant-execution-index-plan',
    'engine-task-execution-' || g, 'managerApproval', 'Manager approval',
    'owner-' || (g % 200), 'PENDING', 1,
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second', null
from generate_series(1, 40000) g;
insert into ap_sla_instance (
    sla_instance_id, tenant_id, approval_instance_id, task_id,
    collaboration_participant_id, definition_key, task_definition_key,
    target_type, policy_id, policy_version, calendar_id, calendar_version,
    time_zone, responsible_user_id, original_responsible_user_id,
    started_at, due_at, next_reminder_at, overdue_at, paused_at, pause_reason,
    accumulated_paused_millis, terminal_at, terminal_reason, status,
    last_action_sequence, request_id, trace_id, version, created_at, updated_at
)
select md5('sla-execution-' || g)::uuid, 'tenant-execution-index-plan',
    md5('approval-execution-' || g)::uuid, md5('task-execution-' || g)::uuid,
    null, 'purchasePayment', 'managerApproval', 'TASK',
    md5('execution-policy')::uuid, 1, null, null, 'UTC',
    'owner-' || (g % 200), 'owner-' || (g % 200),
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-02 00:00:00+00',
    timestamptz '2026-01-01 12:00:00+00',
    timestamptz '2026-01-02 00:15:00+00', null, null, 0, null, null,
    'ACTIVE', 0, 'sla-request-' || g, 'sla-trace-' || g, 1,
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second'
from generate_series(1, 40000) g;
insert into ap_sla_execution_intent (
    intent_id, tenant_id, sla_instance_id, approval_instance_id, task_id,
    collaboration_participant_id, policy_id, policy_version, calendar_id,
    calendar_version, source_intent_id, action_type, action_sequence,
    scheduled_at, available_at, status, lease_owner, lease_until, attempt_count,
    max_attempts, next_attempt_at, idempotency_key, payload_json,
    responsible_user_id, request_id, trace_id, version, created_at, updated_at,
    completed_at, dead_at, cancelled_at, last_error_code, last_error_summary
)
select md5('execution-intent-' || g)::uuid, 'tenant-execution-index-plan',
    md5('sla-execution-' || g)::uuid, md5('approval-execution-' || g)::uuid,
    md5('task-execution-' || g)::uuid, null, md5('execution-policy')::uuid, 1,
    null, null, null, 'REMINDER', 1,
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    case when g<=10000 then 'READY' when g<=20000 then 'RETRY_WAIT'
         when g<=30000 then 'CLAIMED' else 'DEAD' end,
    case when g between 20001 and 30000 then 'worker-seed' else null end,
    case when g between 20001 and 30000
         then timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second'
         else null end,
    case when g between 10001 and 20000 then 1
         when g between 30001 and 40000 then 3 else 0 end,
    3, timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    'execution-idempotency-' || g, '{}'::jsonb, 'owner-' || (g % 200),
    'execution-request-' || g, 'execution-trace-' || g, 1,
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
    timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second', null,
    case when g between 30001 and 40000
         then timestamptz '2026-01-02 00:00:00+00' else null end,
    null,
    case when g between 30001 and 40000 then 'PERMANENT_FAILURE' else null end,
    case when g between 30001 and 40000 then 'seeded dead evidence' else null end
from generate_series(1, 40000) g;
