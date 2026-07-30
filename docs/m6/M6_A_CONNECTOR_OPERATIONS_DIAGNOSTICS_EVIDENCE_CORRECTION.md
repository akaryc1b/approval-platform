# M6-A-P8 Connector Operations Diagnostics Evidence Correction

## Purpose

This correction record supplements and supersedes the pending documented-head status in
`M6_A_CONNECTOR_OPERATIONS_DIAGNOSTICS_EVIDENCE.md`. It preserves every natural validation Run,
records the P6 Token single-flight handoff defect exposed after the P8 evidence commit, and records
the verified correction. It does not change the P8 diagnostics scope or authorize production use.

Final status remains pending until this correction-record Head naturally passes the permanent
workflow and all four artifacts are downloaded and matched.

## P8 feature lineage

- feature: `3cd7fc23a2947e90aaa59c5448cef607f2033417`
- test-only correction: `5b06e75a714469e6290e2ef1465bb1ac1c5c1f7f`
- initial evidence: `db22325f6ab0e251d645059c86527cbbd6fd37b8`
- Token handoff correction: `519eb1db65f0bae46e0af2238479cd5105b27e1d`

All branch updates used `force=false`. No rebase, amend, squash, reset, force push or history rewrite
was used.

## Successful P8 implementation validation

Natural Run `30442091862` / #884 at `5b06e75a714469e6290e2ef1465bb1ac1c5c1f7f` completed
`success`:

- all four permanent jobs: success
- Maven reactor: `BUILD SUCCESS`
- aggregate: `1080 / 0 / 0 / 0`
- P8 focused: `48 / 0 / 0 / 0`

Downloaded ZIP SHA-256 values exactly matched GitHub:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30442091862` | `8720100016` | `ea0d7614e1569940883949e89534c638d4301cac0467ea937ba7e7107cea5be7` |
| `approval-vben-30442091862` | `8719896831` | `502756e206df4864601c0a24370628694165268ab352a964c9cab4d4fd8f0741` |
| `approval-mobile-30442091862` | `8719884519` | `1164e12118147d80b4b7890bdea0cda6bb7d929d1306bfbe51b891f26b720e4d` |
| `approval-hygiene-30442091862` | `8719860302` | `10d3667123c2b64d2eaf45060b7b9a55db65916cdd83bd08d074d953109c360f` |

## Retained evidence-head failure

Natural Run `30443390978` / #885 at initial evidence Head
`db22325f6ab0e251d645059c86527cbbd6fd37b8` completed `failure`. Repository hygiene, Vben and
UniApp succeeded. Maven failed in the pre-existing P6 test
`DingTalkTokenCoordinatorTest.concurrentSameBindingUsesOneSingleFlightEndpointAttempt`: expected one
endpoint call and observed two.

The Run was not cancelled, deleted, hidden or manually rerun. Its artifacts remain retained:

| Artifact | ID | GitHub SHA-256 |
| --- | ---: | --- |
| `approval-maven-30443390978` | `8720398035` | `cdf1fc6a2edefecaf54401f06baa33e85b37bf2cf572105bb67c3c977e89c174` |
| `approval-vben-30443390978` | `8720430560` | `6e2cd40a73cf25c189d6621ecf2bcb57cf0e82e4c38046e3f51a02c02297ed99` |
| `approval-mobile-30443390978` | `8720407818` | `12bc5f79c4ea7755c6bb22998af87117d8b937da88722c60cfb1a3a909b5223c` |
| `approval-hygiene-30443390978` | `8720385870` | `6309935b728643712ff234c1ee414fc96a42722c2fb359ce9e6bb2da2b1fd858` |

## Root cause and bounded correction

A late contender could read no cache entry, pause before flight registration, then become a new
leader after the first leader installed the Token and removed its completed flight. The new leader
could call the endpoint a second time without rechecking the installed cache.

Commit `519eb1db65f0bae46e0af2238479cd5105b27e1d` closes only that handoff window. A newly elected
leader rechecks the installed cache before endpoint acquisition. A still-valid entry is security
revalidated, returned as `CACHE_HIT`, and used to complete the new flight. The correction adds no
retry, loop, worker, scheduler, persistence, endpoint fallback, new credential authority or approval
mutation.

A deterministic local latch-based reproduction held a contender after its first cache read, allowed
the first flight to install and remove itself, and then released the contender. The corrected code
made one endpoint call and returned `CACHE_HIT` to the late contender.

## Successful handoff-correction validation

Natural Run `30444126743` / #886 at `519eb1db65f0bae46e0af2238479cd5105b27e1d` completed
`success`:

- all four permanent jobs: success
- Maven reactor: `BUILD SUCCESS`
- aggregate: `1080 / 0 / 0 / 0`
- P8 focused: `48 / 0 / 0 / 0`
- `DingTalkTokenCoordinatorTest`: `8 / 0 / 0 / 0`

All four ZIPs were downloaded; local SHA-256 exactly matched GitHub:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30444126743` | `8720884968` | `e2d61fb0750b6c932c6629f62e8801ec6d08b8e8e168c4ea261cff1f6e2884ca` |
| `approval-vben-30444126743` | `8720723832` | `fe50cb5ba63880cc817df080484a81f07a0ee3ff9974b1a820baf3873c3604d8` |
| `approval-mobile-30444126743` | `8720701263` | `853129443676af56c7ae40c765f90f485a8863b228a7fe3889dafc13fcf62c87` |
| `approval-hygiene-30444126743` | `8720680455` | `b3684d2bbf7db8692a91e13a014f5d73659a058238006e3a231d7cb3ad277cb9` |

## Repository gate before this correction record

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`
- PR #67 Head: `519eb1db65f0bae46e0af2238479cd5105b27e1d`
- relation: ahead `90`, behind `0`
- PR #67: Open, Draft, unmerged and mergeable
- reviews and review threads: none
- repository auto-merge: disabled
- Issues #62, #63, #13 and #14: Open
- frozen PR heads: #68 `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`,
  #69 `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`,
  #70 `9d588215e869c8f1332c0bc1a2809fbd235c2efa`
- Flyway highest migration: V48; V49 absent
- automatic PR/main workflow count: one

## Authority boundary

P8 remains default disabled, process-local, read-only, non-durable, non-audit and non-production.
It does not authorize real Provider calls, production credentials or endpoints, Token operations,
persistence, workers, schedulers, retry/replay/recovery/reconciliation, public Web/Mobile management
controls, Flyway V49, approval-state mutation, Ready, auto-merge, merge or Issue closure.

## Final documented-head gate

This correction-record commit must naturally trigger the existing permanent workflow. P8 becomes
`PERMANENTLY_VALIDATED` only after all four jobs succeed, the Maven counts remain stable, four
unexpired artifacts exist, and every downloaded ZIP SHA-256 exactly matches GitHub.
