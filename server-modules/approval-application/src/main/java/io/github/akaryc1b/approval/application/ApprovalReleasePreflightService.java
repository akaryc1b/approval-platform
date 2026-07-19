package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Server-authoritative publication and deployment readiness checks. */
public final class ApprovalReleasePreflightService {

    private static final String PREFLIGHT_VERSION = "approval-release-preflight-v1";
    private static final Comparator<Issue> ISSUE_ORDER = Comparator
        .comparing(Issue::severity)
        .thenComparing(Issue::code)
        .thenComparing(Issue::subject)
        .thenComparing(Issue::message);

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalReleaseDeploymentStore deployments;
    private final ApprovalFormPackageResolver formResolver;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDefinitionSimulator simulator;
    private final ApprovalDslCompiler compiler;
    private final ApprovalDefinitionHasher definitionHasher;
    private final ApprovalReleasePackageHasher releaseHasher;

    public ApprovalReleasePreflightService(
        ApprovalDesignDraftStore drafts,
        ApprovalDefinitionVersionStore definitions,
        ApprovalReleasePackageStore releases,
        ApprovalReleaseDeploymentStore deployments,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator,
        ApprovalDslCompiler compiler,
        ApprovalDefinitionHasher definitionHasher,
        ApprovalReleasePackageHasher releaseHasher
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.definitions = Objects.requireNonNull(definitions);
        this.releases = Objects.requireNonNull(releases);
        this.deployments = Objects.requireNonNull(deployments);
        this.formResolver = new ApprovalFormPackageResolver(formPackages, forms, uiSchemas);
        this.validator = Objects.requireNonNull(validator);
        this.simulator = Objects.requireNonNull(simulator);
        this.compiler = Objects.requireNonNull(compiler);
        this.definitionHasher = Objects.requireNonNull(definitionHasher);
        this.releaseHasher = Objects.requireNonNull(releaseHasher);
    }

