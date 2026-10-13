# Boot-private HttpClient direct OSV diagnostic

The actual official OSV API query covered all **472 package targets**: 471 Maven and one npm, including **457 zero-result targets** and 15 affected targets. The complete normalized inventory decreased **19 → 16** against accepted merged-main CI 1843. Exactly these build-plugin records were removed:

- `GHSA-hjcp-jmpx-g3qm` for `org.apache.httpcomponents.client5:httpclient5@5.5.2`
- `GHSA-hf6x-8p5f-cgmf` for `org.apache.httpcomponents.core5:httpcore5@5.3.6`
- `GHSA-v3jc-474w-2wm6` for `org.apache.httpcomponents.core5:httpcore5-h2@5.3.6`

Nothing was added or changed; all 16 retained normalized records are identical in every field.

Collection ran from **2026-10-12T14:15:35.817107+00:00** through **2026-10-12T14:16:11.284927+00:00**. One 46,922-byte batch POST and 10 unique advisory GET requests succeeded against the official `api.osv.dev` host, with no retries, redirects, cookies or application authentication. Submitted package data was limited to names, versions and ecosystems. Only returned advisory IDs were used for detail requests. All input positions, zero results, advisory mappings and full detail retrieval are represented in this evidence.

The exact request derives from the actual retained Boot candidate E2 and root POM, bound to the Boot HTTP transition manifest. The E2's commit SHA `88a7fb07a90fc1cef2b8185e88ed60aa1f83d2be` identifies its committed baseline; the query used the dirty precommit candidate inventory. It is not a query on a committed final candidate tree. The target delta replaces three tuples: HttpClient 5.5.2 → 5.6.3 and Core/Core-H2 5.3.6 → 5.4.3. All retained target references and scopes are unchanged.

This directory contains the complete target inventory, exact API request and scanner-helper-equivalent bytes, all 487 derivation rows, complete query results, normalized current and baseline findings, complete semantic comparison, redacted HTTP status/hash provenance, and exact modification timestamps. Raw advisory descriptions/details, HTTP headers, transport configuration/URLs, private filesystem paths, credentials and environment values are excluded. Raw API responses remain private. Public HTTP provenance uses only the official host, allowed endpoint and returned advisory ID. Hashes bind retained bytes; they do not independently authenticate a remote execution.

All 10 detail timestamps carry additional fractional digits and agree with the batch values at batch precision. Both exact strings are preserved. This does not prove representation-only differences, exact nanosecond equality, absence of a sub-microsecond change, or unchanged full advisory metadata. Baseline raw advisory bodies are unavailable, so comparison establishes equality only of the complete normalized records. Fixed versions retain the existing normalizer's union across matching affected-package entries; they are not an upgrade recommendation or a reachability conclusion.

This is a **direct API diagnostic**, not an OSV CLI invocation, canonical E4, completed security preflight, graph admission, or release approval. The normalized `E4_OSV_SCANNER` source class is retained only for format compatibility. No prior finding disposition is transferred. **Release remains blocked.** Final source/CI validation and explicit finding review remain necessary.
