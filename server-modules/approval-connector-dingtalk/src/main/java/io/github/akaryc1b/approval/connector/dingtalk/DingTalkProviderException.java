package io.github.akaryc1b.approval.connector.dingtalk;

/**
 * Bounded provider-declared error decoded from a successful HTTP response.
 */
public final class DingTalkProviderException extends RuntimeException {

    private final long providerCode;

    public DingTalkProviderException(long providerCode, String message) {
        super(message == null || message.isBlank() ? "DingTalk provider rejected request" : message);
        this.providerCode = providerCode;
    }

    public long providerCode() {
        return providerCode;
    }
}