    public PreflightReport preflightPublication(PublicationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ApprovalDesignDraft draft = drafts.find(request.tenantId(), request.draftId())
            .orElseThrow(() -> new ApprovalDesignExceptions.DraftNotFound(
                "Approval DSL draft was not found for the tenant"
            ));
        List<Issue> issues = new ArrayList<>();
        if (draft.status() == ApprovalDesignDraft.Status.ARCHIVED) {
            issues.add(error(
                "DRAFT_ARCHIVED",
                draft.draftId().toString(),
                "archived Approval DSL drafts cannot be published"
            ));
        }
        if (draft.revision() != request.expectedRevision()) {
            issues.add(error(
                "DRAFT_REVISION_STALE",
                draft.draftId().toString(),
                "draft revision changed from the expected revision"
            ));
        }
        if (draft.definition().version() != request.targetDefinitionVersion()) {
            issues.add(error(
                "DEFINITION_VERSION_MISMATCH",
                draft.definitionKey(),
                "target definition version must match the Approval DSL draft version"
            ));
        }

        String definitionHash = definitionHasher.hash(draft.definition());
        checkDefinitionVersion(
            request,
            definitionHash,
            draft.formPackage().packageHash(),
            issues
        );

        ApprovalFormPackageResolver.ExactFormPackage exact = null;
        try {
            exact = formResolver.resolve(draft);
        } catch (RuntimeException exception) {
            issues.add(error(
                "FORM_PACKAGE_RESOLUTION_FAILED",
                draft.formPackage().formKey(),
                safeMessage(exception, "exact Form Package could not be resolved")
            ));
        }

        ApprovalDslCompiler.CompiledDefinition compiled = null;
        CompilerSummary compilerSummary = CompilerSummary.notRun();
        SimulationSummary simulationSummary = SimulationSummary.notRun();
        DeploymentCompatibilitySummary compatibility =
            DeploymentCompatibilitySummary.notChecked(request.deploymentTarget());
        GeneratedHashes hashes = new GeneratedHashes(
            definitionHash,
            draft.formPackage().packageHash(),
            null,
            null,
            null,
            null
        );

        if (exact != null) {
            ApprovalDefinitionValidator.ValidationReport validation = validator.validate(
                draft.definition(),
                exact.form().definition(),
                exact.uiSchema().definition()
            );
            validation.issues().forEach(value -> issues.add(new Issue(
                Severity.valueOf(value.severity().name()),
                value.code(),
                value.subject(),
                value.message()
            )));
            if (!hasErrors(issues)) {
                CompilationResult compilation = compileDeterministically(draft, exact, issues);
                compiled = compilation.compiled();
                compilerSummary = compilation.summary();
                if (compiled != null) {
                    String bpmnHash = releaseHasher.artifactHash(compiled.bpmnXml());
                    String metadataHash = releaseHasher.deploymentMetadataHash(
                        compiled.compilerVersion(),
                        compiled.resourceName(),
                        bpmnHash
                    );
                    String packageHash = releaseHasher.hash(
                        draft.definitionKey(),
                        request.targetReleaseVersion(),
                        request.targetDefinitionVersion(),
                        definitionHash,
                        exact.formPackage().packageVersion(),
                        exact.formPackage().packageHash(),
                        exact.formPackage().formVersion(),
                        exact.formPackage().formHash(),
                        exact.formPackage().uiSchemaVersion(),
                        exact.formPackage().uiSchemaHash(),
                        compiled.compilerVersion(),
                        compiled.resourceName(),
                        compiled.bpmnXml(),
                        compiled.contentHash(),
                        bpmnHash,
                        null,
                        null,
                        metadataHash
                    );
                    hashes = new GeneratedHashes(
                        definitionHash,
                        exact.formPackage().packageHash(),
                        compiled.contentHash(),
                        bpmnHash,
                        metadataHash,
                        packageHash
                    );
                    compatibility = inspectBpmn(
                        request.deploymentTarget(),
                        draft.definitionKey(),
                        compiled.bpmnXml(),
                        null,
                        false,
                        issues
                    );
                    checkReleaseVersion(request, draft, packageHash, issues);
                    simulationSummary = simulate(request, draft, exact, issues);
                }
            } else {
                issues.add(info(
                    "COMPILATION_SKIPPED",
                    draft.definitionKey(),
                    "compilation was skipped because static validation has errors"
                ));
            }
        }

        return report(
            Scope.PUBLICATION,
            request.tenantId(),
            draft.draftId(),
            draft.revision(),
            draft.definitionKey(),
            request.targetDefinitionVersion(),
            request.targetReleaseVersion(),
            request.deploymentTarget(),
            hashes,
            compilerSummary,
            simulationSummary,
            compatibility,
            issues,
            compatibility.supported()
        );
    }

