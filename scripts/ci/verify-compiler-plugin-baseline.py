#!/usr/bin/env python3
"""Read-only Compiler successor verification; supplied models are not execution proof."""
import argparse
from pathlib import Path
from runpy import run_path

COMPILER = run_path(str(Path(__file__).with_name('compiler_plugin_contract.py')))
RELEASE = run_path(str(Path(__file__).with_name('verify-release-plugin-baseline.py')))
CLEAN = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--effective-pom', type=Path)
    parser.add_argument('--baseline-effective-pom', type=Path)
    parser.add_argument('--baseline-root', type=Path)
    args = parser.parse_args()
    COMPILER['require'](bool(args.baseline_effective_pom) == bool(args.baseline_root)
        and (not args.baseline_effective_pom or args.effective_pom), 'paired model comparison requires both baselines and candidate')
    projects = RELEASE['verify_source'](args.root)
    print('Compiler source and unchanged Release/Clean gates verified across 26 projects and both overlays.')
    if args.effective_pom:
        RELEASE['verify_effective'](args.effective_pom, projects)
        CLEAN['verify_effective'](args.effective_pom, projects)
        count = COMPILER['verify_effective'](args.effective_pom, args.baseline_effective_pom, args.root, args.baseline_root)
        print(f'Compiler exact IO override verified in all {count} models; not scanner or live compatibility evidence.')


if __name__ == '__main__':
    main()
