create table ap_delegation_rule (
    tenant_id varchar(128) not null,
    rule_id uuid not null,
    principal_id varchar(256) not null,
    delegate_id varchar(256) not null,
    scope varchar(32) not null,
    definition_key varchar(256),
    valid_from timestamptz not null,
    valid_until timestamptz not null,
    status varchar(32) not null,
    reason varchar(2000) not null,
    created_by varchar(256) not null,
    created_at timestamptz not null,
    revoked_by varchar(256),
    revoked_at timestamptz,
    revoke_reason varchar(2000),
    version bigint not null,
    primary key (tenant_id, rule_id),
    constraint chk_delegation_principal_delegate
        check (principal_id <> delegate_id),
    constraint chk_delegation_scope
        check (
            (scope = 'ALL' and definition_key is null)
            or (scope = 'DEFINITION' and definition_key is not null)
        ),
    constraint chk_delegation_window
        check (valid_from < valid_until),
    constraint chk_delegation_status
        check (status in ('ACTIVE', 'REVOKED')),
    constraint chk_delegation_revocation
        check (
            (
                status = 'ACTIVE'
                and revoked_by is null
                and revoked_at is null
                and revoke_reason is null
            )
            or (
                status = 'REVOKED'
                and revoked_by is not null
                and revoked_at is not null
                and revoke_reason is not null
            )
        ),
    constraint chk_delegation_version
        check (version > 0)
);

create index idx_delegation_principal_status_window
    on ap_delegation_rule (
        tenant_id,
        principal_id,
        status,
        valid_from,
        valid_until
    );

create index idx_delegation_delegate_status_window
    on ap_delegation_rule (
        tenant_id,
        delegate_id,
        status,
        valid_from,
        valid_until
    );

create index idx_delegation_definition_resolution
    on ap_delegation_rule (
        tenant_id,
        principal_id,
        definition_key,
        status,
        valid_from,
        valid_until
    );
