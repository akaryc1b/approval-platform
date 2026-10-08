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

CPU and scheduler counters describe the owned leader, not the full Chromium
process tree. Cgroup counters may include other members. Event-loop utilization
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
