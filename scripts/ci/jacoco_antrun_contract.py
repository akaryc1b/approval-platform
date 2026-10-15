"""Exact two-owner Plexus successor and byte-preserving accepted source view.

Data-only source/model validation. No Maven, Java, network or scanner execution.
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
CURRENT_POM = 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c'
PRIOR_POM = '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579'
UTILITIES_CONTRACT = 'scripts/ci/boot_utilities_contract.py'
UTILITIES_SHA = '6ef0328fc5fb015e9a9f13a7999236275aaead5d0364fa0ed7ec2296bbced39f'
ANTRUN_ADDITION = '''                <!-- Keep the inherited AntRun plugin inactive; constrain only its private realm. -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-antrun-plugin</artifactId>
                    <version>3.1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.codehaus.plexus</groupId>
                            <artifactId>plexus-utils</artifactId>
                            <version>3.6.1</version>
                        </dependency>
                    </dependencies>
                </plugin>
'''
JACOCO_ADDITION = '''                <dependencies>
                    <dependency>
                        <groupId>org.codehaus.plexus</groupId>
                        <artifactId>plexus-utils</artifactId>
                        <version>3.6.1</version>
                    </dependency>
                </dependencies>
'''


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked(root, name, expected):
    file = root / name
    require(file.is_file() and not file.is_symlink() and file.resolve() == file
            and file.resolve().is_relative_to(root) and file.stat().st_size <= 2 * 1024 * 1024,
            'Pair requires a bounded real source file: ' + name)
    require(digest(file) == expected, 'Pair source bytes drift: ' + name)
    return file


def is_pair_source(root):
    """Dispatch only; exact verification is required before using its result."""
    return b'<artifactId>maven-antrun-plugin</artifactId>' in (root / 'pom.xml').read_bytes()


def assembly_contract():
    return run_path(str(Path(__file__).with_name('assembly_plexus_contract.py')))


def before_assembly(raw):
    if b'<artifactId>maven-assembly-plugin</artifactId>' in raw:
        return assembly_contract()['reverse_source'](raw)
    return raw


def reverse_source(raw):
    raw = before_assembly(raw)
    require(hashlib.sha256(raw).hexdigest() == CURRENT_POM, 'Pair exact candidate source bytes changed')
    text = raw.decode('utf8')
    require(text.count(ANTRUN_ADDITION) == 1 and text.count(JACOCO_ADDITION) == 1,
            'Pair exact private additions missing or ambiguous')
    previous = text.replace(ANTRUN_ADDITION, '', 1).replace(JACOCO_ADDITION, '', 1).encode('utf8')
    require(hashlib.sha256(previous).hexdigest() == PRIOR_POM,
            'Pair source changed beyond the exact JaCoCo and AntRun overrides')
    return previous


def utilities(root):
    checked(root, UTILITIES_CONTRACT, UTILITIES_SHA)
    return run_path(str(root / UTILITIES_CONTRACT))


def source_inventory(root):
    root = root.resolve()
    old = utilities(root)
    checked(root, old['WITNESS'], old['WITNESS_SHA'])
    rows = json.loads((root / old['WITNESS']).read_text())['sourcePomFiles']
    require(len(rows) == 28 and len({row['path'] for row in rows}) == 28, 'Pair requires exact 28-POM inventory')
    result = {}
    supplied_pom = (root / 'pom.xml').read_bytes()
    current_pom = CURRENT_POM
    if b'<artifactId>maven-assembly-plugin</artifactId>' in supplied_pom:
        contract = assembly_contract()
        contract['verify_source'](root)
        current_pom = contract['CURRENT_POM']
    for row in rows:
        name = row['path']
        require(not name.startswith('/') and all(part not in ('', '.', '..') for part in name.split('/')),
                'Pair unsafe source member')
        expected = current_pom if name == 'pom.xml' else row['candidateSha256']
        checked(root, name, expected)
        result[name] = expected
    checked(root, old['RULES'], old['RULES_SHA']); result[old['RULES']] = old['RULES_SHA']
    roots = {root, *root.parents, *[(root / row['path']).parent for row in rows]}
    require(all(not (directory / '.mvn').exists() for directory in roots), 'Pair unreviewed Maven configuration')
    reverse_source((root / 'pom.xml').read_bytes())
    return result


def materialize_retained_source(source, destination):
    source, destination = source.resolve(), destination.absolute()
    require(destination.resolve() == destination and not destination.exists()
            and not destination.is_relative_to(source) and not source.is_relative_to(destination),
            'Pair projection requires a fresh real path outside supplied source')
    supplied = source_inventory(source)
    old = utilities(source)
    historical = {**old['HISTORICAL_FILES'], UTILITIES_CONTRACT: UTILITIES_SHA,
                  'docs/operations/boot-utilities-plugin-evidence/candidate-root-pom.xml': PRIOR_POM}
    for name, expected in historical.items():
        checked(source, name, expected); supplied[name] = expected
    accepted = reverse_source((source / 'pom.xml').read_bytes())
    require(accepted == (source / 'docs/operations/boot-utilities-plugin-evidence/candidate-root-pom.xml').read_bytes(),
            'Pair reversal differs from retained accepted utilities source')
    destination.mkdir(parents=False, exist_ok=False)
    retained = {}
    for name in supplied:
        data = accepted if name == 'pom.xml' else (source / name).read_bytes()
        file = destination / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
        retained[name] = digest(file)
    result = {'schemaVersion': 'JACOCO_ANTRUN_RETAINED_SOURCE_VIEW_V1',
              'suppliedSourceRoot': str(source), 'retainedSourceRoot': str(destination),
              'suppliedSourceHashes': supplied, 'retainedSourceHashes': retained,
              'suppliedRootPomSha256': supplied['pom.xml'], 'retainedRootPomSha256': PRIOR_POM,
              'adapterSha256': digest(Path(__file__)),
              'scope': 'Exact two private plugin additions reversed for unchanged accepted utilities contracts. No current pair compatibility claim.'}
    verify_projection(result)
    require(len(old['verify_source'](destination)) == 29, 'Pair accepted predecessor failed unchanged utilities contracts')
    return result


def verify_projection(projection):
    require(projection['schemaVersion'] == 'JACOCO_ANTRUN_RETAINED_SOURCE_VIEW_V1'
            and projection['suppliedRootPomSha256'] == projection['suppliedSourceHashes'].get('pom.xml')
            and projection['retainedRootPomSha256'] == PRIOR_POM
            and digest(Path(__file__)) == projection['adapterSha256'], 'Pair projection identity changed')
    source, retained = (Path(projection[key]).resolve() for key in ('suppliedSourceRoot', 'retainedSourceRoot'))
    require(hashlib.sha256(before_assembly((source / 'pom.xml').read_bytes())).hexdigest() == CURRENT_POM,
            'Pair projection accepted predecessor changed')
    require(set(projection['suppliedSourceHashes']) == set(projection['retainedSourceHashes']),
            'Pair projection inventory membership changed')
    for root, key in [(source, 'suppliedSourceHashes'), (retained, 'retainedSourceHashes')]:
        for name, expected in projection[key].items():
            checked(root, name, expected)
        config_roots = {root, *root.parents, *[(root / name).parent for name in projection[key] if name.endswith('pom.xml')]}
        require(all(not (directory / '.mvn').exists() for directory in config_roots), 'Pair projected Maven configuration changed')
    require([name for name in projection['suppliedSourceHashes'] if projection['suppliedSourceHashes'][name]
             != projection['retainedSourceHashes'][name]] == ['pom.xml'], 'Pair projection altered unrelated source bytes')
    require(reverse_source((source / 'pom.xml').read_bytes()) == (retained / 'pom.xml').read_bytes(),
            'Pair projection root reversal changed')
    members = list(retained.rglob('*'))
    require(not any(file.is_symlink() for file in members)
            and {str(file.relative_to(retained)) for file in members if file.is_file()}
            == set(projection['retainedSourceHashes']), 'Pair retained view member drift')


def verify_source(root):
    root = root.resolve()
    result = source_inventory(root)
    with tempfile.TemporaryDirectory(prefix='jacoco-antrun-source-') as directory:
        materialize_retained_source(root, Path(directory) / 'accepted-utilities')
    return result


def fixture_plugins(root, baseline=False):
    """Return validated plugin elements for a disposable fixture, without executing goals."""
    verify_source(root)
    raw = (root / 'pom.xml').read_bytes()
    source = ET.fromstring(reverse_source(raw) if baseline else raw)
    jacoco = [p for p in source.findall('m:build/m:plugins/m:plugin', NS)
              if p.findtext('m:artifactId', namespaces=NS) == 'jacoco-maven-plugin']
    require(len(jacoco) == 1, 'Pair requires one active JaCoCo source plugin')
    jacoco = copy.deepcopy(jacoco[0]); jacoco.find('m:version', NS).text = '0.8.15'
    if baseline:
        antrun = ET.Element(P + 'plugin')
        for key, value in [('groupId', 'org.apache.maven.plugins'), ('artifactId', 'maven-antrun-plugin'), ('version', '3.1.0')]:
            ET.SubElement(antrun, P + key).text = value
    else:
        rows = [p for p in source.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
                if p.findtext('m:artifactId', namespaces=NS) == 'maven-antrun-plugin']
        require(len(rows) == 1, 'Pair requires one managed AntRun source plugin')
        antrun = copy.deepcopy(rows[0])
    return {'jacoco': jacoco, 'antrun': antrun}


def verify_effective(source_root, file, baseline, baseline_root):
    source_root, baseline_root = source_root.resolve(), baseline_root.resolve()
    verify_source(source_root)
    expected = source_inventory(source_root); expected['pom.xml'] = PRIOR_POM
    for name, value in expected.items():
        checked(baseline_root, name, value)
    parser = run_path(str(source_root / 'scripts/ci/checkstyle_plugin_contract.py'))
    clean = run_path(str(source_root / 'scripts/ci/clean_plugin_contract.py'))
    value, one, fields, semantic = (parser[key] for key in ('value', 'one', 'fields', 'semantic'))
    def models(path):
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16 * 1024 * 1024,
                'Pair requires bounded model files')
        document = ET.parse(path).getroot()
        require(document.tag in (P + 'projects', 'projects'), 'Pair malformed effective document')
        rows = list(document)
        require(len(rows) == 26 and all(row.tag == P + 'project' for row in rows), 'Pair requires all 26 models')
        ids = [(value(row, 'groupId'), value(row, 'artifactId'), value(row, 'version')) for row in rows]
        require(len(set(ids)) == 26 and {(g, a) for g, a, _ in ids}
                == {(clean['PROJECT_GROUP'], a) for a in clean['REACTOR'].values()}, 'Pair effective identity drift')
        return rows, ids
    before, prior_ids = models(baseline); after, current_ids = models(file)
    require(prior_ids == current_ids, 'Pair model order or version drift')
    for prior, current in zip(before, after):
        reverted = copy.deepcopy(current)
        for artifact, group, version, location, active in [
            ('jacoco-maven-plugin', 'org.jacoco', '0.8.15', 'm:build/m:plugins/m:plugin', True),
            ('maven-antrun-plugin', 'org.apache.maven.plugins', '3.1.0', 'm:build/m:pluginManagement/m:plugins/m:plugin', False),
        ]:
            owners = [p for p in reverted.findall(location, NS) if value(p, 'artifactId') == artifact]
            all_owners = [p for p in reverted.findall('.//m:plugin', NS) if value(p, 'artifactId') == artifact]
            require(len(owners) == len(all_owners) == 1, 'Pair effective plugin escaped its sole owner: ' + artifact)
            plugin = owners[0]
            # Maven's effective model omits the standard plugin group on AntRun.
            # Preserve that XML shape for paired equality; do not normalize other owners.
            default_group = artifact == 'maven-antrun-plugin' and one(plugin, 'groupId', False) is None
            actual_group = 'org.apache.maven.plugins' if default_group else value(plugin, 'groupId')
            require(actual_group == group and value(plugin, 'version') == version,
                    'Pair effective plugin identity drift: ' + artifact)
            if not active:
                require(plugin.find('m:executions', NS) is None, 'Pair AntRun lifecycle activation')
            dependencies = one(plugin, 'dependencies'); require(len(dependencies) == 1, 'Pair private dependency count changed')
            dependency = list(dependencies)[0]
            fields(dependency, ['groupId', 'artifactId', 'version'] + (['scope'] if active else []))
            require(value(dependency, 'groupId') == 'org.codehaus.plexus'
                    and value(dependency, 'artifactId') == 'plexus-utils'
                    and value(dependency, 'version') == '3.6.1'
                    and value(dependency, 'scope', False) == ('compile' if active else ''),
                    'Pair private Plexus identity or effective scope changed')
            plugin.remove(dependencies)
        require(semantic(prior, str(baseline_root)) == semantic(reverted, str(source_root)),
                'Pair effective model changed beyond the exact two private dependencies: ' + value(current, 'artifactId'))
    return {'modelCount': 26, 'activeJacocoModelCount': 26, 'managedAntRunModelCount': 26,
            'antRunExecutionCount': 0, 'allOtherModelFieldsUnchanged': True,
            'scope': 'Supplied model validation only; caller establishes actual execution provenance. Two overlays are source-bound only.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--materialize-retained-source', type=Path)
    parser.add_argument('--effective-pom', type=Path)
    parser.add_argument('--baseline-effective-pom', type=Path)
    parser.add_argument('--baseline-root', type=Path)
    args = parser.parse_args()
    model_args = (args.effective_pom, args.baseline_effective_pom, args.baseline_root)
    if args.materialize_retained_source:
        require(not any(model_args), 'Pair projection and model validation are separate operations')
        result = materialize_retained_source(args.root, args.materialize_retained_source)
    else:
        result = {'status': 'PASSED_JACOCO_ANTRUN_SOURCE', 'sourcePomCount': 28,
                  'sourceHashes': verify_source(args.root), 'canonicalCiEvidence': False,
                  'compatibilityAcceptanceClaimed': False, 'scannerDispositionClaimed': False}
        if any(model_args):
            require(all(model_args), 'Pair requires both model files and both source roots')
            result.update(verify_effective(args.root, *model_args))
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
