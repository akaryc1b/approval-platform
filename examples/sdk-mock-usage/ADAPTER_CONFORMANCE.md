# Adapter conformance blueprint

This blueprint is deterministic and transport-free. It uses no URL, endpoint, token, credential, clock, sleep or scheduler.

A conforming adapter test should:

1. select an accepted compatibility profile before transport policy evaluation;
2. bind one `OperationPolicy` or `OperationTransportPolicy` to one SDK operation;
3. provide a finite scripted sequence of normalized adapter responses and virtual durations;
4. preserve the exact request, request ID, trace ID and idempotency key on every attempt;
5. assert attempt timeout, response category, scheduled delay and total virtual elapsed time;
6. verify retryable failures stop at the request budget or attempt limit;
7. verify unauthorized, conflict, expired, unsupported-version and permanent responses never retry;
8. verify no tenant, operator, permission, authority or audit evidence is accepted from policy or response metadata.

A real adapter, clock, scheduler and network implementation require a later acceptance gate.
