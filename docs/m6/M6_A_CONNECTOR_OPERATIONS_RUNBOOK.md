# M6-A Connector Operations Non-Production Runbook

## Operating mode

- `NON_PRODUCTION_ONLY`
- `DEFAULT_DISABLED`
- `NO_REAL_PROVIDER`
- `NO_REAL_SECRET_BACKEND`
- `NO_APPROVAL_STATE_MUTATION`

## Preconditions

1. PR #67 is Open + Draft and behind `main` by zero.
2. Issues #62, #63, #13 and #14 are Open.
3. Connector operations diagnostics remain disabled unless a synthetic test context explicitly
   enables them.
4. Synthetic fixtures contain no customer tenant, credential, Token or endpoint material.
5. The only automatic workflow is `.github/workflows/approval-platform-validation.yml`.

## Synthetic execution

1. Use only fixed clocks, fake credential material sources, recording transports and hashed tenant
   references.
2. Run the full fault/security matrix and deterministic rehearsal manifest.
3. Confirm every transport path dispatches at most once.
4. Confirm pre-dispatch rejection performs zero Provider dispatches.
5. Confirm diagnostics failure is best effort and cannot change invocation results.
6. Query only the GET diagnostics and summary routes under the existing management permission.
7. Confirm `Cache-Control: no-store`, hash-only output and low-cardinality metrics.

## Abort and containment

Immediately stop the synthetic context when any boundary fails, a real endpoint or Secret appears,
a dispatch count exceeds one, a tenant isolation assertion fails, a page token validates across
tenants, a mutation route appears, or a durable/background component is created. Preserve the
failed natural workflow and its artifacts; do not hide or blindly rerun it.

## Cleanup

Close Token leases, zero temporary byte arrays, close the page-token codec, destroy the process-local
diagnostics store, and discard all synthetic fixtures. There is no durable P8/P9 state to clean up.

## Evidence capture

Record commit SHA, natural Run ID/number, all four job conclusions, Maven aggregate and focused
counts, artifact IDs/digests, downloaded ZIP SHA-256 values, PR/Issue/review state, `main` relation,
Flyway highest version and automatic-workflow count.

## Escalation

A production request must remain blocked and be referred to Security, Platform and Operations owners
against the production blocker catalog. This runbook grants no production authority.
