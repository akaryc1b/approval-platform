# Grafana browser rehearsal

The existing native observability validation now also runs the shipped overview
and engine-job dashboards in Grafana OSS 13.2.2 and an installed Chromium.
This is a disposable loopback rehearsal, not a hosted monitoring service.

## What it executes

The existing digest-verified Prometheus binary is reused. One additional Grafana
OSS archive is downloaded from the official versioned URL, bounded to 512 MiB and
verified against the pinned SHA-256 before extraction. Grafana data, provisioning,
SQLite database and browser profile live only in one owned temporary directory.
No npm package, browser download, permanent workflow or production configuration
is added. The prior native-suite ceiling is unchanged.

The two repository JSON dashboards are copied unchanged and imported through
Grafana's **file provisioning**. This is not an import-editor UI test. Their
stored query lists must still contain exactly the original 25 and 11 expressions.
The browser logs in as a freshly created Viewer, checks all 25 overview panels,
follows the actual dashboard links in both directions, and verifies that datasource,
environment, instance and time range survive navigation.

A controlled HTTP metric source is scraped by real Prometheus. The unmodified
engine-job rules derive healthy sampling; no recording-rule output is fabricated.
A second healthy target deliberately shares the instance name in another environment.
The engine page must render 3/5/2/1, then unknown rather than zero when sampling
fails, then four genuine zeros after sampling recovers. The trend canvas and alert
panel are rendered. This does not wait for or retest the two-minute alert hold;
existing native rule/delivery tests cover their separate firing/recovery contracts.

Anonymous dashboard API access must return 401 and a Viewer dashboard-create request
must return 403. These are fixture checks of basic Grafana roles, not tenant-specific
Prometheus data authorization. A Viewer can query a datasource beyond the queries on
these dashboards; deployment permissions and networking remain operator responsibilities.

## Isolation and evidence

Grafana, Prometheus and the metric source listen only on 127.0.0.1. Chromium uses a
fresh profile and anonymous DevTools pipes, not a public debugging port. Browser
hostname resolution is disabled except loopback. Grafana analytics, update checks,
plugin preinstallation and public sign-up are disabled for the fixture. Generated
passwords are never included in receipts or page screenshots. Child processes receive
an explicit environment without GitHub, cloud or provider credentials.

The browser uses `--no-sandbox` only in this isolated CI fixture, consistent with
headless container restrictions. It must not be used to browse untrusted websites
or a production Grafana. Runtime and command waits are bounded; cleanup terminates
owned process groups and removes the temporary profile, database and listeners.
A timeout, launch failure, missing panel, wrong quantity, rejected navigation or
cleanup failure fails validation. No failed native run is converted into success.

A successful `OPS_GRAFANA_BROWSER_VERIFIED` receipt in the existing Hygiene log
includes source hashes, exact state observations, navigation/role results, cleanup
and four bounded JPEG screenshots with byte counts and hashes. Images cover a
1440x1000 viewport, not an assertion that every dashboard panel fits in that image.
Only controlled fixture data appears in them. Unit-test protocol fixtures never
print a native success receipt. Download/hash success alone does not prove browser
rendering; inspect the native run result.

## Local checks and limits

`node --test scripts/tests/ops-grafana-browser.test.mjs` exercises the source,
receipt, navigation, archive, process-cleanup and Chromium pipe helpers. Its visible
DOM test uses about:blank: it is deliberately not a local Grafana success claim.
The full native rehearsal executes through the existing pinned Prometheus provisioner
in GitHub CI. Local managed-browser policies can block loopback navigation; do not
disable administrative policy to turn that into a local native pass.

This does not cover the full approval database-to-dashboard chain, Grafana Cloud,
other browsers, browser accessibility, a real human recipient, production datasource
permissions or supported trace/log backends. Monitor recovery is not business recovery.

Official references:
- https://grafana.com/grafana/download/13.2.2?edition=oss
- https://grafana.com/docs/grafana/latest/administration/provisioning/
- https://grafana.com/docs/grafana/latest/administration/data-source-management/
- https://github.com/grafana/grafana/blob/v13.2.2/packages/grafana-e2e-selectors/src/selectors/components.ts
