-- M5-D6: preserve the immutable engine-request lineage when UNKNOWN reconciliation closes.
-- This does not authorize migration redispatch, rollback, force success or public execution.

alter table ap_process_migration_attempt
 drop constraint ck_process_migration_attempt_request_v37;

alter table ap_process_migration_attempt
 add constraint ck_process_migration_attempt_request_v46 check (
  (status in ('ENGINE_REQUESTED','VERIFYING','UNKNOWN','RECONCILING','SUCCEEDED')
   or (status in ('BLOCKED_STALE','FAILED_TERMINAL') and engine_outcome='UNKNOWN'))
   = (engine_request_reference is not null)
 );
