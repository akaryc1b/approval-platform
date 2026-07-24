package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService;
import io.github.akaryc1b.approval.application.ApprovalFormComponentRegistry;
import io.github.akaryc1b.approval.application.ProcessTemplateCanonicalHasher;
import io.github.akaryc1b.approval.application.ProcessTemplateDraftCreationService;
import io.github.akaryc1b.approval.application.ProcessTemplateFormPackageEvidenceHasher;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator;
import io.github.akaryc1b.approval.application.ProcessTemplateImportCoordinator;
import io.github.akaryc1b.approval.application.ProcessTemplateImportPreviewService;
import io.github.akaryc1b.approval.application.ProcessTemplateLocalFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.ProcessTemplateLocalTenantRegistryResolver;
import io.github.akaryc1b.approval.application.ProcessTemplatePackageValidator;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** Local-only production wiring for governed process-template preview and DRAFT import. */
@Configuration(proxyBeanMethods = false)
public class ProcessTemplateImportConfiguration {

    @Bean
    ApprovalFormComponentRegistry approvalFormComponentRegistry() {
        return new ApprovalFormComponentRegistry();
    }

    @Bean
    ProcessTemplatePackageValidator processTemplatePackageValidator() {
        return new ProcessTemplatePackageValidator();
    }

    @Bean
    ProcessTemplateCanonicalHasher processTemplateCanonicalHasher() {
        return new ProcessTemplateCanonicalHasher();
    }

    @Bean
    ProcessTemplateImportPreviewService processTemplateImportPreviewService(
        ProcessTemplatePackageValidator validator,
        ProcessTemplateCanonicalHasher hasher
    ) {
        return new ProcessTemplateImportPreviewService(validator, hasher);
    }

    @Bean
    ProcessTemplateDraftCreationService processTemplateDraftCreationService(
        ProcessTemplateImportPreviewService previewService,
        ApprovalArtifactTransferService artifactTransferService
    ) {
        return new ProcessTemplateDraftCreationService(previewService, artifactTransferService);
    }

    @Bean
    ProcessTemplateTenantRegistryResolver processTemplateTenantRegistryResolver(
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalFormComponentRegistry componentRegistry,
        @Value("${approval.process-template.platform-protocol-version:1.0.0}")
        String platformProtocolVersion,
        @Value("${approval.process-template.connector-capabilities:}")
        String connectorCapabilities,
        @Value("${approval.process-template.business-reference-types:}")
        String businessReferenceTypes,
        @Value("${approval.process-template.organization-placeholders:}")
        String organizationPlaceholders,
        @Value("${approval.process-template.identity-placeholders:}")
        String identityPlaceholders
    ) {
        return new ProcessTemplateLocalTenantRegistryResolver(
            packages,
            forms,
            uiSchemas,
            componentRegistry,
            platformProtocolVersion,
            csv(connectorCapabilities),
            csv(businessReferenceTypes),
            csv(organizationPlaceholders),
            csv(identityPlaceholders)
        );
    }

    @Bean
    ProcessTemplateImportCoordinator processTemplateImportCoordinator(
        ProcessTemplateImportPreviewService previewService,
        ProcessTemplateDraftCreationService draftCreationService,
        ProcessTemplateTenantRegistryResolver registryResolver
    ) {
        return new ProcessTemplateImportCoordinator(
            previewService,
            draftCreationService,
            registryResolver
        );
    }

    @Bean
    ProcessTemplateFormPackageEvidenceHasher processTemplateFormPackageEvidenceHasher() {
        return new ProcessTemplateFormPackageEvidenceHasher();
    }

    @Bean
    ProcessTemplateFormPackageEvidenceResolver processTemplateFormPackageEvidenceResolver(
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ProcessTemplateFormPackageEvidenceHasher hasher
    ) {
        return new ProcessTemplateLocalFormPackageEvidenceResolver(
            packages,
            forms,
            uiSchemas,
            hasher
        );
    }

    @Bean
    ProcessTemplateGovernedImportCoordinator processTemplateGovernedImportCoordinator(
        ProcessTemplateImportCoordinator coordinator,
        ProcessTemplateFormPackageEvidenceResolver formPackageResolver,
        ProcessTemplateFormPackageEvidenceHasher hasher
    ) {
        return new ProcessTemplateGovernedImportCoordinator(
            coordinator,
            formPackageResolver,
            hasher
        );
    }

    static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        TreeSet<String> result = new TreeSet<>();
        Arrays.stream(value.split(",", -1))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .forEach(result::add);
        return Set.copyOf(result);
    }
}
