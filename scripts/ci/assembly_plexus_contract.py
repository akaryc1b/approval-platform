"""Bounded Assembly-only source and paired-model contract; no project execution.

The accepted witness is read as sealed data. Historical callbacks are not invoked.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path
import xml.etree.ElementTree as ET

P = '{http://maven.apache.org/POM/4.0.0}'
NS = {'m': P[1:-1]}
CURRENT_POM = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629'
PRIOR_POM = 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c'
WITNESS = 'docs/operations/jacoco-antrun-plugin-evidence/source-witness.json'
WITNESS_SHA = '56e5701e73c25262887edcf73663cf9f7eff870a34891927107dfeceec174971'
RULES = 'config/checkstyle/checkstyle.xml'
RULES_SHA = 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
ADDITION = '''                <!-- Keep Assembly inactive; constrain only its private Plexus Utils dependency. -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-assembly-plugin</artifactId>
                    <version>3.7.1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.codehaus.plexus</groupId>
                            <artifactId>plexus-utils</artifactId>
                            <version>4.0.3</version>
                        </dependency>
                    </dependencies>
                </plugin>
'''


def require(ok, message):
    if not ok:
        raise ValueError('Assembly ' + message)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked(root, name, expected):
    require(not name.startswith('/') and all(part not in ('', '.', '..') for part in name.split('/')),
            'unsafe source member')
    file = root / name
    require(file.is_file() and not file.is_symlink() and file.resolve() == file
            and file.is_relative_to(root) and file.stat().st_size <= 2 * 1024 * 1024,
            'requires bounded real source file: ' + name)
    require(digest(file) == expected, 'source bytes drift: ' + name)
    return file


def is_assembly_source(root):
    """Dispatch hint only; callers must subsequently verify exact source bytes."""
    return b'<artifactId>maven-assembly-plugin</artifactId>' in (root / 'pom.xml').read_bytes()


def reverse_source(raw):
    require(hashlib.sha256(raw).hexdigest() == CURRENT_POM, 'candidate source bytes changed')
    text = raw.decode('utf8')
    require(text.count(ADDITION) == 1, 'private addition missing or ambiguous')
    previous = text.replace(ADDITION, '', 1).encode('utf8')
    require(hashlib.sha256(previous).hexdigest() == PRIOR_POM, 'source changed beyond private addition')
    return previous


def source_inventory(root):
    root = root.resolve()
    checked(root, WITNESS, WITNESS_SHA)
    rows = json.loads((root / WITNESS).read_text())['sourcePomFiles']
    require(len(rows) == len({row['path'] for row in rows}) == 28, 'requires exact 28-POM inventory')
    result = {}
    for row in rows:
        name = row['path']
        expected = CURRENT_POM if name == 'pom.xml' else row['candidateSha256']
        checked(root, name, expected); result[name] = expected
    checked(root, RULES, RULES_SHA); result[RULES] = RULES_SHA
    roots = {root, *root.parents, *[(root / row['path']).parent for row in rows]}
    require(all(not (directory / '.mvn').exists() for directory in roots), 'unreviewed Maven configuration')
    reverse_source((root / 'pom.xml').read_bytes())
    return result


def verify_source(root):
    return source_inventory(root)


def materialize_retained_source(source, destination):
    source, destination = source.resolve(), destination.absolute()
    require(destination.resolve() == destination and not destination.exists()
            and not destination.is_relative_to(source) and not source.is_relative_to(destination),
            'projection requires fresh real path outside source')
    supplied = source_inventory(source)
    supplied[WITNESS] = WITNESS_SHA
    previous = reverse_source((source / 'pom.xml').read_bytes())
    destination.mkdir(parents=False, exist_ok=False)
    retained = {}
    for name in supplied:
        data = previous if name == 'pom.xml' else (source / name).read_bytes()
        file = destination / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
        retained[name] = digest(file)
    result = {'schemaVersion': 'ASSEMBLY_PLEXUS_RETAINED_SOURCE_VIEW_V1',
              'suppliedSourceRoot': str(source), 'retainedSourceRoot': str(destination),
              'suppliedSourceHashes': supplied, 'retainedSourceHashes': retained,
              'suppliedRootPomSha256': CURRENT_POM, 'retainedRootPomSha256': PRIOR_POM,
              'adapterSha256': digest(Path(__file__)),
              'scope': 'Exact Assembly private addition reversed; no historical callbacks or compatibility claim.'}
    verify_projection(result)
    return result


def verify_projection(projection):
    require(projection['schemaVersion'] == 'ASSEMBLY_PLEXUS_RETAINED_SOURCE_VIEW_V1'
            and projection['suppliedRootPomSha256'] == CURRENT_POM
            and projection['retainedRootPomSha256'] == PRIOR_POM
            and digest(Path(__file__)) == projection['adapterSha256'], 'projection identity changed')
    source, retained = (Path(projection[key]).resolve() for key in ('suppliedSourceRoot', 'retainedSourceRoot'))
    require(set(projection['suppliedSourceHashes']) == set(projection['retainedSourceHashes']),
            'projection membership changed')
    for root, key in [(source, 'suppliedSourceHashes'), (retained, 'retainedSourceHashes')]:
        for name, expected in projection[key].items():
            checked(root, name, expected)
        roots = {root, *root.parents, *[(root / name).parent for name in projection[key] if name.endswith('pom.xml')]}
        require(all(not (directory / '.mvn').exists() for directory in roots), 'projected Maven configuration changed')
    changed = [name for name in projection['suppliedSourceHashes']
               if projection['suppliedSourceHashes'][name] != projection['retainedSourceHashes'][name]]
    require(changed == ['pom.xml'], 'projection altered unrelated source')
    require(reverse_source((source / 'pom.xml').read_bytes()) == (retained / 'pom.xml').read_bytes(),
            'projection reversal changed')
    members = list(retained.rglob('*'))
    require(not any(file.is_symlink() for file in members)
            and {str(file.relative_to(retained)) for file in members if file.is_file()}
            == set(projection['retainedSourceHashes']), 'retained view membership drift')


def fixture_plugin(root, baseline=False):
    """Return the exact candidate plugin or inherited baseline coordinates."""
    verify_source(root)
    if baseline:
        plugin = ET.Element(P + 'plugin')
        for key, value in [('groupId', 'org.apache.maven.plugins'), ('artifactId', 'maven-assembly-plugin'), ('version', '3.7.1')]:
            ET.SubElement(plugin, P + key).text = value
        return plugin
    document = ET.parse(root / 'pom.xml').getroot()
    plugins = [p for p in document.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
               if p.findtext('m:artifactId', namespaces=NS) == 'maven-assembly-plugin']
    require(len(plugins) == 1, 'requires one root managed declaration')
    return copy.deepcopy(plugins[0])


def verify_effective(source_root, file, baseline, baseline_root):
    source_root, baseline_root = source_root.resolve(), baseline_root.resolve()
    expected = verify_source(source_root); expected['pom.xml'] = PRIOR_POM
    for name, sha in expected.items():
        checked(baseline_root, name, sha)
    parser = run_path(str(source_root / 'scripts/ci/checkstyle_plugin_contract.py'))
    clean = run_path(str(source_root / 'scripts/ci/clean_plugin_contract.py'))
    value, one, fields, semantic = (parser[key] for key in ('value', 'one', 'fields', 'semantic'))
    def models(path):
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16 * 1024 * 1024,
                'requires bounded model files')
        document = ET.parse(path).getroot()
        require(document.tag in (P + 'projects', 'projects'), 'malformed effective document')
        rows = list(document)
        require(len(rows) == 26 and all(row.tag == P + 'project' for row in rows), 'requires all 26 models')
        ids = [(value(row, 'groupId'), value(row, 'artifactId'), value(row, 'version')) for row in rows]
        require(len(set(ids)) == 26 and {(g, a) for g, a, _ in ids}
                == {(clean['PROJECT_GROUP'], a) for a in clean['REACTOR'].values()}, 'effective project identity drift')
        return rows, ids
    before, prior_ids = models(baseline); after, current_ids = models(file)
    require(prior_ids == current_ids, 'model order or version drift')
    for prior, current in zip(before, after):
        reverted = copy.deepcopy(current)
        owners = [p for p in reverted.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
                  if value(p, 'artifactId') == 'maven-assembly-plugin']
        all_owners = [p for p in reverted.findall('.//m:plugin', NS) if value(p, 'artifactId') == 'maven-assembly-plugin']
        require(len(owners) == len(all_owners) == 1, 'effective Assembly escaped sole managed owner')
        plugin = owners[0]
        require(value(plugin, 'groupId', False) in ('', 'org.apache.maven.plugins')
                and value(plugin, 'version') == '3.7.1', 'effective plugin identity drift')
        require(plugin.find('m:executions', NS) is None, 'lifecycle activation')
        dependencies = one(plugin, 'dependencies'); fields(dependencies, ['dependency'])
        dependency = one(dependencies, 'dependency'); fields(dependency, ['groupId', 'artifactId', 'version'])
        require(value(dependency, 'groupId') == 'org.codehaus.plexus'
                and value(dependency, 'artifactId') == 'plexus-utils'
                and value(dependency, 'version') == '4.0.3', 'private dependency drift')
        plugin.remove(dependencies)
        require(semantic(prior, str(baseline_root)) == semantic(reverted, str(source_root)),
                'effective model changed beyond private dependency: ' + value(current, 'artifactId'))
    return {'modelCount': 26, 'managedAssemblyModelCount': 26, 'assemblyExecutionCount': 0,
            'allOtherModelFieldsUnchanged': True, 'nonReactorOverlaysSourceBoundOnly': 2}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps({'status': 'PASSED_ASSEMBLY_SOURCE', 'sourceHashes': verify_source(args.root)}, sort_keys=True))
