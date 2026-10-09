# Bounded-memory archive hashing

The Prometheus/Alertmanager and Grafana archive verifiers now use one reusable
buffer of at most 1 MiB instead of reading the complete archive into a Buffer.
This is an independent reduction in the verifier process's temporary memory
requirement. It is **not evidence that Chromium startup I/O contention is fixed**;
any effect on that failure remains unproven.

## Preserved boundaries

- The exported verification functions remain synchronous and return only after
  comparing the complete SHA-256 with the existing expected/pinned digest.
- The existing 120 MiB Prometheus/Alertmanager and 512 MiB Grafana limits remain
  unchanged, as do the pinned URLs, digests, download and extraction commands.
- Empty files, non-regular files and final-component symlinks are rejected.
  Opening uses `O_NOFOLLOW` and `O_NONBLOCK` on the existing Linux CI platform;
  descriptor metadata must match the checked path before hashing begins.
- Each positive read advances by its actual byte count. Reads cannot consume
  more than the initially checked file size plus one byte for an EOF probe.
  Premature EOF, growth, invalid read counts and I/O errors fail closed without
  retry. Final descriptor/path checks also reject observed replacement or writes.
- Every successfully opened descriptor is closed in `finally`; close errors
  also prevent success. Verification does not make subsequent extraction an
  atomic filesystem snapshot, or eliminate every possible concurrent-write race.

There are no changes to browser launch order, arguments, timeouts, retries,
prewarming, temporary-directory placement, workflows, scanners or acceptance
criteria. This change is separate from the Maven Release plugin upgrade.

## Tests

```sh
node --test scripts/tests/ops-archive-digest.test.mjs
node --test scripts/tests/ops-prometheus-rule-runtime.test.mjs
node --test scripts/tests/m4-sla-calendar-boundary.test.mjs
```

The new tests use real files and SHA-256, plus an injected filesystem seam for
short/invalid reads, I/O failures and deterministic mutation races. They check
buffer reuse, both caller limits, digest mismatch, finite growth handling and
descriptor closure. The existing Prometheus test imports the new suite, so the
existing M4 aggregate runs it without a workflow change. Local runs retain the
pre-existing CI-only native provisioning skip and do not establish native CI
acceptance.

## Reproducing the isolated memory measurement

```sh
node scripts/benchmarks/archive-digest-memory-benchmark.mjs
```

The benchmark writes deterministic, nonsparse files at both archive size limits
using a small buffer. Each sample hashes the same bytes in a fresh Node process,
either using the previous whole-file strategy or the new helper, and verifies
the digest. Three sequential trials alternate strategy order. Fixture creation
is outside the measured child; a GC occurs before each sample, not between
hashing and sampling. Output includes every sample, ArrayBuffer allocation
increase, process peak RSS and hash duration. No browser or download is invoked.
For a smaller smoke run, use `--sizes-mib 2 --trials 1`.

Local Linux x64 / Node v24.19.0 measurement (2026-10-09), median of three trials:

| File size | Whole-file ArrayBuffer increase | Bounded increase | Whole-file peak RSS | Bounded peak RSS |
| --- | --- | --- | --- | --- |
| 120 MiB | 120 MiB | 1 MiB | 152.625 MiB | 33.625 MiB |
| 512 MiB | 512 MiB | 1 MiB | 544.500 MiB | 33.875 MiB |

These are synthetic archive-sized inputs, not downloaded upstream archives.
Peak RSS includes the Node runtime; filesystem cache is not isolated or flushed.
Hash durations are reported for transparency, not a performance guarantee.
This establishes the isolated hashing-memory benefit, not an end-to-end CI,
disk-I/O, browser-startup or production-capacity improvement.
