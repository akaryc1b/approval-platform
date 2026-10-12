# Checkstyle plugin dependency evidence

The root POM adds eight lines: a Commons Lang version property and a plugin-local dependency that changes Checkstyle's Lang from 3.17.0 to 3.18.0. Maven Checkstyle Plugin stays at 3.6.0, its Checkstyle engine stays at 9.3, and the rules stay byte-identical. Reversing only those additions restores the accepted root POM; the other 27 POMs are unchanged.

The [graph transition](../checkstyle-plugin-transition.json) verifies the sole Checkstyle-owner substitution and preserves all other 16 plugin owners, runtime components and edges, imported BOMs, scopes, licenses, reactor roots and Actions graph. The resulting plugin union has 269 coordinates; the exact OSV input contains 472 packages.

The predecessor is accepted main commit `4d685762d2db10497abe7668d1009b7e1a056796`, natural CI run 38037670972 (#1838). [Recovery provenance](accepted-main-provenance.json), [baseline E2](baseline-E2.json) and the [preserved Compiler lineage](preserved-compiler-lineage.json) retain that actual acceptance chain. Older evidence and pins remain intact.

[Candidate E2](candidate-E2.json), the [owner report](plugin-owner-report.txt) and [capture receipt](capture-execution.json) came from a bounded offline capture of the dirty precommit candidate. They do not claim evidence for a later commit. Exact-head CI must independently bind its checkout and current E2/scanner output to that commit.

Local validation passed the source contract, 40 source/model fixture cases, and 618 graph/consumer regression cases without skips. The [paired model receipt](model-verification.json) records two actual 26-model captures; [source witness](source-witness.json) binds their hashes, emitted model order and unchanged source inputs. Raw model XML remains in retained execution evidence.

The [final compatibility verification](compatibility-verification.json) records the completed local offline baseline/candidate goals, observed artifact checks and final harness verification. Its later model capture is retained separately from the earlier graph witness's paired capture.

The [OSV diagnostic](osv-diagnostic/README.md) describes the separately retained query and its limits. Graph admission and local verification do not clear findings or authorize release; the historical gates and release block remain.
