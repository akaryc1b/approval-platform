# Chromium startup diagnostics

## Why this exists

Natural CI #1813 (`37749940449`, head
`52fc2b03895784ad139acf977c77a7b904b5fdf1`) passed eight jobs. Repository hygiene
stopped at two existing `Browser.getVersion` timeouts before the scanner stage:

- The integrated Grafana browser had spawned, with zero stderr bytes and zero
  protocol bytes/responses.
- The separate real pipe test had spawned, with 573 stderr bytes classified as
  D-Bus messages and zero protocol bytes/responses.
- A later isolated CJK-font browser probe in that same job passed in 3.216 seconds.

These observations establish failed startup handshakes, not the cause. D-Bus
messages do not establish a D-Bus fault. Maven/JDBC and four runtime envelope
audits passed, but the run did not reach E4 and was not fully accepted.

The runner images differed. Green #1812 used image `20261004.327.1` and
provisioner `20261002.596`; #1813 used the older image `20260927.320.1` and
provisioner `20260901.588`. Both reported Ubuntu 24.04.5, runner 2.337.0 and westus.
The actual #1812 browser receipt reports `Chrome/154.0.8037.97`. The older image
manifest lists `154.0.8037.57`, but #1813 did not retain its actual browser version.
Earlier green #1809/#1810 receipts used `.57`. The image/version difference alone
therefore does not prove a defect.

## Preserved execution behavior

This change adds observations only. It retains the original executable selection,
Chromium arguments, environment, profile isolation, one launch, 10,000 ms command
timer, TERM/KILL cleanup and 1,500 ms grace periods. Browser, screenshot, native
rule and cleanup assertions remain mandatory. No retry, timeout increase,
security setting, downloaded browser, fixed runner image or suppression is added.
A later green run does not by itself prove the intermittent problem resolved.

## Diagnostic receipt

The driver emits one `GRAFANA_BROWSER_DIAGNOSTICS=` JSON line per owned browser
when cleanup finishes. Its separate stderr line survives the integrated runtime's
existing sanitized error wrapper and bounded stderr tail. The original error
continues to fail the test.

The bounded fields provide:

- Monotonic elapsed times for launch, spawn, first queued write, write callback,
  first stderr/protocol data, successful handshake, failure, exit and cleanup
- Elapsed command time and timer drift, parent event-loop active/idle time, and
  the measured duration of each resource snapshot
- A launch and pre-cleanup failure snapshot of the owned child: process-state
  enum, RSS, threads, CPU ticks and scheduler CPU/wait counters
- Allowlisted host pressure and unified-cgroup CPU, memory and PID-limit counters,
  where the kernel exposes them
- Allowlisted exit code/signal, TERM/KILL outcome and observed cleanup completion
- The successful CDP handshake's bounded numeric Chrome/HeadlessChrome version

CPU ticks in `/proc/PID/stat` aggregate that process's threads. The scheduler
counters in `/proc/PID/schedstat` describe only the main task. Neither includes
the full Chromium child-process tree. Cgroup counters may include other members. Event-loop utilization
is not a measurement of maximum loop lag. The version field exists only after a
successful handshake and does not identify an executable wrapper or native binary.

Comparing the two resource snapshots can distinguish CPU execution, scheduler
waiting, throttling or pressure growth. Queued-write versus callback timing helps
separate pipe completion from a browser response; it does not prove the browser
read the message. Timer drift and event-loop activity help distinguish a late
parent callback from a native child that never responded. None is a standalone
causal verdict. Missing fields are unavailable evidence, not zero usage.

Reads are limited to fixed-size virtual kernel files for the owned child and
host/cgroup counters. They occur at launch and first failure, without polling.
They are byte/iteration-bounded synchronous reads, not a hard real-time OS
latency guarantee. Diagnostic failures are ignored and do not replace the
original browser or cleanup failure.

No raw stderr, page content, environment, command line, filesystem path or process
ID is emitted. Unrecognized versions, process states, signals, malformed files,
oversized input or unsupported proc/cgroup layouts produce omitted/null diagnostic
data. A strict output-size cap preserves the existing bounded-error channel.

## Validation boundary

Adversarial tests cover malformed and unavailable virtual files, path traversal,
byte bounds, private text, delayed write callbacks, timer drift, cleanup outcomes,
wrapped errors and reporter failures. Existing browser transport tests continue
to apply. A local cloud Chromium failure with permission/socket restrictions is
a different failure mode from the GitHub startup timeout and is not a reproduction.
Natural CI must still pass the complete existing browser and scanner gates.

