# M5-D2 Governance Acceptance

## Decision

- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D overall: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- PR #58 remains `Open / Draft`.
- Issues #13, #14 and #56 remain Open.
- M5-D3 is the next authorized slice; M5-D7 and later remain blocked.

This record is the explicit governance acceptance authorized for the already implemented M5-D2 shared command fence, bounded claim and durable lease slice. It grants no production execution authority and creates no reusable runtime capability token.

## Reverified repository basis

Acceptance was made only after re-querying GitHub and confirming:

- `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`;
- M5 branch: `agent/m5-governed-process-instance-migration`;
- accepted evidence head: `1e27dcc69d9c899b3593f9bb464fc1847a595513`;
- PR #58: Open, Draft, mergeable, base `main`, ahead 55, behind 0;
- Flyway is continuous through `V40` and no historical migration was changed;
- Issues #13, #14 and #56 remain Open;
- PRs #67, #68, #69 and #70 remain independent Open Draft PRs;
- none of the four M6 changed-file sets contains a Flyway migration;
- there is no unresolved PR #58 review thread;
- `.github/workflows/approval-platform-validation.yml` remains the only workflow with automatic `pull_request` or `push` triggers.

No local Git checkout was present in the execution environment. Repository and branch state were therefore resolved from GitHub refs and immutable commit/run evidence; no local ref, worktree or unpushed change existed to conflict with the remote branch.

## Accepted D2 scope

The accepted implementation is limited to:

- exact initial attempt provisioning from one current `CONSUMED` plan;
- one exact tenant and approval instance per attempt;
- bounded deterministic claims using current tenant/intent prefixes;
- a shared tenant/instance command fence used by migration and business commands;
- durable lease ownership, same-owner renewal, exact-expiry takeover and stale-owner fencing;
- append-only claim-batch and command-fence event evidence;
- audit-failure rollback for provisioning, claim, renewal and takeover;
- an internal one-shot claim runner that is disabled by default.

D2 invokes no Flowable migration API, mutates no runtime binding, exposes no public execution endpoint, adds no Web or Mobile execution control and starts no resident scheduler.

## Permanent committed-head validation

The final D2 evidence commit is:

- commit: `1e27dcc69d9c899b3593f9bb464fc1847a595513`;
- message: `docs: freeze migration claim evidence`.

The corresponding permanent workflow run is:

- workflow: `Approval Platform Validation`;
- Run ID: `30187720943`;
- run number: `#564`;
- head: `1e27dcc69d9c899b3593f9bb464fc1847a595513`;
- conclusion: `success`.

All four jobs succeeded:

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Repository hygiene | `89755298997` | success |
| Java 21 / Maven / PostgreSQL | `89755298998` | success |
| Vben TypeScript / production build | `89755298984` | success |
| UniApp TypeScript / H5 / WeChat | `89755298973` | success |

The implementation evidence retains the validated aggregate of 586 Maven tests with zero failures, errors or skipped tests, plus 34/34 D1/D2 permanent Node governance boundaries.

## Artifact integrity

Each Run #564 artifact was downloaded again during this acceptance decision. The independently computed local ZIP SHA-256 exactly matched the GitHub digest:

| Artifact | ID | GitHub digest / local ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30187720943` | `8627594481` | `eaf01b7066017017aba1fc4930b2e65cced72b1d83e32c8e98a935f5af464e30` — exact match |
| `approval-vben-30187720943` | `8627556607` | `ca9ac648ef32d88fb87f50ef15b5bc763e7722aa216c2caf4b93bea2e9e5aa45` — exact match |
| `approval-mobile-30187720943` | `8627551054` | `33dc4a82ea38c52c5be3f2ecc95e76463113f532454ad8a30e4c0897af7a7d4e` — exact match |
| `approval-hygiene-30187720943` | `8627544056` | `891dbcb714ea6ca33b8e5058fdd6cc2f79f45d821782a1848a9b53cc40af96a4` — exact match |

## Acceptance invariants

Acceptance confirms the following boundaries remain enforced:

- no production code reads or writes Flowable `ACT_*` tables;
- no Flowable call occurs in D2;
- no runtime-binding mutation occurs in D2;
- no client supplies authoritative tenant, worker or engine identity;
- execution, worker and automatic reconciliation are disabled by default;
- no public execute, force, rollback or reconciliation endpoint exists;
- no resident scheduler or automatic retry of `UNKNOWN` exists;
- no definition-wide or unbounded batch migration exists;
- no fake rollback or force-success behavior exists;
- no M6 dependency or source modification exists;
- no Ready transition, auto-merge, merge or issue closure is authorized.

## Next gate

Only M5-D3 — the default-disabled, one-instance Flowable executor with short platform transactions around an out-of-transaction public-API dispatch — is authorized to begin. API return must not be treated as verified completion. D4 verification, D5 runtime-binding CAS and D6 durable `UNKNOWN` reconciliation require their own independently validated slices before being called complete.
