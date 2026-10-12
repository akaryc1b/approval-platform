#!/usr/bin/env python3
"""Read-only Checkstyle successor verification; supplied models are not execution proof."""
import argparse
from pathlib import Path
from runpy import run_path

CHECKSTYLE = run_path(str(Path(__file__).with_name('checkstyle_plugin_contract.py')))
RELEASE = run_path(str(Path(__file__).with_name('verify-release-plugin-baseline.py')))
CLEAN = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))
COMPILER = run_path(str(Path(__file__).with_name('compiler_plugin_contract.py')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--effective-pom', type=Path)
    parser.add_argument('--baseline-effective-pom', type=Path)
    parser.add_argument('--baseline-root', type=Path)
    args = parser.parse_args()
    CHECKSTYLE['require'](bool(args.effective_pom) == bool(args.baseline_effective_pom) == bool(args.baseline_root),
                         'Checkstyle model comparison requires candidate, baseline and baseline root')
    projects = RELEASE['verify_source'](args.root)
    CHECKSTYLE['verify_source'](args.root, projects)
    print('Checkstyle exact root additions and unchanged Compiler/Release/Clean source verified across 26 projects and both overlays.')
    if args.effective_pom:
        RELEASE['verify_effective'](args.effective_pom, projects)
        CLEAN['verify_effective'](args.effective_pom, projects)
        COMPILER['verify_effective'](args.effective_pom)
        count = CHECKSTYLE['verify_effective'](args.root, args.effective_pom, args.baseline_effective_pom, args.baseline_root)
        print(f'Checkstyle exact Lang override verified in all {count} paired models; not scanner or live compatibility evidence.')


if __name__ == '__main__':
    main()
