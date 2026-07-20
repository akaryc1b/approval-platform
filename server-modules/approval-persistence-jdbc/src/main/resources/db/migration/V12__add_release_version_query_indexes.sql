create index ap_approval_definition_history_idx
    on ap_approval_definition (
        tenant_id,
        definition_key,
        definition_version desc,
        published_at desc
    );

create index ap_approval_release_package_history_idx
    on ap_approval_release_package (
        tenant_id,
        definition_key,
        release_version desc,
        published_at desc
    );

create index ap_approval_release_deployment_definition_idx
    on ap_approval_release_deployment (
        tenant_id,
        definition_key,
        release_version desc
    );
