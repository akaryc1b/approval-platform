# M6-F R0 Acceptance

Status: `R0_IMPLEMENTATION_ACCEPTED / DOCUMENTED_HEAD_VALIDATION_REQUIRED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Accepted implementation Head

- branch: `agent/m6-f-controlled-automation-and-ai-governance`
- base: `main`
- exact base: `492a428627d3be707d5723350506302ca04841b0`
- accepted R0 implementation Head: `aea3be339d60c91873642afb1f6f12829b450a7d`
- Pull Request: #88
- PR state at acceptance: Open + Draft + mergeable
- R0 commits:
  - `c0b19404fe85bb24906da0bb7cae72caa8f614a9` — rebaseline and authority threat model
  - `ccae6a1d3a8f5e979b9ae17911d9257fcd221da5` — controlled automation authority boundary
  - `aea3be339d60c91873642afb1f6f12829b450a7d` — permanent workflow attachment through the existing M6 aggregate

No rebase, squash, amend, force push, direct `main` commit, Ready transition, merge or Issue closure occurred.

## 2. Natural permanent workflow

- workflow: `.github/workflows/approval-platform-validation.yml`
- event: natural `pull_request`
- Run ID: `30972949603`
- Run number: `1271`
- exact checkout Head: `aea3be339d60c91873642afb1f6f12829b450a7d`
- conclusion: `success`

All nine physical jobs completed successfully:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `92201001795` | success |
| Persistence JDBC / shard 0 | `92201001819` | success |
| Persistence JDBC / shard 1 | `92201001794` | success |
| Persistence JDBC / shard 2 | `92201001797` | success |
| Persistence JDBC / shard 3 | `92201001805` | success |
| Java 21 / Maven / PostgreSQL aggregate | `92201350031` | success |
| Vben TypeScript / production build | `92201001791` | success |
| UniApp TypeScript / H5 / WeChat | `92201001762` | success |
| Repository hygiene | `92201001809` | success |

## 3. Rebuilt test evidence

- Maven core: `1273 / 0 / 0 / 0`
- Persistence JDBC: `295 / 0 / 0 / 0`
- aggregate: `1568 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `160 / 160`
- OpenAI: `62 / 62`
- application: `233 / 233`
- architecture: `139 / 139`
- server: `180 / 180`
- JDBC shard counts: `77 + 88 + 43 + 87 = 295`
- selected JDBC classes: `73`
- Surefire report classes: `72`
- selected abstract classes without reports: `1`
- non-abstract selected classes without reports: `0`
- duplicate shard assignments: `0`
- selection coverage: exact
- Maven `BUILD SUCCESS`: present
- all four JDBC shard logs `BUILD SUCCESS`: present

Permanent boundary evidence:

- previous M6 transport/P7 boundary: `79 / 79`
- new M6-F R0 tests: `8 / 8`
- combined permanent M6 boundary: `87 / 87`
- failures/errors/skips: `0 / 0 / 0`

The R0 boundary proves:

- `AI_IS_NOT_AN_OPERATOR`;
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- Provider modules do not import application command authority;
- M6-E PC/Mobile remain `commandAvailable: false`;
- M6-F contains no credential or executable payload carrier;
- no M6-F Queue, Worker, Scheduler or polling path exists;
- migration upper bound remains `V49` with no `V50+`;
- the existing automatic workflow remains unique;
- all high-risk and arbitrary-execution Action categories remain explicitly excluded.

## 4. Four independently verified artifacts

Every ZIP was downloaded and independently hashed. Local size and SHA-256 exactly matched GitHub metadata.

| Artifact | ID | Size | SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8917234144` | `328023` | `f855bd519dc0cbd9201ec4905ae81c18900ee5ab874a051aaa57bd016c57d846` | exact |
| Vben | `8917220891` | `18891` | `a3297c3063d1e7e08ec2947aa049a9052c7dc22ed0fca46b44302c1d8ca50773` | exact |
| Mobile | `8917207475` | `9810` | `02efcbb10228fb91e3fe7d33e5e20ecd27ac4a37e1555627060eb65255594400` | exact |
| Hygiene | `8917193919` | `12371` | `41abf6aab208bafac5efeb11594bb179acc7c97f5083ec276285f19ff1bbf83a` | exact |

All artifacts were unexpired and recorded expiry `2026-11-03T03:40:50Z`.

## 5. Repository and governance state

- `main` remained `492a428627d3be707d5723350506302ca04841b0` throughout R0 implementation acceptance.
- PR #83 remained Merged / Closed and unchanged.
- Issue #80 remained Closed / Completed.
- Issues #81, #82, #62, #13 and #14 remained Open.
- Issue #82 remained blocked by Issue #81 post-main formal closure.
- PR #88 had no review submission, no unresolved review thread and no Reaction blocker at acceptance time.
- the highest migration remained `V49`;
- no second automatic workflow was added;
- no application command was qualified or bound;
- no Provider, model, Prompt, Secret access, HTTP/SQL/script Action, connector command, Flowable command or autonomous executor was added.

## 6. Gate decision

R0 implementation is accepted. This acceptance document is append-only and creates a new documented Head that must complete its own natural `pull_request` workflow before P0 implementation begins.

The Action Whitelist remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P0 is authorized only to audit already-existing application commands and make an honest empty-or-single Action decision. P0 is not authorized to implement execution capability.

`AI_IS_NOT_AN_OPERATOR`