## Native wait snapshot

CI #1815 narrowed the next question. Its parent loop was mostly idle, the write
callback completed promptly, and the 10-second timer drift was approximately
1 ms. Shared I/O-pressure counters increased during the wait, but those counters
did not identify a Chrome task or a particular file. No I/O cause or browser fix
is claimed.

The first failure now records a separate native snapshot before cleanup. It reads
only the owned browser process, at most 64 task records and 65 directory entries.
Each task's stat and wait-channel reads have byte caps; enumeration uses bounded
directory iteration rather than listing an arbitrary directory before slicing.
`nativeSampleMs` records the synchronous sampler duration. No periodic sampler,
extra launch, warmup, flag change or timeout change is introduced.

The snapshot contains only fixed categories and numbers:

- Process minor/major faults, read/write byte counters, and a known executable
  path category. `other` does not identify or authenticate an executable.
- Thread-state and coarse wait-site histograms, a scanned/read count, and
  complete/partial/unavailable coverage. `ioOrPageWait`, `D`, a socket wait or
  a futex wait is a clue about the observed state, not proof of a storage,
  network, font or lock fault. Unresolved/denied wait-channel zero is unknown.
  Complete coverage means the bounded enumeration and reads succeeded; these
  sequential reads are not an atomic process-wide snapshot.
- A combined DevTools pipe-name count. Chromium 154.0.8037.97 gives its two pipe
  threads different long names, but Linux truncates both to `DevToolsPipeHan`.
  The detector cannot distinguish reader from writer. The existing successful
  CJK probe observes its already-running browser; it records `verified` only
  with complete name coverage and at least two matches, otherwise `unknown`.
  This observation is not an added readiness gate. A later verified process
  cannot prove naming succeeded in an earlier failed process.
- Current task-delay-accounting availability and the sum of readable completed
  per-task block-I/O-delay ticks. Current enablement does not prove historical
  coverage. A still-running I/O wait may not yet appear in completed counters;
  missing or zero values cannot exclude it. Exited or unscanned tasks are not
  covered. Process I/O counters can include waited-for children and are not a
  complete or exclusive measurement of the current child-process tree.
- Profile filesystem category, available bytes and free inodes. If the profile
  does not exist yet, its immediate parent is examined and explicitly labeled
  `scope: parent` / `profileState: missing`. Denied or unavailable metadata is
  marked accordingly. No filesystem path is emitted.

Filesystem metadata calls are count-bounded synchronous calls, not hard
wall-clock-bounded system calls; pathological OS/filesystem latency can delay
diagnostic completion. The duration is retained rather than hidden. The
3,000-byte JSON budget is preserved. Optional I/O-window details are omitted
before any existing fields. If the original receipt itself exceeds that budget,
shared resource details are explicitly omitted first, preserving native,
lifecycle, original-failure and cleanup information. A further size fallback
explicitly omits native details too; a non-serializable native extension also
falls back to the original receipt with an explicit omission. A failure-only
snapshot can miss a wait that ended before the deadline. The retained real #1815 timeout payload
plus a fully populated native snapshot is tested against the complete existing
4,000-character wrapped stderr tail.

Field semantics and marker names are checked against primary sources:

