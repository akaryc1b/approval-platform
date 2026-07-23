create table ap_work_calendar (
    calendar_id uuid not null,
    tenant_id varchar(128) not null,
    calendar_key varchar(100) not null,
    display_name varchar(200) not null,
    time_zone varchar(100) not null,
    status varchar(32) not null,
    active_version integer,
    created_by varchar(200) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null,
    primary key (tenant_id, calendar_id),
    constraint uk_work_calendar_key unique (tenant_id, calendar_key),
    constraint chk_work_calendar_key
        check (calendar_key ~ '^[A-Za-z][A-Za-z0-9._:-]{1,99}$'),
    constraint chk_work_calendar_status
        check (status in ('DRAFT', 'PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    constraint chk_work_calendar_active_version
        check (active_version is null or active_version > 0),
    constraint chk_work_calendar_version check (version > 0),
    constraint chk_work_calendar_timestamps check (updated_at >= created_at)
);

create unique index uk_work_calendar_active_key
    on ap_work_calendar (tenant_id, calendar_key)
    where status = 'ACTIVE';

create index idx_work_calendar_active_lookup
    on ap_work_calendar (tenant_id, calendar_key, active_version)
    where status = 'ACTIVE';

create index idx_work_calendar_list
    on ap_work_calendar (tenant_id, updated_at desc, calendar_id);

create table ap_work_calendar_version (
    calendar_id uuid not null,
    tenant_id varchar(128) not null,
    calendar_version integer not null,
    time_zone varchar(100) not null,
    effective_from timestamptz,
    effective_to timestamptz,
    content_hash char(64) not null,
    status varchar(32) not null,
    immutable boolean not null,
    published_by varchar(200),
    published_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (tenant_id, calendar_id, calendar_version),
    constraint fk_work_calendar_version_identity
        foreign key (tenant_id, calendar_id)
        references ap_work_calendar (tenant_id, calendar_id),
    constraint chk_work_calendar_version_number check (calendar_version > 0),
    constraint chk_work_calendar_version_hash check (content_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_work_calendar_version_status
        check (status in ('DRAFT', 'PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    constraint chk_work_calendar_version_effective_range
        check (effective_from is null or effective_to is null or effective_from < effective_to),
    constraint chk_work_calendar_version_publication
        check (
            (not immutable and published_by is null and published_at is null and status = 'DRAFT')
            or
            (immutable and published_by is not null and published_at is not null
                and status in ('PUBLISHED', 'ACTIVE', 'INACTIVE', 'ARCHIVED'))
        ),
    constraint chk_work_calendar_version_timestamps check (updated_at >= created_at)
);

alter table ap_work_calendar
    add constraint fk_work_calendar_active_version
    foreign key (tenant_id, calendar_id, active_version)
    references ap_work_calendar_version (tenant_id, calendar_id, calendar_version);

create index idx_work_calendar_version_status
    on ap_work_calendar_version (tenant_id, calendar_id, status, calendar_version desc);

create table ap_work_calendar_date_override (
    tenant_id varchar(128) not null,
    calendar_id uuid not null,
    calendar_version integer not null,
    calendar_date date not null,
    override_type varchar(32) not null,
    primary key (tenant_id, calendar_id, calendar_version, calendar_date),
    constraint fk_work_calendar_override_version
        foreign key (tenant_id, calendar_id, calendar_version)
        references ap_work_calendar_version (tenant_id, calendar_id, calendar_version)
        on delete cascade,
    constraint chk_work_calendar_override_type
        check (override_type in ('HOLIDAY', 'NON_WORKING', 'COMPENSATORY_WORKING', 'WORKING'))
);

create index idx_work_calendar_override_date
    on ap_work_calendar_date_override (tenant_id, calendar_id, calendar_version, calendar_date);

create table ap_work_calendar_interval (
    interval_id uuid primary key,
    tenant_id varchar(128) not null,
    calendar_id uuid not null,
    calendar_version integer not null,
    scope_type varchar(32) not null,
    day_of_week smallint,
    calendar_date date,
    sequence_no smallint not null,
    start_time time without time zone not null,
    end_time time without time zone not null,
    crosses_midnight boolean generated always as (start_time > end_time) stored,
    constraint fk_work_calendar_interval_version
        foreign key (tenant_id, calendar_id, calendar_version)
        references ap_work_calendar_version (tenant_id, calendar_id, calendar_version)
        on delete cascade,
    constraint chk_work_calendar_interval_scope
        check (
            (scope_type = 'WEEKLY' and day_of_week between 1 and 7 and calendar_date is null)
            or
            (scope_type = 'DATE_OVERRIDE' and day_of_week is null and calendar_date is not null)
        ),
    constraint chk_work_calendar_interval_sequence check (sequence_no between 1 and 16),
    constraint chk_work_calendar_interval_non_zero check (start_time <> end_time)
);

create unique index uk_work_calendar_weekly_interval
    on ap_work_calendar_interval (
        tenant_id, calendar_id, calendar_version, day_of_week, sequence_no
    )
    where scope_type = 'WEEKLY';

create unique index uk_work_calendar_date_interval
    on ap_work_calendar_interval (
        tenant_id, calendar_id, calendar_version, calendar_date, sequence_no
    )
    where scope_type = 'DATE_OVERRIDE';

create index idx_work_calendar_interval_version
    on ap_work_calendar_interval (tenant_id, calendar_id, calendar_version, scope_type);

create or replace function ap_calendar_interval_start_minutes(
    p_scope_type varchar,
    p_day_of_week smallint,
    p_calendar_date date,
    p_start_time time
) returns bigint
language sql
immutable
as $$
    select case
        when p_scope_type = 'WEEKLY' then
            ((p_day_of_week::bigint - 1) * 1440)
            + (extract(hour from p_start_time)::bigint * 60)
            + extract(minute from p_start_time)::bigint
        else
            ((p_calendar_date - date '1970-01-01')::bigint * 1440)
            + (extract(hour from p_start_time)::bigint * 60)
            + extract(minute from p_start_time)::bigint
    end
$$;

create or replace function ap_calendar_interval_end_minutes(
    p_scope_type varchar,
    p_day_of_week smallint,
    p_calendar_date date,
    p_start_time time,
    p_end_time time
) returns bigint
language sql
immutable
as $$
    select ap_calendar_interval_start_minutes(
        p_scope_type,
        p_day_of_week,
        p_calendar_date,
        p_start_time
    )
    + case
        when p_end_time > p_start_time then
            (extract(hour from p_end_time)::bigint * 60)
            + extract(minute from p_end_time)::bigint
            - (extract(hour from p_start_time)::bigint * 60)
            - extract(minute from p_start_time)::bigint
        else
            1440
            + (extract(hour from p_end_time)::bigint * 60)
            + extract(minute from p_end_time)::bigint
            - (extract(hour from p_start_time)::bigint * 60)
            - extract(minute from p_start_time)::bigint
      end
$$;

create or replace function ap_reject_overlapping_calendar_interval()
returns trigger
language plpgsql
as $$
declare
    v_start bigint;
    v_end bigint;
begin
    v_start := ap_calendar_interval_start_minutes(
        new.scope_type, new.day_of_week, new.calendar_date, new.start_time
    );
    v_end := ap_calendar_interval_end_minutes(
        new.scope_type, new.day_of_week, new.calendar_date, new.start_time, new.end_time
    );

    if exists (
        select 1
        from ap_work_calendar_interval existing
        where existing.tenant_id = new.tenant_id
          and existing.calendar_id = new.calendar_id
          and existing.calendar_version = new.calendar_version
          and existing.scope_type = new.scope_type
          and not (tg_op = 'UPDATE' and existing.interval_id = old.interval_id)
          and (
              case
                  when new.scope_type = 'WEEKLY' then
                      (
                          v_start < ap_calendar_interval_end_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time,
                              existing.end_time
                          )
                          and
                          ap_calendar_interval_start_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time
                          ) < v_end
                      )
                      or (
                          v_start < ap_calendar_interval_end_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time,
                              existing.end_time
                          ) + 10080
                          and
                          ap_calendar_interval_start_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time
                          ) + 10080 < v_end
                      )
                      or (
                          v_start < ap_calendar_interval_end_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time,
                              existing.end_time
                          ) - 10080
                          and
                          ap_calendar_interval_start_minutes(
                              existing.scope_type,
                              existing.day_of_week,
                              existing.calendar_date,
                              existing.start_time
                          ) - 10080 < v_end
                      )
                  else
                      v_start < ap_calendar_interval_end_minutes(
                          existing.scope_type,
                          existing.day_of_week,
                          existing.calendar_date,
                          existing.start_time,
                          existing.end_time
                      )
                      and
                      ap_calendar_interval_start_minutes(
                          existing.scope_type,
                          existing.day_of_week,
                          existing.calendar_date,
                          existing.start_time
                      ) < v_end
              end
          )
    ) then
        raise exception using
            errcode = '23514',
            message = 'APPROVAL_CALENDAR_INTERVAL_INVALID: calendar intervals overlap';
    end if;

    return new;
