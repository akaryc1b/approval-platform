package io.github.akaryc1b.approval.compiler;

import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalDslCompilerTest {

    private final ApprovalDslCompiler compiler = new ApprovalDslCompiler();
    private final ApprovalDefinitionValidator validator = new ApprovalDefinitionValidator();

    @Test
    void compilesPurchasePaymentDefinitionDeterministically() {
        ApprovalDefinition process = PurchasePaymentTemplate.processDefinition();
        FormDefinition form = PurchasePaymentTemplate.formDefinition();

        var first = compiler.compile(process, form);
        var second = compiler.compile(process, form);

        assertEquals(first, second);
        assertEquals("purchase-payment-v2.bpmn20.xml", first.resourceName());
        assertEquals("1.1.0", first.compilerVersion());
        assertEquals(64, first.contentHash().length());
        assertTrue(first.bpmnXml().contains("<process id=\"purchase-payment\""));
        assertTrue(first.bpmnXml().contains("flowable:assignee=\"${managerAssignee}\""));
        assertTrue(first.bpmnXml().contains("flowable:assignee=\"${initiatorAssignee}\""));
        assertTrue(first.bpmnXml().contains("${amount >= 10000}"));
        assertTrue(first.bpmnXml().contains("flowable:collection=\"financeApprovers\""));
        assertTrue(first.bpmnXml().contains(
            "${approvalDecision == 'REJECTED' || nrOfCompletedInstances == nrOfInstances}"
        ));
        assertTrue(first.bpmnXml().contains("${approvalDecision == 'REJECTED'}"));
        assertTrue(first.bpmnXml().contains("targetRef=\"initiatorRevision\""));
        assertTrue(validator.validate(process, form).valid());
    }

    @Test
    void rejectsUnsafeAssigneeVariables() {
        ApprovalDefinition original = PurchasePaymentTemplate.processDefinition();
        ApprovalDefinition.ApprovalStep unsafe = new ApprovalDefinition.ApprovalStep(
            "unsafeApproval",
            "Unsafe",
            new ApprovalDefinition.AssigneeRule(
                ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
                "${system.exit()}",
                ApprovalDefinition.EmptyAssigneePolicy.FAIL
            ),
            ApprovalDefinition.ApprovalMode.single(),
            "end"
        );
        ApprovalDefinition definition = new ApprovalDefinition(
            original.schemaVersion(),
            original.definitionKey(),
            original.version(),
            original.name(),
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "unsafeApproval"),
                unsafe,
                new ApprovalDefinition.EndNode("end", "End")
            )
        );

        var report = validator.validate(definition, PurchasePaymentTemplate.formDefinition());

        assertFalse(report.valid());
        assertTrue(report.issues().stream()
            .anyMatch(issue -> "INVALID_ASSIGNEE_VARIABLE".equals(issue.code())));
        assertThrows(
            ApprovalDefinitionValidator.DefinitionValidationException.class,
            () -> compiler.compile(definition, PurchasePaymentTemplate.formDefinition())
        );
    }

    @Test
    void rejectsUnknownGraphReferences() {
        ApprovalDefinition definition = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "Invalid graph",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "missing"),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );

        var report = validator.validate(definition, PurchasePaymentTemplate.formDefinition());

        assertFalse(report.valid());
        assertTrue(report.issues().stream()
            .anyMatch(issue -> "UNKNOWN_NODE_REFERENCE".equals(issue.code())));
        assertTrue(report.issues().stream()
            .anyMatch(issue -> "UNREACHABLE_NODE".equals(issue.code())));
    }

    @Test
    void rejectsUncontrolledCyclesWithoutHandleStep() {
        ApprovalDefinition definition = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            "Unsafe cycle",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "approvalA"),
                new ApprovalDefinition.ApprovalStep(
                    "approvalA",
                    "Approval A",
                    singleAssignee("managerA"),
                    ApprovalDefinition.ApprovalMode.single(),
                    "approvalB"
                ),
                new ApprovalDefinition.ApprovalStep(
                    "approvalB",
                    "Approval B",
                    singleAssignee("managerB"),
                    ApprovalDefinition.ApprovalMode.single(),
                    "approvalA"
                ),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );

        var report = validator.validate(definition, PurchasePaymentTemplate.formDefinition());

        assertFalse(report.valid());
        assertTrue(report.issues().stream()
            .anyMatch(issue -> "PROCESS_CYCLE".equals(issue.code())));
    }

    @Test
    void rejectsFormAndProcessKeyMismatch() {
        FormDefinition source = PurchasePaymentTemplate.formDefinition();
        FormDefinition otherForm = new FormDefinition(
            source.schemaVersion(),
            "other-form",
            source.version(),
            source.name(),
            source.fields()
        );

        var report = validator.validate(PurchasePaymentTemplate.processDefinition(), otherForm);

        assertFalse(report.valid());
        assertTrue(report.issues().stream()
            .anyMatch(issue -> "FORM_PROCESS_KEY_MISMATCH".equals(issue.code())));
    }

    private static ApprovalDefinition.AssigneeRule singleAssignee(String variable) {
        return new ApprovalDefinition.AssigneeRule(
            ApprovalDefinition.AssigneeResolver.VARIABLE_USER,
            variable,
            ApprovalDefinition.EmptyAssigneePolicy.FAIL
        );
    }
}
