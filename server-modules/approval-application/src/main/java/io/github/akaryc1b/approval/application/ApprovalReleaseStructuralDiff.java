package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic structural Diff for immutable Approval DSL and Release Packages. */
public final class ApprovalReleaseStructuralDiff {

    private static final String DEFINITION_SUBJECT = "$definition";
    private static final String RELEASE_SUBJECT = "$release";

    public Result diff(
        ApprovalDefinitionVersion before,
        ApprovalDefinitionVersion after
    ) {
        return diff(before, after, null, null);
    }

    public Result diff(
        ApprovalDefinitionVersion before,
        ApprovalDefinitionVersion after,
        ApprovalReleasePackage beforeRelease,
        ApprovalReleasePackage afterRelease
    ) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        requireSameDefinition(before, after);
        requireMatchingRelease(before, beforeRelease, "beforeRelease");
        requireMatchingRelease(after, afterRelease, "afterRelease");
        if ((beforeRelease == null) != (afterRelease == null)) {
            throw new IllegalArgumentException(
                "beforeRelease and afterRelease must either both be present or absent"
            );
        }

        List<Change> changes = new ArrayList<>();
        compareDefinitions(before, after, changes);
        if (beforeRelease != null) {
            compareReleasePackages(beforeRelease, afterRelease, changes);
        }
        changes.sort(changeComparator());
        return new Result(
            before.definitionKey(),
            before.version(),
            after.version(),
            beforeRelease == null ? null : beforeRelease.releaseVersion(),
            afterRelease == null ? null : afterRelease.releaseVersion(),
            changes
        );
    }

    private static void compareDefinitions(
        ApprovalDefinitionVersion before,
        ApprovalDefinitionVersion after,
        List<Change> changes
    ) {
        ApprovalDefinition left = before.definition();
        ApprovalDefinition right = after.definition();
        modified(
            changes,
            SubjectType.DEFINITION,
            DEFINITION_SUBJECT,
            "/schemaVersion",
            left.schemaVersion(),
            right.schemaVersion(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.DEFINITION,
            DEFINITION_SUBJECT,
            "/name",
            left.name(),
            right.name(),
            Impact.LOW
        );
        modified(
            changes,
            SubjectType.DEFINITION,
            DEFINITION_SUBJECT,
            "/startNodeId",
            left.startNodeId(),
            right.startNodeId(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.DEFINITION,
            DEFINITION_SUBJECT,
            "/definitionHash",
            before.contentHash(),
            after.contentHash(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.FORM_PACKAGE,
            "$form-package",
            "/version",
            before.formPackageVersion(),
            after.formPackageVersion(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.FORM_PACKAGE,
            "$form-package",
            "/hash",
            before.formPackageHash(),
            after.formPackageHash(),
            Impact.HIGH
        );
        compareNodes(left.nodes(), right.nodes(), changes);
    }

    private static void compareNodes(
        List<ApprovalDefinition.ProcessNode> before,
        List<ApprovalDefinition.ProcessNode> after,
        List<Change> changes
    ) {
        Map<String, ApprovalDefinition.ProcessNode> left = nodes(before);
        Map<String, ApprovalDefinition.ProcessNode> right = nodes(after);
        Set<String> ids = new TreeSet<>();
        ids.addAll(left.keySet());
        ids.addAll(right.keySet());
        for (String id : ids) {
            ApprovalDefinition.ProcessNode leftNode = left.get(id);
            ApprovalDefinition.ProcessNode rightNode = right.get(id);
            if (leftNode == null) {
                changes.add(new Change(
                    ChangeType.ADDED,
                    SubjectType.NODE,
                    id,
                    nodePath(id),
                    null,
                    nodeKind(rightNode),
                    Impact.HIGH
                ));
            } else if (rightNode == null) {
                changes.add(new Change(
                    ChangeType.REMOVED,
                    SubjectType.NODE,
                    id,
                    nodePath(id),
                    nodeKind(leftNode),
                    null,
                    Impact.HIGH
                ));
            } else {
                compareNode(leftNode, rightNode, changes);
            }
        }
    }

    private static void compareNode(
        ApprovalDefinition.ProcessNode before,
        ApprovalDefinition.ProcessNode after,
        List<Change> changes
    ) {
        String id = before.id();
        modified(
            changes,
            SubjectType.NODE,
            id,
            nodePath(id) + "/name",
            before.name(),
            after.name(),
            Impact.LOW
        );
        String beforeKind = nodeKind(before);
        String afterKind = nodeKind(after);
        if (!beforeKind.equals(afterKind)) {
            modified(
                changes,
                SubjectType.NODE,
                id,
                nodePath(id) + "/kind",
                beforeKind,
                afterKind,
                Impact.HIGH
            );
            return;
        }
        if (before instanceof ApprovalDefinition.StartNode left
            && after instanceof ApprovalDefinition.StartNode right) {
            compareNext(id, left.next(), right.next(), changes);
        } else if (before instanceof ApprovalDefinition.ApprovalStep left
            && after instanceof ApprovalDefinition.ApprovalStep right) {
            compareApproval(left, right, changes);
        } else if (before instanceof ApprovalDefinition.HandleStep left
            && after instanceof ApprovalDefinition.HandleStep right) {
            compareHandle(left, right, changes);
        } else if (before instanceof ApprovalDefinition.ConditionStep left
            && after instanceof ApprovalDefinition.ConditionStep right) {
            compareCondition(left, right, changes);
        } else if (before instanceof ApprovalDefinition.ParallelSplitNode left
            && after instanceof ApprovalDefinition.ParallelSplitNode right) {
            compareParallelSplit(left, right, changes);
        } else if (before instanceof ApprovalDefinition.ParallelJoinNode left
            && after instanceof ApprovalDefinition.ParallelJoinNode right) {
            compareNext(id, left.next(), right.next(), changes);
        }
    }

    private static void compareApproval(
        ApprovalDefinition.ApprovalStep before,
        ApprovalDefinition.ApprovalStep after,
        List<Change> changes
    ) {
        String path = nodePath(before.id());
        modified(
            changes,
            SubjectType.NODE,
            before.id(),
            path + "/assignee",
            before.assignee(),
            after.assignee(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.NODE,
            before.id(),
            path + "/mode",
            before.mode(),
            after.mode(),
            Impact.HIGH
        );
        compareNext(before.id(), before.next(), after.next(), changes);
        modified(
            changes,
            SubjectType.NODE,
            before.id(),
            path + "/rejectNext",
            before.rejectNext(),
            after.rejectNext(),
            Impact.HIGH
        );
    }

    private static void compareHandle(
        ApprovalDefinition.HandleStep before,
        ApprovalDefinition.HandleStep after,
        List<Change> changes
    ) {
        modified(
            changes,
            SubjectType.NODE,
            before.id(),
            nodePath(before.id()) + "/assignee",
            before.assignee(),
            after.assignee(),
            Impact.HIGH
        );
        compareNext(before.id(), before.next(), after.next(), changes);
    }

    private static void compareNext(
        String nodeId,
        String before,
        String after,
        List<Change> changes
    ) {
        modified(
            changes,
            SubjectType.NODE,
            nodeId,
            nodePath(nodeId) + "/next",
            before,
            after,
            Impact.HIGH
        );
    }

    private static void compareCondition(
        ApprovalDefinition.ConditionStep before,
        ApprovalDefinition.ConditionStep after,
        List<Change> changes
    ) {
        String nodeId = before.id();
        modified(
            changes,
            SubjectType.CONDITION_ROUTE,
            nodeId,
            nodePath(nodeId) + "/defaultNext",
            before.defaultNext(),
            after.defaultNext(),
            Impact.HIGH
        );
        List<String> leftOrder = before.routes().stream()
            .map(ApprovalReleaseStructuralDiff::routeSignature)
            .toList();
        List<String> rightOrder = after.routes().stream()
            .map(ApprovalReleaseStructuralDiff::routeSignature)
            .toList();
        if (sameMembers(leftOrder, rightOrder) && !leftOrder.equals(rightOrder)) {
            changes.add(new Change(
                ChangeType.REORDERED,
                SubjectType.CONDITION_ROUTE,
                nodeId,
                nodePath(nodeId) + "/routes/order",
                leftOrder,
                rightOrder,
                Impact.HIGH
            ));
            return;
        }
        int common = Math.min(before.routes().size(), after.routes().size());
        for (int index = 0; index < common; index++) {
            compareRoute(
                nodeId,
                index,
                before.routes().get(index),
                after.routes().get(index),
                changes
            );
        }
        for (int index = common; index < before.routes().size(); index++) {
            changes.add(new Change(
                ChangeType.REMOVED,
                SubjectType.CONDITION_ROUTE,
                nodeId + ':' + index,
                nodePath(nodeId) + "/routes/" + index,
                routeSignature(before.routes().get(index)),
                null,
                Impact.HIGH
            ));
        }
        for (int index = common; index < after.routes().size(); index++) {
            changes.add(new Change(
                ChangeType.ADDED,
                SubjectType.CONDITION_ROUTE,
                nodeId + ':' + index,
                nodePath(nodeId) + "/routes/" + index,
                null,
                routeSignature(after.routes().get(index)),
                Impact.HIGH
            ));
        }
    }

    private static void compareRoute(
        String nodeId,
        int index,
        ApprovalDefinition.ConditionRoute before,
        ApprovalDefinition.ConditionRoute after,
        List<Change> changes
    ) {
        String subjectId = nodeId + ':' + index;
        String path = nodePath(nodeId) + "/routes/" + index;
        modified(
            changes,
            SubjectType.CONDITION_ROUTE,
            subjectId,
            path + "/condition/field",
            before.condition().field(),
            after.condition().field(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.CONDITION_ROUTE,
            subjectId,
            path + "/condition/operator",
            before.condition().operator(),
            after.condition().operator(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.CONDITION_ROUTE,
            subjectId,
            path + "/condition/value",
            before.condition().value(),
            after.condition().value(),
            Impact.HIGH
        );
        modified(
            changes,
            SubjectType.CONDITION_ROUTE,
            subjectId,
            path + "/next",
            before.next(),
            after.next(),
            Impact.HIGH
        );
    }

    private static void compareParallelSplit(
        ApprovalDefinition.ParallelSplitNode before,
        ApprovalDefinition.ParallelSplitNode after,
        List<Change> changes
    ) {
        String nodeId = before.id();
        modified(
            changes,
            SubjectType.NODE,
            nodeId,
            nodePath(nodeId) + "/joinNodeId",
            before.joinNodeId(),
            after.joinNodeId(),
            Impact.HIGH
        );
        Map<String, ApprovalDefinition.ParallelBranch> left = branches(before.branches());
        Map<String, ApprovalDefinition.ParallelBranch> right = branches(after.branches());
        Set<String> ids = new TreeSet<>();
        ids.addAll(left.keySet());
        ids.addAll(right.keySet());
        for (String branchId : ids) {
            ApprovalDefinition.ParallelBranch leftBranch = left.get(branchId);
            ApprovalDefinition.ParallelBranch rightBranch = right.get(branchId);
            String path = nodePath(nodeId) + "/branches/" + branchId;
            if (leftBranch == null) {
                changes.add(new Change(
                    ChangeType.ADDED,
                    SubjectType.PARALLEL_BRANCH,
                    branchId,
                    path,
                    null,
                    rightBranch,
                    Impact.HIGH
                ));
            } else if (rightBranch == null) {
                changes.add(new Change(
                    ChangeType.REMOVED,
                    SubjectType.PARALLEL_BRANCH,
                    branchId,
                    path,
                    leftBranch,
                    null,
                    Impact.HIGH
                ));
            } else {
                modified(
                    changes,
                    SubjectType.PARALLEL_BRANCH,
                    branchId,
                    path + "/name",
                    leftBranch.name(),
                    rightBranch.name(),
                    Impact.LOW
                );
                modified(
                    changes,
                    SubjectType.PARALLEL_BRANCH,
                    branchId,
                    path + "/next",
                    leftBranch.next(),
                    rightBranch.next(),
                    Impact.HIGH
                );
            }
        }
        List<String> leftOrder = before.branches().stream()
            .map(ApprovalDefinition.ParallelBranch::id)
            .toList();
        List<String> rightOrder = after.branches().stream()
            .map(ApprovalDefinition.ParallelBranch::id)
            .toList();
        if (sameMembers(leftOrder, rightOrder) && !leftOrder.equals(rightOrder)) {
            changes.add(new Change(
                ChangeType.REORDERED,
                SubjectType.PARALLEL_BRANCH,
                nodeId,
                nodePath(nodeId) + "/branches/order",
                leftOrder,
                rightOrder,
                Impact.HIGH
            ));
        }
    }

    private static void compareReleasePackages(
        ApprovalReleasePackage before,
        ApprovalReleasePackage after,
        List<Change> changes
    ) {
        artifact(changes, SubjectType.DEFINITION, "/definitionHash",
            before.definitionHash(), after.definitionHash(), Impact.HIGH);
        artifact(changes, SubjectType.FORM_PACKAGE, "/formPackageVersion",
            before.formPackageVersion(), after.formPackageVersion(), Impact.HIGH);
        artifact(changes, SubjectType.FORM_PACKAGE, "/formPackageHash",
            before.formPackageHash(), after.formPackageHash(), Impact.HIGH);
        artifact(changes, SubjectType.FORM_PACKAGE, "/formVersion",
            before.formVersion(), after.formVersion(), Impact.HIGH);
        artifact(changes, SubjectType.FORM_PACKAGE, "/formHash",
            before.formHash(), after.formHash(), Impact.HIGH);
        artifact(changes, SubjectType.UI_PERMISSIONS, "/uiSchemaVersion",
            before.uiSchemaVersion(), after.uiSchemaVersion(), Impact.HIGH);
        artifact(changes, SubjectType.UI_PERMISSIONS, "/uiSchemaHash",
            before.uiSchemaHash(), after.uiSchemaHash(), Impact.HIGH);
        artifact(changes, SubjectType.COMPILER, "/compilerVersion",
            before.compilerVersion(), after.compilerVersion(), Impact.HIGH);
        artifact(changes, SubjectType.BPMN, "/compiledArtifactHash",
            before.compiledArtifactHash(), after.compiledArtifactHash(), Impact.HIGH);
        artifact(changes, SubjectType.BPMN, "/bpmnHash",
            before.bpmnHash(), after.bpmnHash(), Impact.HIGH);
        artifact(changes, SubjectType.RELEASE_PACKAGE, "/deploymentMetadataHash",
            before.deploymentMetadataHash(), after.deploymentMetadataHash(), Impact.MEDIUM);
        artifact(changes, SubjectType.RELEASE_PACKAGE, "/packageHash",
            before.packageHash(), after.packageHash(), Impact.HIGH);
    }

    private static void artifact(
        List<Change> changes,
        SubjectType subjectType,
        String path,
        Object before,
        Object after,
        Impact impact
    ) {
        modified(
            changes,
            subjectType,
            RELEASE_SUBJECT,
            path,
            before,
            after,
            impact
        );
    }

    private static void modified(
        List<Change> changes,
        SubjectType subjectType,
        String subjectId,
        String path,
        Object before,
        Object after,
        Impact impact
    ) {
        if (!Objects.equals(before, after)) {
            changes.add(new Change(
                ChangeType.MODIFIED,
                subjectType,
                subjectId,
                path,
                before,
                after,
                impact
            ));
        }
    }

    private static Map<String, ApprovalDefinition.ProcessNode> nodes(
        List<ApprovalDefinition.ProcessNode> values
    ) {
        Map<String, ApprovalDefinition.ProcessNode> result = new LinkedHashMap<>();
        for (ApprovalDefinition.ProcessNode value : values) {
            result.put(value.id(), value);
        }
        return result;
    }

    private static Map<String, ApprovalDefinition.ParallelBranch> branches(
        List<ApprovalDefinition.ParallelBranch> values
    ) {
        Map<String, ApprovalDefinition.ParallelBranch> result = new LinkedHashMap<>();
        for (ApprovalDefinition.ParallelBranch value : values) {
            result.put(value.id(), value);
        }
        return result;
    }

    private static boolean sameMembers(List<String> before, List<String> after) {
        if (before.size() != after.size()) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String value : before) {
            counts.merge(value, 1, Integer::sum);
        }
        for (String value : after) {
            Integer count = counts.get(value);
            if (count == null) {
                return false;
            }
            if (count == 1) {
                counts.remove(value);
            } else {
                counts.put(value, count - 1);
            }
        }
        return counts.isEmpty();
    }

    private static String routeSignature(ApprovalDefinition.ConditionRoute route) {
        return route.condition().field()
            + ':'
            + route.condition().operator().name()
            + ':'
            + route.condition().value().toPlainString()
            + "->"
            + route.next();
    }

    private static String nodeKind(ApprovalDefinition.ProcessNode node) {
        if (node instanceof ApprovalDefinition.StartNode) {
            return "START";
        }
        if (node instanceof ApprovalDefinition.ApprovalStep) {
            return "APPROVAL";
        }
        if (node instanceof ApprovalDefinition.HandleStep) {
            return "HANDLE";
        }
        if (node instanceof ApprovalDefinition.ConditionStep) {
            return "CONDITION";
        }
        if (node instanceof ApprovalDefinition.ParallelSplitNode) {
            return "PARALLEL_SPLIT";
        }
        if (node instanceof ApprovalDefinition.ParallelJoinNode) {
            return "PARALLEL_JOIN";
        }
        if (node instanceof ApprovalDefinition.EndNode) {
            return "END";
        }
        throw new IllegalArgumentException("unsupported Approval DSL node type");
    }

    private static String nodePath(String nodeId) {
        return "/nodes/" + nodeId;
    }

    private static Comparator<Change> changeComparator() {
        return Comparator.comparing(Change::subjectType)
            .thenComparing(Change::subjectId)
            .thenComparing(Change::path)
            .thenComparing(Change::changeType);
    }

    private static void requireSameDefinition(
        ApprovalDefinitionVersion before,
        ApprovalDefinitionVersion after
    ) {
        if (!before.tenantId().equals(after.tenantId())
            || !before.definitionKey().equals(after.definitionKey())) {
            throw new IllegalArgumentException(
                "Approval DSL versions must belong to the same tenant and definition"
            );
        }
    }

    private static void requireMatchingRelease(
        ApprovalDefinitionVersion definition,
        ApprovalReleasePackage release,
        String name
    ) {
        if (release == null) {
            return;
        }
        if (!definition.tenantId().equals(release.tenantId())
            || !definition.definitionKey().equals(release.definitionKey())
            || definition.version() != release.definitionVersion()
            || !definition.contentHash().equals(release.definitionHash())) {
            throw new IllegalArgumentException(name + " does not match its Approval DSL version");
        }
    }

    public enum ChangeType {
        ADDED,
        REMOVED,
        MODIFIED,
        REORDERED
    }

    public enum SubjectType {
        DEFINITION,
        NODE,
        CONDITION_ROUTE,
        PARALLEL_BRANCH,
        FORM_PACKAGE,
        UI_PERMISSIONS,
        COMPILER,
        BPMN,
        RELEASE_PACKAGE
    }

    public enum Impact {
        LOW,
        MEDIUM,
        HIGH
    }

    public record Change(
        ChangeType changeType,
        SubjectType subjectType,
        String subjectId,
        String path,
        Object before,
        Object after,
        Impact impact
    ) {
        public Change {
            changeType = Objects.requireNonNull(changeType, "changeType must not be null");
            subjectType = Objects.requireNonNull(subjectType, "subjectType must not be null");
            subjectId = requireText(subjectId, "subjectId");
            path = requireText(path, "path");
            impact = Objects.requireNonNull(impact, "impact must not be null");
        }
    }

    public record Result(
        String definitionKey,
        int fromDefinitionVersion,
        int toDefinitionVersion,
        Integer fromReleaseVersion,
        Integer toReleaseVersion,
        List<Change> changes
    ) {
        public Result {
            definitionKey = requireText(definitionKey, "definitionKey");
            if (fromDefinitionVersion < 1 || toDefinitionVersion < 1) {
                throw new IllegalArgumentException("definition versions must be positive");
            }
            if (fromReleaseVersion != null && fromReleaseVersion < 1) {
                throw new IllegalArgumentException("fromReleaseVersion must be positive");
            }
            if (toReleaseVersion != null && toReleaseVersion < 1) {
                throw new IllegalArgumentException("toReleaseVersion must be positive");
            }
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
