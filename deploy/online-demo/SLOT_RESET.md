# Private evaluator stacks and reset rehearsal

Tracking: #144 / #107; implementation remains in Draft PR #148.

This increment supplies `evaluation-docker-slots.mjs`, a real Docker reset adapter for the existing invitation controller. It creates two independent backend/PostgreSQL/Redis stacks and a probe in each backend's network namespace. It does **not** connect browser sessions to business APIs, seed a workflow, start the signed payment sandbox, or publish an online service.

## Integration and execution

The existing `online-demo-images-runtime.mjs run|ci` entrypoint first completes its unchanged backend/PC/H5 image build and startup smoke, including cleanup. It then passes the already-inspected image IDs to `executeEvaluationSlotRehearsal`. No second image build, registry push, workflow or CI job is introduced. The existing runtime test entrypoint still imports all earlier image/session/page cases and now imports the slot suite.

The real runner addresses only `unix:///var/run/docker.sock`, with an argument vector rather than a shell. It does not inherit Docker contexts, remote-engine URLs, application credentials or Java/Node injection variables. This increment targets a local rootful Linux/amd64 engine; rootless and remote engines are not accepted targets. Engine identity, backend commit/tree/archive/version labels, runtime user, and pinned PostgreSQL/Redis/Node image IDs are rechecked before allocation.

The caller must supply a private recorder. Scope is recorded before resource creation, including exact names and ownership labels, so uncertain creation has a cleanup target. Recorded source/resource metadata excludes database passwords, session/invitation tokens and reset nonces. Errors expose fixed codes and phase identifiers, not Docker output or container environments. The default runner bounds output to 1 MiB, commands to five seconds, and cleanup commands to two seconds. A subprocess timeout/abort does not prove a Docker daemon mutation stopped: an uncertain mutation permanently quarantines that adapter slot, even when best-effort cleanup subsequently observes no resources.

The same existing controller supplies single-use invitations, absolute expiry, credential rotation, and revocation before reset. The rehearsal configures its reset timeout to sixty seconds and the adapter's deadline to fifty-five seconds. The real-stack adapter never supplies a no-op acknowledgement.

## Isolation and replacement

Each adapter has a random namespace and exactly `slot-a` / `slot-b`. Each generation has its own labelled internal bridge network, PostgreSQL container/password, Redis container/password, backend and non-root probe. No host ports, host mounts, named/anonymous volumes, privilege escalation, automatic restarts, or cross-network attachments are requested. Application/probe roots are read-only and capability-dropped. Database/cache entrypoints retain their official image behavior; they are not claimed to have the same non-root/rootfs settings as the application.

PostgreSQL data, Redis data and the backend/probe temporary directory use bounded tmpfs. Container limits also bound memory, CPU and processes. The backend retains principal identity and loopback binding; generic Connector and dispatch remain disabled. Only Actuator health is exposed. The probe has a forty-five-minute lifetime backstop; the rehearsal explicitly removes it much earlier.

Reset revokes access at the controller, removes the selected old stack, verifies absence, creates a new generation, checks backend health/JAR checksum and inspected container/mount/network settings, and records success before acknowledging the exact reset nonce. Any failure prevents acknowledgement. Cleanup compares resource ID, exact name, namespace, slot, generation and role before removal. It refuses unexpected volumes, foreign ownership and nonempty networks; failures do not skip cleanup of the other slot. It never uses broad prune or image deletion.

A new process uses a new namespace and does not adopt or certify old stacks. Controller state begins quarantined. Process crashes can leave labelled containers/networks requiring trusted operator recovery from the recorded scope. Automatic crash recovery, multi-process leasing, a deployed operator command and secure physical erasure are **not** delivered. Labels guard accidental cleanup, not an attacker controlling the Docker daemon.

Docker internal networks isolate bridge traffic between slots, but do not constitute a complete host/gateway egress policy. Host services and a Docker operator remain outside this boundary. See the official [internal network behavior](https://docs.docker.com/reference/cli/docker/network/create/#network-internal-mode---internal) and [tmpfs lifecycle](https://docs.docker.com/engine/storage/tmpfs/). Do not infer hosted network containment, secure erasure or production durability from this rehearsal.

## Real rehearsal and evidence

The CI path performs these checks with the existing real backend image:

1. Initialize both slots through `createEvaluationSessions` and the Docker adapter; confirm fresh synthetic marker storage.
2. Admit two distinct control sessions, then write different markers into a dedicated `evaluation_reset_probe` table, a Redis key and `/tmp/evaluation-reset-probe.txt` in each backend.
3. Read both marker sets and probe PostgreSQL/Redis by their inspected IPs. Four same-slot connections must succeed; four cross-slot connections must fail.
4. End the first session, verify immediate credential revocation and a completed real replacement; its three old markers must be absent, while the second slot's resource IDs, markers and control session remain unchanged.
5. Admit a replacement in the first slot, advance the controller's deterministic test clock to expire only the second session, run its real reset, and verify the replacement is untouched.
6. Disable admission, attempt cleanup of both stacks, and require cleanup success for the overall rehearsal result.

The expiry clock is simulated to avoid a thirty-minute wait. Docker operations, backend startup, marker reads/writes, networking and removal are real in the CI command. Unit tests substitute Docker and do not establish those runtime results. The marker table is a test-only table, not a platform or Flowable table. The file marker is **not** an attachment uploaded through an approval API. No approval record or Outbox row is mutated to reset or advance a business process.

`evaluation-slot-resources.json` and `evaluation-slot-rehearsal.json` are retained inside the existing `.runtime/online-demo-image-runtime/run-*/` directory and existing image artifact upload. The primary image receipt remains separate. The command succeeds only when both smoke and slot rehearsal succeed; an image-only success cannot release the slot claim. The new result is `PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED`, with explicit synthetic-marker scope. Failed or interrupted results are not passes.

The slot phase has at most six minutes and must fit within forty-two minutes from the beginning of the combined command, reserving job time for bounded cleanup. The original image check retains its own thirty-eight-minute cap. Missing Docker/images, insufficient remaining time, failed readiness/probes/recording or incomplete cleanup fails rather than skipping. A killed runner cannot prove its cleanup completed.

## Still required for online evaluation

The invitation page still reports `businessAccess: NOT_CONNECTED`. Trusted business principal binding, dedicated online-demo configuration, actual seeded PC/H5 scenario execution, attachment and Outbox/signed-sandbox reset, complete outbound controls, image scanning/reproducibility, hosted limits, TLS/DNS deployment and browser acceptance remain outstanding. Neither a synthetic reset marker nor a green startup receipt closes those requirements.
