package io.github.akaryc1b.approval.integration;

import org.junit.jupiter.api.Test;

class SignedCallbackProbeTest {
    @Test
    void boundedReceiverVerifiesBeforeAcceptingAndDeduplicatesRetries() throws Exception {
        SignedCallbackProbeChecks.main(new String[0]);
    }
}
