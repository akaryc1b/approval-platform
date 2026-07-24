# Aggregate checkpoint and reconciliation escalation mock

This example remains deterministic and transport-free.

A host supplies already-validated aggregate snapshots and an exact list of snapshot digests confirmed by a fake export. `ScriptedAggregateCheckpointStore` records a chained checkpoint only when the complete snapshot set was exported, the caller ordinal is monotonic and no snapshot is reused.

A host also supplies an existing handoff reconciliation proof and an explicit evaluation ordinal. `ScriptedReconciliationEscalationStore` records `none`, `observe`, `investigate`, `block` or `resolved` without reading a clock. Only an acknowledged-confirmed reconciliation can atomically create a finalization checkpoint.

Capacity, scripted failure, partial export, checkpoint discontinuity, ordinal regression, escalation regression and finalization conflict leave the fake stores unchanged. No endpoint, token, broker, logger, telemetry backend or database is present.
