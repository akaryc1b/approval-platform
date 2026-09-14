# Executable Prometheus rule checks

The existing `ops-observability-boundary.test.mjs` entry now imports the rule-runtime
suite. In GitHub CI this provisions **promtool 3.13.3**, checks its upstream archive
SHA-256 before extraction, checks the executable version, parses the actual rule file,
and evaluates both the original fixtures and the additional regression matrix.
The permanent workflow, its permissions and artifact paths are unchanged. Output is
retained in the existing M4 SLA/calendar log inside the Hygiene artifact.

The matrix contains fourteen groups and forty-eight assertions for nine alerts:
existing availability, HTTP error ratio/P95, SLA overdue/dead/retry and Connector
failure/timeout signals, plus complete scrape-target disappearance. Cases cover
pending periods, firing, rolling-window recovery, low/no traffic, unrelated HTTP
jobs and counter reset without a new failure. The regression file uses JSON syntax,
which is valid YAML. It retains exact expected labels and annotations.

Run from a repository checkout with Linux x64, curl and tar:

```sh
node scripts/ops/verify-prometheus-rules.mjs
```

For an already-installed **3.13.3** tool, including on other supported platforms:

```sh
node scripts/ops/verify-prometheus-rules.mjs --tool /absolute/path/to/promtool
```

An explicitly provided local tool is operator-trusted; the CI path always downloads
and verifies the pinned archive and cannot select another tool through an environment
variable. No cloud, GitHub or Provider credentials are passed to downloaded tools.
Download, parser, evaluation, timeout and checksum failures fail the check, with no
automatic retries or successful fallback. Temporary files are deleted on exit.

`OPS_PROMETHEUS_RULES_VERIFIED` means that the rule parser and evaluation engine passed
these fixtures. It does **not** prove live scraping, business-event instrumentation,
Alertmanager/webhook delivery, notification deduplication, trace propagation or
telemetry-backend failure isolation. Local Node tests exercise runner orchestration
with fixtures and explicitly skip the real download/engine test outside CI; they
must not be reported as actual promtool execution.

## ApprovalPlatformScrapeMissing

**Meaning:** no `up` series for `job="approval-platform"` has been present for more
than two minutes. Unlike `ApprovalPlatformDown`, this covers missing discovery or
removed scrape configuration, not an existing target returning `up=0`.

Check Prometheus configuration, target discovery, relabel rules and recent deployment
changes. Restore the private management scrape target without opening management
endpoints to public ingress or editing business/Flowable tables. A restored target
clears this alert; a target that still fails scraping is handled by
`ApprovalPlatformDown`. Absence of one replica while other replicas remain is not
covered: that requires a declared replica inventory rather than inventing targets.

**Resolution:** target discovery is present again and scrapes succeed. Separately
verify application health; disappearing telemetry is not proof that approvals failed.

## Interpretation and remaining work

An `increase(...[10m])` signal clears when its events leave the observation window.
That is not proof a dead letter was repaired, an overdue task finished or a business
notification was delivered. Incident closure still follows the existing alert
runbook and durable business evidence.

The inherited PR #147 security failure on `07cce83` is independently outstanding:
Run `33852990045` rejected an E2 dependency-graph change after the Prometheus/OTLP
dependencies were added. This change does not alter historical graph hashes, remove
dependencies, suppress findings or weaken the scanner gate. Overall CI and production
observability must not be described as complete on the strength of rule tests alone.

Upstream references:

- https://prometheus.io/download/ (3.13.3 Linux amd64 archive and SHA-256)
- https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/
