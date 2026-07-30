package io.github.akaryc1b.approval.connector.dingtalk.token;

@FunctionalInterface
public interface DingTalkTokenEndpointPort {

    void acquire(
        DingTalkTokenEndpointRequest request,
        byte[] applicationKey,
        byte[] applicationSecret,
        ResponseUse responseUse
    );

    @FunctionalInterface
    interface ResponseUse {
        void accept(byte[] material, long lifetimeSeconds);
    }
}
