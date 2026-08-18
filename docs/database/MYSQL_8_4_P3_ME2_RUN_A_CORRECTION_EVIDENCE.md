# MySQL 8.4 P3-ME2 Run A Correction Evidence

## Exact failed Run

```text
Repository:   akaryc1b/approval-platform
Draft PR:     #92
Formal Head:  87303b0b6daa76fcd9123403056b1be2364067b6
Main base:    78c084dba54dbdde12af4599c2eb4883e6a0890d
Run ID:       32127958889
Run number:   1543
Event:        pull_request
Attempt:      1
Conclusion:   failure
```

Run A was the first and only natural implementation validation for the initial P3-ME2 formal Head.
No workflow dispatch, same-Head rerun or empty trigger commit was used.

## Job result

Eight physical jobs completed their own verification successfully:

```text
Repository hygiene
Java 21 / Maven core
Persistence JDBC / shard 0
Persistence JDBC / shard 2
Persistence JDBC / shard 3
Vben TypeScript / production build
UniApp TypeScript / H5 / WeChat
Maven evidence assembly completed its upload path
```

`Persistence JDBC / shard 1` failed. The final Maven aggregate therefore correctly concluded
failure instead of hiding the shard result.

## Exact failure

The target suite was selected and executed:

```text
JdbcApprovalMigrationDiagnosticsQueryMySqlIntegrationTest
Tests:    2
Failures: 0
Errors:   2
Skipped:  0
```

Both methods failed at the same production query boundary:

```text
findPlanDiagnostics(...)
PLAN_DIAGNOSTICS_SQL
```

The retained Surefire cause was:

```text
java.sql.SQLSyntaxErrorException
You have an error in your SQL syntax ... near 'kill
  on kill.tenant_id=run.tenant_id ...'
```

The failing SQL used the unquoted derived-table alias:

```sql
) kill
```

MySQL 8.4 parsed `KILL` as a keyword rather than the intended alias.

Classification:

```text
COMPATIBILITY_BUG
MYSQL_RESERVED_ALIAS
NOT_TEST_FIXTURE_BUG
NOT_ENVIRONMENT_FAILURE
NOT_TRANSIENT_INFRA_FAILURE
```

## Independent failed Artifact inspection

The merged Maven Artifact from Run A was independently downloaded and verified:

```text
Artifact ID:  9321217016
Bytes:        1290400
SHA-256:      dc3a0c6ebfbc79a14634987f66607620cb5df6e91d7729028fa8c35fc7c22f93
ZIP entries:  351
ZIP integrity:PASS
```

The archive contained all four shard selections, logs and Surefire reports, including the exact ME2
XML and text report. The failed history is retained and will not be rerun in place.

## Test-first correction

The correction staging branch first added a permanent source contract requiring:

```text
) kill_observation
kill_observation.observation_id
```

and rejecting:

```text
) kill
 kill.
```

Test-first correction commit:

```text
547cceaa73d83302f28f093bcc3d2459e5285fc5
```

## Production correction

The subsequent production correction renames only that SQL alias:

```text
kill -> kill_observation
```

It changes no selected column, predicate, latest-row ordering, tenant boundary, result mapping,
PostgreSQL SQL, application port, transaction boundary or evidence semantics.

Production correction commit:

```text
3b498774475bab968a8103bf8e05645f745abb5b
```

The exact production patch is limited to the alias declaration and references in
`PLAN_DIAGNOSTICS_SQL`.

## Remaining authorization

The bounded gate has one CI execution left:

```text
Run A: consumed / failure retained
Run B: authorized once after ordinary correction merge
Run C: prohibited
```

```text
P3_ME2_RUN_A_FAILURE_RETAINED
P3_ME2_CORRECTION_STAGED
CI_BUDGET_REMAINING=1
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_SAME_HEAD_RERUN
NO_WORKFLOW_DISPATCH
NO_READY
NO_MAIN_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
