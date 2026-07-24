package io.github.akaryc1b.approval.connector.dingtalk;

/**
 * Injected transport boundary. M6-A-P1 provides no network implementation.
 */
@FunctionalInterface
public interface DingTalkTransport {

    DingTalkTransportResponse exchange(DingTalkTransportRequest request);
}
