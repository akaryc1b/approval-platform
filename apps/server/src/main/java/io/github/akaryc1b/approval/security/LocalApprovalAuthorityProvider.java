package io.github.akaryc1b.approval.security;

import java.util.Set;

/** Server-owned local-demo authorities; never consulted for production principals. */
@FunctionalInterface
public interface LocalApprovalAuthorityProvider {

    Set<String> authorities(String tenantId, String operatorId, String requestPath);
}
