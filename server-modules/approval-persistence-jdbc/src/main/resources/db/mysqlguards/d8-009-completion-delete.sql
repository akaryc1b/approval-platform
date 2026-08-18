create trigger trg_process_migration_plan_completion_delete_guard_v48
 before delete on ap_process_migration_plan_completion
 for each row
 signal sqlstate '45000'
  set message_text='M5-D8 evidence is append-only'
