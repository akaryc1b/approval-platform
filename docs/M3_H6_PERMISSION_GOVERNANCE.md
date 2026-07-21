# M3 H6 Permission Governance

## Boundary

All handlers below `/api/approval/management` require a method-level or controller-level `ApprovalManagementPermission` capability.

The management interceptor now denies an undeclared management handler before execution, including when the configurable permission boundary is in bypass mode. Participant handlers outside the management path are unaffected.

Undeclared management access records the low-cardinality `undeclared/denied` metric and the `APPROVAL_MANAGEMENT_PERMISSION_UNDECLARED` security event, then returns the existing stable 403 response without exposing supplied authority values.

## Matrix

The closed capability matrix separates:

- read, design, publish, deploy, activate and transfer;
- audit read, export and verification;
- consistency read and detect-only execution;
- operational-failure read and governed replay.

Authorities use the `approval.management.` prefix. Authority values and metric tags are unique. Authenticated principal roles, a bounded trusted-host permission header and the global management-admin override remain supported.

## Coverage

Automated classpath coverage scans controllers rooted at `/api/approval/management` and rejects mapped handlers without an explicit capability. Runtime tests cover exact permission, admin override, denied role, missing principal, trusted-header validation, bypass observability, participant-handler separation and undeclared-management denial.

Permanent CI also exposed an H4 pagination flake: consistency checks created in one clock tick had equal timestamps and random UUID ordering. Consistency timestamps are now monotonic at PostgreSQL microsecond precision while retaining `started_at desc, check_id desc` query ordering.

## Evidence

Code head `23b208aa3ad0d110e23331dd1142545b20f30cfb` passed permanent run `29763717438`:

- all four workflow jobs passed;
- management interceptor tests: 6 passed;
- automatic endpoint contract: 1 passed;
- explicit permission coverage: 1 passed;
- requirement matrix uniqueness: 1 passed;
- consistency integration tests: 6 passed;
- operational-failure integration tests: 7 passed;
- PostgreSQL module: 122 tests, 0 failures, 0 errors, 0 skipped;
- apps/server: 17 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module reactor: `BUILD SUCCESS`;
- Vben, UniApp, H5 and WeChat verification passed.

This is the single H6 governance record and is not edited after creation.
