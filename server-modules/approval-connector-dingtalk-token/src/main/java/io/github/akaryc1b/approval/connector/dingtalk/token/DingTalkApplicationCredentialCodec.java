package io.github.akaryc1b.approval.connector.dingtalk.token;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

final class DingTalkApplicationCredentialCodec {

    private static final int MAXIMUM_KEY_BYTES = 512;
    private static final int MAXIMUM_SECRET_BYTES = 4_096;

    private DingTalkApplicationCredentialCodec() {
    }

    static void decode(byte[] material, CredentialUse use) {
        Objects.requireNonNull(material, "material must not be null");
        Objects.requireNonNull(use, "use must not be null");
        if (material.length < Integer.BYTES * 2) {
            throw malformed();
        }
        ByteBuffer buffer = ByteBuffer.wrap(material).asReadOnlyBuffer();
        int keyLength = buffer.getInt();
        if (keyLength < 1 || keyLength > MAXIMUM_KEY_BYTES
            || buffer.remaining() < keyLength + Integer.BYTES) {
            throw malformed();
        }
        byte[] applicationKey = new byte[keyLength];
        buffer.get(applicationKey);
        int secretLength = buffer.getInt();
        if (secretLength < 1 || secretLength > MAXIMUM_SECRET_BYTES
            || buffer.remaining() != secretLength) {
            Arrays.fill(applicationKey, (byte) 0);
            throw malformed();
        }
        byte[] applicationSecret = new byte[secretLength];
        buffer.get(applicationSecret);
        try {
            use.accept(applicationKey, applicationSecret);
        } finally {
            Arrays.fill(applicationKey, (byte) 0);
            Arrays.fill(applicationSecret, (byte) 0);
        }
    }

    private static DingTalkTokenLifecycleException malformed() {
        return new DingTalkTokenLifecycleException(
            DingTalkTokenFailure.CREDENTIAL_MATERIAL_FAILURE
        );
    }

    @FunctionalInterface
    interface CredentialUse {
        void accept(byte[] applicationKey, byte[] applicationSecret);
    }
}
