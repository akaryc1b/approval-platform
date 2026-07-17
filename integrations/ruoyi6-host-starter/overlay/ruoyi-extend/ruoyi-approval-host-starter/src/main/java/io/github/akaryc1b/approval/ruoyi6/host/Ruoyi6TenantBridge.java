package io.github.akaryc1b.approval.ruoyi6.host;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bridges Approval Platform tenant identity to the host application's tenancy model.
 *
 * RuoYi-Vue-Plus 6.X has no native tenant context, so the default implementation
 * accepts exactly one configured logical tenant. Custom host projects may replace
 * this bean to enter and leave their own tenant context around data access.
 */
public interface Ruoyi6TenantBridge {

    TenantDescriptor resolve(String requestedTenantId);

    default <T> T execute(String requestedTenantId, Supplier<T> action) {
        resolve(requestedTenantId);
        return action.get();
    }

    record TenantDescriptor(
        String id,
        String name,
        boolean active,
        Map<String, String> attributes
    ) {
        public TenantDescriptor {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
