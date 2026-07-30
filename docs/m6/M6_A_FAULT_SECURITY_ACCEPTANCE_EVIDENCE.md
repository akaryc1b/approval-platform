# M6-A-P9 Fault, Security, Concurrency and Release Rehearsal Evidence

## Decision

- `FAULT_SECURITY_ACCEPTANCE_IMPLEMENTED`
- `NON_PRODUCTION_RELEASE_REHEARSAL_COMPLETED`
- `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
- `APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`

This evidence closes M6-A-P9 only. It does not authorize a real provider call, a real Secret Backend,
production enablement, persistence, workers, schedulers, retry/replay/recovery, or approval-state mutation.

## Accepted implementation lineage

| Purpose | Commit |
| --- | --- |
| P9 fault/security acceptance, runbook, blocker catalog and synthetic rehearsal | `f824b16cee51273a6484dc7a07a85379ace075dc` |
| Preserve Secret scan while avoiding a complete PEM header literal in test source | `d71a1d863120a764ae5199d0383813751fb8c168` |

Both branch updates were ordinary fast-forwards with `force=false`. No rebase, force push, squash,
amend, reset or history rewrite was used.

## Accepted scope

P9 adds only tests, test resources and governance documentation:

- 42 closed fault/security scenarios;
- exact zero-or-one dispatch assertions;
- fail-closed pre-dispatch outcomes and `UNKNOWN_AFTER_DISPATCH` handling;
- Secret literal and Secret-file extension scanning;
- default-disabled, GET-only, no-store diagnostics checks;
- V48/no-V49 and one-automatic-workflow checks;
- deterministic non-production rehearsal manifest;
- connector operations runbook;
- 20-item production blocker catalog.

The rehearsal manifest SHA-256 is:

`dd68005bc98d52c15dd40c3445cfc3544022d7e39e9ec88894e4e414635ac52f`

## Retained failed validation

Natural workflow Run `30447754692` / #888 at `f824b16cee51273a6484dc7a07a85379ace075dc`
completed with overall failure and is retained.

- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success;
- Repository hygiene: failure.

The existing repository Secret boundary correctly found a complete PEM header marker embedded in the new
Secret-scanning test source. The test was changed to assemble the same runtime marker from separate safe
fragments. No Secret rule was removed or weakened, and no production source changed.

Run #888 artifacts remain retained:

| Artifact | ID | GitHub digest |
| --- | ---: | --- |
| `approval-maven-30447754692` | `8722347274` | `2565995b28666849e46d190b81d74ac9dd9abfd1c059c10e6cf9b8c38d1cd878` |
| `approval-vben-30447754692` | `8722209372` | `3e992c7755bf216bed23531971cd08b828034f156e22e354ebbf94cf4fab092f` |
| `approval-mobile-30447754692` | `8722187201` | `f2fc57bca373b21456e2da865210fbb52d4fcd94833a736c17c32a15849ce939` |
| `approval-hygiene-30447754692` | `8722166240` | `36035e2423ec74c69f24a2642e0c9a70ae88dc628c72afea2dd67b4e1e3e9fdc` |

## Successful implementation validation

Natural workflow Run `30448287907` / #889 at
`d71a1d863120a764ae5199d0383813751fb8c168` completed successfully.

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success;
- Maven reactor: `BUILD SUCCESS`;
- Maven aggregate: `1132 / 0 / 0 / 0`;
- `M6AConnectorFaultAcceptanceTest`: `46 / 0 / 0 / 0`;
- `M6AConnectorSecurityAcceptanceTest`: `6 / 0 / 0 / 0`;
- total P9 focused: `52 / 0 / 0 / 0`.

### Verified artifacts

Each ZIP was downloaded and its local SHA-256 exactly matched the GitHub digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30448287907` | `8722606687` | `b15b60b5e583134f3bb731131a610e102ec6c3e651335f48428a0fb112007043` |
| `approval-vben-30448287907` | `8722421067` | `ab53256b6cd16486f16403c013c8922898b121e8c09f112df9fc5f472221b3e7` |
| `approval-mobile-30448287907` | `8722406301` | `cbd90a8f623f722ab6c05b62bdb85964ea5a5bb5b7e86c81b58c149e49d3093a` |
| `approval-hygiene-30448287907` | `8722383731` | `a74142f4166680a91d5e17ccbf754abd56c96dbc4a9f7bc4992bb5f249f07d20` |

## Repository gate before evidence commit

- verified `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- branch relation: ahead `93`, behind `0`;
- PR #67: Open, Draft, unmerged and mergeable;
- requested reviews: none;
- review submissions: none;
- review threads: none;
- repository auto-merge setting: disabled;
- Issues #62, #63, #13 and #14: Open;
- PR #68 head: `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- PR #69 head: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70 head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- Flyway remains through V48;
- `.github/workflows/approval-platform-validation.yml` remains the only automatic PR/main workflow.

## Permanent boundaries retained

- no real DingTalk request or customer endpoint;
- no real Secret Backend or committed usable Secret material;
- no diagnostic persistence or Flyway V49;
- no worker, scheduler, event listener or background execution;
- no automatic retry, replay, recovery or reconciliation;
- no connector operation with POST, PUT, PATCH or DELETE;
- no public Web/Mobile production connector controls;
- no approval, reject, return, transfer, withdraw, terminate or migrate command authority;
- no PR Ready transition, auto-merge, merge or issue closure.

The authoritative documented-head result is established only by the natural workflow run generated by the
evidence commit that contains this file.
