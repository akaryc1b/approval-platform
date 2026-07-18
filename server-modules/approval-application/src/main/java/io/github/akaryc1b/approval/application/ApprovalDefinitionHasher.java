package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic content hash for the ordered Approval DSL protocol. */
public final class ApprovalDefinitionHasher {

    public String hash(ApprovalDefinition definition) {
        StringBuilder canonical = new StringBuilder(4096);
        append(canonical, definition.schemaVersion());
        append(canonical, definition.definitionKey());
        append(canonical, definition.version());
        append(canonical, definition.name());
        append(canonical, definition.startNodeId());
        append(canonical, definition.nodes().size());
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            appendNode(canonical, node);
        }
        return sha256(canonical.toString());
    }

    private static void appendNode(
        StringBuilder canonical,
        ApprovalDefinition.ProcessNode node
    ) {
        if (node instanceof ApprovalDefinition.StartNode start) {
            append(canonical, "START");
            appendBase(canonical, start);
            append(canonical, start.next());
        } else if (node instanceof ApprovalDefinition.ApprovalStep approval) {
            append(canonical, "APPROVAL");
            appendBase(canonical, approval);
            appendAssignee(canonical, approval.assignee());
            append(canonical, approval.mode().type().name());
            append(canonical, approval.next());
            append(canonical, approval.rejectNext());
        } else if (node instanceof ApprovalDefinition.HandleStep handle) {
            append(canonical, "HANDLE");
            appendBase(canonical, handle);
            appendAssignee(canonical, handle.assignee());
            append(canonical, handle.next());
        } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
            append(canonical, "CONDITION");
            appendBase(canonical, condition);
            append(canonical, condition.routes().size());
            for (ApprovalDefinition.ConditionRoute route : condition.routes()) {
                append(canonical, route.condition().field());
                append(canonical, route.condition().operator().name());
                append(
                    canonical,
                    route.condition().value().stripTrailingZeros().toPlainString()
                );
                append(canonical, route.next());
            }
            append(canonical, condition.defaultNext());
        } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
            append(canonical, "PARALLEL_SPLIT");
            appendBase(canonical, split);
            append(canonical, split.branches().size());
            for (ApprovalDefinition.ParallelBranch branch : split.branches()) {
                append(canonical, branch.id());
                append(canonical, branch.name());
                append(canonical, branch.next());
            }
            append(canonical, split.joinNodeId());
        } else if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
            append(canonical, "PARALLEL_JOIN");
            appendBase(canonical, join);
            append(canonical, join.next());
        } else {
            ApprovalDefinition.EndNode end = (ApprovalDefinition.EndNode) node;
            append(canonical, "END");
            appendBase(canonical, end);
        }
    }

    private static void appendBase(
        StringBuilder canonical,
        ApprovalDefinition.ProcessNode node
    ) {
        append(canonical, node.id());
        append(canonical, node.name());
    }

    private static void appendAssignee(
        StringBuilder canonical,
        ApprovalDefinition.AssigneeRule assignee
    ) {
        append(canonical, assignee.resolver().name());
        append(canonical, assignee.variable());
        append(canonical, assignee.emptyPolicy().name());
    }

    private static void append(StringBuilder canonical, int value) {
        append(canonical, Integer.toString(value));
    }

    private static void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-1:");
            return;
        }
        canonical.append(value.length()).append(':').append(value).append('|');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
