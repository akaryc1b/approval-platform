#!/usr/bin/env python3
from __future__ import annotations

import argparse
import collections
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SUREFIRE_PATTERNS = (
    "Test*.java",
    "*Test.java",
    "*Tests.java",
    "*TestCase.java",
)
ABSTRACT_CLASS_PATTERN = re.compile(r"\babstract\s+class\s+")


def test_source_root(repository_root: Path) -> Path:
    return (
        repository_root
        / "server-modules"
        / "approval-persistence-jdbc"
        / "src"
        / "test"
        / "java"
    )


def class_name(test_root: Path, path: Path) -> str:
    return ".".join(path.relative_to(test_root).with_suffix("").parts)


def discover_expected_classes(repository_root: Path) -> list[str]:
    test_root = test_source_root(repository_root)
    if not test_root.is_dir():
        raise RuntimeError(f"test source directory is missing: {test_root}")

    files: set[Path] = set()
    for pattern in SUREFIRE_PATTERNS:
        files.update(test_root.rglob(pattern))

    classes = [class_name(test_root, path) for path in sorted(files)]
    if not classes:
        raise RuntimeError("no Surefire-compatible persistence JDBC test classes were found")
    return classes


def discover_abstract_classes(repository_root: Path) -> set[str]:
    test_root = test_source_root(repository_root)
    abstract_classes: set[str] = set()
    for path in test_root.rglob("*.java"):
        source = path.read_text(encoding="utf-8")
        if ABSTRACT_CLASS_PATTERN.search(source):
            abstract_classes.add(class_name(test_root, path))
    return abstract_classes


def read_selected_classes(artifact_root: Path, expected_shards: int) -> list[str]:
    manifests = sorted(artifact_root.rglob("selected-tests-*.txt"))
    if len(manifests) != expected_shards:
        raise RuntimeError(
            f"expected {expected_shards} shard manifests, found {len(manifests)}: "
            + ", ".join(str(path) for path in manifests)
        )

    selected: list[str] = []
    for manifest in manifests:
        classes = [
            line.strip()
            for line in manifest.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        if not classes:
            raise RuntimeError(f"shard manifest is empty: {manifest}")
        selected.extend(classes)
    return selected


def parse_surefire_reports(
    artifact_root: Path,
) -> tuple[set[str], int, int, int, int, float]:
    report_files = sorted(artifact_root.rglob("TEST-*.xml"))
    if not report_files:
        raise RuntimeError("no Surefire XML reports were found in persistence shard artifacts")

    report_classes: set[str] = set()
    tests = failures = errors = skipped = 0
    elapsed = 0.0
    for report_file in report_files:
        root = ET.parse(report_file).getroot()
        suites = [root] if root.tag.endswith("testsuite") else list(root)
        for suite in suites:
            suite_name = suite.attrib.get("name", "").strip()
            if not suite_name:
                raise RuntimeError(f"Surefire report has no suite name: {report_file}")
            if suite_name in report_classes:
                raise RuntimeError(f"duplicate Surefire report class: {suite_name}")
            report_classes.add(suite_name)
            tests += int(suite.attrib.get("tests", "0"))
            failures += int(suite.attrib.get("failures", "0"))
            errors += int(suite.attrib.get("errors", "0"))
            skipped += int(suite.attrib.get("skipped", "0"))
            elapsed += float(suite.attrib.get("time", "0"))

    return report_classes, tests, failures, errors, skipped, elapsed


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify complete and non-overlapping Persistence JDBC CI shards."
    )
    parser.add_argument("artifact_root", type=Path)
    parser.add_argument("summary_path", type=Path)
    parser.add_argument("--expected-shards", type=int, default=4)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    try:
        repository_root = args.repository_root.resolve()
        expected = discover_expected_classes(repository_root)
        selected = read_selected_classes(args.artifact_root.resolve(), args.expected_shards)

        counts = collections.Counter(selected)
        duplicates = sorted(name for name, count in counts.items() if count != 1)
        missing = sorted(set(expected) - set(selected))
        unexpected = sorted(set(selected) - set(expected))
        if duplicates or missing or unexpected:
            details = []
            if duplicates:
                details.append("duplicate selections: " + ", ".join(duplicates))
            if missing:
                details.append("missing selections: " + ", ".join(missing))
            if unexpected:
                details.append("unexpected selections: " + ", ".join(unexpected))
            raise RuntimeError("; ".join(details))

        report_classes, tests, failures, errors, skipped, elapsed = parse_surefire_reports(
            args.artifact_root.resolve()
        )
        abstract_classes = discover_abstract_classes(repository_root)
        selected_without_reports = sorted(set(selected) - report_classes)
        unexpected_report_classes = sorted(report_classes - set(selected))
        non_abstract_without_reports = sorted(
            set(selected_without_reports) - abstract_classes
        )
        if unexpected_report_classes or non_abstract_without_reports:
            details = []
            if unexpected_report_classes:
                details.append(
                    "unexpected report classes: " + ", ".join(unexpected_report_classes)
                )
            if non_abstract_without_reports:
                details.append(
                    "selected non-abstract classes without reports: "
                    + ", ".join(non_abstract_without_reports)
                )
            raise RuntimeError("; ".join(details))
        if tests <= 0:
            raise RuntimeError("persistence shard reports contain no executed tests")
        if failures or errors or skipped:
            raise RuntimeError(
                "persistence shard results are not clean: "
                f"tests={tests}, failures={failures}, errors={errors}, skipped={skipped}"
            )

        summary = "\n".join(
            (
                "Persistence JDBC shard verification",
                f"shards: {args.expected_shards}",
                f"selected test classes: {len(selected)}",
                f"Surefire report classes: {len(report_classes)}",
                f"selected abstract classes without reports: {len(selected_without_reports)}",
                f"tests: {tests}",
                f"failures: {failures}",
                f"errors: {errors}",
                f"skipped: {skipped}",
                f"aggregate reported test time: {elapsed:.3f} s",
                "selection coverage: exact",
                "duplicate selection count: 0",
                "non-abstract selected classes without reports: 0",
            )
        ) + "\n"
        args.summary_path.parent.mkdir(parents=True, exist_ok=True)
        args.summary_path.write_text(summary, encoding="utf-8")
        print(summary, end="")
        return 0
    except (OSError, RuntimeError, ET.ParseError, ValueError) as error:
        print(f"Persistence JDBC shard verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
