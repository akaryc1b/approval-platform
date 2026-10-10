# Clean candidate OSV diagnostic: review copy

This is a publication-safe review package prepared locally. It has not been published.

The actual captured uncommitted Clean candidate has complete official OSV coverage for 474/474 packages, including 453 with zero findings. Compared with accepted main1834 E4, normalized OSV findings decreased from 25 to 23. Exactly GHSA-78wr-2p64-hpwj and GHSA-gwrp-pvrq-jmwv for commons-io:commons-io@2.6 disappeared. All 23 retained normalized findings are identical in every field; there are no additions or changed retained findings.

The source base commit field is 757fe355b2d868916e112f2fcbbe093b865e0669. The actual POM was uncommitted. This remains standalone precommit OSV-only diagnostic evidence. It does not establish exact-head E4, graph admission, finding disposition, other scanner results, natural CI success or release clearance.

The genuine retained OSV-Scanner 2.5.0 binary, source origin, module h1 checksums and all retained source files were independently checked before querying the official default OSV API. The package input, raw report and repository validation code are bound by hashes in these files. Independent reconstruction checked all normalized fields, full input coverage and rejection of invalid evidence.

Two direct-network attempts failed before a valid query result was obtained. Their outputs are preserved locally as invalid scan evidence. The selected valid query used the environment's supported transport without changing TLS verification, destination, system settings or credentials. Raw advisory bodies, execution logs and private transport details remain outside this package. No raw advisory bodies or credentials are included here.

Accepted main1834 E4 retains normalized findings rather than raw advisory bodies. Therefore this comparison establishes identical retained normalized fields, not equality of raw advisory timestamps, descriptions or ranges. The baseline CI binary and the retained local binary have different byte hashes; both identify OSV-Scanner 2.5.0 at the same official source commit. The scanner metadata differences are explicit in comparison-to-main1834.json.

The repository coverage object retains its standard rawReportRetained=false field; its enclosing standalone diagnostic wrapper separately records that the actual raw report is retained locally. No prior finding review disposition is transferred.

Files:
- verified-osv-summary.json: complete result, hashes and precommit limits
- normalized-osv-findings.json: all 23 normalized findings
- diagnostic-target-coverage.json: all 474 exact tuples with references, scopes and advisory identities
- comparison-to-main1834.json: complete normalized finding and scanner metadata difference
- provenance-verification.json: independently verified official binary/source/module origin
- input-derivation.json: actual inventory, input and validation-source bindings
- independent-revalidation.json: reconstruction and negative-check results
- public-file-checksums.sha256: byte hashes of this review package
