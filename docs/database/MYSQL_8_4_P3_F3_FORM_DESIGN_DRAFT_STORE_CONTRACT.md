# MySQL 8.4 P3-F3 Form Design Draft Store Contract

## Baseline identity

```text
source formal Head: 495a33b7cde9e4d628f5a75f78860069b92feac3
source accepted capability: MYSQL_P3_F2_UI_SCHEMA_STORE_PROVEN
implementation branch: agent/mysql-8-4-p3-f3-form-design-draft-store-staging
formal branch: agent/mysql-8-4-production-compatibility
PR: #92 remains Open + Draft
Issue: #91 remains Open
```

P3-F3 converts only the existing mutable `ApprovalFormDesignDraftStore` persistence authority for MySQL 8.4. It does not redesign the form designer and does not add product capability.

```text
MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

## Exact production scope

P3-F3 adds:

```text
JdbcApprovalFormDesignDraftStoreFactory
JdbcMySqlApprovalFormDesignDraftStore
ApprovalFormConfiguration -> trusted factory binding
```

The existing application contract remains:

```text
ApprovalFormDesignDraftStore
ApprovalFormDesignService
FormDesignDraft
```

P3-F3 does not modify the existing PostgreSQL implementation `JdbcApprovalFormDesignDraftStore` and does not edit PostgreSQL Flyway migrations.

```text
POSTGRESQL_FORM_DESIGN_DRAFT_STORE_UNCHANGED
APPLICATION_FORM_DESIGN_SERVICE_DATABASE_NEUTRAL
```

## Trusted implementation selection

`JdbcApprovalFormDesignDraftStoreFactory` resolves the database from trusted JDBC metadata through `ApprovalDatabaseVendorResolver`.

```text
PostgreSQL 16 -> JdbcApprovalFormDesignDraftStore
MySQL 8.4    -> JdbcMySqlApprovalFormDesignDraftStore
```

No request value, profile string, tenant data or user-controlled field selects the persistence implementation.

## MySQL draft representation

The existing MySQL clean-current-schema baseline remains authoritative. P3-F3 does not create a second draft table or change the PostgreSQL V9 definition.

MySQL draft identity remains:

```text
tenant_id, draft_id
```

MySQL binds UUID values through the governed `JdbcDatabaseValueAdapter` and stores mutable draft timestamps as UTC `datetime(6)` values. Before binding, P3-F3 truncates an application `Instant` down to microsecond precision with `truncatedTo(ChronoUnit.MICROS)`.

This floor operation is deliberate. A mutable draft timestamp must never be persisted later than the application clock merely because a sub-microsecond value sits on the 500 ns rounding boundary. In particular, `...07.999999500Z` persists as `...07.999999Z`, not as the next second. This preserves the domain invariant that an immediate update cannot observe `createdAt` later than its current `updatedAt` value.

This P3-F3 mutable-draft timestamp rule is local to the MySQL draft adapter. It does not change PostgreSQL timestamp behavior or the separately governed audit hash timestamp canonicalization contract.

### Form Schema evidence

MySQL Binary JSON is not used as the semantic source for Form Schema text. P3-F3 stores a closed two-member envelope:

```json
{
  "encoding": "CANONICAL_JSON_TEXT_V1",
  "payload": "<exact serialized FormDefinition JSON text>"
}
```

Readback requires exactly the two members above and uses strict duplicate detection. Missing, unknown, extended, duplicate or malformed evidence fails closed.

`FormSchemaHasher` must be stable before and after persistence.

### UI Schema evidence

P3-F3 directly reuses the already accepted P3-F2 `JdbcMySqlUiSchemaCodec`:

```text
CANONICAL_UI_SCHEMA_TYPED_JSON_V1
```

This preserves the closed typed-value protocol for `Byte`, `Short`, `Integer`, `Long`, `BigInteger`, `Float`, `Double`, scaled `BigDecimal`, Unicode strings, booleans and nested lists/maps. `UiSchemaHasher` must be stable before and after draft persistence.

P3-F3 does not introduce reflective polymorphic typing, class-name deserialization or fallback evidence repair.

## Tenant and query contract

Every exact read, row lock and compare-and-swap update requires both:

```text
tenant_id
draft_id
```

Draft listing always predicates by exact tenant identity. MySQL keyword matching uses:

```text
lower(form_key) like concat('%', lower(:keyword), '%')
lower(name)     like concat('%', lower(:keyword), '%')
```

Ordering remains the existing contract:

```text
updated_at desc, draft_id
```

The accepted MySQL collation remains `utf8mb4_0900_as_cs`, so tenant identity remains case-sensitive.

## CAS and lock contract

Mutable updates remain optimistic compare-and-swap operations:

```text
where tenant_id = :tenantId
  and draft_id = :draftId
  and revision = :expectedRevision
  and status in ('DRAFT', 'VALIDATED')
