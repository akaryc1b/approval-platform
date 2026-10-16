"""Bounded Assembly/Enforcer Lang-only source and paired-model contract; no project execution.

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
CURRENT_POM = 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932'
PRIOR_POM = '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f'
WITNESS = 'docs/operations/assembly-plexus-evidence/source-witness.json'
WITNESS_SHA = '3a8dfc5cb42b4b07af04a0ffdc40f2dbda73a9103ecd61f62f1e8fbe0eacaf21'
RULES = 'config/checkstyle/checkstyle.xml'
RULES_SHA = 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
ADDITIONS = ['        <maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>\n', '        <maven.assembly.commons-lang3.version>3.18.0</maven.assembly.commons-lang3.version>\n', '                        <dependency>\n                            <groupId>org.apache.commons</groupId>\n                            <artifactId>commons-lang3</artifactId>\n                            <version>${maven.assembly.commons-lang3.version}</version>\n                        </dependency>\n', '                <dependencies>\n                    <dependency>\n                        <groupId>org.apache.commons</groupId>\n                        <artifactId>commons-lang3</artifactId>\n                        <version>${maven.enforcer.commons-lang3.version}</version>\n                    </dependency>\n                </dependencies>\n']
OLD_COMMENT = '<!-- Keep Assembly inactive; constrain only its private Plexus Utils dependency. -->'
NEW_COMMENT = '<!-- Keep Assembly inactive; constrain its private utility dependencies. -->'

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
    for addition in ADDITIONS:
        require(text.count(addition) == 1, 'scoped Lang addition missing or ambiguous')
        text = text.replace(addition, '', 1)
    require(text.count(NEW_COMMENT) == 1 and OLD_COMMENT not in text, 'Assembly comment drift')
    previous = text.replace(NEW_COMMENT, OLD_COMMENT, 1).encode('utf8')
    require(hashlib.sha256(previous).hexdigest() == PRIOR_POM, 'source changed beyond scoped Lang additions')
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
    result = {'schemaVersion': 'ASSEMBLY_ENFORCER_LANG_RETAINED_SOURCE_VIEW_V1',
              'suppliedSourceRoot': str(source), 'retainedSourceRoot': str(destination),
              'suppliedSourceHashes': supplied, 'retainedSourceHashes': retained,
              'suppliedRootPomSha256': CURRENT_POM, 'retainedRootPomSha256': PRIOR_POM,
              'adapterSha256': digest(Path(__file__)),
              'scope': 'Exact two scoped Lang declarations/properties reversed; no historical callbacks or compatibility claim.'}
    verify_projection(result)
    return result


def verify_projection(projection):
    require(projection['schemaVersion'] == 'ASSEMBLY_ENFORCER_LANG_RETAINED_SOURCE_VIEW_V1'
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
    owner_counts = {'maven-assembly-plugin': 0, 'maven-enforcer-plugin': 0}
    for prior, current in zip(before, after):
        reverted = copy.deepcopy(current)
        properties = one(reverted, 'properties')
        for name in ('maven.assembly.commons-lang3.version', 'maven.enforcer.commons-lang3.version'):
            require(value(properties, name) == '3.18.0', 'scoped Lang property drift')
            require(prior.find('m:properties/m:' + name, NS) is None, 'unexpected baseline property')
            properties.remove(one(properties, name))
        for owner, location in [('maven-assembly-plugin', 'm:build/m:pluginManagement/m:plugins/m:plugin'),
                                ('maven-enforcer-plugin', 'm:build/m:plugins/m:plugin')]:
            plugins = [p for p in reverted.findall(location, NS) if value(p, 'artifactId') == owner]
            old_plugins = [p for p in prior.findall(location, NS) if value(p, 'artifactId') == owner]
            require(len(plugins) == len(old_plugins) == 1, 'requires one exact owner declaration')
            plugin, old_plugin = plugins[0], old_plugins[0]
            require(value(plugin, 'groupId', False) in ('', 'org.apache.maven.plugins'), 'foreign owner group')
            require(value(plugin, 'version') == ('3.7.1' if owner == 'maven-assembly-plugin' else '3.5.0'), 'owner version changed')
            dependencies = one(plugin, 'dependencies')
            rows = [p for p in dependencies if value(p, 'groupId') == 'org.apache.commons' and value(p, 'artifactId') == 'commons-lang3']
            require(len(rows) == 1, 'requires one scoped Lang declaration')
            dependency = rows[0]
            fields(dependency, ['groupId', 'artifactId', 'version']
                   + (['scope'] if owner == 'maven-enforcer-plugin' else []))
            if owner == 'maven-enforcer-plugin':
                require(value(dependency, 'scope') == 'compile', 'active Enforcer dependency scope drift')
            require(value(dependency, 'version') == '3.18.0', 'scoped Lang version drift')
            require(not [p for p in old_plugin.findall('m:dependencies/m:dependency', NS)
                         if value(p, 'groupId') == 'org.apache.commons' and value(p, 'artifactId') == 'commons-lang3'],
                    'unexpected baseline Lang override')
            dependencies.remove(dependency)
            if old_plugin.find('m:dependencies', NS) is None:
                require(len(dependencies) == 0, 'unexpected added owner dependency')
                plugin.remove(dependencies)
            owner_counts[owner] += 1
        require(semantic(prior, str(baseline_root)) == semantic(reverted, str(source_root)),
                'effective model changed beyond two scoped Lang overrides: ' + value(current, 'artifactId'))
    require(set(owner_counts.values()) == {26}, 'unexpected inherited owner coverage')
    return {'modelCount': 26, 'ownerModelCounts': owner_counts,
            'allOtherModelFieldsUnchanged': True, 'nonReactorOverlaysSourceBoundOnly': 2}




if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps({'status': 'PASSED_ASSEMBLY_ENFORCER_LANG_SOURCE', 'sourceHashes': verify_source(args.root)}, sort_keys=True))
