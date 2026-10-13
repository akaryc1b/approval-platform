"""Exact Boot-private HTTP successor and byte-preserving historical source view.

All functions are data-only. No Maven, Java, network, shell or scanner execution.
Historical contracts are copied unchanged and still validate their own baselines.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path
import tempfile
import xml.etree.ElementTree as ET

P = '{http://maven.apache.org/POM/4.0.0}'
NS = {'m': P[1:-1]}
CURRENT_POM = 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1'
PRIOR_POM = 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5'
WITNESS = 'docs/operations/compiler-plugin-evidence/source-witness.json'
WITNESS_SHA = '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a'
RULES = 'config/checkstyle/checkstyle.xml'
RULES_SHA = 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
ADDITION = '''                        <dependency>
                            <groupId>org.apache.httpcomponents.client5</groupId>
                            <artifactId>httpclient5</artifactId>
                            <version>5.6.3</version>
                        </dependency>
'''
HISTORICAL_FILES = {'docs/operations/checkstyle-guava-evidence/candidate-root-pom.xml': 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5',
 'docs/operations/checkstyle-plugin-evidence/candidate-root-pom.xml': 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb',
 'docs/operations/compiler-plugin-evidence/candidate-root-pom.xml': '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a',
 'docs/operations/compiler-plugin-evidence/source-witness.json': '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a',
 'scripts/ci/checkstyle_guava_contract.py': '3a638ea0678150388f7b9b1b21d7fcb649e1c5541250c8c51dafc0e0c2a48a7d',
 'scripts/ci/checkstyle_plugin_contract.py': 'd5d1927221ae93a27dc07ace72cc8d48b2e0af1b9cae55ee2c6d34434902c0cf',
 'scripts/ci/clean_plugin_contract.py': 'b48a8ad2d3baf4d8da714bf6017dcbdf6bbd19eed500576a17fdf08b43eaa75e',
 'scripts/ci/compiler_plugin_contract.py': '03f19ef2c91bebc41ef52e4c5822e2287173452f7545daec34048a12140344c1',
 'scripts/ci/verify-checkstyle-plugin-baseline.py': 'dcc78fdac45c3814893efc81d1c771bfe47da2083a6585034822591e4a6938c0',
 'scripts/ci/verify-clean-plugin-baseline.py': 'ae248f133cd747b655030d4d3858d87f715dbd791b11e605206e032b1c62e433',
 'scripts/ci/verify-compiler-plugin-baseline.py': '79e06c0a0eea3a4e19626e6c256796423078d62348a70e7cf08f47683e5de0d6',
 'scripts/ci/verify-release-plugin-baseline.py': '2fb1c9b8552c0ce913842550e2a3bf9c8f890ccda7fdac11bcebb618284e9e28'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked_file(root, name, expected):
    file = root / name
    require(file.is_file() and not file.is_symlink() and file.resolve() == file and file.resolve().is_relative_to(root)
            and file.stat().st_size <= 2 * 1024 * 1024, 'Boot bounded source path required: ' + name)
    require(digest(file) == expected, 'Boot source bytes drift: ' + name)
    return file


def is_boot_source(root):
    """Dispatch only; the exact source validator remains mandatory afterward."""
    raw = (root / 'pom.xml').read_bytes()
    return b'<artifactId>httpclient5</artifactId>' in raw


def reverse_source(raw):
    require(hashlib.sha256(raw).hexdigest() == CURRENT_POM, 'Boot exact candidate source bytes changed')
    text = raw.decode('utf8')
    require(text.count(ADDITION) == 1, 'Boot exact Client addition missing or ambiguous')
    before = text.replace(ADDITION, '', 1).encode('utf8')
    require(hashlib.sha256(before).hexdigest() == PRIOR_POM, 'Boot source changed beyond the sole Client override')
    return before


def source_inventory(root, current=True):
    root = root.resolve()
    checked_file(root, WITNESS, WITNESS_SHA)
    witness = json.loads((root / WITNESS).read_text())
    rows = witness['sourcePomFiles']
    require(len(rows) == 28 and len({row['path'] for row in rows}) == 28, 'Boot requires exact retained 28-POM inventory')
    hashes = {}
    for row in rows:
        name = row['path']
        require(not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')), 'Unsafe source member')
        expected = (CURRENT_POM if current else PRIOR_POM) if name == 'pom.xml' else row['candidateSha256']
        checked_file(root, name, expected); hashes[name] = expected
    checked_file(root, RULES, RULES_SHA); hashes[RULES] = RULES_SHA
    config_roots = {root, *root.parents, *[(root / row['path']).parent for row in rows]}
    require(all(not (directory / '.mvn').exists() for directory in config_roots), 'Boot unreviewed Maven configuration')
    if current:
        reverse_source((root / 'pom.xml').read_bytes())
    return hashes


def materialize_retained_source(source, destination):
    """Write one exclusive predecessor view, preserving every supplied input."""
    source, destination = source.resolve(), destination.absolute()
    require(destination.resolve() == destination and not destination.exists()
            and not destination.is_relative_to(source) and not source.is_relative_to(destination),
            'Boot projection requires a fresh real path outside supplied source')
    supplied = source_inventory(source)
    for name, expected in HISTORICAL_FILES.items():
        checked_file(source, name, expected); supplied[name] = expected
    require(reverse_source((source / 'pom.xml').read_bytes()) ==
            (source / 'docs/operations/checkstyle-guava-evidence/candidate-root-pom.xml').read_bytes(),
            'Boot predecessor differs from retained accepted Guava POM')
    destination.mkdir(parents=False, exist_ok=False)
    historical = {}
    for name, expected in supplied.items():
        data = (source / name).read_bytes()
        if name == 'pom.xml':
            data = reverse_source(data)
        file = destination / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
        historical[name] = digest(file)
    projection = {'schemaVersion': 'BOOT_HTTP_RETAINED_SOURCE_VIEW_V1',
        'suppliedSourceRoot': str(source), 'retainedSourceRoot': str(destination),
        'suppliedSourceHashes': supplied, 'retainedSourceHashes': historical,
        'suppliedRootPomSha256': CURRENT_POM, 'retainedRootPomSha256': PRIOR_POM,
        'adapterSha256': digest(Path(__file__)),
        'scope': 'Exact Boot Client override removed for unchanged historical gates. This predecessor view does not claim current Boot transport coverage.'}
    verify_projection(projection)
    guava = run_path(str(destination / 'scripts/ci/checkstyle_guava_contract.py'))
    require(len(guava['verify_source'](destination)) == 26, 'Boot restored historical source failed its unchanged gates')
    return projection


def verify_projection(projection):
    require(digest(Path(__file__)) == projection['adapterSha256'], 'Boot adapter changed during projected execution')
    require(projection['suppliedRootPomSha256'] == CURRENT_POM and projection['retainedRootPomSha256'] == PRIOR_POM,
            'Boot projection pin identity changed')
    source, retained = (Path(projection[name]).resolve() for name in ('suppliedSourceRoot', 'retainedSourceRoot'))
    for root, key in [(source, 'suppliedSourceHashes'), (retained, 'retainedSourceHashes')]:
        for name, expected in projection[key].items():
            checked_file(root, name, expected)
    require(set(projection['suppliedSourceHashes']) == set(projection['retainedSourceHashes']), 'Boot projection membership drift')
    require([name for name in projection['suppliedSourceHashes']
             if projection['suppliedSourceHashes'][name] != projection['retainedSourceHashes'][name]] == ['pom.xml'],
            'Boot projection changed other supplied bytes')
    require(reverse_source((source / 'pom.xml').read_bytes()) == (retained / 'pom.xml').read_bytes(),
            'Boot projection root reversal changed')
    for root in (source, retained):
        config_roots = {root, *root.parents, *[(root / name).parent for name in projection['suppliedSourceHashes'] if name.endswith('pom.xml')]}
        require(all(not (directory / '.mvn').exists() for directory in config_roots), 'Boot projection Maven configuration changed')
    entries = list(retained.rglob('*'))
    require(not any(file.is_symlink() for file in entries)
            and {str(file.relative_to(retained)) for file in entries if file.is_file()} == set(projection['retainedSourceHashes']),
            'Boot retained source membership changed')


def verify_source(root):
    root = root.resolve()
    hashes = source_inventory(root)
    with tempfile.TemporaryDirectory(prefix='boot-http-source-') as temporary:
        projection = materialize_retained_source(root, Path(temporary) / 'accepted-guava')
        require(len(projection['suppliedSourceHashes']) >= len(hashes), 'Boot source projection incomplete')
    return hashes


def verify_effective(source_root, file, baseline, baseline_root):
    """Validate the paired supplied26-model documents; caller establishes execution provenance."""
    source_root, baseline_root = source_root.resolve(), baseline_root.resolve()
    source_inventory(source_root)
    # The actual baseline capture contains the exact retained POM/rule subset.
    expected = source_inventory(source_root)
    expected['pom.xml'] = PRIOR_POM
    for name, h in expected.items():
        checked_file(baseline_root, name, h)
    for name, h in HISTORICAL_FILES.items():
        checked_file(source_root, name, h)
    clean = run_path(str(source_root / 'scripts/ci/clean_plugin_contract.py'))
    parser = run_path(str(source_root / 'scripts/ci/checkstyle_plugin_contract.py'))
    value, one, fields, semantic = (parser[key] for key in ('value', 'one', 'fields', 'semantic'))
    def models(path):
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16 * 1024 * 1024, 'Bounded model file required')
        document = ET.parse(path).getroot()
        require(document.tag in (P + 'projects', 'projects'), 'Boot malformed effective model document')
        rows = list(document)
        require(len(rows) == 26 and all(row.tag == P + 'project' for row in rows), 'Boot requires all 26 models')
        ids = [(value(row, 'groupId'), value(row, 'artifactId'), value(row, 'version')) for row in rows]
        require(len(set(ids)) == 26 and {(g, a) for g, a, _ in ids}
                == {(clean['PROJECT_GROUP'], a) for a in clean['REACTOR'].values()}, 'Boot model identity or membership drift')
        return rows, ids
    before, prior_ids = models(baseline); after, candidate_ids = models(file)
    require(prior_ids == candidate_ids, 'Boot model order/identity drift')
    changes = []
    for prior, current in zip(before, after):
        normalized = copy.deepcopy(current)
        managed = [p for p in normalized.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
                   if value(p, 'artifactId') == 'spring-boot-maven-plugin']
        active_plugins = [p for p in normalized.findall('m:build/m:plugins/m:plugin', NS)
                          if value(p, 'artifactId') == 'spring-boot-maven-plugin']
        all_boot = [p for p in normalized.findall('.//m:plugin', NS) if value(p, 'artifactId') == 'spring-boot-maven-plugin']
        require(len(managed) == 1 and len(active_plugins) <= 1 and len(all_boot) == len(managed) + len(active_plugins),
                'Boot effective declaration escaped managed/active owners')
        activated = value(normalized, 'artifactId') in ('approval-server', 'approval-generic-spring-host-example')
        require(bool(active_plugins) == activated, 'Boot active owner membership drift')
        for plugin in managed + active_plugins:
            require(value(plugin, 'groupId') == 'org.springframework.boot' and value(plugin, 'version') == '4.0.8', 'Boot retained plugin pin drift')
            dependencies = one(plugin, 'dependencies')
            additions = [d for d in dependencies if value(d, 'groupId') == 'org.apache.httpcomponents.client5'
                         and value(d, 'artifactId') == 'httpclient5']
            require(len(additions) == 1, 'Boot requires exactly one local Client addition')
            addition = additions[0]; fields(addition, ['groupId', 'artifactId', 'version'] + (['scope'] if activated else []))
            require(value(addition, 'version') == '5.6.3' and value(addition, 'scope', False) == ('compile' if activated else ''),
                    'Boot actual Client version/scope shape drift')
            dependencies.remove(addition)
        require(semantic(prior, str(baseline_root)) == semantic(normalized, str(source_root)),
                'Boot effective model changed beyond exact Client additions: ' + value(normalized, 'artifactId'))
        changes.append({'artifactId': value(normalized, 'artifactId'), 'managedAdditions': 1,
                        'activeAdditions': len(active_plugins), 'managedScope': 'compile' if activated else 'omitted'})
    require(sum(row['activeAdditions'] for row in changes) == 2, 'Boot active model count drift')
    return {'modelCount': 26, 'managedBootModelCount': 26, 'activeBootModelCount': 2,
            'allOtherModelFieldsUnchanged': True, 'models': changes,
            'scope': 'Validation of supplied26-model documents; caller establishes actual execution provenance. The two source-bound overlays are outside this document set.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--materialize-retained-source', type=Path)
    parser.add_argument('--effective-pom', type=Path)
    parser.add_argument('--baseline-effective-pom', type=Path)
    parser.add_argument('--baseline-root', type=Path)
    args = parser.parse_args()
    if args.materialize_retained_source:
        require(not any((args.effective_pom, args.baseline_effective_pom, args.baseline_root)), 'Projection and model verification are separate operations')
        result = materialize_retained_source(args.root, args.materialize_retained_source)
    else:
        hashes = verify_source(args.root)
        result = {'status': 'PASSED_BOOT_HTTP_SOURCE', 'sourcePomCount': 28,
                  'sourceHashes': hashes, 'canonicalCiEvidence': False, 'transportCompatibilityClaimed': False}
        if any((args.effective_pom, args.baseline_effective_pom, args.baseline_root)):
            require(all((args.effective_pom, args.baseline_effective_pom, args.baseline_root)), 'Paired model files/source roots required')
            result.update(verify_effective(args.root, args.effective_pom, args.baseline_effective_pom, args.baseline_root))
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
