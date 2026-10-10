# Compiler candidate OSV diagnostic

The actual official OSV API query covered all **473 package targets**, including
453 targets with no returned advisory and 20 affected targets. Full normalized
findings decreased **23 to 22** against accepted natural-main CI 1836. Exactly
`GHSA-78wr-2p64-hpwj` (`CVE-2024-47554`) for `commons-io:commons-io@2.11.0` was
removed. No finding was added and all 22 retained normalized records are identical
in every field. Both complete normalized inventories and the full comparison are
included; no historical disposition is transferred.

The query ran from **2026-10-10T03:53:51.901168Z through
2026-10-10T03:57:11.794716Z**, against the original capture-2 precommit POM inventory
based on public main `0c55ff5e7d974022e5c76d9a88a4312d2af81827`. It was a direct
official API diagnostic: one 47,016-byte POST to `https://api.osv.dev/v1/querybatch`
and 15 unique advisory GET requests, all successful. Only package name/version/
ecosystem tuples were sent. No scanner CLI, canonical E4 or other scanner ran as
part of this diagnostic.

The first validation attempt stopped because detail timestamps carry more
fractional digits than querybatch. Its incomplete receipt is retained privately.
A reviewed continuation retrieved the remaining details without repeating the
inventory query or successful detail requests. All 15 timestamp pairs agree at
querybatch's six fractional digits; this does not establish exact nanosecond
equality or exclude a change within the unreported sub-microsecond interval.
The exact comparisons are included in `timestamp-precision-comparison.json`.

Independent review reconstructed all targets, ordering, refs, scopes, query bytes,
pagination, advisory attribution, zero-finding coverage and every normalized
field. Capture 3 at local commit `b94b8c08c27dc9f43de4aba3699b2559df420c5f` has the
same complete graph, helper bytes, package/ref/scope inventory and exact query
input. This permits reuse of the original diagnostic for that identical input;
it does not change the query time or claim the query ran on capture 3. Local
capture commit/tree values are archival provenance. CI checks current source and
input bytes and does not require these private local Git objects in public history.

Accepted CI 1836 retained normalized findings but no raw advisory bodies. The
comparison therefore proves equality of all retained normalized fields, not raw
database descriptions, timestamps or ranges. Fixed versions retain the existing
normalizer's union across matching affected-package entries; they are not by
themselves an upgrade recommendation or a reachability conclusion. The normalized
records keep `sourceClass: E4_OSV_SCANNER` for format compatibility, while every
execution/provenance wrapper explicitly identifies direct API collection.

Raw advisory bodies, HTTP headers and private transport configuration remain
outside this package. Redacted HTTP provenance retains official destinations,
methods, times, statuses and request/response body hashes. There are no credentials,
personal data or exploit details here. `releaseBlocked` remains true; final full
scanner/CI evidence and explicit review remain necessary.

Files include the original summary, complete target coverage, normalized current
and baseline findings, full comparison, exact request and helper-equivalent input,
all source derivation rows, query target results, timestamp precision comparison,
redacted HTTP provenance, both independent review receipts, source/input binding,
and checksums for every public file. The original `query-plan.json` retains its
historical `PREPARED_NOT_QUERIED` state; completion is recorded separately in
`execution.complete.json` and `verified-osv-summary.json`.
