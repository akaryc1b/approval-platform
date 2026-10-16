# Bounded Assembly/Enforcer Lang transition evidence

This directory retains the successful local dirty-candidate source, paired-model,
and dependency-graph captures from 2026-10-16. The source change is limited to
private Commons Lang 3.14.0 → 3.18.0 under Assembly 3.7.1 and Enforcer 3.5.0.
Both owners occur 26 times; the other 15 owner inventories and occurrences are
unchanged. Reversing the owner-derived coordinate union and report digest restores
the complete accepted graph, including runtime components, edges, scopes,
licenses, BOMs, pnpm and Actions. Lang 3.18.0 already occurs under other owners:
the union adds no coordinate and removes only Lang 3.14.0.

## Independently fixed accepted baseline

The baseline is accepted main `9e3489f2aef3ffb36508367591bc0d77ceb99bd1`, tree
`e92e5cc524d4642dfbd4ddc9ba6980bd01adf6b2`, run 38835342453 / #1868. Its
retained hygiene artifact is ID 12020971600, 6,318,917 bytes, SHA-256
`377aea835486f28459ee92670db3329cdd10f5e862a99962ee703a46d4e7851f`.

`baseline-E2.json` and `preserved-server-remediation.json` preserve exact decoded
receipt bytes. `preserved-jar-lineage.json` is the exact E4 graph-lineage object,
reserialized with its unchanged canonical content digest. It also equals the
accepted server receipt's `subsequentGraphTransition`. `baseline-root-pom.xml`
preserves the accepted Git blob and equals the retained JAR candidate root.

`accepted-artifact-inventory.json` is a derived immutable closure, not a hosted
receipt. Its 171 files total exactly 30,554,782 bytes: the prior 152-file closure,
15 further JAR manifest/source/model evidence files, and four direct baseline
anchors. Both complete retained JAR models are included. The new reader uses a
separate 32 MiB ceiling; the historical JAR reader's 16 MiB ceiling is unchanged.
The new closure adds actual read/hash work. The inventory and all predecessor
anchors are fixed independently in the reader, so resealing candidate evidence
cannot select a different predecessor or shorten this closure.

## Retained capture and current reads

The candidate E2, owner report, root snapshot, collector, execution receipts,
runtime/cache inventories, source projection, model contract, comparison and two
full effective models retain the successful capture bytes. The witness binds
exactly 28 source POMs, 55 graph input files, 57 paired-model input files, and 26
paired effective models. The two non-reactor overlay POMs are source-bound only.
The model contract here is the actual archived capture helper; it is not a claim
that later adapter or validator edits were executed during that capture.

The direct reader compares all 17 owners and the complete graph. At the final
receipt it rereads current source, retained models, the accepted closure and the
JAR archive; the existing exact-byte Lang → JAR source reversal remains the
source boundary. Accepted JAR lineage is immutable data, with no call to JAR or
earlier full graph admission. Current CI callers must independently bind their
verified checkout head. The focused test suite uses only data comparisons and
isolated mutations, with subprocess/network calls blocked during direct-receipt
negative tests.

## Limits and release state

These captures are local and precommit, with dirty source. They are not canonical
CI, final validator-source execution, plugin compatibility acceptance, or new
reviewer acceptance. Captured runtime/cache manifests bind retained provenance;
this reader does not rerun or rehash those external tool/cache installations.
Historical raw evidence availability limits remain inherited, including the
accepted hygiene checkout-log limitation.

The manifest derives exact OSV input identities only: 467 accepted packages and
466 candidate packages. No OSV query or finding removal is established by this
graph transition. Fresh complete scanner evidence and explicit finding review
remain required. There are no suppressions or exceptions; `releaseBlocked` and
`findingReviewRequired` remain true. Production deployment is not authorized.
