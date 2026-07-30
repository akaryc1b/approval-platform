# M6-A-P9 Non-Production Release Rehearsal

## Contract

- contract: `m6-a-p9-rehearsal-v1`
- environment: `NON_PRODUCTION_SYNTHETIC`
- manifest SHA-256: `dd68005bc98d52c15dd40c3445cfc3544022d7e39e9ec88894e4e414635ac52f`
- real network: false
- real Secret Backend: false
- production execution authority: false
- approval-state mutation authority: false

## Deterministic procedure

1. Verify PR #67 remains Open + Draft and is behind `main` by zero.
2. Verify connector invocation and operations diagnostics remain default disabled.
3. Verify Flyway remains through V48 and V49 is absent.
4. Verify exactly one automatic PR/main workflow exists.
5. Execute the permanent Secret literal and forbidden-artifact scans.
6. Execute every closed fault/security scenario.
7. Execute the full Maven reactor and both client builds.
8. Download all four workflow artifacts and match local ZIP SHA-256 to GitHub.
9. Recheck PR, Issue, review, migration and frozen-branch gates.

## Abort conditions

Abort the rehearsal and preserve evidence when any test fails, an artifact expires or is absent, a
SHA differs, `main` moves behind the branch, a governed Issue closes, a review thread appears, V49
appears, a second automatic workflow appears, or production authority becomes true.

## Result semantics

A successful rehearsal proves only deterministic non-production readiness evidence. It does not
constitute production promotion, operational approval, Secret Backend approval, customer endpoint
approval, Ready status, auto-merge or merge authorization.