    public PreflightReport preflightDeployment(DeploymentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ApprovalReleasePackage release = releases.find(
            request.tenantId(),
            request.definitionKey(),
            request.releaseVersion()
        ).orElseThrow(() -> new ReleasePackageNotFoundException(
            "Release Package was not found for the tenant"
        ));
        List<Issue> issues = new ArrayList<>();
        String bpmnHash = releaseHasher.artifactHash(release.bpmnArtifact());
        String metadataHash = releaseHasher.deploymentMetadataHash(
            release.compilerVersion(),
            release.bpmnResourceName(),
            bpmnHash
        );
        String packageHash = releaseHasher.hash(
            release.definitionKey(),
            release.releaseVersion(),
            release.definitionVersion(),
            release.definitionHash(),
            release.formPackageVersion(),
            release.formPackageHash(),
            release.formVersion(),
            release.formHash(),
            release.uiSchemaVersion(),
            release.uiSchemaHash(),
            release.compilerVersion(),
            release.bpmnResourceName(),
            release.bpmnArtifact(),
            release.compiledArtifactHash(),
            bpmnHash,
            release.dmnArtifact(),
            release.dmnHash(),
            metadataHash
        );
        requireHashMatch("BPMN_HASH_MISMATCH", release.bpmnHash(), bpmnHash, issues);
        requireHashMatch(
            "DEPLOYMENT_METADATA_HASH_MISMATCH",
            release.deploymentMetadataHash(),
            metadataHash,
            issues
        );
        requireHashMatch("RELEASE_PACKAGE_HASH_MISMATCH", release.packageHash(), packageHash, issues);

        ApprovalReleaseDeployment deployment = deployments.find(
            request.tenantId(),
            request.definitionKey(),
            request.releaseVersion()
        ).orElse(null);
        String deploymentStatus = deployment == null ? null : deployment.status().name();
        boolean semanticReplay = false;
        if (deployment == null) {
            issues.add(info(
                "NO_EXISTING_DEPLOYMENT",
                release.definitionKey(),
                "Release Package has not been deployed to this platform projection"
            ));
        } else {
            if (!deployment.releasePackageHash().equals(release.packageHash())) {
                issues.add(error(
                    "DEPLOYMENT_PACKAGE_HASH_MISMATCH",
                    release.definitionKey(),
                    "deployment projection references different Release Package content"
                ));
            }
            if (deployment.status() == ApprovalReleaseDeployment.Status.PENDING) {
                issues.add(error(
                    "DEPLOYMENT_PENDING",
                    release.definitionKey(),
                    "a deployment attempt is already pending"
                ));
            } else if (deployment.status() == ApprovalReleaseDeployment.Status.FAILED) {
                issues.add(warning(
                    "PREVIOUS_DEPLOYMENT_FAILED",
                    release.definitionKey(),
                    "the previous deployment failed and requires an explicit retry"
                ));
            } else {
                semanticReplay = true;
                issues.add(info(
                    "SEMANTIC_DEPLOYMENT_REPLAY",
                    release.definitionKey(),
                    "the same immutable Release Package is already deployed"
                ));
            }
        }

        DeploymentCompatibilitySummary compatibility = inspectBpmn(
            request.deploymentTarget(),
            release.definitionKey(),
            release.bpmnArtifact(),
            deploymentStatus,
            semanticReplay,
            issues
        );
        GeneratedHashes hashes = new GeneratedHashes(
            release.definitionHash(),
            release.formPackageHash(),
            release.compiledArtifactHash(),
            bpmnHash,
            metadataHash,
            packageHash
        );
        CompilerSummary compilerSummary = new CompilerSummary(
            true,
            true,
            true,
            release.compilerVersion(),
            release.bpmnResourceName(),
            release.bpmnArtifact().getBytes(StandardCharsets.UTF_8).length
        );
        boolean deploymentAllowed = deployment == null
            || deployment.status() != ApprovalReleaseDeployment.Status.PENDING;
        return report(
            Scope.DEPLOYMENT,
            request.tenantId(),
            release.sourceDraftId(),
            null,
            release.definitionKey(),
            release.definitionVersion(),
            release.releaseVersion(),
            request.deploymentTarget(),
            hashes,
            compilerSummary,
            SimulationSummary.notRun(),
            compatibility,
            issues,
            deploymentAllowed && compatibility.supported()
        );
    }

    private CompilationResult compileDeterministically(
        ApprovalDesignDraft draft,
        ApprovalFormPackageResolver.ExactFormPackage exact,
        List<Issue> issues
    ) {
        try {
            ApprovalDslCompiler.CompiledDefinition first = compiler.compile(
                draft.definition(),
                exact.form().definition()
            );
            ApprovalDslCompiler.CompiledDefinition second = compiler.compile(
                draft.definition(),
                exact.form().definition()
            );
            boolean deterministic = first.equals(second);
            if (!deterministic) {
                issues.add(error(
                    "NON_DETERMINISTIC_COMPILATION",
                    draft.definitionKey(),
                    "repeated compilation produced different immutable artifacts"
                ));
            }
            return new CompilationResult(first, new CompilerSummary(
                true,
                deterministic,
                deterministic,
                first.compilerVersion(),
                first.resourceName(),
                first.bpmnXml().getBytes(StandardCharsets.UTF_8).length
            ));
        } catch (RuntimeException exception) {
            issues.add(error(
                "COMPILATION_FAILED",
                draft.definitionKey(),
                safeMessage(exception, "Approval DSL compilation failed")
            ));
            return new CompilationResult(null, new CompilerSummary(
                true,
                false,
                false,
                ApprovalDslCompiler.COMPILER_VERSION,
                null,
                0
            ));
        }
    }

