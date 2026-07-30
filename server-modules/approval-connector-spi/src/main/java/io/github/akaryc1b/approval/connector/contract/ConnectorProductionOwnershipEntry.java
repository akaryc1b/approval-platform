package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * One deterministic production-capability ownership decision.
 */
public record ConnectorProductionOwnershipEntry(
    ConnectorProductionCapability capability,
    ConnectorProductionOwner owner,
    ConnectorProductionDecision decision,
    String rationaleCode
) {

    public ConnectorProductionOwnershipEntry {
        capability = Objects.requireNonNull(capability, "capability must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        decision = Objects.requireNonNull(decision, "decision must not be null");
        rationaleCode = ConnectorContractSupport.requireCode(
            rationaleCode,
            "rationaleCode"
        );
    }

    public String canonicalEvidence() {
        return capability.name()
            + "|" + owner.name()
            + "|" + decision.name()
            + "|" + rationaleCode;
    }
}
