#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  echo "usage: $0 <shard-index> <shard-total> <manifest-path>" >&2
  exit 2
fi

shard_index="$1"
shard_total="$2"
manifest_path="$3"

if ! [[ "$shard_index" =~ ^[0-9]+$ && "$shard_total" =~ ^[1-9][0-9]*$ ]]; then
  echo "shard index and total must be non-negative integers, with total greater than zero" >&2
  exit 2
fi

if (( shard_index >= shard_total )); then
  echo "shard index ${shard_index} must be lower than shard total ${shard_total}" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="${repository_root}/server-modules/approval-persistence-jdbc/src/test/java"

if [[ ! -d "$test_root" ]]; then
  echo "persistence JDBC test source directory is missing: ${test_root}" >&2
  exit 1
fi

mapfile -d '' test_files < <(
  find "$test_root" -type f \
    \( -name 'Test*.java' -o -name '*Test.java' -o -name '*Tests.java' -o -name '*TestCase.java' \) \
    -print0 | sort -z
)

if (( ${#test_files[@]} == 0 )); then
  echo "no Surefire-compatible persistence JDBC test classes were found" >&2
  exit 1
fi

selected=()
for file in "${test_files[@]}"; do
  relative_path="${file#${test_root}/}"
  class_name="${relative_path%.java}"
  class_name="${class_name//\//.}"
  checksum="$(printf '%s' "$class_name" | cksum | awk '{print $1}')"
  if (( checksum % shard_total == shard_index )); then
    selected+=("$class_name")
  fi
done

if (( ${#selected[@]} == 0 )); then
  echo "no persistence JDBC tests were assigned to shard ${shard_index}/${shard_total}" >&2
  exit 1
fi

mkdir -p "$(dirname "$manifest_path")"
printf '%s\n' "${selected[@]}" >"$manifest_path"

(
  IFS=,
  printf '%s\n' "${selected[*]}"
)
