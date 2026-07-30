package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.io.Serial;
import java.util.Objects;

public final class DingTalkTokenLifecycleException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final DingTalkTokenFailure failure;

    public DingTalkTokenLifecycleException(DingTalkTokenFailure failure) {
        super(
            "DingTalk Token lifecycle rejected operation: "
                + requireFailure(failure).stableCode()
        );
        this.failure = failure;
    }

    public DingTalkTokenFailure failure() {
        return failure;
    }

    private static DingTalkTokenFailure requireFailure(DingTalkTokenFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        if (failure == DingTalkTokenFailure.NONE) {
            throw new IllegalArgumentException("failure must not be NONE");
        }
        return failure;
    }
}