```

A successful mutation advances the revision. A stale concurrent writer receives no silent overwrite; `ApprovalFormDesignService` converts the failed CAS into `DraftRevisionConflictException`.

Validation/archive serialization continues to use the existing Store `lock` protocol. PostgreSQL retains its existing row-level `FOR UPDATE`; MySQL uses InnoDB row-level `SELECT ... FOR UPDATE` against the already existing draft row. P3-F3 does not replace this with a new advisory/named-lock namespace.

The row lock is transaction scoped by the caller. The permanent MySQL matrix must prove a concurrent transaction blocks while the first transaction owns the row lock, and that rollback releases the lock.

## Transaction and rollback contract

`ApprovalFormDesignService` remains database-neutral and continues to execute mutating commands inside the existing `JdbcIdempotencyGuard` transaction boundary.

The permanent MySQL matrix must prove:

1. concurrent service updates against the same expected revision have exactly one CAS winner;
2. the stale writer fails rather than overwriting the committed row;
3. a draft update performed while holding `FOR UPDATE` is removed by surrounding rollback;
4. the rolled-back row lock is released and a later transaction can lock/update the same draft;
5. tenant-scoped reads and listing never expose another tenant's row;
6. persisted Form/UI evidence recomputes the same deterministic hashes;
7. a `...999999500Z` timestamp is floored to microsecond precision rather than carried into the next second.

## Strict admission and forbidden shortcuts

P3-F3 retains strict insert/update semantics. It does not use:

```text
INSERT IGNORE
REPLACE INTO
ON DUPLICATE KEY UPDATE
FOREIGN_KEY_CHECKS
automatic retry
evidence repair
pg_advisory_lock
pg_advisory_xact_lock
```

P3-F3 does not weaken the draft status/revision checks or relational constraints.

## Permanent acceptance matrix

The bounded permanent suites are:

```text
JdbcApprovalFormDesignDraftStoreFactoryTest
JdbcApprovalFormDesignDraftStoreMySqlContractTest
JdbcApprovalFormDesignDraftStoreMySqlIntegrationTest
```

The real integration suite uses MySQL 8.4 Testcontainers with:

```text
InnoDB
utf8mb4_0900_as_cs
UTC session time
READ COMMITTED
strict SQL mode
datetime(6)
useAffectedRows=false
```

It must cover factory selection, create/update round-trip, deterministic listing, case-sensitive tenant isolation, typed UI evidence, Form/UI hash stability, concurrent CAS, row-lock blocking, rollback restoration, malformed evidence fail-closed behavior and the sub-microsecond carry boundary.

Existing PostgreSQL form-design integration tests remain mandatory regressions and are not replaced by the MySQL suite.

## Explicit non-scope

P3-F3 does not implement or imply MySQL compatibility for:

- P3-F4 Form Package Store;
- P3-F5 Form Submission Store;
- Approval Release Lifecycle;
- Flowable MySQL execution;
- a complete dual-database CI matrix;
- historical upgrade, backup or restore;
- MySQL production authorization;
- PR Ready transition;
- merge of PR #92;
- closure of Issue #91.

The MySQL integration suite deliberately supplies an unsupported `ApprovalFormPackageStore` so that any accidental Package Store execution fails immediately rather than silently expanding P3-F3 scope.

```text
MYSQL_P3_F3_FORM_DESIGN_DRAFT_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