end;
$$;

create trigger trg_reject_overlapping_calendar_interval
before insert or update on ap_work_calendar_interval
for each row execute function ap_reject_overlapping_calendar_interval();

create or replace function ap_enforce_calendar_collection_limits()
returns trigger
language plpgsql
as $$
declare
    v_count integer;
begin
    if tg_table_name = 'ap_work_calendar_date_override' then
        select count(*) into v_count
        from ap_work_calendar_date_override
        where tenant_id = new.tenant_id
          and calendar_id = new.calendar_id
          and calendar_version = new.calendar_version;
        if tg_op = 'INSERT' then
            v_count := v_count + 1;
        end if;
        if v_count > 20000 then
            raise exception using
                errcode = '23514',
                message = 'APPROVAL_CALENDAR_OVERRIDE_CONFLICT: override count exceeds 20000';
        end if;
    else
        select count(*) into v_count
        from ap_work_calendar_interval
        where tenant_id = new.tenant_id
          and calendar_id = new.calendar_id
          and calendar_version = new.calendar_version
          and scope_type = new.scope_type
          and day_of_week is not distinct from new.day_of_week
          and calendar_date is not distinct from new.calendar_date;
        if tg_op = 'INSERT' then
            v_count := v_count + 1;
        end if;
        if v_count > 16 then
            raise exception using
                errcode = '23514',
                message = 'APPROVAL_CALENDAR_INTERVAL_INVALID: daily interval count exceeds 16';
        end if;
    end if;
    return new;
