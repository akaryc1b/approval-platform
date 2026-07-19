package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalReleaseStructuralDiffTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-19T00:00:00Z");

    private final ApprovalReleaseStructuralDiff diff = new ApprovalReleaseStructuralDiff();

    @Test
    void producesDeterministicStructuralChanges() {
        ApprovalDefinitionVersion before = version(
            1,
            hash('1'),
            new ApprovalDefinition(
                ApprovalDefinition.CURRENT_SCHEMA_VERSION,
                "purchase-payment",
                1,
                "采购付款",
                "start",
                List.of(
                    new ApprovalDefinition.StartNode("start", "开始", "approve"),
                    new ApprovalDefinition.ApprovalStep(
                        "approve",
                        "部门审批",
                        assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER),
                        ApprovalDefinition.ApprovalMode.single(),
                        "end"
                    ),
                    new ApprovalDefinition.EndNode("end", "结束")
                )
            )
        );
        ApprovalDefinitionVersion after = version(
            2,
            hash('2'),
            new ApprovalDefinition(
                ApprovalDefinition.CURRENT_SCHEMA_VERSION,
                "purchase-payment",
                2,
                "采购付款流程",
                "start",
                List.of(
                    new ApprovalDefinition.StartNode("start", "开始", "approve"),
                    new ApprovalDefinition.ApprovalStep(
                        "approve",
                        "财务审批",
                        assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST),
                        ApprovalDefinition.ApprovalMode.all(),
                        "end",
                        "revise"
                    ),
                    new ApprovalDefinition.HandleStep(
                        "revise",
                        "申请人修订",
                        assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER),
                        "approve"
                    ),
                    new ApprovalDefinition.EndNode("end", "结束")
                )
            )
        );

        ApprovalReleaseStructuralDiff.Result first = diff.diff(before, after);
        ApprovalReleaseStructuralDiff.Result second = diff.diff(before, after);

        assertEquals(first, second);
        assertTrue(first.changes().stream().anyMatch(change ->
            change.changeType() == ApprovalReleaseStructuralDiff.ChangeType.ADDED
                && change.subjectId().equals("revise")
        ));
        assertTrue(first.changes().stream().anyMatch(change ->
            change.path().equals("/nodes/approve/assignee")
        ));
        assertTrue(first.changes().stream().anyMatch(change ->
            change.path().equals("/nodes/approve/mode")
        ));
        assertTrue(first.changes().stream().anyMatch(change ->
            change.path().equals("/nodes/approve/rejectNext")
        ));
    }

    @Test
    void reportsParallelBranchReorderingByStableBranchId() {
        ApprovalDefinitionVersion before = version(
            1,
            hash('3'),
            parallelDefinition(1, List.of(
                new ApprovalDefinition.ParallelBranch("finance", "财务", "finance-task"),
                new ApprovalDefinition.ParallelBranch("legal", "法务", "legal-task")
            ))
        );
        ApprovalDefinitionVersion after = version(
            2,
            hash('4'),
            parallelDefinition(2, List.of(
                new ApprovalDefinition.ParallelBranch("legal", "法务", "legal-task"),
                new ApprovalDefinition.ParallelBranch("finance", "财务", "finance-task")
            ))
        );

        ApprovalReleaseStructuralDiff.Result result = diff.diff(before, after);

        List<ApprovalReleaseStructuralDiff.Change> reorderChanges = result.changes().stream()
            .filter(change -> change.changeType()
                == ApprovalReleaseStructuralDiff.ChangeType.REORDERED)
            .toList();
        assertEquals(1, reorderChanges.size());
        assertEquals("split", reorderChanges.getFirst().subjectId());
        assertEquals("/nodes/split/branches/order", reorderChanges.getFirst().path());
        assertEquals(List.of("finance", "legal"), reorderChanges.getFirst().before());
        assertEquals(List.of("legal", "finance"), reorderChanges.getFirst().after());
    }

    @Test
    void reportsConditionRouteOrderSeparatelyFromExpressionChanges() {
        ApprovalDefinition.ConditionRoute high = route(
            "amount",
            ApprovalDefinition.ComparisonOperator.GREATER_THAN,
            1000,
            "manager"
        );
        ApprovalDefinition.ConditionRoute low = route(
            "amount",
            ApprovalDefinition.ComparisonOperator.LESS_THAN_OR_EQUAL,
            1000,
            "end"
        );
        ApprovalDefinitionVersion before = version(1, hash('5'), conditionDefinition(1, high, low));
        ApprovalDefinitionVersion after = version(2, hash('6'), conditionDefinition(2, low, high));

        ApprovalReleaseStructuralDiff.Result result = diff.diff(before, after);

        assertTrue(result.changes().stream().anyMatch(change ->
            change.changeType() == ApprovalReleaseStructuralDiff.ChangeType.REORDERED
                && change.path().equals("/nodes/route/routes/order")
        ));
    }

    private static ApprovalDefinition parallelDefinition(
        int version,
        List<ApprovalDefinition.ParallelBranch> branches
    ) {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "purchase-payment",
            version,
            "并行审批",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "开始", "split"),
                new ApprovalDefinition.ParallelSplitNode(
                    "split",
                    "并行审批",
                    branches,
                    "join"
                ),
                new ApprovalDefinition.HandleStep(
                    "finance-task",
                    "财务处理",
                    assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER),
                    "join"
                ),
                new ApprovalDefinition.HandleStep(
                    "legal-task",
                    "法务处理",
                    assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER),
                    "join"
                ),
                new ApprovalDefinition.ParallelJoinNode("join", "汇聚", "end"),
                new ApprovalDefinition.EndNode("end", "结束")
            )
        );
    }

    private static ApprovalDefinition conditionDefinition(
        int version,
        ApprovalDefinition.ConditionRoute first,
        ApprovalDefinition.ConditionRoute second
    ) {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "purchase-payment",
            version,
            "条件审批",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "开始", "route"),
                new ApprovalDefinition.ConditionStep(
                    "route",
                    "金额路由",
                    List.of(first, second),
                    "end"
                ),
                new ApprovalDefinition.HandleStep(
                    "manager",
                    "经理处理",
                    assignee(ApprovalDefinition.AssigneeResolver.VARIABLE_USER),
                    "end"
                ),
                new ApprovalDefinition.EndNode("end", "结束")
            )
        );
    }

    private static ApprovalDefinition.ConditionRoute route(
        String field,
        ApprovalDefinition.ComparisonOperator operator,
        int value,
        String next
    ) {
        return new ApprovalDefinition.ConditionRoute(
            new ApprovalDefinition.ComparisonCondition(
                field,
                operator,
                java.math.BigDecimal.valueOf(value)
            ),
            next
        );
    }

    private static ApprovalDefinition.AssigneeRule assignee(
        ApprovalDefinition.AssigneeResolver resolver
    ) {
        return new ApprovalDefinition.AssigneeRule(
            resolver,
            "approverIds",
            ApprovalDefinition.EmptyAssigneePolicy.FAIL
        );
    }

    private static ApprovalDefinitionVersion version(
        int version,
        String hash,
        ApprovalDefinition definition
    ) {
        return new ApprovalDefinitionVersion(
            "tenant-a",
            "purchase-payment",
            version,
            hash,
            1,
            hash('f'),
            definition,
            UUID.nameUUIDFromBytes(("draft-" + version).getBytes(StandardCharsets.UTF_8)),
            "subject:publisher",
            PUBLISHED_AT.plusSeconds(version)
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
