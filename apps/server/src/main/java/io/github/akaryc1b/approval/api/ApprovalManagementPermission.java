package io.github.akaryc1b.approval.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the closed management capability required by an Approval administration API. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApprovalManagementPermission {

    String ADMIN_AUTHORITY = "approval.management.admin";

    Requirement value();

    enum Requirement {
        READ("approval.management.read", "read"),
        DESIGN("approval.management.design", "design"),
        PUBLISH("approval.management.publish", "publish"),
        DEPLOY("approval.management.deploy", "deploy"),
        ACTIVATE("approval.management.activate", "activate"),
        TRANSFER("approval.management.transfer", "transfer"),
        AUDIT_READ("approval.management.audit.read", "audit-read"),
        AUDIT_EXPORT("approval.management.audit.export", "audit-export"),
        AUDIT_VERIFY("approval.management.audit.verify", "audit-verify"),
        CONSISTENCY_READ("approval.management.consistency.read", "consistency-read"),
        CONSISTENCY_RUN("approval.management.consistency.run", "consistency-run");

        private final String authority;
        private final String metricTag;

        Requirement(String authority, String metricTag) {
            this.authority = authority;
            this.metricTag = metricTag;
        }

        public String authority() {
            return authority;
        }

        public String metricTag() {
            return metricTag;
        }
    }
}
