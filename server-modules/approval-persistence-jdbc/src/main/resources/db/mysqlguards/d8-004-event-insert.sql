create trigger trg_process_migration_plan_aggregate_event_insert_guard_v48
 before insert on ap_process_migration_plan_aggregate_event
 for each row
begin
 if not (
   json_unquote(json_extract(new.payload_json,'$.eventId')) <=> new.event_id
   and json_unquote(json_extract(new.payload_json,'$.tenantId')) <=> new.tenant_id
   and json_unquote(json_extract(
     new.payload_json,'$.aggregateId'
   )) <=> new.aggregate_id
   and json_unquote(json_extract(new.payload_json,'$.planId')) <=> new.plan_id
   and json_unquote(json_extract(new.payload_json,'$.intentId')) <=> new.intent_id
   and cast(json_unquote(json_extract(
     new.payload_json,'$.aggregateRevision'
   )) as signed) <=> new.aggregate_revision
   and json_unquote(json_extract(new.payload_json,'$.status')) <=> new.status
   and json_unquote(json_extract(
     new.payload_json,'$.terminalOutcome'
   )) <=> new.terminal_outcome
   and json_unquote(json_extract(
     new.payload_json,'$.pauseReason'
   )) <=> new.pause_reason
   and json_unquote(json_extract(
     new.payload_json,'$.predecessorHash'
   )) <=> new.predecessor_hash
   and json_unquote(json_extract(
     new.payload_json,'$.aggregateHash'
   )) <=> new.aggregate_hash
   and json_unquote(json_extract(
     new.payload_json,'$.eventHash'
   )) <=> new.event_hash
   and (
     case json_type(json_extract(new.payload_json,'$.happenedAt'))
       when 'STRING' then cast(replace(replace(replace(
         json_unquote(json_extract(new.payload_json,'$.happenedAt')),
         'T',' '
       ),'Z',''),'+00:00','') as datetime(6))
       when 'INTEGER' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.happenedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DOUBLE' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.happenedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       when 'DECIMAL' then timestampadd(
         microsecond,
         cast(cast(json_unquote(json_extract(
           new.payload_json,'$.happenedAt'
         )) as decimal(30,9))*1000000 as signed),
         cast('1970-01-01 00:00:00' as datetime(6))
       )
       else null
     end
   ) <=> new.happened_at
   and json_unquote(json_extract(
     new.payload_json,'$.requestId'
   )) <=> new.request_id
   and (
     case json_type(json_extract(new.payload_json,'$.traceId'))
       when 'NULL' then null
       else json_unquote(json_extract(new.payload_json,'$.traceId'))
     end
   ) <=> new.trace_id
   and json_unquote(json_extract(
     new.payload_json,'$.auditReference'
   )) <=> new.audit_reference
 ) then
  signal sqlstate '45000'
   set message_text='D8 aggregate event payload mismatch';
 end if;

 if not exists (
   select 1
   from ap_process_migration_plan_aggregate aggregate_value
   where aggregate_value.tenant_id=new.tenant_id
     and aggregate_value.aggregate_id=new.aggregate_id
     and aggregate_value.plan_id=new.plan_id
     and aggregate_value.intent_id=new.intent_id
     and aggregate_value.aggregate_revision=new.aggregate_revision
     and aggregate_value.status=new.status
     and aggregate_value.terminal_outcome=new.terminal_outcome
     and aggregate_value.pause_reason=new.pause_reason
     and aggregate_value.predecessor_hash=new.predecessor_hash
     and aggregate_value.aggregate_hash=new.aggregate_hash
     and aggregate_value.request_id=new.request_id
     and aggregate_value.audit_reference=new.audit_reference
 ) then
  signal sqlstate '45000'
   set message_text='D8 aggregate event lineage mismatch';
 end if;
end
