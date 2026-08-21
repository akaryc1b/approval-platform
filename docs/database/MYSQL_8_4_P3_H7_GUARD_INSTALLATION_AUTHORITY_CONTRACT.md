# MySQL 8.4 P3-H7-GA Guard Installation Authority Contract

Status: `STAGING / NOT_ACCEPTED`

## Gate identity

```text
NEXT_GATE_SELECTED=P3-H7-GA
NAME=MySQL D7 Guard Installation Authority
SOURCE_MAIN=779c4fbd09dcf17d45cc523e725222797cc5cb85
SOURCE_FORMAL=e13aca388890e3f7318e3b5b49e2b31034acf0e2
SOURCE_RUN=31776347361/#1457/failure
```

This is a new, independent compatibility gate. It is not H7 Run C and does not reopen the
exhausted P3-H7 correction budget.

## Scope

This gate addresses only the server-owned authority required to install the ten MySQL V50 D7
append-only UPDATE/DELETE triggers while MySQL binary logging is enabled.

In scope:

- read-only Flyway preflight of `@@GLOBAL.log_bin` and
  `@@GLOBAL.log_bin_trust_function_creators`;
- fail-closed rejection before platform DDL when binary logging is enabled and trusted trigger
  creators are disabled;
- one shared production-equivalent MySQL 8.4 Testcontainers server posture;
- positive clean-baseline installation with binary logging retained;
- negative default-server proof with zero `ap_*` tables created;
- governed MySQL V50 checksum assertion correction from the obsolete pre-guard value
  `-392744558` to the exact guard-bearing checksum `1718152560`.

Out of scope:

- new D7 business behavior;
- D8/H8 or later migration protocol steps;
- PostgreSQL migration changes;
- `SET GLOBAL`, `SET PERSIST`, binary-log disabling or elevated application authority;
- Flowable, historical upgrade/restore, operations, performance or production promotion.

## Invariants

```text
SERVER_OWNS_TRIGGER_INSTALLATION_AUTHORITY
MIGRATION_CONNECTION_IS_READ_ONLY_FOR_GLOBAL_VARIABLES
MIGRATION_DOES_NOT_DISABLE_BINARY_LOGGING
NO_SUPER_PRIVILEGE_DEPENDENCY
UNTRUSTED_BINARY_LOGGED_SERVER_FAILS_BEFORE_PLATFORM_DDL
TRUSTED_MYSQL_8_4_SERVER_INSTALLS_GOVERNED_D7_GUARDS
POSTGRESQL_V1_V50_UNCHANGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

The migration identity still requires the ordinary MySQL `TRIGGER` privilege on the platform
schema. Broad `SUPER` authority is not an accepted substitute: the governed posture is a
server-owned trust setting plus schema-scoped trigger authority. This gate does not grant
privileges, parse user-controlled authority, or silently continue when trigger creation fails.

MySQL 8.4 marks `log_bin_trust_function_creators` as deprecated. This bounded gate targets only
MySQL 8.4 and records that limitation explicitly; it does not claim compatibility with a future
major version where the variable may be removed.

## Test-first matrix

| Test | Required proof |
| --- | --- |
| `MySqlV50TriggerInstallationAuthorityTest` | closed variable parsing, fail-closed decision, read-only probe |
| `MySql84ProductionTestServerTest` | exact shared server posture and no binlog/constraint weakening |
| `MySqlV50TriggerInstallationAuthorityIntegrationTest` | default binary-logged untrusted server fails before `ap_*` DDL |
| `MySqlFlywayCleanMigrationIntegrationTest` | trusted server reaches V50 and installs all ten D7 guards |
| `MySqlV50SchemaUniqueConstraintNameTest` | exact current V50 checksum remains governed |
| Existing MySQL suites | shared positive baseline, no skipped target test |
| Existing PostgreSQL suites | no regression |

## Validation boundary

Available locally:

```text
Java 21 source compilation of the bounded preflight helper and baseline with bounded API stubs
static source review
diff and whitespace review
```

Unavailable locally:

```text
Maven
Docker
Testcontainers
```

Those remain `NOT_LOCALLY_EXECUTABLE` and must be exercised by the natural PR Run for this new
formal gate after staging is frozen and integrated by ordinary Merge Commit.

## CI budget

```text
planned full CI: 1
maximum full CI: 2
same-head rerun: forbidden
workflow_dispatch: forbidden
empty trigger commit: forbidden
```

No `ACCEPTED`, `PROVEN` or `SUPPORTED` claim is authorized before the exact formal Head and its
permanent artifacts are independently verified.
