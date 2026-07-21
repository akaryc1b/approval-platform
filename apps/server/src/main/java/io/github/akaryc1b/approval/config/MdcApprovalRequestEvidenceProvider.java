package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;

import org.slf4j.MDC;

/** Resolves request and actor evidence established by the server identity filter. */
final class MdcApprovalRequestEvidenceProvider implements ApprovalRequestEvidenceProvider {

    @Override
    public RequestEvidence current() {
        return new RequestEvidence(
            requiredMdc("operatorId"),
            requiredMdc("requestId"),
            optionalMdc("traceId")
        );
    }

    private static String requiredMdc(String key) {
        String value = MDC.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("trusted approval request evidence is unavailable");
        }
        return value;
    }

    private static String optionalMdc(String key) {
        String value = MDC.get(key);
        return value == null || value.isBlank() ? null : value;
    }
}
