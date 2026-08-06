# M6-F P6-B — Provider Governance Plan Acceptance

## Scope

P6-B adds deterministic, non-executable review plans for the existing frozen AI Provider
runtime. It does not create a Provider configuration mutation API, traffic controller, deployment
controller, Secret resolver, command adapter, Worker, Queue, Scheduler or automatic retry path.

The only new endpoint is:

`GET /api/approval/management/ai-governance/change-plan?operation=<CANARY|ROLLOUT|ROLLBACK>`

The endpoint requires tenant-scoped management `READ`, a canonical trusted `X-Tenant-Id` header,
and returns `Cache-Control: no-store`.

## Source of truth

Every plan is derived from the P6-A server-owned governance snapshot. The plan preserves:

- the exact Provider, model, Prompt, policy and output-schema inventory;
- the source governance snapshot SHA-256;
- the runtime posture observed by the P6-A snapshot;
- `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`.

The request cannot select a Provider, model, Prompt, policy, endpoint, Secret, traffic percentage,
command or deployment target. The only accepted request input is the closed operation enum.

## Canary decision

Canary plans are always `BLOCKED` in P6-B.

Required blocker codes include:

- `AI_PROVIDER_CANARY_RUNTIME_NOT_IMPLEMENTED`;
- `AI_PROVIDER_SECOND_VERSION_NOT_AVAILABLE`;
- `AI_PROVIDER_TRAFFIC_MUTATION_NOT_AVAILABLE`;
- `AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE`.

When the existing runtime is disabled, the plan also reports
`AI_PROVIDER_RUNTIME_NOT_CONFIGURED`.

P6-B allocates zero traffic and grants no mutation or apply authority.

## Rollout decision

Rollout plans are always `BLOCKED` in P6-B.

Required blocker codes include:

- `AI_PROVIDER_CANARY_EVIDENCE_NOT_AVAILABLE`;
- `AI_PROVIDER_ROLLOUT_MUTATION_NOT_AVAILABLE`;
- `AI_PROVIDER_SECOND_VERSION_NOT_AVAILABLE`;
- `AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE`.

No rollout can be declared ready without a separately implemented and accepted Canary runtime,
real Canary evidence, a second exact version, production reauthentication and governed traffic
mutation.

## Rollback decision

Rollback is a review-ready operator release posture, not an executable API action.

For a configured runtime, the exact operator step codes are:

1. `AI_ROLLBACK_STEP_DISABLE_EXISTING_RUNTIME_FLAG`;
2. `AI_ROLLBACK_STEP_REDEPLOY_THROUGH_ESTABLISHED_RELEASE_PROCESS`;
3. `AI_ROLLBACK_STEP_VERIFY_READ_ONLY_GOVERNANCE_SNAPSHOT`.

The mechanism is `DISABLE_RUNTIME_FLAG_AND_REDEPLOY`. The plan does not modify the flag, perform a
deployment or verify the release automatically.

For an already disabled runtime, the mechanism is `ALREADY_DISABLED` and the only step is:

`AI_ROLLBACK_STEP_NO_ACTION_REQUIRED_RUNTIME_ALREADY_DISABLED`.

## Permanent authority boundary

Every P6-B plan enforces:

- mode `NON_EXECUTABLE_REVIEW_ONLY`;
- planned traffic percentage `0`;
- production reauthentication available `false`;
- Provider invocation authorized `false`;
- Secret resolution authorized `false`;
- traffic mutation authorized `false`;
- configuration mutation authorized `false`;
- deployment authorized `false`;
- apply authorized `false`;
- command execution authorized `false`;
- automatic retry authorized `false`.

The plan evidence hash covers the source snapshot, operation, status, exact inventory, blockers,
operator steps, target runtime posture, Action Whitelist and P5 decision. Tampering with any covered
field invalidates construction.

## Explicit exclusions

P6-B does not add or authorize:

- Provider, model, Prompt, policy, endpoint or Secret mutation;
- Canary traffic, shadow traffic, split traffic or production routing changes;
- rollout promotion, deployment, rollback execution or release automation;
- live circuit-state mutation or cost/rate policy mutation;
- approve, reject/return, transfer, withdraw, terminate, migrate or any application command;
- arbitrary HTTP, SQL, script, executable expression or connector command;
- direct Flowable command or `ACT_*` table access;
- Queue, Worker, Scheduler, listener, polling or automatic retry;
- a new Flyway migration or a second automatic workflow.

## Validation contract

Acceptance requires:

- configured and disabled Canary plans remain blocked;
- configured and disabled Rollout plans remain blocked;
- configured Rollback produces only the three release-level operator step codes;
- disabled Rollback reports that no action is required;
- every authority boolean remains false;
- plan hashes are deterministic and tamper-evident;
- the endpoint is GET-only, tenant-scoped and `no-store`;
- architecture tests reject command, Provider invocation, network, Secret, scheduler and Flowable
  dependencies;
- the existing nine-job permanent workflow and four final artifacts succeed at the exact P6-B Head.

`AI_IS_NOT_AN_OPERATOR`