- [Chromium 154.0.8037.97 DevTools pipe implementation](https://raw.githubusercontent.com/chromium/chromium/154.0.8037.97/content/browser/devtools/devtools_pipe_handler.cc)
- [Linux process/thread stat implementation](https://raw.githubusercontent.com/torvalds/linux/v6.17/fs/proc/array.c)
- [Linux proc filesystem fields and I/O counters](https://docs.kernel.org/filesystems/proc.html)
- [Linux delay-accounting limitations](https://docs.kernel.org/accounting/delay-accounting.html)
- [Linux filesystem magic constants](https://raw.githubusercontent.com/torvalds/linux/v6.17/include/uapi/linux/magic.h)

## Anonymous cgroup/device I/O window

CI #1818 recorded 961 major faults, 132,087,808 process read bytes and about
6.674 seconds of added cgroup full-I/O pressure during the failed handshake.
That is evidence of correlated native I/O delay, not a faulting filename or a
proven browser remedy. The optional `ioWindow` adds a narrow comparison at the
same launch and first-failure sample points. The integrated rehearsal now also
retains a separate handshake window on success, as described below. No launch,
probe, retry, deadline, cleanup or acceptance condition changes.

The existing validated cgroup membership supplies a private location under the
conventional `/sys/fs/cgroup` mount. Each phase reads its `io.stat` and the fixed
`/proc/diskstats`, at most 8,193 bytes each including the overflow sentinel.
Across both phases this adds four logical reads and at most 32,772 bytes, without
another membership read. Each table permits 64 nonempty rows, 1,024 bytes and
32 tokens per row; the union of group device keys is also capped at 64. Oversized,
duplicate-key, non-LF-terminated, or unsupported tables are unknown, not successful
prefix samples. Diskstats supports the 11-, 15-, and 17-counter Linux layouts.
Existing resource-sample durations include this added work. These are bounded
bytes/iterations, not hard wall-clock limits: reading cgroup counters can flush
kernel accounting. No polling, device traversal or additional privileges are used.

The summary contains only fixed enums and nullable finite numbers:

- `devices` counts distinct observed group keys; `paired` counts usable matching
  group rows with monotonic counters. Neither counts physical disks.
- `selected: largest-read-delta` chooses one paired group row with the largest
  positive read-byte delta, using a stable private key tie-break. Selection happens
  before checking device availability. There is no write-only fallback, device
  summation, inferred zero baseline, or substitution of a smaller readable device.
- `cgroup` reports that row's read/write bytes and operations; `device` reports
  matching device read/write bytes and completed operations, active-I/O and weighted
  milliseconds, and final `inFlight`. All are deltas except the in-flight gauge.
  Disk sectors use 512 bytes. Counter decreases, unsafe arithmetic, missing matches
  or a changed private device name make affected deltas unknown.
- `spanMs` measures the difference between sampling starts; unavailable,
  non-increasing or over-one-day clocks invalidate the window. Reads are sequential,
  not atomic. `scope: visible-root` or `visible-nested` describes only the membership
  path shape; `unknown` covers unavailable or changed membership.
- `coverage: complete` means the bounded parsed inputs and paired counters were
  usable, not complete system visibility. `partial` retains a usable selected row
  if possible when other rows or matching device counters are unavailable.
  `unavailable` gives no group delta. `no-activity` means all paired group counters
  were unchanged (or both group tables empty), not that no I/O wait occurred.
  A complete write-only window has no selected row. Missing/null values are never
  zero-traffic evidence.

The mount/namespace mapping is not independently discovered or authenticated.
A visible root can be a namespace root, and even identical membership/device
keys and names cannot prove cgroup or hardware continuity. No alternate mounts
are searched after a failure. Private paths, device keys/names and baseline maps
never enter the receipt and are discarded after comparison or cleanup.

Accounting scopes differ. Linux's non-root cgroup accounting records submitted
bios and can include descendants and unrelated group members; actual root
accounting may instead derive from whole-device statistics. Diskstats counts
completed device requests, potentially merged from multiple bios. Thus device
minus cgroup traffic is not an estimate of traffic from other workloads, and
operation counts are not interchangeable. Weighted time may exceed elapsed time;
active-I/O time can undercount concurrency. Partitions, stacked/virtual devices,
sampling skew, queued work and hidden host contention limit interpretation. These
counters cannot identify a faulting page/file, isolate Chrome, or prove causality.

The unchanged 3,000-byte JSON budget tries the new summary last in priority:
if it does not fit or serialization fails, it is removed before any existing
PSI/native/lifecycle fields. A fixed `ioOmitted` reason is included only if it
also fits. Only an already-oversized original receipt uses the older fallbacks.
Tests combine the saved #1818 receipt with maximal new counters and exercise
exact-budget fallback. Existing native browser/scanner acceptance remains required;
this instrumentation and local mocked tests are not a fix or acceptance evidence.

Sources for these conservative semantics (not an assertion of #1818's kernel):

- [Linux cgroup v2 I/O interface](https://docs.kernel.org/admin-guide/cgroup-v2.html#io-interface-files)
- [Linux v6.17 block-cgroup accounting](https://github.com/torvalds/linux/blob/v6.17/block/blk-cgroup.c)
- [Linux I/O-statistics fields and limitations](https://docs.kernel.org/admin-guide/iostats.html)
- [Linux block-statistics units](https://docs.kernel.org/block/stat.html)

## First integrated rehearsal: retained success and service context

Natural CI #1832 failed the unchanged 10-second `Browser.getVersion` command.
Its first protocol-data marker at 10,865.694 ms was recorded after failure; those
bytes were discarded without parsing. This is not evidence of a completed late
handshake. Later component successes do not complete that failed rehearsal.
Previously, successful full-rehearsal results dropped their startup stderr
records, preventing a like-for-like comparison with a failed first launch.

Only the integrated runtime enables the new optional evidence capture. Immediately
before its one browser construction, it samples the two already-owned Prometheus
and Grafana leader handles. It reads stat/io/stat for each, and host/group dirty
and writeback gauges. A single bounded current-runtime cgroup membership read
supplies the private conventional mount location. The browser's existing resource
reads must report that same location at launch and at the endpoint; there is no
additional post-spawn/pre-command-timer I/O or alternate mount discovery.

The companion closes on the first handshake or failure. A successful handshake
is marked before sampling, after its command timer was cleared. Later failure
does not resample or relabel the completed companion. Service identity uses the
owned handle's live state, matching private PID and exact field-22 start-time
tokens within each stat/io/stat bracket and across endpoints. Unknown layouts,
missing/exited/duplicate handles, changed identities, counter decreases and
unsafe sums produce explicit coverage and null totals. Both leaders must pair
before aggregate read/write bytes are reported. No descendants are discovered.

The separate `GRAFANA_BROWSER_STARTUP_IO=` record is at most 512 JSON bytes:

- `end`, `spanMs`, `leadMs` and `sampleMs` identify the first endpoint, its span
  from baseline start, the baseline-start lead to browser origin, and both sampler
  durations. They do not pretend that the prelaunch baseline equals browser launch.
- `services` contains expected/paired counts, coverage and aggregate accounted
  read/write-byte deltas. It does not distinguish the two services individually.
- Host and cgroup `dirtyBytes`/`writebackBytes` are `[baseline, endpoint]` gauges.
  Decreases are valid. They are never subtracted or described as completed traffic.
- Missing, denied, malformed, duplicate, oversize or truncated input stays null.
  Capture/serialization/size failures produce a small fixed unavailable record.

The companion adds at most 17 logical reads / 40,977 bytes including overflow
sentinels across both endpoints. Each service bracket has 2,048/1,024/2,048-byte
caps; gauge and baseline membership reads have 4,096-byte caps. The existing
bounded reader, fixed paths, parser row bounds and private identity guards apply.
Virtual-file latency is not hard wall-clock bounded; durations are reported.

After the first successful integrated handshake, a separate resource snapshot
retains the existing owned CPU/RSS/scheduler fields, full PSI, and a non-consuming
group/device I/O comparison against the launch baseline. This adds at most 18
logical reads / 48,658 bytes and records its duration. `nativeSampling:
not-requested` is explicit: it does not add native leader I/O reads, thread
enumeration, statfs or readlink. Any later failure still uses the original launch
baseline and retains every original failure/native/PSI field and assertion.

After cleanup, a guarded typed callback adds `startupEvidence` to the existing
successful result: the canonical finalized diagnostic, handshake endpoint and
companion. It never parses or forwards arbitrary stderr. The callback receives
detached numeric/enum-allowlisted data. The new handshake endpoint is capped at
2,048 bytes and the bundle at 6,000; unavailable additions do not evict existing
diagnostics or change acceptance. The original diagnostic remains at 3,000 bytes,
the error tail at 4,000 characters, and screenshot/output ceilings are unchanged.
The two stderr records remain adjacent, leaving room for the original failure.

These are accounting observations, not a browser cure. Linux process read bytes
describe storage accounting, while write bytes are charged when pages become
dirty and may later be cancelled. Leader counters include live threads and may
include reaped children, not a complete live descendant tree. Start-time tokens
are clock-tick/time-namespace based and do not authenticate executables or detect
exec. Host/group gauges are shared, non-atomic endpoints and cannot identify
which file or earlier operation created writeback. No service/group/device
subtraction, outside-workload attribution or causality claim is made. Full native
rehearsal, screenshot, CJK, access-control, cleanup and scanner gates still apply.

Primary semantics:
[Linux proc accounting and memory gauges](https://docs.kernel.org/filesystems/proc.html),
[process I/O implementation](https://raw.githubusercontent.com/torvalds/linux/v6.17/fs/proc/base.c),
[reaped-child accounting](https://raw.githubusercontent.com/torvalds/linux/v6.17/kernel/exit.c),
[cgroup memory gauges](https://docs.kernel.org/admin-guide/cgroup-v2.html), and
[process start-time fields](https://raw.githubusercontent.com/torvalds/linux/v6.17/fs/proc/array.c).