    private SimulationSummary simulate(
        PublicationRequest request,
        ApprovalDesignDraft draft,
        ApprovalFormPackageResolver.ExactFormPackage exact,
        List<Issue> issues
    ) {
        if (request.scenario() == null) {
            issues.add(info(
                "SIMULATION_NOT_REQUESTED",
                draft.definitionKey(),
                "no publication scenario was supplied for this preflight"
            ));
            return SimulationSummary.notRun();
        }
        try {
            ApprovalDefinitionSimulator.SimulationResult result = simulator.simulate(
                draft.definition(),
                exact.form().definition(),
                request.scenario()
            );
            List<String> codes = result.issues().stream()
                .map(ApprovalDefinitionSimulator.SimulationIssue::code)
                .sorted()
                .toList();
            if (result.status() == ApprovalDefinitionSimulator.SimulationStatus.BLOCKED
                || result.status()
                    == ApprovalDefinitionSimulator.SimulationStatus.TRANSITION_LIMIT_REACHED) {
                issues.add(warning(
                    "SIMULATION_NOT_TERMINAL",
                    result.terminalNodeId(),
                    "publication scenario did not reach a normal terminal result"
                ));
            } else {
                issues.add(info(
                    "SIMULATION_TERMINAL",
                    result.terminalNodeId(),
                    "publication scenario reached " + result.status().name()
                ));
            }
            return new SimulationSummary(
                true,
                true,
                result.status().name(),
                result.terminalNodeId(),
                result.steps().size(),
                codes
            );
        } catch (RuntimeException exception) {
            issues.add(error(
                "SIMULATION_FAILED",
                draft.definitionKey(),
                safeMessage(exception, "publication simulation failed")
            ));
            return new SimulationSummary(true, false, null, null, 0, List.of());
        }
    }

    private void checkDefinitionVersion(
        PublicationRequest request,
        String definitionHash,
        String formPackageHash,
        List<Issue> issues
    ) {
        definitions.find(
            request.tenantId(),
            request.definitionKey(),
            request.targetDefinitionVersion()
        ).ifPresent(existing -> {
            if (existing.contentHash().equals(definitionHash)
                && existing.formPackageHash().equals(formPackageHash)) {
                issues.add(info(
                    "SEMANTIC_DEFINITION_REPLAY",
                    request.definitionKey(),
                    "target Approval DSL version already has the same immutable content"
                ));
            } else {
                issues.add(error(
                    "DEFINITION_VERSION_CONFLICT",
                    request.definitionKey(),
                    "target Approval DSL version already has different immutable content"
                ));
            }
        });
    }

    private void checkReleaseVersion(
        PublicationRequest request,
        ApprovalDesignDraft draft,
        String packageHash,
        List<Issue> issues
    ) {
        releases.find(
            request.tenantId(),
            request.definitionKey(),
            request.targetReleaseVersion()
        ).ifPresent(existing -> {
            if (existing.packageHash().equals(packageHash)) {
                issues.add(info(
                    "SEMANTIC_RELEASE_REPLAY",
                    request.definitionKey(),
                    "target Release Package version already has the same immutable content"
                ));
            } else {
                issues.add(error(
                    "RELEASE_VERSION_CONFLICT",
                    request.definitionKey(),
                    "target Release Package version already has different immutable content"
                ));
            }
        });
        releases.findByDraft(request.tenantId(), draft.draftId()).ifPresent(existing -> {
            if (existing.releaseVersion() != request.targetReleaseVersion()
                || existing.definitionVersion() != request.targetDefinitionVersion()
                || !existing.packageHash().equals(packageHash)) {
                issues.add(error(
                    "DRAFT_ALREADY_PUBLISHED_DIFFERENTLY",
                    draft.draftId().toString(),
                    "draft is already bound to a different immutable release"
                ));
            }
        });
    }

