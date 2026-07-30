package io.github.akaryc1b.approval.connector.dingtalk.token;

@FunctionalInterface
public interface DingTalkTokenKillSwitch {

    Decision evaluate(String trustedTenantId, String routePlanHash, String expectedRevision);

    record Decision(boolean acquisitionAllowed, String revision, String reasonCode) {

        public Decision {
            revision = DingTalkTokenSupport.identifier(revision, "revision");
            reasonCode = DingTalkTokenSupport.stableCode(reasonCode, "reasonCode");
        }
    }
}
