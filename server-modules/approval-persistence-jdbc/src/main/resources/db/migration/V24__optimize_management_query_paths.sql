create index idx_audit_event_tenant_action_time
    on ap_audit_event (
        tenant_id,
        action,
        occurred_at desc,
        tenant_sequence desc
    );

create index idx_notification_dead_management
    on ap_notification_intent (
        tenant_id,
        updated_at desc,
        intent_id
    )
    where status = 'DEAD_LETTER';

create index idx_notification_dead_connector
    on ap_notification_intent (
        tenant_id,
        (coalesce(metadata_json ->> 'connectorKey', 'approval-connector')),
        updated_at desc,
        intent_id
    )
    where status = 'DEAD_LETTER' and channel = 'CONNECTOR';

create index idx_consistency_failed_management
    on ap_consistency_check (
        tenant_id,
        completed_at desc,
        check_id
    )
    where status = 'FAILED';