    private DeploymentCompatibilitySummary inspectBpmn(
        String target,
        String definitionKey,
        String bpmn,
        String existingDeploymentStatus,
        boolean semanticReplay,
        List<Issue> issues
    ) {
        String processKey = null;
        boolean wellFormed = false;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(bpmn))
            );
            var processes = document.getElementsByTagNameNS("*", "process");
            if (processes.getLength() != 1) {
                issues.add(error(
                    "BPMN_PROCESS_COUNT_INVALID",
                    definitionKey,
                    "BPMN artifact must contain exactly one process"
                ));
            } else {
                processKey = processes.item(0).getAttributes().getNamedItem("id").getNodeValue();
                wellFormed = true;
                if (!definitionKey.equals(processKey)) {
                    issues.add(error(
                        "BPMN_PROCESS_KEY_MISMATCH",
                        definitionKey,
                        "BPMN process key does not match the platform definition key"
                    ));
                }
            }
        } catch (Exception exception) {
            issues.add(error(
                "BPMN_NOT_WELL_FORMED",
                definitionKey,
                "BPMN artifact could not be parsed safely"
            ));
        }
        boolean keyMatches = definitionKey.equals(processKey);
        return new DeploymentCompatibilitySummary(
            target,
            wellFormed,
            processKey,
            keyMatches,
            existingDeploymentStatus,
            semanticReplay,
            wellFormed && keyMatches
        );
    }

    private PreflightReport report(
        Scope scope,
        String tenantId,
        UUID draftId,
        Long draftRevision,
        String definitionKey,
        int definitionVersion,
        int releaseVersion,
        String target,
        GeneratedHashes hashes,
        CompilerSummary compilerSummary,
        SimulationSummary simulationSummary,
        DeploymentCompatibilitySummary compatibility,
        List<Issue> issues,
        boolean targetDeployable
    ) {
        List<Issue> ordered = issues.stream().sorted(ISSUE_ORDER).toList();
        List<Issue> errors = severity(ordered, Severity.ERROR);
        List<Issue> warnings = severity(ordered, Severity.WARNING);
        List<Issue> infos = severity(ordered, Severity.INFO);
        boolean publishable = errors.isEmpty();
        boolean deployable = errors.isEmpty() && targetDeployable;
        String issueMaterial = ordered.stream()
            .map(value -> value.severity()
                + "|"
                + value.code()
                + "|"
                + value.subject()
                + "|"
                + value.message())
            .collect(Collectors.joining("\n"));
        String hash = releaseHasher.hashValues(
            PREFLIGHT_VERSION,
            scope,
            tenantId,
            draftId,
            draftRevision,
            definitionKey,
            definitionVersion,
            releaseVersion,
            target,
            hashes.definitionHash(),
            hashes.formPackageHash(),
            hashes.compiledArtifactHash(),
            hashes.bpmnHash(),
            hashes.deploymentMetadataHash(),
            hashes.releasePackageHash(),
            compilerSummary.compilerVersion(),
            compilerSummary.resourceName(),
            simulationSummary.status(),
            simulationSummary.terminalNodeId(),
            compatibility.processDefinitionKey(),
            compatibility.existingDeploymentStatus(),
            issueMaterial
        );
        return new PreflightReport(
            scope,
            tenantId,
            draftId,
            draftRevision,
            definitionKey,
            definitionVersion,
            releaseVersion,
            target,
            errors,
            warnings,
            infos,
            hashes,
            compilerSummary,
            simulationSummary,
            compatibility,
            publishable,
            deployable,
            hash
        );
    }

    private static List<Issue> severity(List<Issue> issues, Severity severity) {
        return issues.stream().filter(value -> value.severity() == severity).toList();
    }

    private static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(value -> value.severity() == Severity.ERROR);
    }

    private static void requireHashMatch(
        String code,
        String expected,
        String actual,
        List<Issue> issues
    ) {
        if (!expected.equals(actual)) {
            issues.add(error(code, "Release Package", "stored and generated hashes differ"));
        }
    }

    private static Issue error(String code, String subject, String message) {
        return new Issue(Severity.ERROR, code, subject, message);
    }

    private static Issue warning(String code, String subject, String message) {
        return new Issue(Severity.WARNING, code, subject, message);
    }

    private static Issue info(String code, String subject, String message) {
        return new Issue(Severity.INFO, code, subject, message);
    }

    private static String safeMessage(RuntimeException exception, String fallback) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String target(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private record CompilationResult(
        ApprovalDslCompiler.CompiledDefinition compiled,
        CompilerSummary summary
    ) {
    }

    public enum Scope {
        PUBLICATION,
        DEPLOYMENT
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public record PublicationRequest(
        String tenantId,
        UUID draftId,
        long expectedRevision,
        String definitionKey,
        int targetDefinitionVersion,
        int targetReleaseVersion,
        String deploymentTarget,
        ApprovalDefinitionSimulator.Scenario scenario
    ) {
        public PublicationRequest {
            tenantId = text(tenantId, "tenantId");
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
            definitionKey = text(definitionKey, "definitionKey");
            if (targetDefinitionVersion < 1 || targetReleaseVersion < 1) {
                throw new IllegalArgumentException("target versions must be positive");
            }
            deploymentTarget = target(deploymentTarget);
        }
    }

    public record DeploymentRequest(
        String tenantId,
        String definitionKey,
        int releaseVersion,
        String deploymentTarget
    ) {
        public DeploymentRequest {
            tenantId = text(tenantId, "tenantId");
            definitionKey = text(definitionKey, "definitionKey");
            if (releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
            deploymentTarget = target(deploymentTarget);
        }
    }

    public record Issue(Severity severity, String code, String subject, String message) {
        public Issue {
            severity = Objects.requireNonNull(severity, "severity must not be null");
            code = text(code, "code");
            subject = text(subject, "subject");
            message = text(message, "message");
        }
    }

    public record GeneratedHashes(
        String definitionHash,
        String formPackageHash,
        String compiledArtifactHash,
        String bpmnHash,
        String deploymentMetadataHash,
        String releasePackageHash
    ) {
    }

    public record CompilerSummary(
        boolean attempted,
        boolean successful,
        boolean deterministic,
        String compilerVersion,
        String resourceName,
        int artifactBytes
    ) {
        public CompilerSummary {
            if (artifactBytes < 0) {
                throw new IllegalArgumentException("artifactBytes must not be negative");
            }
        }

        private static CompilerSummary notRun() {
            return new CompilerSummary(
                false,
                false,
                false,
                ApprovalDslCompiler.COMPILER_VERSION,
                null,
                0
            );
        }
    }

    public record SimulationSummary(
        boolean requested,
        boolean executed,
        String status,
        String terminalNodeId,
        int stepCount,
        List<String> issueCodes
    ) {
        public SimulationSummary {
            if (stepCount < 0) {
                throw new IllegalArgumentException("stepCount must not be negative");
            }
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }

        private static SimulationSummary notRun() {
            return new SimulationSummary(false, false, null, null, 0, List.of());
        }
    }

    public record DeploymentCompatibilitySummary(
        String target,
        boolean bpmnWellFormed,
        String processDefinitionKey,
        boolean processKeyMatches,
        String existingDeploymentStatus,
        boolean semanticReplay,
        boolean supported
    ) {
        public DeploymentCompatibilitySummary {
            target = ApprovalReleasePreflightService.target(target);
        }

        private static DeploymentCompatibilitySummary notChecked(String target) {
            return new DeploymentCompatibilitySummary(
                target,
                false,
                null,
                false,
                null,
                false,
                false
            );
        }
    }

    public record PreflightReport(
        Scope scope,
        String tenantId,
        UUID draftId,
        Long draftRevision,
        String definitionKey,
        int targetDefinitionVersion,
        int targetReleaseVersion,
        String deploymentTarget,
        List<Issue> errors,
        List<Issue> warnings,
        List<Issue> infos,
        GeneratedHashes generatedHashes,
        CompilerSummary compiler,
        SimulationSummary simulation,
        DeploymentCompatibilitySummary deploymentCompatibility,
        boolean publishable,
        boolean deployable,
        String preflightHash
    ) {
        public PreflightReport {
            scope = Objects.requireNonNull(scope, "scope must not be null");
            tenantId = text(tenantId, "tenantId");
            definitionKey = text(definitionKey, "definitionKey");
            deploymentTarget = target(deploymentTarget);
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            infos = infos == null ? List.of() : List.copyOf(infos);
            generatedHashes = Objects.requireNonNull(generatedHashes);
            compiler = Objects.requireNonNull(compiler);
            simulation = Objects.requireNonNull(simulation);
            deploymentCompatibility = Objects.requireNonNull(deploymentCompatibility);
            preflightHash = text(preflightHash, "preflightHash");
        }

        public List<String> warningCodes() {
            return warnings.stream().map(Issue::code).distinct().sorted().toList();
        }
    }

    public static final class ReleasePackageNotFoundException extends RuntimeException {
        public ReleasePackageNotFoundException(String message) {
            super(message);
        }
    }
}
