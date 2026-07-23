package io.github.akaryc1b.approval.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the closed management capability and server-resolved resource scope. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApprovalManagementPermission {

    String ADMIN_AUTHORITY = "approval.management.admin";

    Requirement value();

    ResourceScope resourceScope() default ResourceScope.TENANT;

    String departmentPathVariable() default "";

    enum ResourceScope {
        TENANT,
        DEPARTMENT
    }

    enum Requirement {
        READ("approval.management.read", "read"),
        DESIGN("approval.management.design", "design"),
        SLA_READ("approval.management.sla.read", "sla-read", false),
        SLA_DESIGN("approval.management.sla.design", "sla-design", true),
        SLA_PUBLISH("approval.management.sla.publish", "sla-publish", true),
        SLA_ACTIVATE("approval.management.sla.activate", "sla-activate", true),
        PUBLISH("approval.management.publish", "publish", true),
        DEPLOY("approval.management.deploy", "deploy", true),
        ACTIVATE("approval.management.activate", "activate", true),
        RELEASE_LIFECYCLE(
            "approval.management.release.lifecycle",
            "release-lifecycle",
            true
        ),
        RELEASE_MIGRATION_ASSESS(
            "approval.management.release.migration.assess",
            "release-migration-assess",
            true
        ),
        TRANSFER("approval.management.transfer", "transfer", true),
        AUDIT_READ("approval.management.audit.read", "audit-read"),
        AUDIT_EXPORT("approval.management.audit.export", "audit-export", true),
        AUDIT_VERIFY("approval.management.audit.verify", "audit-verify", true),
        CONSISTENCY_READ("approval.management.consistency.read", "consistency-read"),
        CONSISTENCY_RUN("approval.management.consistency.run", "consistency-run", true),
        OPERATIONAL_FAILURE_READ(
            "approval.management.operational-failure.read",
            "operational-failure-read"
        ),
        OPERATIONAL_FAILURE_REPLAY(
            "approval.management.operational-failure.replay",
            "operational-failure-replay",
            true
        );

        private final String authority;
        private final String metricTag;
        private final boolean reasonRequired;

        Requirement(String authority, String metricTag) {
            this(authority, metricTag, false);
        }

        Requirement(String authority, String metricTag, boolean reasonRequired) {
            this.authority = authority;
            this.metricTag = metricTag;
            this.reasonRequired = reasonRequired;
        }

        public String authority() {
            return authority;
        }

        public String metricTag() {
            return metricTag;
        }

        public boolean requiresReason() {
            return reasonRequired;
        }
    }
}
