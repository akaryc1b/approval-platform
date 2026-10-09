# R3A current-source revalidation

The immutable historical R3A review remains unchanged. Its Tomcat 11.0.15 finding,
Boot plugin/buildpack 4.0.2 ownership, and historical 4/143 disposition totals
are retained only inside `historicalReview` in the V2 collector output.

The new hash-pinned transition binds the current admitted dependency graph,
Tomcat 11.0.26 JAR identity, Boot plugin/buildpack 4.0.8, and unchanged
httpcore5 5.3.6. The collector resolves current Maven evidence, verifies the
clean exact head before and after collection, validates every Tomcat runtime
path edge against current E2, and derives plugin ownership from the actual raw
report whose digest must match that E2.

Current Tomcat checks still require no Tribes dependency, no vulnerable cloud
membership JAR entries, and no activation markers in first-party production
source/configuration. JAR materialization uses a dedicated
`M6_PR_E_E3_R3A_JAR_REPOSITORY`; it cannot replace the complete Maven repository
used for E2 license metadata.

The current Tomcat observation does not invent a current scanner finding or
transfer the old `NOT_APPLICABLE` disposition. The unchanged httpcore finding
remains `UNRESOLVED`, with its actual current plugin owner. Current OSV or I4
totals are not inferred from the old R3A decision. Release blocking and all
non-authorizing historical records remain intact.

The unit fixture preserves actual plugin-report bytes losslessly through
deduplicated segments; its reconstructed SHA-256 matches retained Maven
evidence. Unit fixtures do not replace the real Maven/JAR CI collection.
