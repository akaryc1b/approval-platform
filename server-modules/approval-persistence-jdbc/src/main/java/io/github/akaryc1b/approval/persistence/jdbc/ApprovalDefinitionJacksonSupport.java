package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;

/** Registers the closed Approval DSL node discriminator without coupling the domain to Jackson. */
public final class ApprovalDefinitionJacksonSupport {

    private ApprovalDefinitionJacksonSupport() {
    }

    public static ObjectMapper configure(ObjectMapper objectMapper) {
        return objectMapper.addMixIn(
            ApprovalDefinition.ProcessNode.class,
            ProcessNodeMixin.class
        );
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "kind"
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ApprovalDefinition.StartNode.class, name = "START"),
        @JsonSubTypes.Type(value = ApprovalDefinition.ApprovalStep.class, name = "APPROVAL"),
        @JsonSubTypes.Type(value = ApprovalDefinition.HandleStep.class, name = "HANDLE"),
        @JsonSubTypes.Type(value = ApprovalDefinition.ConditionStep.class, name = "CONDITION"),
        @JsonSubTypes.Type(
            value = ApprovalDefinition.ParallelSplitNode.class,
            name = "PARALLEL_SPLIT"
        ),
        @JsonSubTypes.Type(
            value = ApprovalDefinition.ParallelJoinNode.class,
            name = "PARALLEL_JOIN"
        ),
        @JsonSubTypes.Type(value = ApprovalDefinition.EndNode.class, name = "END")
    })
    private abstract static class ProcessNodeMixin {
    }
}
