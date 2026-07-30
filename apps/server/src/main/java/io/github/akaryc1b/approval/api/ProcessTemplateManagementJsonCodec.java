package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_DEPENDENCIES;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_JSON_DEPTH;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_JSON_ELEMENTS;
import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_STRING_LENGTH;

/** Strict management decoder that never accepts trusted tenant or registry evidence from clients. */
@Component
final class ProcessTemplateManagementJsonCodec {

    static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    private static final Set<String> PREVIEW_FIELDS = Set.of(
        "templatePackage",
        "targetDefinitionKey",
        "targetDefinitionVersion",
        "targetDraftName",
        "bindings"
    );
    private static final Set<String> CREATE_FIELDS = Set.of(
        "templatePackage",
        "targetDefinitionKey",
        "targetDefinitionVersion",
        "targetDraftName",
        "bindings",
        "expectedGovernedPreviewHash",
        "artifactEnvelope"
    );
    private static final Set<String> BINDING_FIELDS = Set.of(
        "kind",
        "sourceKey",
        "targetResourceKey",
        "targetVersion"
    );

    private final ObjectMapper mapper;
    private final ProcessTemplatePackageJsonCodec packageCodec;
    private final ApprovalArtifactTransferJsonCodec artifactCodec;

    ProcessTemplateManagementJsonCodec(
        ObjectMapper objectMapper,
        ProcessTemplatePackageJsonCodec packageCodec,
        ApprovalArtifactTransferJsonCodec artifactCodec
    ) {
        mapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_JSON_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .maxNumberLength(100)
            .build());
        this.packageCodec = java.util.Objects.requireNonNull(packageCodec, "packageCodec");
        this.artifactCodec = java.util.Objects.requireNonNull(artifactCodec, "artifactCodec");
    }

    DecodedPreview decodePreview(byte[] body, String trustedTenantId) {
        JsonNode root = readRoot(body, PREVIEW_FIELDS);
        DecodedBase base = decodeBase(root, trustedTenantId);
        return new DecodedPreview(
            base.templatePackage(),
            base.packageBytes(),
            base.previewRequest()
        );
    }

    DecodedCreateDraft decodeCreateDraft(byte[] body, String trustedTenantId) {
        JsonNode root = readRoot(body, CREATE_FIELDS);
        DecodedBase base = decodeBase(root, trustedTenantId);
        return new DecodedCreateDraft(
            base.templatePackage(),
            base.packageBytes(),
            base.previewRequest(),
            text(root, "expectedGovernedPreviewHash"),
            decodeEnvelope(required(root, "artifactEnvelope"), base.previewRequest())
        );
    }

    private DecodedBase decodeBase(JsonNode root, String trustedTenantId) {
        JsonNode packageNode = required(root, "templatePackage");
        requireObject(packageNode, "templatePackage");
        byte[] packageBytes = write(packageNode, "templatePackage");
        TemplatePackage templatePackage = packageCodec.decode(packageBytes);
        PreviewRequest request = new PreviewRequest(
            trustedTenantId,
            text(root, "targetDefinitionKey"),
            integer(root, "targetDefinitionVersion"),
            text(root, "targetDraftName"),
            bindings(required(root, "bindings"), trustedTenantId)
        );
        return new DecodedBase(templatePackage, packageBytes.length, request);
    }

    private TransferEnvelope decodeEnvelope(JsonNode envelope, PreviewRequest previewRequest) {
        requireObject(envelope, "artifactEnvelope");
        ObjectNode synthetic = mapper.createObjectNode();
        synthetic.set("envelope", envelope);
        synthetic.put("targetDefinitionKey", previewRequest.targetDefinitionKey());
        synthetic.put("targetDefinitionVersion", previewRequest.targetDefinitionVersion());
        synthetic.put("targetFormPackageVersion", targetFormPackageVersion(previewRequest));
        synthetic.put("targetName", previewRequest.targetDraftName());
        return artifactCodec.decodeImport(write(synthetic, "artifactEnvelope")).envelope();
    }

    private static int targetFormPackageVersion(PreviewRequest request) {
        return request.bindings().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .map(TenantBinding::targetVersion)
            .filter(java.util.Objects::nonNull)
            .filter(version -> version > 0)
            .findFirst()
            .orElse(1);
    }

    private static List<TenantBinding> bindings(JsonNode node, String trustedTenantId) {
        requireArray(node, "bindings");
        if (node.size() > MAX_DEPENDENCIES) {
            throw tooLarge("binding count exceeds " + MAX_DEPENDENCIES);
        }
        List<TenantBinding> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            requireObject(value, "binding");
            rejectUnknownFields(value, BINDING_FIELDS, "binding");
            result.add(new TenantBinding(
                enumValue(value, "kind", BindingKind.class),
                text(value, "sourceKey"),
                trustedTenantId,
                text(value, "targetResourceKey"),
                nullableInteger(value, "targetVersion")
            ));
        }
        return List.copyOf(result);
    }

    private JsonNode readRoot(byte[] body, Set<String> fields) {
        if (body == null || body.length == 0) {
            throw invalid("management import request body must not be empty");
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw tooLarge("management import request exceeds the 4 MiB maximum");
        }
        try {
            JsonNode root = mapper.readTree(body);
            requireObject(root, "management import request");
            rejectUnknownFields(root, fields, "management import request");
            if (countElements(root, 0) > MAX_JSON_ELEMENTS) {
                throw tooLarge("management import JSON element count exceeds " + MAX_JSON_ELEMENTS);
            }
            return root;
        } catch (ProcessTemplateException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("management import request is not valid strict JSON");
        }
    }

    private byte[] write(JsonNode node, String subject) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException exception) {
            throw new ProcessTemplateException(subject + " could not be normalized safely", exception);
        }
    }

    private static int countElements(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw tooLarge("management import JSON depth exceeds " + MAX_JSON_DEPTH);
        }
        int count = 1;
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            count = Math.addExact(count, countElements(children.next(), depth + 1));
            if (count > MAX_JSON_ELEMENTS) {
                return count;
            }
        }
        return count;
    }

    private static void rejectUnknownFields(
        JsonNode node,
        Set<String> allowed,
        String subject
    ) {
        Set<String> unknown = new HashSet<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw invalid(subject + " contains unknown fields " + unknown);
        }
    }

    private static JsonNode required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            throw invalid(name + " is required");
        }
        return value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static Integer nullableInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be a 32-bit integer or null");
        }
        return value.intValue();
    }

    private static <T extends Enum<T>> T enumValue(
        JsonNode node,
        String name,
        Class<T> type
    ) {
        try {
            return Enum.valueOf(type, text(node, name));
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " has an unsupported value");
        }
    }

    private static void requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be a JSON object");
        }
    }

    private static void requireArray(JsonNode node, String name) {
        if (node == null || !node.isArray()) {
            throw invalid(name + " must be a JSON array");
        }
    }

    private static ProcessTemplateException invalid(String message) {
        return new ProcessTemplateException(message);
    }

    private static ProcessTemplateException.PackageTooLarge tooLarge(String message) {
        return new ProcessTemplateException.PackageTooLarge(message);
    }

    record DecodedPreview(
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest
    ) {
    }

    record DecodedCreateDraft(
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest,
        String expectedGovernedPreviewHash,
        TransferEnvelope artifactEnvelope
    ) {
    }

    private record DecodedBase(
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest
    ) {
    }
}
