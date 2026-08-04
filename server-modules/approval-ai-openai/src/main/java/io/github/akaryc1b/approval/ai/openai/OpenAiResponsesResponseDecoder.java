package io.github.akaryc1b.approval.ai.openai;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.DUPLICATE_PROPERTY;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.HTTP_STATUS_REJECTED;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.INVALID_UTF8;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.MALFORMED_JSON;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.MODEL_MISMATCH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.PROVIDER_ERROR;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.REFUSAL;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.REQUEST_ID_MISMATCH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.REQUEST_ID_MISSING;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.RESPONSE_STATUS_REJECTED;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.RESPONSE_TOO_LARGE;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.RESULT_INVALID;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.SCHEMA_MISMATCH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.UNKNOWN_PROPERTY;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.USAGE_INVALID;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.VERSION_MISMATCH;
public final class OpenAiResponsesResponseDecoder {
private static final JsonFactory JSON_FACTORY = new JsonFactory()
.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);
private static final Set<String> ROOT_FIELDS = Set.of(
"background",
"completed_at",
"conversation",
"created_at",
"error",
"id",
"incomplete_details",
"instructions",
"max_output_tokens",
"max_tool_calls",
"metadata",
"model",
"object",
"output",
"parallel_tool_calls",
"previous_response_id",
"prompt",
"prompt_cache_key",
"prompt_cache_retention",
"reasoning",
"safety_identifier",
"service_tier",
"status",
"store",
"temperature",
"text",
"tool_choice",
"tools",
"top_logprobs",
"top_p",
"truncation",
"usage",
"user"
);
private static final Set<String> MESSAGE_FIELDS = Set.of(
"content",
"id",
"role",
"status",
"type"
);
private static final Set<String> CONTENT_FIELDS = Set.of(
"annotations",
"logprobs",
"text",
"type"
);
private static final Set<String> TEXT_FIELDS = Set.of("format");
private static final Set<String> FORMAT_FIELDS = Set.of(
"description",
"name",
"schema",
"strict",
"type"
);
private static final Set<String> USAGE_FIELDS = Set.of(
"input_tokens",
"input_tokens_details",
"output_tokens",
"output_tokens_details",
"total_tokens"
);
private static final Set<String> INPUT_USAGE_FIELDS = Set.of("cached_tokens");
private static final Set<String> OUTPUT_USAGE_FIELDS = Set.of("reasoning_tokens");
private static final Set<String> RESULT_FIELDS = Set.of(
"assertionStatus",
"authority",
"confidence",
"evidenceReferences",
"limitations",
"missingMaterials",
"needsHumanReview",
"observations",
"recommendations",
"riskSignals",
"summary",
"versions"
);
public OpenAiResponsesProtocol.DecodedResponse decode(
OpenAiResponsesTransportPort.Response response,
OpenAiResponsesProtocol.DecodeExpectations expectations
) {
Objects.requireNonNull(response, "response must not be null");
Objects.requireNonNull(expectations, "expectations must not be null");
if (response.statusCode() != 200) {
throw OpenAiResponsesProtocol.failure(HTTP_STATUS_REJECTED);
}
String providerRequestId = exactText(
response.requestId(),
200,
REQUEST_ID_MISSING
);
String providerRequestIdHash = OpenAiResponsesProtocol.sha256Utf8(
providerRequestId
);
String admittedClientRequestIdHash = response.transportEvidence()
.clientRequestIdHash();
if (!admittedClientRequestIdHash.equals(
expectations.admittedRequestIdHash()
)) {
throw OpenAiResponsesProtocol.failure(REQUEST_ID_MISMATCH);
}
byte[] body = response.bodyCopy();
if (body.length == 0
|| body.length > OpenAiResponsesProtocol.MAXIMUM_RESPONSE_BYTES) {
throw OpenAiResponsesProtocol.failure(RESPONSE_TOO_LARGE);
}
String bodyText = decodeUtf8(body);
ObjectNode root = object(parse(bodyText), MALFORMED_JSON);
requireAllowed(root, ROOT_FIELDS);
String responseId = text(root, "id", 200);
requireExactText(root, "object", "response", SCHEMA_MISMATCH);
requireExactText(root, "status", "completed", RESPONSE_STATUS_REJECTED);
if (presentNonNull(root, "error")) {
throw OpenAiResponsesProtocol.failure(PROVIDER_ERROR);
}
if (presentNonNull(root, "incomplete_details")) {
throw OpenAiResponsesProtocol.failure(RESPONSE_STATUS_REJECTED);
}
requireExactText(
root,
"model",
OpenAiResponsesProtocol.MODEL_SNAPSHOT,
MODEL_MISMATCH
);
requireBoolean(root, "store", false, SCHEMA_MISMATCH);
requireBoolean(root, "background", false, SCHEMA_MISMATCH);
requireNull(root, "previous_response_id");
requireNull(root, "conversation");
requireAbsentOrNull(root, "prompt");
requireAbsentOrNull(root, "prompt_cache_key");
requireAbsentOrNull(root, "prompt_cache_retention");
requireAbsentOrNull(root, "safety_identifier");
requireAbsentOrNull(root, "user");
requireAbsentOrNull(root, "max_tool_calls");
requireAbsentOrZero(root, "top_logprobs");
requireAbsentOrEmptyObject(root, "metadata");
requireEmptyArray(root, "tools");
requireExactText(root, "tool_choice", "none", SCHEMA_MISMATCH);
requireTextFormat(root);
ArrayNode output = array(root, "output");
if (output.size() != 1) {
throw OpenAiResponsesProtocol.failure(OUTPUT_NOT_EXACT);
}
ObjectNode message = object(output.get(0), OUTPUT_NOT_EXACT);
requireAllowed(message, MESSAGE_FIELDS);
requireExactText(message, "type", "message", OUTPUT_NOT_EXACT);
requireExactText(message, "role", "assistant", OUTPUT_NOT_EXACT);
requireExactText(message, "status", "completed", OUTPUT_NOT_EXACT);
ArrayNode content = array(message, "content");
if (content.size() != 1) {
throw OpenAiResponsesProtocol.failure(OUTPUT_NOT_EXACT);
}
ObjectNode outputText = object(content.get(0), OUTPUT_NOT_EXACT);
if ("refusal".equals(optionalText(outputText, "type"))) {
throw OpenAiResponsesProtocol.failure(REFUSAL);
}
requireAllowed(outputText, CONTENT_FIELDS);
requireExactText(outputText, "type", "output_text", OUTPUT_NOT_EXACT);
requireEmptyArray(outputText, "annotations");
requireNullOrEmptyArray(outputText, "logprobs");
String structuredText = text(
outputText,
"text",
OpenAiResponsesProtocol.MAXIMUM_STRUCTURED_OUTPUT_CHARACTERS
);
ObjectNode result = object(parse(structuredText), MALFORMED_JSON);
requireAllowed(result, RESULT_FIELDS);
AiAdvisoryResult advisory = advisory(result, expectations);
OpenAiResponsesProtocol.Usage usage = usage(objectField(root, "usage"));
return new OpenAiResponsesProtocol.DecodedResponse(
advisory,
usage,
providerRequestIdHash,
OpenAiResponsesProtocol.sha256Utf8(responseId)
);
}
private static AiAdvisoryResult advisory(
ObjectNode result,
OpenAiResponsesProtocol.DecodeExpectations expectations
) {
requireBoolean(result, "needsHumanReview", true, RESULT_INVALID);
requireExactText(result, "authority", "ADVISORY", RESULT_INVALID);
requireExactText(
result,
"assertionStatus",
"UNVERIFIED_ADVISORY",
RESULT_INVALID
);
requireVersions(objectField(result, "versions"), expectations.versions());
List<AiAdvisoryResult.Observation> observations = observations(
array(result, "observations"),
expectations.limits().maximumObservations()
);
List<AiAdvisoryResult.RiskSignal> riskSignals = riskSignals(
array(result, "riskSignals"),
expectations.limits().maximumRiskSignals()
);
List<AiAdvisoryResult.MissingMaterial> missingMaterials = missingMaterials(
array(result, "missingMaterials"),
expectations.limits().maximumMissingMaterials()
);
List<AiAdvisoryResult.Recommendation> recommendations = recommendations(
array(result, "recommendations"),
expectations.limits().maximumRecommendations()
);
List<AiAdvisoryResult.EvidenceReference> evidence = evidenceReferences(
array(result, "evidenceReferences"),
expectations.limits().maximumEvidenceReferences(),
expectations.providerFieldKeys()
);
List<String> limitations = strings(
array(result, "limitations"),
1,
expectations.limits().maximumLimitations(),
1_000
);
validateEvidence(observations, riskSignals, recommendations, evidence);
validateItemIds(observations, riskSignals, missingMaterials, recommendations);
ObjectNode confidence = objectField(result, "confidence");
requireAllowed(confidence, Set.of("band", "score"));
double score = finiteDouble(confidence, "score");
AiAdvisoryResult.ConfidenceBand band = enumValue(
AiAdvisoryResult.ConfidenceBand.class,
text(confidence, "band", 16),
RESULT_INVALID
);
try {
return new AiAdvisoryResult(
text(result, "summary", 4_000),
observations,
riskSignals,
missingMaterials,
recommendations,
evidence,
new AiAdvisoryResult.Confidence(score, band),
limitations,
true,
expectations.versions(),
AiAdvisoryResult.Authority.ADVISORY,
AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
);
} catch (IllegalArgumentException failure) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
}
private static List<AiAdvisoryResult.Observation> observations(
ArrayNode values,
int maximum
) {
requireArraySize(values, 0, maximum);
List<AiAdvisoryResult.Observation> output = new ArrayList<>();
for (JsonNode value : values) {
ObjectNode item = object(value, RESULT_INVALID);
requireAllowed(item, Set.of("evidenceReferenceIds", "id", "text"));
output.add(new AiAdvisoryResult.Observation(
text(item, "id", 120),
text(item, "text", 2_000),
strings(array(item, "evidenceReferenceIds"), 1, 64, 120)
));
}
return List.copyOf(output);
}
private static List<AiAdvisoryResult.RiskSignal> riskSignals(
ArrayNode values,
int maximum
) {
requireArraySize(values, 0, maximum);
List<AiAdvisoryResult.RiskSignal> output = new ArrayList<>();
for (JsonNode value : values) {
ObjectNode item = object(value, RESULT_INVALID);
requireAllowed(
item,
Set.of("evidenceReferenceIds", "id", "severity", "text")
);
output.add(new AiAdvisoryResult.RiskSignal(
text(item, "id", 120),
enumValue(
AiAdvisoryResult.RiskSeverity.class,
text(item, "severity", 16),
RESULT_INVALID
),
text(item, "text", 2_000),
strings(array(item, "evidenceReferenceIds"), 1, 64, 120)
));
}
return List.copyOf(output);
}
private static List<AiAdvisoryResult.MissingMaterial> missingMaterials(
ArrayNode values,
int maximum
) {
requireArraySize(values, 0, maximum);
List<AiAdvisoryResult.MissingMaterial> output = new ArrayList<>();
for (JsonNode value : values) {
ObjectNode item = object(value, RESULT_INVALID);
requireAllowed(item, Set.of("id", "materialType", "reason"));
output.add(new AiAdvisoryResult.MissingMaterial(
text(item, "id", 120),
text(item, "materialType", 160),
text(item, "reason", 2_000)
));
}
return List.copyOf(output);
}
private static List<AiAdvisoryResult.Recommendation> recommendations(
ArrayNode values,
int maximum
) {
requireArraySize(values, 0, maximum);
List<AiAdvisoryResult.Recommendation> output = new ArrayList<>();
for (JsonNode value : values) {
ObjectNode item = object(value, RESULT_INVALID);
requireAllowed(
item,
Set.of("evidenceReferenceIds", "id", "text", "type")
);
output.add(new AiAdvisoryResult.Recommendation(
text(item, "id", 120),
enumValue(
AiAdvisoryResult.RecommendationType.class,
text(item, "type", 40),
RESULT_INVALID
),
text(item, "text", 2_000),
strings(array(item, "evidenceReferenceIds"), 1, 64, 120)
));
}
return List.copyOf(output);
}
private static List<AiAdvisoryResult.EvidenceReference> evidenceReferences(
ArrayNode values,
int maximum,
Set<String> providerFieldKeys
) {
requireArraySize(values, 1, maximum);
List<AiAdvisoryResult.EvidenceReference> output = new ArrayList<>();
Set<String> ids = new HashSet<>();
for (JsonNode value : values) {
ObjectNode item = object(value, RESULT_INVALID);
requireAllowed(item, Set.of("description", "fieldKey", "id"));
String id = text(item, "id", 120);
String fieldKey = text(item, "fieldKey", 160);
if (!ids.add(id) || !providerFieldKeys.contains(fieldKey)) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
output.add(new AiAdvisoryResult.EvidenceReference(
id,
fieldKey,
text(item, "description", 1_000)
));
}
return List.copyOf(output);
}
private static void validateEvidence(
List<AiAdvisoryResult.Observation> observations,
List<AiAdvisoryResult.RiskSignal> risks,
List<AiAdvisoryResult.Recommendation> recommendations,
List<AiAdvisoryResult.EvidenceReference> evidence
) {
Map<String, AiAdvisoryResult.EvidenceReference> byId = new HashMap<>();
evidence.forEach(value -> byId.put(value.id(), value));
Set<String> used = new HashSet<>();
observations.forEach(value -> requireEvidence(
value.evidenceReferenceIds(),
byId,
used
));
risks.forEach(value -> requireEvidence(
value.evidenceReferenceIds(),
byId,
used
));
recommendations.forEach(value -> requireEvidence(
value.evidenceReferenceIds(),
byId,
used
));
if (!used.equals(byId.keySet())) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
}
private static void requireEvidence(
List<String> ids,
Map<String, AiAdvisoryResult.EvidenceReference> evidence,
Set<String> used
) {
Set<String> local = new HashSet<>();
if (ids.isEmpty()) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
for (String id : ids) {
if (!local.add(id) || !evidence.containsKey(id)) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
used.add(id);
}
}
private static void validateItemIds(
List<AiAdvisoryResult.Observation> observations,
List<AiAdvisoryResult.RiskSignal> risks,
List<AiAdvisoryResult.MissingMaterial> missing,
List<AiAdvisoryResult.Recommendation> recommendations
) {
Set<String> ids = new HashSet<>();
observations.forEach(value -> addUnique(ids, value.id()));
risks.forEach(value -> addUnique(ids, value.id()));
missing.forEach(value -> addUnique(ids, value.id()));
recommendations.forEach(value -> addUnique(ids, value.id()));
}
private static void addUnique(Set<String> ids, String id) {
if (!ids.add(id)) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
}
private static OpenAiResponsesProtocol.Usage usage(ObjectNode usage) {
requireAllowed(usage, USAGE_FIELDS);
long input = nonNegativeLong(usage, "input_tokens");
long output = nonNegativeLong(usage, "output_tokens");
long total = nonNegativeLong(usage, "total_tokens");
long cached = 0;
if (presentNonNull(usage, "input_tokens_details")) {
ObjectNode details = objectField(usage, "input_tokens_details");
requireAllowed(details, INPUT_USAGE_FIELDS);
cached = optionalNonNegativeLong(details, "cached_tokens");
}
long reasoning = 0;
if (presentNonNull(usage, "output_tokens_details")) {
ObjectNode details = objectField(usage, "output_tokens_details");
requireAllowed(details, OUTPUT_USAGE_FIELDS);
reasoning = optionalNonNegativeLong(details, "reasoning_tokens");
}
try {
return new OpenAiResponsesProtocol.Usage(
input,
output,
total,
cached,
reasoning
);
} catch (IllegalArgumentException failure) {
throw OpenAiResponsesProtocol.failure(USAGE_INVALID);
}
}
private static void requireTextFormat(ObjectNode root) {
ObjectNode text = objectField(root, "text");
requireAllowed(text, TEXT_FIELDS);
ObjectNode format = objectField(text, "format");
requireAllowed(format, FORMAT_FIELDS);
requireExactText(format, "type", "json_schema", SCHEMA_MISMATCH);
requireExactText(
format,
"name",
OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME,
SCHEMA_MISMATCH
);
requireBoolean(format, "strict", true, SCHEMA_MISMATCH);
if (!format.has("schema") || !format.get("schema").isObject()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireVersions(
ObjectNode versions,
AiVersionReferences expected
) {
requireAllowed(
versions,
Set.of(
"knowledgeSource",
"model",
"outputSchema",
"policy",
"promptTemplate",
"provider"
)
);
ObjectNode provider = objectField(versions, "provider");
requireAllowed(provider, Set.of("providerId", "version"));
requireExactText(
provider,
"providerId",
expected.provider().providerId(),
VERSION_MISMATCH
);
requireExactText(
provider,
"version",
expected.provider().version(),
VERSION_MISMATCH
);
ObjectNode model = objectField(versions, "model");
requireAllowed(model, Set.of("modelId", "providerId", "version"));
requireExactText(
model,
"providerId",
expected.model().providerId(),
VERSION_MISMATCH
);
requireExactText(
model,
"modelId",
expected.model().modelId(),
VERSION_MISMATCH
);
requireExactText(
model,
"version",
expected.model().version(),
VERSION_MISMATCH
);
ObjectNode prompt = objectField(versions, "promptTemplate");
requireAllowed(prompt, Set.of("contentHash", "templateId", "version"));
requireExactText(
prompt,
"templateId",
expected.promptTemplate().templateId(),
VERSION_MISMATCH
);
requireExactText(
prompt,
"version",
expected.promptTemplate().version(),
VERSION_MISMATCH
);
requireExactText(
prompt,
"contentHash",
expected.promptTemplate().contentHash(),
VERSION_MISMATCH
);
ObjectNode knowledge = objectField(versions, "knowledgeSource");
requireAllowed(
knowledge,
Set.of("containsCustomerData", "contentHash", "sourceId", "version")
);
requireExactText(
knowledge,
"sourceId",
expected.knowledgeSource().sourceId(),
VERSION_MISMATCH
);
requireExactText(
knowledge,
"version",
expected.knowledgeSource().version(),
VERSION_MISMATCH
);
requireExactText(
knowledge,
"contentHash",
expected.knowledgeSource().contentHash(),
VERSION_MISMATCH
);
requireBoolean(
knowledge,
"containsCustomerData",
expected.knowledgeSource().containsCustomerData(),
VERSION_MISMATCH
);
ObjectNode policy = objectField(versions, "policy");
requireAllowed(policy, Set.of("contentHash", "policyId", "version"));
requireExactText(
policy,
"policyId",
expected.policy().policyId(),
VERSION_MISMATCH
);
requireExactText(
policy,
"version",
expected.policy().version(),
VERSION_MISMATCH
);
requireExactText(
policy,
"contentHash",
expected.policy().contentHash(),
VERSION_MISMATCH
);
ObjectNode outputSchema = objectField(versions, "outputSchema");
requireAllowed(outputSchema, Set.of("schemaId", "version"));
requireExactText(
outputSchema,
"schemaId",
expected.outputSchema().schemaId(),
VERSION_MISMATCH
);
if (nonNegativeLong(outputSchema, "version")
!= expected.outputSchema().version()) {
throw OpenAiResponsesProtocol.failure(VERSION_MISMATCH);
}
}
private static JsonNode parse(String value) {
try (JsonParser parser = JSON_FACTORY.createParser(value)) {
JsonNode output = MAPPER.readTree(parser);
if (output == null || parser.nextToken() != null) {
throw OpenAiResponsesProtocol.failure(MALFORMED_JSON);
}
return output;
} catch (OpenAiResponsesProtocol.ProtocolException failure) {
throw failure;
} catch (JsonProcessingException failure) {
if (failure.getOriginalMessage() != null
&& failure.getOriginalMessage().contains("Duplicate field")) {
throw OpenAiResponsesProtocol.failure(DUPLICATE_PROPERTY);
}
throw OpenAiResponsesProtocol.failure(MALFORMED_JSON);
} catch (IOException failure) {
throw OpenAiResponsesProtocol.failure(MALFORMED_JSON);
}
}
private static String decodeUtf8(byte[] value) {
try {
return StandardCharsets.UTF_8.newDecoder()
.onMalformedInput(CodingErrorAction.REPORT)
.onUnmappableCharacter(CodingErrorAction.REPORT)
.decode(ByteBuffer.wrap(value))
.toString();
} catch (CharacterCodingException failure) {
throw OpenAiResponsesProtocol.failure(INVALID_UTF8);
}
}
private static ObjectNode object(
JsonNode value,
OpenAiResponsesProtocol.Failure failure
) {
if (!(value instanceof ObjectNode object)) {
throw OpenAiResponsesProtocol.failure(failure);
}
return object;
}
private static ObjectNode objectField(ObjectNode object, String name) {
return object(required(object, name), SCHEMA_MISMATCH);
}
private static ArrayNode array(ObjectNode object, String name) {
JsonNode value = required(object, name);
if (!(value instanceof ArrayNode array)) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
return array;
}
private static String text(ObjectNode object, String name, int maximumLength) {
JsonNode value = required(object, name);
if (!value.isTextual()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
return exactText(value.textValue(), maximumLength, SCHEMA_MISMATCH);
}
private static String optionalText(ObjectNode object, String name) {
JsonNode value = object.get(name);
return value != null && value.isTextual() ? value.textValue() : null;
}
private static String exactText(
String value,
int maximumLength,
OpenAiResponsesProtocol.Failure failure
) {
if (value == null || value.isBlank() || value.length() > maximumLength
|| !value.equals(value.trim())) {
throw OpenAiResponsesProtocol.failure(failure);
}
return value;
}
private static void requireExactText(
ObjectNode object,
String name,
String expected,
OpenAiResponsesProtocol.Failure failure
) {
String value = text(object, name, Math.max(200, expected.length()));
if (!expected.equals(value)) {
throw OpenAiResponsesProtocol.failure(failure);
}
}
private static void requireBoolean(
ObjectNode object,
String name,
boolean expected,
OpenAiResponsesProtocol.Failure failure
) {
JsonNode value = required(object, name);
if (!value.isBoolean() || value.booleanValue() != expected) {
throw OpenAiResponsesProtocol.failure(failure);
}
}
private static void requireNull(ObjectNode object, String name) {
JsonNode value = required(object, name);
if (!value.isNull()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireAbsentOrNull(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value != null && !value.isNull()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireAbsentOrZero(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value == null || value.isNull()) {
return;
}
if (!value.isIntegralNumber() || value.longValue() != 0L) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireAbsentOrEmptyObject(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value == null || value.isNull()) {
return;
}
if (!(value instanceof ObjectNode nested) || !nested.isEmpty()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireEmptyArray(ObjectNode object, String name) {
if (!array(object, name).isEmpty()) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
}
private static void requireNullOrEmptyArray(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value == null || value.isNull()) {
return;
}
if (!(value instanceof ArrayNode array) || !array.isEmpty()) {
throw OpenAiResponsesProtocol.failure(OUTPUT_NOT_EXACT);
}
}
private static JsonNode required(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value == null) {
throw OpenAiResponsesProtocol.failure(SCHEMA_MISMATCH);
}
return value;
}
private static boolean presentNonNull(ObjectNode object, String name) {
JsonNode value = object.get(name);
return value != null && !value.isNull();
}
private static void requireAllowed(ObjectNode object, Set<String> allowed) {
Iterator<String> names = object.fieldNames();
while (names.hasNext()) {
if (!allowed.contains(names.next())) {
throw OpenAiResponsesProtocol.failure(UNKNOWN_PROPERTY);
}
}
}
private static void requireArraySize(
ArrayNode values,
int minimum,
int maximum
) {
if (values.size() < minimum || values.size() > maximum) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
}
private static List<String> strings(
ArrayNode values,
int minimum,
int maximum,
int maximumLength
) {
requireArraySize(values, minimum, maximum);
List<String> output = new ArrayList<>();
Set<String> unique = new HashSet<>();
for (JsonNode value : values) {
if (!value.isTextual()) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
String text = exactText(value.textValue(), maximumLength, RESULT_INVALID);
if (!unique.add(text)) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
output.add(text);
}
return List.copyOf(output);
}
private static double finiteDouble(ObjectNode object, String name) {
JsonNode value = required(object, name);
if (!value.isNumber()) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
double output = value.doubleValue();
if (!Double.isFinite(output) || output < 0.0d || output > 1.0d) {
throw OpenAiResponsesProtocol.failure(RESULT_INVALID);
}
return output;
}
private static long nonNegativeLong(ObjectNode object, String name) {
JsonNode value = required(object, name);
if (!value.isIntegralNumber() || !value.canConvertToLong()) {
throw OpenAiResponsesProtocol.failure(USAGE_INVALID);
}
long output = value.longValue();
if (output < 0) {
throw OpenAiResponsesProtocol.failure(USAGE_INVALID);
}
return output;
}
private static long optionalNonNegativeLong(ObjectNode object, String name) {
JsonNode value = object.get(name);
if (value == null) {
return 0;
}
return nonNegativeLong(object, name);
}
private static <E extends Enum<E>> E enumValue(
Class<E> type,
String value,
OpenAiResponsesProtocol.Failure failure
) {
try {
return Enum.valueOf(type, value);
} catch (IllegalArgumentException invalid) {
throw OpenAiResponsesProtocol.failure(failure);
}
}
}
