# Adapter conformance blueprint

This blueprint is deterministic and transport-free. It uses no URL, endpoint address, token, usable credential, clock, sleep or scheduler.

A conforming adapter test should:

1. select an accepted compatibility profile;
2. bind one transport policy to one SDK operation;
3. select one server-owned logical endpoint descriptor;
4. resolve a server authentication context and reference-only credential lease;
5. open the adapter before exchange and close it after completion;
6. provide a finite sequence of normalized responses and virtual durations;
7. preserve exact request, correlation, endpoint, context and lease identity across attempts;
8. assert timeout, category, delay, elapsed time, lifecycle and lease release;
9. verify terminal responses never retry;
10. verify no trusted authority evidence is accepted from public or adapter metadata.

A real endpoint address, credential executor, clock, scheduler and network implementation require a later acceptance gate.
