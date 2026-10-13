# Boot-private Plexus Utils and Commons Lang3 direct OSV diagnostic

The actual official OSV API query covered all **471 package targets**: 470 Maven and one npm, including **458 zero-result targets** and 13 affected targets. The complete normalized inventory decreased **16 → 14** against accepted merged-main CI 1846. Exactly these build-plugin records were removed:

- `GHSA-6fmv-xxpf-w3cw` for `org.codehaus.plexus:plexus-utils@3.5.1`
- `GHSA-j288-q9x7-2f5v` for `org.apache.commons:commons-lang3@3.16.0`

Nothing was added or changed; all 14 retained normalized records are identical in every field. Records for other Plexus Utils and Commons Lang3 versions remain; these advisory IDs are not globally eliminated.

Collection ran from **2026-10-13T10:23:42.398575+00:00** through **2026-10-13T10:24:14.207770+00:00**. One 46,827-byte batch POST and 10 unique advisory GET requests succeeded against the official `api.osv.dev` host, with no retries, redirects, cookies or application authentication. Submitted package data was limited to names, versions and ecosystems. Only returned advisory IDs were used for detail requests. All input positions, zero results, advisory mappings and full detail retrieval are represented.

The exact request derives from the actual retained Boot utilities candidate E2 and root POM, bound to the Boot utilities transition manifest. E2 commit SHA `d38f57a82b13058d0e09839bdfb9a2ddf99d4508` identifies the committed baseline; the query used the dirty precommit candidate inventory. It is not a query on a committed final candidate tree. The target delta removes Lang3 3.16.0 and Plexus Utils 3.5.1, adds Plexus Utils 3.6.1, and retains the already-present Lang3 3.18.0 target exactly once. There are 471 targets versus 472 in the baseline. All retained target references and scopes are unchanged.

This directory contains the complete target inventory, exact API request and scanner-helper-equivalent bytes, all 486 derivation rows, complete query results, normalized current and baseline findings, complete semantic comparison, redacted HTTP status/hash provenance, and exact modification timestamps. Raw advisory descriptions/details, HTTP headers, transport configuration/URLs, private filesystem paths, credentials and environment values are excluded. Raw API responses remain private. Public HTTP provenance uses only the official host, allowed endpoint and returned advisory ID. Hashes bind retained bytes; they do not independently authenticate remote execution.

All 10 detail timestamps carry additional fractional digits and agree with batch values at batch precision. Both exact strings are preserved. This does not prove representation-only differences, exact nanosecond equality, absence of sub-microsecond change, or unchanged full advisory metadata. Baseline raw advisory bodies are unavailable, so comparison establishes equality only of complete normalized records. Fixed versions retain the existing normalizer's union across matching affected-package entries; they are not upgrade recommendations or reachability conclusions.

This is a **direct API diagnostic**, not an OSV CLI invocation, canonical E4, completed security preflight, graph admission, or release approval. The normalized `E4_OSV_SCANNER` source class is retained only for format compatibility. No prior finding disposition is transferred. **Release remains blocked.** Final source/CI validation and explicit finding review remain necessary.
