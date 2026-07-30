package io.github.akaryc1b.approval.connector.dingtalk.http;

import io.github.akaryc1b.approval.connector.dingtalk.DingTalkTransportResponse;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@FunctionalInterface
interface DingTalkHttpSender {

    DingTalkTransportResponse send(
        URI uri,
        Map<String, String> headers,
        String body,
        Duration timeout
    );
}
