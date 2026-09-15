# Observability dependency graph transition

This is structural dependency review for PR #147, not vulnerability clearance,
production readiness, or closure of Issue #146.

## Evidence and exact delta

The [transition manifest](observability-dependency-transition.json) records two
retained CI SBOMs and their independently checked artifact ZIP digests:

- Base: `ace5a07b305a0b40777f5b9bcce8e81db4d6beb5`, run `33702077663`.
- Observed: `22c3b41359d8813daac9e798b1d84f09059ae19e`, run `34802810322`.

The base graph is `2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94`.
The observed graph is `27bdcae01a4affff009d6b90ca04989bd14a3cb159bbef6a6a379fab84109a37`.

Two direct additions in `apps/server/pom.xml` explain the expanded runtime:
`spring-boot-starter-opentelemetry:4.0.2` and
`micrometer-registry-prometheus:1.16.2`. The Spring-maintained starter includes
both tracing and Micrometer OTLP metric export; the latter is not an unrelated
application dependency. See [Spring's starter description](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot).

The complete observed difference is **35 added components, 39 added edges, zero
removed components/edges, and one scope change**:
`org.jetbrains:annotations:17.0.0`, `test` to `runtime`. That scope change is
retained explicitly, not discarded as metadata. Existing component versions,
imported BOMs, resolved build-plugin coordinates and plugin digest, reactor
roots, pnpm graph, accepted Actions graph and limitation statements are unchanged.
The precise coordinates and edges are in the manifest; no package-name prefix or
wildcard is used. Missing license metadata in a source SBOM is not license approval.

## Admission and downstream review

`observability-dependency-graph.mjs` checks the E2 content digest, binds the graph
projection to that E2 document, and accepts only the exact base or observed graph.
For the observed graph it verifies the pinned manifest bytes, reverses only its
declared additions/scope change, and requires the entire reconstructed graph to
hash to the original accepted baseline. Replacing just the target hash cannot
hide an unrelated version, BOM, plugin, pnpm or Actions change.

The historical E4 scanner baseline and pgjdbc remediation plan are unchanged.
E4 retains the **actual current graph digest** and adds a separate, head- and
SBOM-bound lineage receipt. The pgjdbc verifier consumes that receipt only for
this precise extension, still rejects either remediated finding reappearing by
ID, upstream ID or alias, and emits V2 evidence linking both graph generations.
The original graph continues to produce its original V1 evidence shape.

The scanner runs OSV, Gitleaks, zizmor and Semgrep on the full current inputs.
It does not remove the newly added components from OSV input, suppress findings,
change scanner versions/rules, replace historical finding dispositions, or
create an additional workflow. Normalized E4 output is retained before downstream
triage so that a later rejected review cannot hide the current findings.

**Graph admission is not finding acceptance.** Newly discovered findings remain
UNRESOLVED through the existing intake unless separately reviewed. The lineage,
E4 and remediation evidence all retain their release-blocked semantics. Existing
scanner identity and workflow-supply-chain checks still apply; any later failure
must be investigated rather than waived to make this transition green.

## Reproduction

Run `node --test scripts/tests/ops-observability-graph-transition.test.mjs` for
deterministic delta and lineage rejection tests. The existing permanent E4 test
imports this suite and runs the real current-graph scanner in CI. Local replay of
the two retained SBOMs proves graph reconstruction, not a new Maven resolution,
a new vulnerability scan, or the absence of current vulnerabilities.

## OTel 1.62.0 follow-on graph

The [upgrade manifest](observability-otel-upgrade.json) retains independently
checked Hygiene artifacts from run `34805063807` (the first observability graph)
and run `34816893697` (Head `a4f627c4232d76da7bd00c3a3443cf691b3f2c7a`). The latter
run failed the BOM-order assertion and graph admission, but its actual E2 payload
was retained and its canonical digest was verified. It is not a passing scanner
or acceptance baseline.

This second delta changes **16 component versions and 17 edge coordinates** and
adds the explicit OTel BOM. Fourteen `io.opentelemetry` components move from
1.55.0 to 1.62.0; `okhttp-jvm` moves from 5.2.1 to 5.3.2, and `okio-jvm` from
3.16.1 to 3.16.4. All 237 component identities excluding version and all 345
logical edges are retained. Component scopes/licenses, build plugins, reactor,
pnpm, accepted Actions and limitations are unchanged. No arbitrary version
range or group-prefix admission is introduced.

For this exact graph the verifier reconstructs the prior observability graph,
then applies the unchanged foundation reversal back to the accepted baseline.
Only cloned evidence is reversed: OSV still receives all **current** components.
V2 graph lineage binds both manifest hashes, all three graph generations, the
current commit and current E2 content digest. Original V1 lineage and historical
pgjdbc evidence remain valid; finding IDs, upstream IDs and aliases must still
be absent from an actually completed scan before that separate proof can pass.

The BOM assertion now matches the generator's observed order: Flowable, OTel,
Spring Boot, Testcontainers. Counts, exact coordinates, versions and scopes stay
strict, and canonical output is still printed before later assertions.

Run `node --test scripts/tests/ops-observability-otel-graph.test.mjs` for the
53 deterministic rejection, lineage and assertion-callback checks. The existing
E2 test imports this suite. These tests use synthetic graphs and scanner replies;
separate offline replay of the retained real E2 documents verifies the actual
reconstruction, not a fresh resolution or scan. The complete E4 scanner and
subsequent triage chain must run on the new commit before reporting a CI result.

The upstream baggage advisory `GHSA-rcgg-9c38-7xpx` names 1.62.0 as patched.
Version alignment and graph admission do not automatically clear either finding,
other scanner findings, Issue #146, or the release block.