end;
$$;

create trigger trg_limit_calendar_overrides
before insert or update on ap_work_calendar_date_override
for each row execute function ap_enforce_calendar_collection_limits();

create trigger trg_limit_calendar_intervals
before insert or update on ap_work_calendar_interval
for each row execute function ap_enforce_calendar_collection_limits();

create or replace function ap_validate_calendar_override_intervals()
returns trigger
language plpgsql
as $$
declare
    v_type varchar(32);
begin
    if new.scope_type <> 'DATE_OVERRIDE' then
        return new;
    end if;
    select override_type into v_type
    from ap_work_calendar_date_override
    where tenant_id = new.tenant_id
      and calendar_id = new.calendar_id
      and calendar_version = new.calendar_version
      and calendar_date = new.calendar_date;
    if v_type is null then
        raise exception using
            errcode = '23514',
            message = 'APPROVAL_CALENDAR_OVERRIDE_CONFLICT: date interval requires an override';
    end if;
    if v_type in ('HOLIDAY', 'NON_WORKING') then
        raise exception using
            errcode = '23514',
            message = 'APPROVAL_CALENDAR_OVERRIDE_CONFLICT: non-working override cannot contain intervals';
    end if;
    return new;
end;
$$;

create trigger trg_validate_calendar_override_intervals
before insert or update on ap_work_calendar_interval
for each row execute function ap_validate_calendar_override_intervals();

create or replace function ap_reject_non_working_override_with_intervals()
returns trigger
language plpgsql
as $$
begin
    if new.override_type in ('HOLIDAY', 'NON_WORKING') and exists (
        select 1 from ap_work_calendar_interval
        where tenant_id = new.tenant_id
          and calendar_id = new.calendar_id
          and calendar_version = new.calendar_version
          and scope_type = 'DATE_OVERRIDE'
          and calendar_date = new.calendar_date
    ) then
        raise exception using
            errcode = '23514',
            message = 'APPROVAL_CALENDAR_OVERRIDE_CONFLICT: non-working override cannot contain intervals';
    end if;
    return new;
end;
$$;

create trigger trg_reject_non_working_override_with_intervals
before update on ap_work_calendar_date_override
for each row execute function ap_reject_non_working_override_with_intervals();

create or replace function ap_reject_immutable_calendar_version_change()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' and old.immutable then
        raise exception using
            errcode = '55000',
            message = 'APPROVAL_CALENDAR_ALREADY_PUBLISHED: published calendar version is immutable';
    end if;
    if tg_op = 'UPDATE' and old.immutable and (
        new.tenant_id is distinct from old.tenant_id
        or new.calendar_id is distinct from old.calendar_id
        or new.calendar_version is distinct from old.calendar_version
        or new.time_zone is distinct from old.time_zone
        or new.effective_from is distinct from old.effective_from
        or new.effective_to is distinct from old.effective_to
        or new.content_hash is distinct from old.content_hash
        or new.immutable is distinct from old.immutable
        or new.published_by is distinct from old.published_by
        or new.published_at is distinct from old.published_at
        or new.created_at is distinct from old.created_at
    ) then
        raise exception using
            errcode = '55000',
            message = 'APPROVAL_CALENDAR_ALREADY_PUBLISHED: published calendar version content is immutable';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_reject_immutable_calendar_version_change
before update or delete on ap_work_calendar_version
for each row execute function ap_reject_immutable_calendar_version_change();

create or replace function ap_reject_immutable_calendar_child_change()
returns trigger
language plpgsql
as $$
declare
    v_tenant_id varchar(128);
    v_calendar_id uuid;
    v_calendar_version integer;
    v_immutable boolean;
begin
    if tg_op = 'DELETE' then
        v_tenant_id := old.tenant_id;
        v_calendar_id := old.calendar_id;
        v_calendar_version := old.calendar_version;
    else
        v_tenant_id := new.tenant_id;
        v_calendar_id := new.calendar_id;
        v_calendar_version := new.calendar_version;
    end if;

    select immutable into v_immutable
    from ap_work_calendar_version
    where tenant_id = v_tenant_id
      and calendar_id = v_calendar_id
      and calendar_version = v_calendar_version;

    if coalesce(v_immutable, false) then
        raise exception using
            errcode = '55000',
            message = 'APPROVAL_CALENDAR_ALREADY_PUBLISHED: published calendar content is immutable';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_reject_immutable_calendar_override_change
before insert or update or delete on ap_work_calendar_date_override
for each row execute function ap_reject_immutable_calendar_child_change();

create trigger trg_reject_immutable_calendar_interval_change
before insert or update or delete on ap_work_calendar_interval
for each row execute function ap_reject_immutable_calendar_child_change();
