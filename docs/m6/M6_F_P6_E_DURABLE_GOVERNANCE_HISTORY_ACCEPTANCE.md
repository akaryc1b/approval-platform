# M6-F P6-E — Durable Governance History Acceptance

## 1. Scope

P6-E adds one tenant-scoped read-only governance history over the durable M6-E P4 evidence already stored by Flyway migration `V49`.

The slice does not add a Provider, Prompt, model, command, traffic allocator, mutation API, worker, scheduler, retry loop or new persistence schema.

The permanent authority invariant remains:

`AI_IS_NOT_AN_OPERATOR`

The Action Whitelist remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P5-A remains skipped:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

## 2. Existing V49 evidence reuse

P6-E reads the existing immutable tables:

- `ap_ai_approval_assistance_evidence`;
- `ap_ai_approval_assistance_evidence_state`.

Those tables already contain tenant, use case, outcome classification, exact version evidence, Provider-attempt posture, advisory-result posture, requested/recorded/retention timestamps and ACTIVE/TOMBSTONED state.

P6-E does not modify the M6-E write path. Approval-assistance generation continues to write evidence through the existing `ApprovalAssistanceDurableEvidenceStore`.

No `V51` migration is introduced. The highest governed migration remains `V50`.

## 3. Query window

The query contract requires:

- one canonical tenant ID;
- a positive `[fromInclusive, toExclusive)` window;
- maximum window length of 31 days;
- `toExclusive` not later than the observation time;
- maximum historical lookback of 3,650 days.

The observation time is taken from the same P6-A governance snapshot that is bound into the returned history evidence.

## 4. Read consistency

`JdbcApprovalAssistanceGovernanceHistoryQuery` uses one read-only `REPEATABLE_READ` transaction for all aggregate queries.

This prevents summary, classification and use-case aggregates from observing different committed database snapshots.

The production query performs SELECT operations only. It does not insert, update, tombstone or delete evidence.

## 5. Durable aggregates

For the exact tenant and time window, the query returns:

- total durable evidence;
- ACTIVE evidence;
- TOMBSTONED evidence;
- Provider invocation count;
- Provider attempt count;
- advisory-result count;
- every closed outcome-classification count;
- every closed approval-assistance use-case count;
- per-use-case Provider invocations and advisory results;
- per-use-case distinct `version_evidence_hash` count;
- per-use-case version stability;
- ACTIVE evidence whose retention deadline is due at the observation time;
- earliest and latest recorded timestamps.

The contract enforces:

- ACTIVE plus TOMBSTONED equals total;
- Provider attempts equal Provider invocations because V49 permits at most one attempt;
- all outcome counts sum to total;
- all use-case counts sum to total;
- unsafe retry count is zero;
- post-invocation fallback count is zero;
- every non-empty use case has at least one version bundle;
- all timestamps remain inside the requested window.

Any incompatible or corrupted aggregate fails closed instead of returning a partial governance view.

## 6. Version stability

Each use case reports one of:

- `EMPTY`;
- `SINGLE_VERSION_BUNDLE`;
- `MULTIPLE_VERSION_BUNDLES`.

`MULTIPLE_VERSION_BUNDLES` produces the blocker:

`AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED`

This is historical-window stability only. Current Runtime-to-snapshot drift remains the responsibility of P6-C.

P6-E does not expose the historical version hashes or add a new public version-hash computation API.

## 7. Retention visibility

ACTIVE evidence with `retention_until <= observedAt` is counted as retention due and produces:

`AI_RETENTION_TOMBSTONE_DUE`

The history endpoint does not tombstone data and does not schedule automatic deletion.

Existing V49 tombstone authorization, reason, CAS, append-only event and deferred state/event consistency constraints remain unchanged.

## 8. Management endpoint

P6-E adds:

`GET /api/approval/management/ai-governance/history?from=<Instant>&to=<Instant>`

The endpoint requires:

- trusted canonical `X-Tenant-Id`;
- tenant-scoped management `READ` authority;
- canonical UTC `Instant` query parameters;
- `Cache-Control: no-store`.

The endpoint has no POST, PUT, PATCH or DELETE mapping.

## 9. Evidence and privacy

The returned view binds:

- the exact P6-A snapshot evidence hash;
- the exact observation and query window;
- durable totals and closed-enum aggregates;
- historical version stability;
- retention-due posture;
- the empty Action Whitelist and skipped P5-A decision.

The view does not expose:

- raw tenant identifiers from other tenants;
- request, subject, resource, projection, route or outcome hashes;
- individual evidence IDs;
- raw Provider input or output;
- Prompt bodies or advisory text;
- Secret material;
- exact global or other-tenant history.

## 10. Honest cost boundary

V49 does not store the P6-D conservative request-cost estimate.

P6-E therefore explicitly reports:

- actual Provider cost unavailable;
- durable cost-upper-bound history unavailable.

It does not infer cost from request counts and does not claim invoice, token or financial-budget reconciliation.

P6-D remains the process-local current-window source for dispatched request counts and admitted cost upper bounds.

## 11. Explicitly unavailable capabilities

P6-E does not provide:

- Provider invocation;
- approval or process command execution;
- history mutation;
- evidence tombstone mutation;
- Kill Switch, Circuit, Rate, Cost, Secret or version mutation;
- actual token or invoice history;
- durable cost-upper-bound history;
- Canary, rollout or rollback execution;
- production reauthentication;
- Queue, Worker, Scheduler, listener, polling or automatic retry.

## 12. Workflow and migration boundary

P6-E adds no Flyway migration and no workflow.

The highest governed migration remains `V50`.

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

## 13. Acceptance tests

P6-E acceptance requires:

- core window, closed-enum and aggregate-coherence tests;
- Server view, evidence and GET-only permission tests;
- exact snapshot-time composition test;
- PostgreSQL V49 tenant/window/state/retention/version aggregation test;
- architecture tests prohibiting writes, Provider/Secret/command authority and new migration/workflow;
- complete permanent workflow success;
- independent verification of all four permanent artifacts.

## 14. Merge boundary

P6-E completion does not authorize Ready, merge, auto-merge or Issue closure.

PR #88 must remain Open + Draft.

Issues #81, #82, #62, #13 and #14 must remain Open.
