create trigger trg_process_migration_plan_aggregate_update_guard_v48
 before update on ap_process_migration_plan_aggregate
 for each row
 signal sqlstate '45000'
  set message_text='M5-D8 evidence is append-only'
