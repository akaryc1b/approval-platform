package io.github.akaryc1b.approval.connector.dingtalk.http;

import java.io.Serial;

/**
 * Fail-closed production transport policy rejection. Messages never include credential material.
 */
public final class DingTalkTransportPolicyException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DingTalkTransportPolicyException(String message) {
        super(message);
    }

    public DingTalkTransportPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
