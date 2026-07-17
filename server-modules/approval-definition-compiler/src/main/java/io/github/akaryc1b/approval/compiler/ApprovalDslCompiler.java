package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministically compiles the product-owned Approval DSL into Flowable-compatible BPMN XML.
 */
public final class ApprovalDslCompiler {

    public static final String COMPILER_VERSION = "1.0.0";

    private final ApprovalDefinitionValidator validator;

    public ApprovalDslCompiler() {
        this(new ApprovalDefinitionValidator());
    }

    public ApprovalDslCompiler(ApprovalDefinitionValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public CompiledDefinition compile(
        ApprovalDefinition definition,
        FormDefinition formDefinition
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(formDefinition, "formDefinition must not be null");
        validator.validateOrThrow(definition, formDefinition);

        String bpmnXml = writeBpmn(definition);
        String resourceName = definition.definitionKey() + "-v" + definition.version() + ".bpmn20.xml";
        String hashMaterial = String.join(
            "\n",
            COMPILER_VERSION,
            definition.definitionKey(),
            Integer.toString(definition.version()),
            formDefinition.formKey(),
            Integer.toString(formDefinition.version()),
            bpmnXml
        );
        return new CompiledDefinition(
            definition.definitionKey(),
            definition.version(),
            formDefinition.formKey(),
            formDefinition.version(),
            COMPILER_VERSION,
            resourceName,
            bpmnXml,
            sha256(hashMaterial)
        );
    }

    private static String writeBpmn(ApprovalDefinition definition) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n")
            .append("             xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            .append("             xmlns:flowable=\"http://flowable.org/bpmn\"\n")
            .append("             targetNamespace=\"https://approval-platform.dev/bpmn\">\n")
            .append("  <process id=\"")
            .append(attribute(definition.definitionKey()))
            .append("\" name=\"")
            .append(attribute(definition.name()))
            .append("\" isExecutable=\"true\">\n");

        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            writeNode(xml, node);
        }
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            writeOutgoingFlows(xml, node);
        }
        xml.append("  </process>\n</definitions>\n");
        return xml.toString();
    }

    private static void writeNode(StringBuilder xml, ApprovalDefinition.ProcessNode node) {
        if (node instanceof ApprovalDefinition.StartNode startNode) {
            xml.append("    <startEvent id=\"")
                .append(attribute(startNode.id()))
                .append("\" name=\"")
                .append(attribute(startNode.name()))
                .append("\"/>\n");
            return;
        }
        if (node instanceof ApprovalDefinition.ApprovalStep approvalStep) {
            writeApproval(xml, approvalStep);
            return;
        }
        if (node instanceof ApprovalDefinition.ConditionStep conditionStep) {
            xml.append("    <exclusiveGateway id=\"")
                .append(attribute(conditionStep.id()))
                .append("\" name=\"")
                .append(attribute(conditionStep.name()))
                .append("\" default=\"")
                .append(defaultFlowId(conditionStep))
                .append("\"/>\n");
            return;
        }
        ApprovalDefinition.EndNode endNode = (ApprovalDefinition.EndNode) node;
        xml.append("    <endEvent id=\"")
            .append(attribute(endNode.id()))
            .append("\" name=\"")
            .append(attribute(endNode.name()))
            .append("\"/>\n");
    }

    private static void writeApproval(
        StringBuilder xml,
        ApprovalDefinition.ApprovalStep approval
    ) {
        boolean collection = approval.assignee().resolver()
            == ApprovalDefinition.AssigneeResolver.VARIABLE_USER_LIST;
        String assigneeVariable = collection
            ? approval.assignee().variable() + "Item"
            : approval.assignee().variable();
        xml.append("    <userTask id=\"")
            .append(attribute(approval.id()))
            .append("\" name=\"")
            .append(attribute(approval.name()))
            .append("\" flowable:assignee=\"${")
            .append(attribute(assigneeVariable))
            .append("}\"");
        if (!collection) {
            xml.append("/>\n");
            return;
        }
        xml.append(">\n")
            .append("      <multiInstanceLoopCharacteristics isSequential=\"false\"")
            .append(" flowable:collection=\"")
            .append(attribute(approval.assignee().variable()))
            .append("\" flowable:elementVariable=\"")
            .append(attribute(assigneeVariable))
            .append("\">\n")
            .append("        <completionCondition xsi:type=\"tFormalExpression\"><![CDATA[")
            .append(completionCondition(approval.mode().type()))
            .append("]]></completionCondition>\n")
            .append("      </multiInstanceLoopCharacteristics>\n")
            .append("    </userTask>\n");
    }

    private static void writeOutgoingFlows(
        StringBuilder xml,
        ApprovalDefinition.ProcessNode node
    ) {
        if (node instanceof ApprovalDefinition.StartNode startNode) {
            writeSimpleFlow(xml, simpleFlowId(startNode.id(), startNode.next()), startNode.id(), startNode.next());
            return;
        }
        if (node instanceof ApprovalDefinition.ApprovalStep approvalStep) {
            writeSimpleFlow(
                xml,
                simpleFlowId(approvalStep.id(), approvalStep.next()),
                approvalStep.id(),
                approvalStep.next()
            );
            return;
        }
        if (node instanceof ApprovalDefinition.ConditionStep conditionStep) {
            int index = 1;
            for (ApprovalDefinition.ConditionRoute route : conditionStep.routes()) {
                String flowId = "flow_" + conditionStep.id() + "_route_" + index;
                xml.append("    <sequenceFlow id=\"")
                    .append(attribute(flowId))
                    .append("\" sourceRef=\"")
                    .append(attribute(conditionStep.id()))
                    .append("\" targetRef=\"")
                    .append(attribute(route.next()))
                    .append("\">\n")
                    .append("      <conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[")
                    .append(conditionExpression(route.condition()))
                    .append("]]></conditionExpression>\n")
                    .append("    </sequenceFlow>\n");
                index++;
            }
            writeSimpleFlow(
                xml,
                defaultFlowId(conditionStep),
                conditionStep.id(),
                conditionStep.defaultNext()
            );
        }
    }

    private static void writeSimpleFlow(
        StringBuilder xml,
        String flowId,
        String source,
        String target
    ) {
        xml.append("    <sequenceFlow id=\"")
            .append(attribute(flowId))
            .append("\" sourceRef=\"")
            .append(attribute(source))
            .append("\" targetRef=\"")
            .append(attribute(target))
            .append("\"/>\n");
    }

    private static String defaultFlowId(ApprovalDefinition.ConditionStep condition) {
        return "flow_" + condition.id() + "_default";
    }

    private static String simpleFlowId(String source, String target) {
        return "flow_" + source + "_" + target;
    }

    private static String completionCondition(ApprovalDefinition.ApprovalModeType type) {
        return switch (type) {
            case ALL -> "${nrOfCompletedInstances == nrOfInstances}";
            case ANY -> "${nrOfCompletedInstances > 0}";
            case SINGLE -> throw new IllegalArgumentException(
                "SINGLE approval mode cannot compile as multi-instance"
            );
        };
    }

    private static String conditionExpression(ApprovalDefinition.ComparisonCondition condition) {
        String operator = switch (condition.operator()) {
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case EQUAL -> "==";
            case NOT_EQUAL -> "!=";
        };
        return "${" + condition.field() + " " + operator + " " + decimal(condition.value()) + "}";
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String attribute(String value) {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CompiledDefinition(
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String compilerVersion,
        String resourceName,
        String bpmnXml,
        String contentHash
    ) {

        public CompiledDefinition {
            definitionKey = requireText(definitionKey, "definitionKey");
            formKey = requireText(formKey, "formKey");
            compilerVersion = requireText(compilerVersion, "compilerVersion");
            resourceName = requireText(resourceName, "resourceName");
            bpmnXml = requireText(bpmnXml, "bpmnXml");
            contentHash = requireText(contentHash, "contentHash");
            if (definitionVersion < 1 || formVersion < 1) {
                throw new IllegalArgumentException("definition and form versions must be positive");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
