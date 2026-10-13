# Boot plugin HttpComponents compatibility verification

The candidate changes the Spring Boot Maven plugin's private HttpComponents dependencies from Client 5.5.2 / Core 5.3.6 to Client 5.6.3 / Core 5.4.3. Boot remains 4.0.8. The local symbolic-linkage/Jackson diagnostic and the separate real Maven goal smoke passed. Full HTTP/TLS/Unix behavior remains pending ordinary Linux CI: this local environment rejected AF_UNIX socket creation with `EPERM` before either transport probe ran.

## What the completed checks establish

- The actual Boot plugin and buildpack JARs contain 260 inspected class files, 32 non-array Apache HC class references, and 49 distinct Apache HC member references with 49 recorded static/instance use sets. All 49 members resolve in both exact realms with matching descriptors, accessible declarations and compatible static/instance use. The sorted member-set SHA-256 is `b0cc9e98b73157c751911403e5f7cc09103ce25796ee6ff53f36ee745bac9300`. An intentionally absent member fails before transport construction. This is a bounded check of actual Boot constant-pool references, not an API-wide or behavioral guarantee.
- The unchanged Jackson probe passes against both isolated 39-JAR resolved classpaths. Both Java probes were compiled against the baseline, then those same class files were run against each realm. JAR hashes and loaded-code-source observations bind the checks to the actual artifacts.
- Real Maven 3.9.16 / Java 21 invocations of Boot 4.0.8 `help` and direct `maven-jar-plugin:3.5.0:jar` plus Boot `repackage` pass for the two tiny synthetic fixtures. Each repackaged archive has the Boot launcher manifest and executes its precompiled main, printing `BOOT_GOAL_MAIN_OK`. This checks natural Mojo loading and executable archive behavior; it is not a full application/package gate.
- Both natural Boot goal realms contain 36 private entries. The 39-entry `resolve-plugins` inventories additionally contain Maven Resolver API 1.4.1, Maven Resolver Util 1.4.1 and SLF4J API 1.7.36. These are distinct observations: resolved library inventory and Maven's natural private goal realm. Complete observed coordinate order is retained for both goals and variants.
- In the natural realms, only the three approved HTTP coordinates change. Every non-HTTP coordinate, JAR byte identity and relative order is unchanged. The full normalized order differs because the HTTP entries move; no full-order-equivalence claim is made.

The diagnostic receipt explicitly reports `DIAGNOSTIC_PASSED` and `linkageOnly=true`. The separate goal receipt reports `PASSED`. Neither receipt satisfies the mandatory full transport gate.

## Artifact identities

All values below are SHA-256. The portable artifact manifest additionally records the complete 42-artifact union and each 39-entry order without machine-specific paths.

| Artifact | Version | SHA-256 |
| --- | --- | --- |
| spring-boot-maven-plugin | 4.0.8 | `39c716a0d7ae881af7cfe5dbb3b3b8a38e8fd67077e7600980c392d1da65b184` |
| spring-boot-buildpack-platform | 4.0.8 | `afd5c3be4bdcd43d95f325fc7ab152d61ca385983f22d645e0538ede45fd9d9a` |
| spring-boot-loader-tools | 4.0.8 | `260e52aa495965a5833f7568d26f80094bcf4eb73eb7a38f748338ebd72493b8` |
| httpclient5 | 5.5.2 | `5aec3f3447ec816e593e380b59fbe3e42083318af6b215bc177386f7742f6146` |
| httpcore5 | 5.3.6 | `8fb4528d3e970aca440523a1bd698a389fa710ecfea4c5c6096698b86d1669ce` |
| httpcore5-h2 | 5.3.6 | `479a8aa5e6f99030a2a446a68d1f62320698932746f811fc656a1ea06b94184d` |
| httpclient5 | 5.6.3 | `80712ff129210d7c73d9fcc1195cbee5c3c34744965c7a05ea107b3648922f18` |
| httpcore5 | 5.4.3 | `18bfbbabb478dfb67f31aeaf428c387f3c3df654582e1309f708ee1f3086830a` |
| httpcore5-h2 | 5.4.3 | `c7db7026b8e2dea39132b04a6069f6671e2858309b20a146ec5c7dd6ed73a0b6` |
| jackson-core | 3.1.7 | `7608c367fd8a92666d28711519599543c180298259963184ce04d2947a8f71e7` |
| jackson-databind | 3.1.7 | `48c781de87e2016e1f0d973748a27130f11643e7ac080173f40e2d9669d84be3` |
| jackson-annotations | 2.21 | `53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b` |

Retained local receipt hashes: symbolic/Jackson diagnostic `e7a7f04a9546b33be9e786bb47b0b553ca8b9f7cc6d0bbeea8ce2cd8b84d7945`; natural goal smoke `e000df9f54bdb885a5705e53ed939472f63faa6ddbec49dca329812298ca7245`.

## Required remaining behavior

Ordinary Linux CI must run both baseline and candidate through the actual Boot `HttpTransport.create(DockerConnectionConfiguration)` factory. The full gate requires ordinary HTTP request/response, body/stream/error handling, response close and connection reuse; fixture-only trusted TLS plus hostname-mismatch and untrusted-certificate rejections; and the custom local Unix-socket transport. A Unix failure fails the gate. The gate also repeats complete member linkage, the absent-member negative control, unchanged Jackson and the separate natural goal smoke using fresh owner captures and sealed runtime/source/artifact inputs.

Both retained local full-fixture attempts failed during Unix listener construction; the already created listener was closed, owned child processes were reaped and ephemeral TLS directories were removed. No local full transport acceptance is claimed. The fixture uses only owned loopback/socket endpoints and temporary test certificates; it changes no host trust or persistent credential configuration. Docker daemon interaction, image build/publication, Windows named pipes, production behavior, security-fix reproduction and scanner clearance are outside these checks.
