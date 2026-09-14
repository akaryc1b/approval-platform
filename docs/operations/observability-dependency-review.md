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
