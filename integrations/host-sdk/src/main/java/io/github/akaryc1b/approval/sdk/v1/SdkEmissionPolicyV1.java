package io.github.akaryc1b.approval.sdk.v1;

/** Shared version marker for deterministic emission policy contracts. */
public final class SdkEmissionPolicyV1 {
    public static final String CONTRACT_VERSION = "1";

    private SdkEmissionPolicyV1() {
    }

    public static final class UnsupportedEmissionPolicyVersionException
        extends IllegalArgumentException {
        private final String contractVersion;

        public UnsupportedEmissionPolicyVersionException(String contractVersion) {
            super("Unsupported emission policy contract version: " + contractVersion);
            this.contractVersion = contractVersion;
        }

        public String contractVersion() {
            return contractVersion;
        }
    }
}
