"""Retain the validated pre-Assembly source for unchanged historical model gates."""
from pathlib import Path
from runpy import run_path


def materialize_accepted_source(source, destination):
    contract = run_path(str(Path(__file__).with_name('assembly_plexus_contract.py')))
    result = contract['materialize_retained_source'](source, destination)
    # Reuse the prior contract's pinned historical input list, without running
    # its callbacks or broadening what those callbacks will accept later.
    pair = run_path(str(Path(__file__).with_name('jacoco_antrun_contract.py')))
    prior = pair['utilities'](source)
    historical = {**prior['HISTORICAL_FILES'], pair['UTILITIES_CONTRACT']: pair['UTILITIES_SHA'],
                  'docs/operations/boot-utilities-plugin-evidence/candidate-root-pom.xml': pair['PRIOR_POM']}
    for name, expected in historical.items():
        contract['checked'](source, name, expected)
        target = destination / name
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((source / name).read_bytes())
        contract['checked'](destination, name, expected)
        result['suppliedSourceHashes'][name] = expected
        result['retainedSourceHashes'][name] = expected
    contract['verify_projection'](result)
    return result
