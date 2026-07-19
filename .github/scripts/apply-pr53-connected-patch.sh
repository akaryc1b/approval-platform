#!/usr/bin/env bash
set -euo pipefail

parts=(
  .github/pr53-d5-helper.part00
  .github/pr53-d5-helper.part01
  .github/pr53-d5-helper.part02
  .github/pr53-d5-helper.part03
  .github/pr53-d5-helper.part04
  .github/pr53-d5-helper.part05
  .github/pr53-d5-helper.part06
  .github/pr53-d5-helper.part07
  .github/pr53-d5-helper.part08
  .github/pr53-d5-helper.part09
  .github/pr53-d5-helper.part10
  .github/pr53-d5-helper.part11
  .github/pr53-d5-helper.part12
  .github/pr53-d5-helper.part13
  .github/pr53-d5-helper.part14
  .github/pr53-d5-helper.part15
  .github/pr53-d5-helper.part16
)

payload_b64="$(mktemp)"
payload_gz="$(mktemp)"
trap 'rm -f "$payload_b64" "$payload_gz"' EXIT
cat "${parts[@]}" > "$payload_b64"
base64 --decode "$payload_b64" > "$payload_gz"
echo "9dfd203e214a27cc0bb46e03e6a274098b30a99f262c8472be427138c5a92cff  $payload_gz" | sha256sum --check --status
gzip --decompress --stdout "$payload_gz" | bash

python3 - <<'PY'
from pathlib import Path

service_path = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationService.java'
)
service = service_path.read_text()
old_list = '            return List.copyOf(normalized);\n'
new_list = '            return Collections.unmodifiableList(normalized);\n'
if service.count(old_list) != 1:
    raise SystemExit('Expected one normalized JSON list copy')
service = service.replace(old_list, new_list, 1)
old_value = '                Objects.requireNonNull(value, "map value must not be null")\n'
new_value = '                requireMapValue(value)\n'
if service.count(old_value) != 1:
    raise SystemExit('Expected one generic map value null check')
service = service.replace(old_value, new_value, 1)
identity_marker = '''    private static Map<String, List<StableIdentitySnapshot>> sortedIdentities(
'''
helper = '''    private static <T> T requireMapValue(T value) {
        if (value == null) {
            throw new IllegalArgumentException("map value must not be null");
        }
        return value;
    }

'''
if service.count(identity_marker) != 1:
    raise SystemExit('Expected identity sorting marker')
service = service.replace(identity_marker, helper + identity_marker, 1)
service_path.write_text(service)

test_path = Path(
    'server-modules/approval-application/src/test/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationServiceTest.java'
)
test = test_path.read_text()
marker = '''        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new NamedScenario(
                "too-many-values",
                "too-many-values",
                values,
                Map.of(),
                Map.of(),
                null,
                List.of(),
                List.of(),
                10
            )
        );
'''
additional = marker + '''
        List<Object> valuesWithNull = new ArrayList<>();
        valuesWithNull.add("present");
        valuesWithNull.add(null);
        NamedScenario nullableArray = new NamedScenario(
            "nullable-array",
            "nullable-array",
            Map.of("items", valuesWithNull),
            Map.of(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            10
        );
        org.junit.jupiter.api.Assertions.assertNull(
            ((List<?>) nullableArray.formValues().get("items")).get(1)
        );

        Map<String, ApprovalDefinitionSimulator.Decision> decisionsWithNull =
            new LinkedHashMap<>();
        decisionsWithNull.put("managerApproval", null);
        IllegalArgumentException invalidDecision =
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NamedScenario(
                    "null-decision",
                    "null-decision",
                    Map.of(),
                    decisionsWithNull,
                    Map.of(),
                    null,
                    List.of(),
                    List.of(),
                    10
                )
            );
        assertEquals("map value must not be null", invalidDecision.getMessage());
'''
if test.count(marker) != 1:
    raise SystemExit('Expected oversized scenario assertion marker')
test_path.write_text(test.replace(marker, additional, 1))
PY

rm -f "${parts[@]}"
git diff --check
