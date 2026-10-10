#!/usr/bin/env python3
"""Verify bounded Clean source, supplied effective models and supplied realm evidence.

This invokes no Maven, Java, SCM or scanner command. A supplied log/model is not
proof of its provenance or an API/exploitability test; actual capture, class-load
origins, compatibility and scanner acceptance remain separate required evidence.
"""

import argparse
from pathlib import Path
from runpy import run_path

CONTRACT = run_path(str(Path(__file__).with_name("clean_plugin_contract.py")))
RELEASE = run_path(str(Path(__file__).with_name("verify-release-plugin-baseline.py")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--effective-pom", type=Path)
    parser.add_argument("--baseline-effective-pom", type=Path)
    parser.add_argument("--baseline-root", type=Path)
    parser.add_argument("--realm-log", type=Path)
    parser.add_argument("--maven-repository", type=Path)
    args = parser.parse_args()
    CONTRACT["require"](not args.maven_repository or args.realm_log, "JAR verification requires a supplied realm log")
    CONTRACT["require"](bool(args.baseline_effective_pom) == bool(args.baseline_root)
                        and (not args.baseline_effective_pom or args.effective_pom),
                        "model comparison requires candidate models, baseline models and baseline root")
    projects = RELEASE["verify_source"](args.root)
    print(f"Clean source contract and retained Release pins verified across {len(projects)} projects; not scanner evidence.")
    if args.effective_pom:
        RELEASE["verify_effective"](args.effective_pom, projects)
        CONTRACT["verify_effective"](args.effective_pom, projects)
        print("Supplied Clean models and retained Release/Site/Dependency/Boot pins verified across all 26 projects.")
    if args.baseline_effective_pom:
        RELEASE["verify_effective"](args.baseline_effective_pom, projects)
        count = CONTRACT["verify_model_delta"](args.effective_pom, args.baseline_effective_pom,
                                               args.root, args.baseline_root)
        print(f"All {count} baseline model semantics preserved after reversing only exact Clean additions and checkout prefixes.")
    if args.realm_log:
        coordinates = CONTRACT["verify_realm"](args.realm_log, args.maven_repository)
        print(f"Supplied Clean realm verified: exactly {len(coordinates)} approved coordinates; not class-load or API evidence.")
        if args.maven_repository:
            print("All three supplied realm JAR paths and approved SHA-256s verified without loading classes.")


if __name__ == "__main__":
    main()
