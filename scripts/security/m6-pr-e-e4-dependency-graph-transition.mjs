#!/usr/bin/env node
import { createHash } from 'node:crypto';

const SHA64 = /^[0-9a-f]{64}$/;
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

export const MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION = Object.freeze({
  "schemaVersion": "M6_PR_E_E4_DEPENDENCY_GRAPH_TRANSITION_V1",
  "repository": "akaryc1b/approval-platform",
  "transitionId": "MYSQL_8_4_P3_H5_DEPENDENCY_GRAPH_REBASELINE",
  "priorAcceptedGraphDigest": "2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94",
  "targetGraphDigest": "e4cffa00582d61a62f5c41548f8da4b8bfb28dd50b7db3aa5d1aa42cd503ddfd",
  "sourceEvidence": {
    "runId": 31696972899,
    "runNumber": 1448,
    "headSha": "de04fd43e62e00ef5ffa77336f54e698d99bc4f0",
    "baseSha": "a3b3bdec13edbdc20dfbdd316f025f22861b9697",
    "e2ContentSha256": "69b574782e0f105d17f3f25fe76b0f752c858028a44c9298339f68104751e10c",
    "classification": "SECURITY_BASELINE_STALENESS_LONG_LIVED_BRANCH_DEPENDENCY_GRAPH_DRIFT"
  },
  "expectedDelta": {
    "addedComponents": [
      "pkg:maven/com.mysql/mysql-connector-j@9.5.0?type=jar",
      "pkg:maven/org.flywaydb/flyway-mysql@11.14.1?type=jar",
      "pkg:maven/org.testcontainers/testcontainers-mysql@2.0.3?type=jar"
    ],
    "removedComponents": [],
    "changedComponents": [],
    "addedEdges": [
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-integration-jdbc@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/org.springframework/spring-tx@7.0.3?type=jar"
      ],
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-persistence-jdbc@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/com.mysql/mysql-connector-j@9.5.0?type=jar"
      ],
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-persistence-jdbc@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/org.flywaydb/flyway-mysql@11.14.1?type=jar"
      ],
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-persistence-jdbc@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/org.testcontainers/testcontainers-mysql@2.0.3?type=jar"
      ],
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-server@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/com.mysql/mysql-connector-j@9.5.0?type=jar"
      ],
      [
        "pkg:maven/io.github.akaryc1b.approval/approval-server@0.1.0-SNAPSHOT?type=jar",
        "pkg:maven/org.testcontainers/testcontainers-mysql@2.0.3?type=jar"
      ]
    ],
    "removedEdges": [
      [
        "pkg:maven/org.springframework/spring-jdbc@7.0.3?type=jar",
        "pkg:maven/org.springframework/spring-tx@7.0.3?type=jar"
      ]
    ],
    "reactorProjectCountBefore": 26,
    "reactorProjectCountAfter": 26,
    "reactorRootsChanged": false,
    "importedBomsChanged": false,
    "resolvedPluginCoordinatesChanged": false,
    "pluginResolutionSha256Changed": false,
    "pnpmChanged": false,
    "githubActionsAcceptedGraphChanged": false,
    "limitationsChanged": false
  },
  "securityBoundary": {
    "dependencyGraphChangeIsFindingDisposition": false,
    "historicalPgjdbcRemediationMayBeCarriedForward": true,
    "currentOsvAbsenceRevalidationRequired": true,
    "scannerMustExecuteAtTargetGraph": true,
    "scannerSuppressionAdded": false,
    "severityDowngradeAdded": false,
    "exceptionAdded": false,
    "readyAuthorized": false,
    "mergeAuthorized": false,
    "productionPromotionAuthorized": false,
    "mysqlProductionSupportAuthorized": false
  }
});

export const MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION_SHA256 =
  '84bb310e52f540128d0dea0d6e7a3779054aa68021ae6c9138520d7fb6fa1d41';

export function verifyApprovedDependencyGraphTransition(
  currentGraphDigest,
  priorAcceptedGraphDigest,
  transition = MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION,
) {
  if (!SHA64.test(currentGraphDigest || '') || !SHA64.test(priorAcceptedGraphDigest || '')) {
    throw new Error('valid E2 graph digests required');
  }
  if (currentGraphDigest === priorAcceptedGraphDigest) {
    return {
      applied: false,
      currentGraphDigest,
      priorAcceptedGraphDigest,
      transitionCanonicalSha256: null,
      transitionId: null,
    };
  }
  if (canonical(transition) !== canonical(MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION)) {
    throw new Error('unapproved dependency graph transition');
  }
  if (sha256(canonical(transition)) !== MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION_SHA256) {
    throw new Error('dependency graph transition canonical mismatch');
  }
  if (transition.priorAcceptedGraphDigest !== priorAcceptedGraphDigest
    || transition.targetGraphDigest !== currentGraphDigest) {
    throw new Error(`E2 graph drift ${currentGraphDigest}`);
  }
  if (transition.securityBoundary?.currentOsvAbsenceRevalidationRequired !== true
    || transition.securityBoundary?.scannerMustExecuteAtTargetGraph !== true
    || transition.securityBoundary?.dependencyGraphChangeIsFindingDisposition !== false
    || transition.securityBoundary?.scannerSuppressionAdded !== false
    || transition.securityBoundary?.severityDowngradeAdded !== false
    || transition.securityBoundary?.exceptionAdded !== false
    || transition.securityBoundary?.readyAuthorized !== false
    || transition.securityBoundary?.mergeAuthorized !== false
    || transition.securityBoundary?.productionPromotionAuthorized !== false
    || transition.securityBoundary?.mysqlProductionSupportAuthorized !== false) {
    throw new Error('dependency graph transition security boundary widened');
  }
  return {
    applied: true,
    currentGraphDigest,
    priorAcceptedGraphDigest,
    transitionCanonicalSha256: MYSQL_P3_H5_DEPENDENCY_GRAPH_TRANSITION_SHA256,
    transitionId: transition.transitionId,
  };
}

export const canonicalDependencyGraphTransition = canonical;
