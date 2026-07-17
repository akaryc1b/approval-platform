package io.github.akaryc1b.approval.domain;

import java.util.Objects;

/**
 * Stable product-level reference to an approval instance.
 *
 * @param tenantId  owning tenant
 * @param instanceId platform instance identifier
 */
public record ApprovalInstanceRef(String tenantId, String instanceId) {

    public ApprovalInstanceRef {
        tenantId = requireText(tenantId, "tenantId");
        instanceId = requireText(instanceId, "instanceId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
