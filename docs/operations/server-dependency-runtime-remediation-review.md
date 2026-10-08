# Server dependency finding lineage review

## Scope and retained history

This append-only change preserves the original E2/E3/E4 baselines, the OTel graph
transitions, the four historical I3 reviews, and the existing pgjdbc proof.
Graph admission and historical finding lineage are separate validations.

The server graph transition reverses only the reviewed 90 component versions,
180 edge endpoints, two new Jackson BOM imports and the Boot BOM version,
13 plugin-coordinate versions, and the plugin-output digest. Scope and license
values are unchanged. Its pinned archival source is `921d5ec5`; current scanner
evidence must independently bind the actual workflow head.

## Two precisely bounded historical identities

The new R4 plan covers only:

- `GHSA-8v8j-3hxp-93wr` / `CVE-2026-40976`, formerly on Spring Boot 4.0.2.
  The historical `NOT_APPLICABLE` review remains historical. The reviewed target
  is 4.0.8; the affected 4.0 branch is fixed in 4.0.6.
- `GHSA-r29c-68gh-xp6x` / `CVE-2026-41293`, formerly on embedded Tomcat 11.0.15.
  The historical `UNRESOLVED` review remains historical. The target is 11.0.26;
  the original 11.0-branch fix was 11.0.22. The later regression
  `CVE-2026-86350` affects 11.0.22–11.0.25 and is fixed in 11.0.26. Its absence
  is an additional guard, not an invented third historical closure.

The full OSV affected-package/range objects are retained and hash-pinned in
`docs/m6/m6-pr-e-e3-r4-server-advisories.json`. Version checks use the exact
reviewed Maven package and affected branch, not a flattened minimum across
different release branches. Primary vendor sources are
[Spring's advisory](https://spring.io/security/cve-2026-40976/) and
[Apache Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html).

## Fresh evidence required in the natural workflow

R4 requires a canonical, complete, redacted four-scanner E4 envelope, a clean
matching checkout, the independently verified current head, the exact original
current E2, its admitted graph receipt, pinned scanner identities, and complete
OSV target coverage. The live OSV invocation uses `--all-packages` without
changing its query dataset. Missing/extra/duplicate targets, filtering or error
diagnostics, contradictory counts, and package/ref/scope changes fail closed.

Only after all checks pass may the two old identities receive
`REMEDIATED_BY_FIXED_COMPONENT_AND_ABSENT_FROM_CURRENT_OSV`. Reappearance of
their upstream IDs or aliases anywhere in the current inventory, including a
plugin realm, rejects the proof. Their prior dispositions are recorded but
never transferred to any current finding. The receipt is revalidated when I3,
the Gitleaks append-only review, and I4 consume it. All current identities remain
in triage. Source and current E2/scanner receipts are bound to the current head;
the archival witness is never relabelled as a new scan.

## Local preparation and remaining blockers

The retained OSV-only preparation is explicitly partial, not E4 or disposition.
It queried exactly 535 package targets on `921d5ec5`: 70 package-advisory findings
across 42 targets, all in build-plugin scope. Seven findings remain on plugin
Jackson 3.1.5. Project Jackson 2.21.7/3.1.7 is a separate set of coordinates.
No current findings are removed or accepted by this change.

The following remain release-blocking or unproved:

- Fresh exact-head full E4 and all mandatory natural CI checks
- Docker-dependent PostgreSQL/Flowable and application-startup verification
- Ten local observability test errors caused by unavailable ByteBuddy VM
  self-attachment; the broader focused selection passed 47 other tests
- Seventy-eight components with unavailable license metadata
- Authoritative GitHub alert inventory
- Bundled binary contents beyond top-level Maven coordinates, including the
  loader's embedded jarmode tool and embedded third-party metadata
- Five Commons Logging duplicate classes, also present in the retained baseline

The corrected two-test dependency compatibility check passes. A build-only
install/repackage and actual packaged classloader/codec probe pass. These are
not a full test, database startup, scanner, deployment, or release acceptance.
No suppression, exception, severity downgrade, merge, or deployment is granted.
