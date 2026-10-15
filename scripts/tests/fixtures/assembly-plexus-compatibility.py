#!/usr/bin/env python3
"""Six paired actual Assembly goals, offline and opt-in. Import/plan is inert."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
from runpy import run_path
import signal
import stat
import sys
import tarfile
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
from checkstyle_live_support import Commands, digest, inside, isolated_env, require, NS, P
from run_boot_live_compatibility import runtime_files, python_origins, hashes, cache_snapshot
from assembly_plexus_inventory import inventory, inspect_class

PINS = HERE / 'assembly-plexus-artifact-pins.json'
CONSUMERS = HERE / 'assembly-plexus-consumers.json'
OBSERVER = HERE / 'AssemblyRealmObserver.java'
SPY = HERE / 'AssemblyRealmCompletionSpy.java'
CONTRACT = ROOT / 'scripts/ci/assembly_plexus_contract.py'
SOURCE_ADAPTER = ROOT / 'scripts/ci/jar_plugin_retained_source.py'
JAR_RETAINED = run_path(str(SOURCE_ADAPTER))
OWNER = 'org.apache.maven.plugins:maven-assembly-plugin:3.7.1'
GOAL = OWNER + ':single'
CASES = ('archives', 'discovery', 'missing-main', 'malformed-main', 'missing-component', 'malformed-component')
FIXED_TIME = '2024-01-02T03:04:06Z'
FIXED_EPOCH = 1704164646
TEXT = 'café & <fixture> 非ASCII'
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'ASSEMBLY_PLEXUS_COMPATIBILITY=true',
        'offline': True, 'sockets': False, 'goal': GOAL, 'variants': ['4.0.0', '4.0.3'],
        'cases': list(CASES), 'actualGoalCount': 12, 'commandTimeoutSeconds': 60,
        'aggregateTimeoutSeconds': 900, 'outerTimeoutSeconds': 930,
        'scannerEvidence': False, 'productionActivationClaim': False}
FILES = {'ordinary.txt': 'ordinary\n', '.hidden': 'hidden\n', '.gitignore': 'root ignore\n',
         'nested/.gitignore': 'nested ignore\n', '.git/config': 'fixture git\n',
         'nested/keep.txt': 'keep\n', 'nested/drop.tmp': 'drop\n', 'café/你好.txt': TEXT + '\n',
         '.hidden-dir/hidden.txt': 'hidden directory\n', 'dir-case/.gitignore/inside.txt': 'ignore directory\n'}


def artifact_path(repository, coordinate):
    parts = coordinate.split(':')
    require(len(parts) == 4 and parts[2] == 'jar' and all(re.fullmatch('[A-Za-z0-9_.-]+', p) for p in parts), 'safe plain JAR coordinate')
    group, artifact, _, version = parts
    return repository.joinpath(*group.split('.'), artifact, version, artifact + '-' + version + '.jar')


def read_pins():
    pins = json.loads(PINS.read_text()); before, after = (pins['variants'][v] for v in ('baseline', 'candidate'))
    require(pins['schemaVersion'] == 1 and pins['owner'] == OWNER and len(before) == len(set(before)) == 21, 'exact Assembly inventory')
    require(after == [c.replace('plexus-utils:jar:4.0.0', 'plexus-utils:jar:4.0.3') for c in before], 'single private replacement')
    require(set(before + after) == set(pins['artifactHashes']) and len(pins['artifactHashes']) == 22, 'exact artifact union')
    return pins


def source_hashes(source):
    contract = run_path(str(CONTRACT))
    values = (JAR_RETAINED['source_inventory'](source) if JAR_RETAINED['is_jar_source'](source)
              else contract['verify_source'](source))
    result = {str(source / key): value for key, value in values.items()}
    files = [Path(__file__).resolve(), PINS, CONSUMERS, OBSERVER, SPY, CONTRACT, SOURCE_ADAPTER, source / contract['WITNESS'],
             HERE / 'assembly-plexus-portable.py',
             HERE / 'assembly_plexus_inventory.py', HERE / 'checkstyle_live_support.py',
             HERE / 'run_boot_live_compatibility.py', HERE.parent / 'assembly-plexus-compatibility-harness.test.mjs',
             HERE.parent / 'assembly-plexus-plugin-live-compatibility.test.mjs']
    result.update({str(p): digest(p) for p in files})
    return result


def prepare_source_projection(config):
    source, output = Path(config['source']), Path(config['output'])
    if not JAR_RETAINED['is_jar_source'](source):
        run_path(str(CONTRACT))['verify_source'](source)
        return None
    projection = JAR_RETAINED['materialize_accepted_source'](
        source, output.with_name(output.name + '-accepted-assembly-source'))
    source_for_contract(config, projection)
    return projection


def source_for_contract(config, projection):
    source, output = Path(config['source']), Path(config['output'])
    require(JAR_RETAINED['is_jar_source'](source) == (projection is not None),
            'explicit JAR historical source projection required')
    if projection is not None:
        require(projection['suppliedSourceRoot'] == str(source)
                and projection['retainedSourceRoot'] == str(output.with_name(output.name + '-accepted-assembly-source')),
                'sealed JAR historical source roots changed')
        JAR_RETAINED['verify_projection'](projection)
        source = Path(projection['retainedSourceRoot'])
    run_path(str(CONTRACT))['verify_source'](source)
    return source


def runtime_hashes(maven, jdk):
    require(maven.name == 'mvn' and (maven.parent.parent / 'lib/maven-core-3.9.16.jar').is_file(), 'Maven 3.9.16 required')
    require('JAVA_VERSION="21.' in (jdk / 'release').read_text(), 'JDK 21 required')
    return hashes(runtime_files(jdk, maven, python_origins()[1]))


def effective_consumers(config, declared, uses):
    """Replace only classes actually imported from Maven's declared XML API.

    Keep the full pinned private inventory separately. A shadowed private class's
    references cannot be attributed to the different class Maven really loads.
    """
    lib = Path(config['maven']).parent.parent / 'lib'
    with zipfile.ZipFile(lib / 'maven-core-3.9.16.jar') as archive:
        extension = ET.fromstring(archive.read('META-INF/maven/extension.xml'))
    exports = {p.text.replace('.', '/') for p in extension.findall('exportedPackages/exportedPackage') if p.text.startswith('org.codehaus.plexus.util.xml.')}
    result = copy.deepcopy(declared); replacements = []; adjusted = [tuple(row) for row in uses]
    core = lib / 'plexus-utils-3.6.1.jar'
    with zipfile.ZipFile(core) as archive:
        for consumer in result['consumers']:
            name = consumer['classEntry'].removesuffix('.class')
            consumer['sourcePath'] = str(artifact_path(Path(config['repository']), consumer['coordinate']))
            with zipfile.ZipFile(consumer['sourcePath']) as private:
                consumer['classSha256'] = hashlib.sha256(private.read(consumer['classEntry'])).hexdigest()
            if name not in exports: continue
            own, refs, members, new_uses = inspect_class(archive.read(consumer['classEntry']))
            require(own == name, 'effective imported consumer identity')
            replacements.append({'class': name, 'declaredCoordinate': consumer['coordinate'],
                                 'declaredPath': consumer['sourcePath'], 'declaredClassSha256': consumer['classSha256'],
                                 'actualPath': str(core), 'actualClassSha256': hashlib.sha256(archive.read(consumer['classEntry'])).hexdigest(),
                                 'declaredTypeReferenceCount': len(consumer['classAndDescriptorReferences']), 'effectiveTypeReferenceCount': len(refs),
                                 'declaredMemberReferenceCount': len(consumer['memberReferences']), 'effectiveMemberReferenceCount': len(members)})
            consumer.update(sourcePath=str(core), classSha256=replacements[-1]['actualClassSha256'], classAndDescriptorReferences=refs, memberReferences=members)
            adjusted = [row for row in adjusted if row[0] != name]
            adjusted.extend((name, *row) for row in new_uses)
    result['mavenImportedConsumers'] = replacements
    return result, sorted(adjusted)


def prepare(config, historical_jar_projection=None):
    paths = {k: Path(config[k]).absolute() for k in ('source', 'maven', 'jdk', 'repository', 'output')}
    require(all(p.resolve() == p and not any(c.isspace() or ord(c) < 32 for c in str(p)) for p in paths.values()), 'canonical JVM-safe paths')
    require(not paths['output'].exists() and paths['output'].parent.is_dir(), 'fresh output under existing directory')
    require(all(not paths['output'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['output']) for k in ('source', 'jdk', 'repository')), 'output/input overlap')
    require(not any((p / '.mvn').exists() for key in ('source', 'output') for p in [paths[key], *paths[key].parents]), 'unexpected Maven bootstrap configuration')
    pins = read_pins()
    for coordinate, expected in pins['artifactHashes'].items():
        file = artifact_path(paths['repository'], coordinate)
        require(file.is_file() and not file.is_symlink() and digest(file) == expected, 'exact pinned artifact: ' + coordinate)
    actual, uses = inventory(pins['variants']['baseline'], lambda c: artifact_path(paths['repository'], c))
    expected = json.loads(CONSUMERS.read_text())
    require(all(actual[k] == expected[k] for k in actual), 'complete current consumer inventory differs')
    effective, effective_uses = effective_consumers(config, actual, uses)
    supplied_hashes = source_hashes(paths['source'])
    projection = (historical_jar_projection if historical_jar_projection is not None
                  else prepare_source_projection(config))
    selected_source = source_for_contract(config, projection)
    return {'schemaVersion': 1, 'plan': PLAN, 'config': {k: str(v) for k, v in paths.items()},
            'sourceHashes': supplied_hashes, 'runtimeHashes': runtime_hashes(paths['maven'], paths['jdk']),
            'historicalJarSourceProjection': projection, 'actualFixtureSourceRoot': str(selected_source),
            'cacheHashes': cache_snapshot(paths['repository']), 'inventory': actual,
            'memberUses': [list(row) for row in uses], 'effectiveInventory': effective, 'effectiveMemberUses': [list(row) for row in effective_uses],
            'pythonOrigins': python_origins()[0]}


def check_seal(seal, complete=False):
    require(seal['schemaVersion'] == 1 and seal['plan'] == PLAN, 'reviewed bounded seal required')
    config = seal['config']
    require(set(config) == {'source', 'maven', 'jdk', 'repository', 'output'}, 'exact sealed configuration keys')
    paths = {k: Path(v) for k, v in config.items()}
    require(all(p.is_absolute() and p.resolve() == p and not any(c.isspace() or ord(c) < 32 for c in str(p)) for p in paths.values()), 'canonical sealed paths')
    require(all(not paths['output'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['output']) for k in ('source', 'jdk', 'repository')), 'sealed output/input overlap')
    expected = json.loads(CONSUMERS.read_text())
    require(seal['inventory'] == {k: expected[k] for k in ('consumerCount', 'directAssemblyConsumerCount', 'consumerCountsByJar', 'consumers')}, 'sealed inventory differs')
    effective, effective_uses = effective_consumers(config, seal['inventory'], seal['memberUses'])
    require(effective == seal['effectiveInventory'] and [list(row) for row in effective_uses] == seal['effectiveMemberUses'], 'effective consumer projection changed')
    require(source_hashes(Path(seal['config']['source'])) == seal['sourceHashes'], 'source changed')
    require(str(source_for_contract(config, seal['historicalJarSourceProjection'])) == seal['actualFixtureSourceRoot'],
            'fixture historical source identity changed')
    if complete:
        require(cache_snapshot(Path(seal['config']['repository'])) == seal['cacheHashes'], 'cache changed')
        require(runtime_hashes(Path(seal['config']['maven']), Path(seal['config']['jdk'])) == seal['runtimeHashes'], 'runtime changed')


def child(parent, name, text=None):
    node = ET.SubElement(parent, name)
    if text is not None: node.text = str(text)
    return node


def xml(root): return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def fixture_pom(plugin, directory, case):
    require(case in CASES, 'fixed case required')
    ET.register_namespace('', NS['m'])
    root = ET.Element(P + 'project')
    for k, v in [('modelVersion', '4.0.0'), ('groupId', 'fixture'), ('artifactId', 'assembly-fixture'), ('version', '1'), ('packaging', 'pom')]: child(root, P + k, v)
    props = child(root, P + 'properties'); child(props, P + 'fixture.value', TEXT); child(props, P + 'project.build.sourceEncoding', 'UTF-8')
    dependencies = child(root, P + 'dependencies'); dep = child(dependencies, P + 'dependency')
    for k, v in [('groupId', 'fixture'), ('artifactId', 'owned-input'), ('version', '1'), ('scope', 'system'), ('systemPath', str(directory / 'dependency.jar'))]: child(dep, P + k, v)
    build = child(root, P + 'build'); child(build, P + 'directory', str(directory / 'target')); child(build, P + 'finalName', 'fixture')
    plugins = child(build, P + 'plugins'); selected = copy.deepcopy(plugin); plugins.append(selected)
    conf = child(selected, P + 'configuration')
    for k, v in [('attach', 'false'), ('outputTimestamp', FIXED_TIME), ('encoding', 'UTF-8'), ('tarLongFileMode', 'posix')]: child(conf, P + k, v)
    if case == 'discovery': child(conf, P + 'descriptorSourceDirectory', str(directory / 'discovery'))
    else: child(child(conf, P + 'descriptors'), P + 'descriptor', str(directory / 'main.xml'))
    archive = child(conf, P + 'archive'); entries = child(archive, P + 'manifestEntries'); child(entries, P + 'Fixture-Identity', 'assembly-plexus')
    return xml(root)


def assembly(identifier, formats=('dir', 'zip', 'jar', 'tar')):
    root = ET.Element('assembly'); child(root, 'id', identifier)
    listing = child(root, 'formats')
    for kind in formats: child(listing, 'format', kind)
    child(root, 'includeBaseDirectory', 'false')
    return root


def file_set(parent, directory, prefix, defaults=None, filtered=False):
    fs = child(parent, 'fileSet')
    for k, v in [('directory', directory), ('outputDirectory', prefix), ('fileMode', '0640'), ('directoryMode', '0750')]: child(fs, k, v)
    child(child(fs, 'includes'), 'include', '**/*'); child(child(fs, 'excludes'), 'exclude', '**/*.tmp')
    if defaults is not None: child(fs, 'useDefaultExcludes', str(defaults).lower())
    if filtered: child(fs, 'filtered', 'true')
    return fs


def component(role):
    root = ET.Element('component-set'); components = child(root, 'components'); item = child(components, 'component')
    for k, v in [('role', 'fixture.' + role), ('implementation', 'fixture.' + role + 'Impl')]: child(item, k, v)
    return xml(root)


def write_case(directory, plugin, case):
    directory.mkdir()
    def put(name, data):
        path = directory / name; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode('utf-8'))
    for name, content in FILES.items(): put('input/' + name, content)
    put('filter/value.txt', '${fixture.value}\n')
    put('component-input/META-INF/plexus/components.xml', component('FromFiles'))
    put('component-input/component-note.txt', 'component descriptor included\n')
    with zipfile.ZipFile(directory / 'dependency.jar', 'x') as archive:
        for name, content in {**FILES, 'META-INF/plexus/components.xml': component('FromDependency')}.items():
            entry = zipfile.ZipInfo(name, (2024, 1, 2, 3, 4, 6)); entry.external_attr = 0o100640 << 16
            archive.writestr(entry, content.encode('utf-8') if isinstance(content, str) else content)
    root = assembly('real')
    sets = child(root, 'fileSets')
    for name, defaults in [('omitted', None), ('true', True), ('false', False)]: file_set(sets, 'input', 'fs-' + name, defaults)
    file_set(sets, 'filter', 'filtered', filtered=True)
    deps = child(root, 'dependencySets')
    for name, defaults in [('omitted', None), ('true', True), ('false', False)]:
        dep = child(deps, 'dependencySet')
        for k, v in [('outputDirectory', 'dep-' + name), ('unpack', 'true'), ('scope', 'test'), ('useProjectArtifact', 'false'), ('useTransitiveDependencies', 'false'), ('fileMode', '0640'), ('directoryMode', '0750')]: child(dep, k, v)
        child(child(dep, 'includes'), 'include', 'fixture:owned-input')
        options = child(dep, 'unpackOptions'); child(child(options, 'excludes'), 'exclude', '**/*.tmp')
        # Merge the second components document at its standard destination once.
        child(options.find('excludes'), 'exclude', 'META-INF/**')
        if defaults is not None: child(options, 'useDefaultExcludes', str(defaults).lower())
    dep = child(deps, 'dependencySet')
    for k, v in [('outputDirectory', '/'), ('unpack', 'true'), ('scope', 'test'), ('useProjectArtifact', 'false'), ('useTransitiveDependencies', 'false')]: child(dep, k, v)
    child(child(dep, 'includes'), 'include', 'fixture:owned-input')
    child(child(child(dep, 'unpackOptions'), 'includes'), 'include', 'META-INF/plexus/components.xml')
    child(child(root, 'componentDescriptors'), 'componentDescriptor', 'component.xml')
    child(child(child(root, 'containerDescriptorHandlers'), 'containerDescriptorHandler'), 'handlerName', 'plexus')
    part = ET.Element('component'); file_set(child(part, 'fileSets'), 'component-input', '/')
    put('component.xml', xml(part))
    if case == 'missing-component': (directory / 'component.xml').unlink()
    elif case == 'malformed-component': put('component.xml', '<component><fileSets></component>')
    if case != 'missing-main': put('main.xml', '<assembly><formats></assembly>' if case == 'malformed-main' else xml(root))
    if case == 'discovery':
        for name, identifier in [('ordinary.xml', 'ordinary'), ('nested/nested.xml', 'nested'), ('.hidden/hidden.xml', 'hidden'), ('.git/git.xml', 'git'), ('.gitignore/ignore-dir.xml', 'ignore-dir')]:
            item = assembly(identifier, ('zip',)); file_set(child(item, 'fileSets'), 'filter', 'filtered', filtered=True)
            put('discovery/' + name, xml(item))
        put('discovery/nested/.gitignore', 'regular ignore file is not XML\n')
    put('pom.xml', fixture_pom(plugin, directory, case))
    return {str(p): digest(p) for p in directory.rglob('*') if p.is_file()}


def maven_command(config, sandbox, project, goal=GOAL):
    require(goal == GOAL, 'only fully qualified Assembly single goal')
    inside(project, sandbox)
    return [config['maven'], '-o', '-B', '-ntp', '-N', '-X', '--strict-checksums',
            '-s', str(sandbox / 'empty-settings.xml'), '-gs', str(sandbox / 'empty-settings.xml'),
            '-t', str(sandbox / 'empty-toolchains.xml'), '-gt', str(sandbox / 'empty-toolchains.xml'),
            '-Dmaven.repo.local=' + config['repository'], '-Dstyle.color=never',
            '-Dmaven.ext.class.path=' + str(sandbox / 'completion-spy.jar'),
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-f', str(project), GOAL]


def linkage_rows(seal):
    rows = []
    selected_uses = seal.get('effectiveMemberUses', seal['memberUses'])
    uses = {tuple(row[:5]): row[5] for row in selected_uses}
    require(len(uses) == len(selected_uses), 'no conflicting invocation kind')
    for consumer in seal.get('effectiveInventory', seal['inventory'])['consumers']:
        name = consumer['classEntry'].removesuffix('.class')
        if 'sourcePath' in consumer: rows.append('\t'.join(('consumer', name, Path(consumer['sourcePath']).as_uri(), consumer['classSha256'])))
        for target in consumer['classAndDescriptorReferences']: rows.append('\t'.join(('type', name, target)))
        for member in consumer['memberReferences']:
            key = (name, member['kind'], member['owner'], member['name'], member['descriptor'])
            require(key in uses, 'all symbolic members have bytecode/handle invocation witnesses')
            rows.append('\t'.join(('member', name, member['owner'], member['kind'], member['name'], member['descriptor'], uses[key])))
    require(len(rows) == len(set(rows)), 'distinct consumer-scoped rows')
    return rows


def effective_class(path, name):
    with zipfile.ZipFile(path) as archive:
        entry = name + '.class'
        if b'multi-release: true' in archive.read('META-INF/MANIFEST.MF').lower():
            for version in range(9, 22):
                candidate = 'META-INF/versions/' + str(version) + '/' + name + '.class'
                if candidate in archive.namelist(): entry = candidate
        return entry, hashlib.sha256(archive.read(entry)).hexdigest()


def observations(stdout, stderr, config, variant, rows, case):
    require('ASSEMBLY_OBSERVER_FAILURE' not in stderr and 'ASSEMBLY_LINKAGE_FAILURE' not in stderr, 'observer/linkage failure')
    terminal = 'MojoSucceeded' if case in ('archives', 'discovery') else 'MojoFailed'
    require([line for line in stderr.splitlines() if line.startswith('ASSEMBLY_GOAL_TERMINAL\t')] == ['ASSEMBLY_GOAL_TERMINAL\t' + terminal], 'one actual selected goal terminal event')
    pins = read_pins(); repo = Path(config['repository']); lib = Path(config['maven']).parent.parent / 'lib'
    with zipfile.ZipFile(lib / 'maven-core-3.9.16.jar') as archive: exports = ET.fromstring(archive.read('META-INF/maven/extension.xml'))
    shared = {x.text for x in exports.findall('exportedArtifacts/exportedArtifact')}
    expected = [c for c in pins['variants'][variant] if ':'.join(c.split(':')[:2]) not in shared]
    marker = '[DEBUG] Populating class realm plugin>' + OWNER
    lines = stdout.splitlines(); starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one actual Assembly realm')
    included = []
    for line in lines[starts[0] + 1:]:
        found = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not found: break
        included.append(found[1])
    require(len(included) == len(set(included)) and set(included) == set(expected), 'exact natural Assembly realm membership')
    urls = [line.split('\t') for line in stderr.splitlines() if line.startswith('ASSEMBLY_REALM_URL\t')]
    require(len(urls) == len(expected) and len({row[1] for row in urls}) == 1, 'one complete loader URL set')
    realm_id = urls[0][1]
    utility = artifact_path(repo, 'org.codehaus.plexus:plexus-utils:jar:' + ('4.0.0' if variant == 'baseline' else '4.0.3'))
    private_xml = artifact_path(repo, 'org.codehaus.plexus:plexus-xml:jar:3.0.0')
    core_utility = lib / 'plexus-utils-3.6.1.jar'
    shared_xml = {'org/codehaus/plexus/util/xml/' + name for name in ('Xpp3Dom', 'pull/XmlPullParser', 'pull/XmlPullParserException', 'pull/XmlSerializer')}
    exported_packages = {x.text for x in exports.findall('exportedPackages/exportedPackage')}
    require(all(name.replace('/', '.') in exported_packages for name in shared_xml), 'explicit shared XML API exports')
    uri_path = lambda value: Path(unquote(urlparse(value).path))
    require({uri_path(row[2]) for row in urls} == {artifact_path(repo, c) for c in expected}, 'exact realm URL paths')
    natural = []
    for line in stderr.splitlines():
        if not line.startswith('ASSEMBLY_NATURAL\t'): continue
        _, name, loader, realm, uri, class_hash = line.split('\t')
        path = uri_path(uri)
        require(path in {artifact_path(repo, c) for c in expected} or path.parent == lib, 'unexplained natural class source')
        entry, expected_hash = effective_class(path, name)
        require(class_hash == expected_hash, 'effective JDK21 class bytes differ')
        require(name.replace('/', '.') + ' source: ' + uri in stderr, 'JVM class-load corroboration missing')
        if path.parent != lib: require(loader == realm_id and ('plugin>' + OWNER) in realm, 'private class loader differs')
        if loader == realm_id and name.startswith('org/codehaus/plexus/util/'):
            require(path == (private_xml if '/util/xml/' in name else utility), 'private utility/XML class origin differs')
        if name in shared_xml: require(path == core_utility and loader != realm_id, 'shared XML API import origin differs')
        if name in ('org/slf4j/Logger', 'org/slf4j/LoggerFactory'):
            require(path == lib / 'slf4j-api-1.7.36.jar' and loader != realm_id, 'shared SLF4J import differs')
        natural.append({'class': name, 'loader': loader, 'realm': realm, 'path': str(path), 'entry': entry, 'sha256': class_hash})
    require(any(r['class'].endswith('/SingleAssemblyMojo') and r['loader'] == realm_id for r in natural), 'actual single mojo missing')
    plugin_classes = {r['class'] for r in natural if r['loader'] == realm_id}
    required = {'org/apache/maven/plugins/assembly/io/DefaultAssemblyReader'}
    if case in ('archives', 'discovery'): required.add('org/codehaus/plexus/util/StringUtils')
    if case != 'missing-main':
        required.update(('org/apache/maven/plugins/assembly/model/io/xpp3/AssemblyXpp3Reader',
                         'org/codehaus/plexus/util/xml/XmlReader', 'org/codehaus/plexus/util/xml/XmlStreamReader',
                         'org/codehaus/plexus/util/xml/pull/MXParser'))
    if case in ('archives', 'malformed-component'):
        required.add('org/apache/maven/plugins/assembly/model/io/xpp3/ComponentXpp3Reader')
    if case == 'archives':
        required.update(('org/apache/maven/plugins/assembly/filter/ComponentsXmlArchiverFileFilter',
                         'org/codehaus/plexus/util/xml/Xpp3DomBuilder', 'org/codehaus/plexus/util/FileUtils',
                         'org/codehaus/plexus/util/IOUtil'))
    if case == 'discovery': required.add('org/codehaus/plexus/util/DirectoryScanner')
    require(required <= plugin_classes, 'required natural private classes missing: ' + str(sorted(required - plugin_classes)))
    require({'org/slf4j/Logger', 'org/slf4j/LoggerFactory', 'org/codehaus/plexus/util/xml/Xpp3Dom',
             'org/codehaus/plexus/util/xml/pull/XmlPullParser'} <= {r['class'] for r in natural}, 'required natural shared imports absent')
    member_rows = [line.split('\t')[1:-1] for line in stderr.splitlines() if line.startswith('ASSEMBLY_MEMBER\t')]
    require(['\t'.join(row) for row in member_rows] == [row for row in rows if row.startswith('member\t')], 'complete member resolution missing')
    required_receipt = 'ASSEMBLY_LINKAGE_COMPLETE\t55\t' + str(sum(r.startswith('type\t') for r in rows)) + '\t' + str(len(member_rows)) + '\t' + hashlib.sha256(('\n'.join(rows) + '\n').encode()).hexdigest()
    require(stderr.splitlines().count(required_receipt) == 1 and stderr.splitlines().count('ASSEMBLY_MISSING_MEMBER\tPASSED') == 1, 'complete linkage/negative receipt missing')
    types = []
    for line in stderr.splitlines():
        if not line.startswith('ASSEMBLY_TYPE\t'): continue
        _, name, loader, realm, uri = line.split('\t'); path = uri_path(uri)
        require(path in {artifact_path(repo, c) for c in expected} or path.parent == lib, 'unexplained linkage origin')
        types.append({'class': name, 'loader': loader, 'realm': realm, 'path': str(path)})
    actual_consumers = [line.split('\t', 1)[1] for line in stderr.splitlines() if line.startswith('ASSEMBLY_CONSUMER\t')]
    require(actual_consumers == [row for row in rows if row.startswith('consumer\t')], 'all effective consumer origins required')
    return {'realm': included, 'naturalOrigins': natural, 'requiredNaturalPrivateClasses': sorted(required), 'linkageOrigins': types,
            'effectiveConsumerOrigins': actual_consumers,
            'linkageMembers': len(member_rows), 'linkageReceipt': required_receipt, 'missingMemberControl': 'PASSED'}


def archive_members(path):
    files, directories, metadata = {}, {}, {}
    def add(name, data, mode, mtime, directory=False):
        require(name and not name.startswith('/') and '..' not in Path(name).parts, 'safe archive member')
        if directory: directories[name.rstrip('/')] = mode & 0o777
        else:
            require(name not in files, 'unique archive file')
            files[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'mode': mode & 0o777}
        metadata[name] = {'timestamp': mtime, 'mode': mode & 0o777}
    if path.is_dir():
        for item in sorted(path.rglob('*')):
            require(not item.is_symlink(), 'no output links')
            add(item.relative_to(path).as_posix(), b'' if item.is_dir() else item.read_bytes(), item.stat().st_mode, None, item.is_dir())
    elif path.suffix in ('.zip', '.jar'):
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                require(item.date_time == (2024, 1, 2, 3, 4, 6), 'fixed ZIP/JAR timestamps')
                add(item.filename, archive.read(item), item.external_attr >> 16, list(item.date_time), item.is_dir())
    elif path.suffix == '.tar':
        with tarfile.open(path, 'r:') as archive:
            for item in archive:
                require(item.isfile() or item.isdir(), 'only regular TAR members')
                require(item.mtime == FIXED_EPOCH and item.uid == item.gid == 0, 'fixed TAR timestamp/ownership')
                add(item.name, archive.extractfile(item).read() if item.isfile() else b'', item.mode, item.mtime, item.isdir())
    else: raise ValueError('unsupported archive kind')
    return {'files': files, 'directories': directories, 'metadata': metadata}


def payload(path, name):
    if path.is_dir(): return (path / name).read_bytes()
    if path.suffix == '.tar':
        with tarfile.open(path) as archive: return archive.extractfile(name).read()
    with zipfile.ZipFile(path) as archive: return archive.read(name)


def archive_result(directory, variant):
    require(variant in ('baseline', 'candidate'), 'exact archive variant')
    target = directory / 'target'
    expected = ['fixture-real', 'fixture-real.jar', 'fixture-real.tar', 'fixture-real.zip']
    require(sorted(p.name for p in target.iterdir() if p.name != 'archive-tmp') == expected, 'exact four output formats')
    outputs = {}
    for filename in expected:
        path = target / filename; info = archive_members(path)
        require(payload(path, 'filtered/value.txt').decode() == TEXT + '\n', 'filtered UTF-8 payload')
        merged = ET.fromstring(payload(path, 'META-INF/plexus/components.xml'))
        roles = sorted(c.findtext('role') for c in merged.findall('components/component'))
        require(roles == ['fixture.FromDependency', 'fixture.FromFiles'], 'real plexus handler merged components')
        # Exact content and member-selection receipts for each default setting.
        selections = {}
        for prefix in ('fs', 'dep'):
            for setting in ('omitted', 'true', 'false'):
                base = prefix + '-' + setting + '/'
                selected = sorted(name.removeprefix(base) for name in info['files'] if name.startswith(base))
                require(selected and 'nested/drop.tmp' not in selected, 'actual include/exclude selection')
                for name in selected:
                    require(name in FILES and payload(path, base + name).decode() == FILES[name], 'exact selected payload')
                    require(info['files'][base + name]['mode'] == 0o640, 'explicit file modes')
                selections[prefix + '-' + setting] = selected
            require(selections[prefix + '-omitted'] == selections[prefix + '-true'], 'omitted and true defaults differ')
            require(selections[prefix + '-false'] == sorted(set(FILES) - {'nested/drop.tmp'}), 'disabled defaults retain exact inputs')
        normal = set(FILES) - {'nested/drop.tmp', '.git/config'}
        if variant == 'candidate': normal -= {'.gitignore', 'nested/.gitignore'}
        for key, selected in selections.items():
            if key.endswith('-false'): continue
            # Actual file-set and unpack paths both inherit the upstream
            # 4.0.3 regular .gitignore default-exclusion change.
            require(set(selected) == normal, 'unexpected archive default exclusions: ' + key + ' ' + str(selected))
        expected_files = {prefix + '/' + name for prefix, names in selections.items() for name in names}
        expected_files.update(('filtered/value.txt', 'component-note.txt', 'META-INF/plexus/components.xml'))
        if path.suffix == '.jar':
            expected_files.add('META-INF/MANIFEST.MF')
            manifest = payload(path, 'META-INF/MANIFEST.MF').decode()
            require('Manifest-Version: 1.0\r\n' in manifest and 'Fixture-Identity: assembly-plexus\r\n' in manifest, 'actual generated JAR manifest')
            info['manifest'] = manifest
        require(set(info['files']) == expected_files, 'exact member list')
        info['selections'] = selections
        info['archiveSha256'] = None if path.is_dir() else digest(path)
        outputs[filename] = info
    return outputs


def compare_archives(before, after):
    """Permit only the measured upstream default-exclusion change."""
    require(set(before) == set(after) == {'fixture-real', 'fixture-real.zip', 'fixture-real.jar', 'fixture-real.tar'}, 'exact paired formats')
    removed = {prefix + '/' + name for prefix in ('fs-omitted', 'fs-true', 'dep-omitted', 'dep-true')
               for name in ('.gitignore', 'nested/.gitignore')}
    changed_dirs = {'fs-' + setting + '/dir-case/.gitignore' for setting in ('omitted', 'true')}
    deltas = {}
    for format_name, baseline in before.items():
        candidate = after[format_name]; tar = format_name.endswith('.tar')
        require(set(candidate['files']) == set(baseline['files']) - removed, 'only eight regular ignore files removed')
        require(all(candidate['files'][n] == baseline['files'][n] for n in candidate['files']), 'retained payload or file mode changed')
        require(all(baseline['directories'].get(n) == 0o750 for n in changed_dirs), 'baseline explicit ignore-directory modes')
        expected_dirs = dict(baseline['directories'])
        for name in changed_dirs:
            if tar: del expected_dirs[name]
            else: expected_dirs[name] = 0o755
        require(candidate['directories'] == expected_dirs, 'unexpected directory membership or mode change')
        expected_metadata = copy.deepcopy(baseline['metadata'])
        for name in removed: del expected_metadata[name]
        for name in list(expected_metadata):
            if name.rstrip('/') not in changed_dirs: continue
            if tar: del expected_metadata[name]
            else: expected_metadata[name]['mode'] = 0o755
        require(candidate['metadata'] == expected_metadata, 'retained timestamps or extra metadata changed')
        require(candidate.get('manifest') == baseline.get('manifest'), 'JAR manifest changed')
        expected_selections = {}
        for key, selection in baseline['selections'].items():
            expected = selection if key.endswith('-false') else sorted(set(selection) - {'.gitignore', 'nested/.gitignore'})
            expected_selections[key] = expected
            require(candidate['selections'][key] == expected, 'unexpected default-setting selection delta')
        require(candidate['archiveSha256'] != baseline['archiveSha256'] if format_name != 'fixture-real'
                else candidate['archiveSha256'] is baseline['archiveSha256'] is None, 'archive-byte change witness')
        normalized = {**baseline, 'files': {n: v for n, v in baseline['files'].items() if n not in removed},
                      'directories': expected_dirs, 'metadata': expected_metadata, 'selections': expected_selections,
                      'archiveSha256': candidate['archiveSha256']}
        require(candidate == normalized, 'any unenumerated archive observation change')
        deltas[format_name] = {'removedRegularFiles': sorted(removed), 'retainedFilesEqual': True,
            'directoryChanges': {n: {'baselineMode': '0750', 'candidateMode': None if tar else '0755',
                                    'candidateEntryPresent': not tar, 'childFileRetained': True} for n in sorted(changed_dirs)},
            'explicitFalseUnchanged': True, 'allOtherMetadataEqual': True}
    return deltas


def discovery_result(directory):
    target = directory / 'target'
    files = sorted(p.name for p in target.iterdir() if p.name != 'archive-tmp')
    expected = ['fixture-hidden.zip', 'fixture-ignore-dir.zip', 'fixture-nested.zip', 'fixture-ordinary.zip']
    require(files == expected, 'actual descriptor discovery output set: ' + str(files))
    for name in files:
        info = archive_members(target / name)
        require(set(info['files']) == {'filtered/value.txt'} and payload(target / name, 'filtered/value.txt').decode() == TEXT + '\n', 'discovered archive payload')
    return {'outputFiles': files, 'discoveredIds': [n.removeprefix('fixture-').removesuffix('.zip') for n in files]}


def failure_result(directory, stdout, status, case):
    require(status == 1 and '[ERROR]' in stdout and 'BUILD FAILURE' in stdout, 'expected Maven failure')
    require('Error reading assemblies' in stdout, 'Assembly descriptor failure attribution')
    marker = 'component.xml' if 'component' in case else 'main.xml'
    require(marker in stdout, 'failed descriptor path required')
    if case.startswith('malformed'): require('XmlPullParserException' in stdout, 'malformed XML parser category')
    elif case == 'missing-component': require('Failed to locate component descriptor: component.xml' in stdout, 'missing component diagnostic')
    else: require('not found' in stdout.lower() or 'does not exist' in stdout.lower(), 'missing descriptor category')
    require(not (directory / 'target').exists() or not any(p.is_file() for p in (directory / 'target').rglob('*')), 'no accepted failure artifact')
    return {'exitStatus': status, 'category': 'malformed-xml' if case.startswith('malformed') else 'missing-descriptor', 'descriptor': marker, 'outputArtifacts': []}


def execute(seal):
    require(os.environ.get('ASSEMBLY_PLEXUS_COMPATIBILITY') == 'true', 'explicit reviewed execution opt-in')
    check_seal(seal, complete=True)
    config = seal['config']; output = Path(config['output']); jdk = Path(config['jdk'])
    require(not output.exists(), 'fresh reviewed output'); output.mkdir()
    for name in ('home', 'tmp', 'observer'): (output / name).mkdir()
    (output / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    (output / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    rows = linkage_rows(seal); members = output / 'members.tsv'; members.write_text('\n'.join(rows) + '\n')
    fixed = {str(p): digest(p) for p in (members, output / 'empty-settings.xml', output / 'empty-toolchains.xml')}
    environment = isolated_env(jdk, output); environment['MAVEN_BASEDIR'] = str(output)
    commands = Commands(output, environment, 900)
    result = {'status': 'RUNNING', 'plan': PLAN, 'commands': commands.results, 'variants': {},
              'inventory': seal['inventory'], 'effectiveInventory': seal['effectiveInventory'], 'sourceHashes': seal['sourceHashes'], 'runtimeHashes': seal['runtimeHashes'],
              'historicalJarSourceProjection': seal['historicalJarSourceProjection'], 'actualFixtureSourceRoot': seal['actualFixtureSourceRoot'],
              'cacheHashes': seal['cacheHashes'], 'scannerEvidence': False, 'productionActivationClaim': False}
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame): raise TimeoutError('bounded pair-set deadline or termination')
    def run(label, command, seconds=60, extra_env=None):
        check_seal(seal)
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        value = commands.run(label, command, seconds, extra_env)
        check_seal(seal)
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        return value
    for sig in previous: signal.signal(sig, deadline)
    signal.alarm(900)
    try:
        status, _, _ = run('compile-observer', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
            '-cp', os.pathsep.join(str(p) for p in sorted((Path(config['maven']).parent.parent / 'lib').glob('*.jar'))),
            '-d', str(output / 'observer'), str(OBSERVER), str(SPY)], 30)
        require(status == 0, 'observer compilation')
        with zipfile.ZipFile(output / 'observer.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: AssemblyRealmObserver\n\n')
            for file in sorted((output / 'observer').glob('AssemblyRealmObserver*.class')): archive.write(file, file.name)
        with zipfile.ZipFile(output / 'completion-spy.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
            archive.writestr('META-INF/plexus/components.xml', '<component-set><components><component>'
                '<role>org.apache.maven.eventspy.EventSpy</role><role-hint>assembly-compatibility</role-hint>'
                '<implementation>AssemblyRealmCompletionSpy</implementation></component></components></component-set>')
            archive.write(output / 'observer/AssemblyRealmCompletionSpy.class', 'AssemblyRealmCompletionSpy.class')
        fixed[str(output / 'observer.jar')] = digest(output / 'observer.jar')
        fixed[str(output / 'completion-spy.jar')] = digest(output / 'completion-spy.jar')
        opts = '-Xmx256m -XX:-UsePerfData -Duser.home=' + str(output / 'home') + ' -Djava.io.tmpdir=' + str(output / 'tmp')
        opts += ' -javaagent:' + str(output / 'observer.jar') + '=' + str(members) + ' -Xlog:class+load=info:stderr'
        contract = run_path(str(CONTRACT))
        for variant in ('baseline', 'candidate'):
            result['variants'][variant] = {}
            plugin = contract['fixture_plugin'](source_for_contract(config, seal['historicalJarSourceProjection']), baseline=variant == 'baseline')
            for case in CASES:
                directory = output / (variant + '-' + case); fixed.update(write_case(directory, plugin, case))
                status, stdout, stderr = run(variant + '-' + case, maven_command(config, output, directory / 'pom.xml'), extra_env={'MAVEN_OPTS': opts})
                observation = observations(stdout, stderr, config, variant, rows, case)
                if case in ('archives', 'discovery'):
                    require(status == 0, 'actual goal failed: ' + variant + '-' + case)
                    behavior = archive_result(directory, variant) if case == 'archives' else discovery_result(directory)
                else: behavior = failure_result(directory, stdout, status, case)
                result['variants'][variant][case] = {'exitStatus': status, 'behavior': behavior, **observation}
        result['expectedArchiveDelta'] = compare_archives(result['variants']['baseline']['archives']['behavior'], result['variants']['candidate']['archives']['behavior'])
        for case in CASES[1:]:
            require(result['variants']['baseline'][case]['behavior'] == result['variants']['candidate'][case]['behavior'], 'paired behavior changed: ' + case)
        for variant in ('baseline', 'candidate'):
            natural = [r for c in result['variants'][variant].values() for r in c['naturalOrigins']]
            selected = {r['class'] for r in natural}
            consumers = [c['classEntry'].removesuffix('.class') for c in seal['inventory']['consumers']]
            result['variants'][variant]['coverage'] = {'naturalConsumers': sorted(set(consumers) & selected),
                'notNaturallyLoadedConsumers': sorted(set(consumers) - selected), 'symbolicallyResolvedConsumers': len(consumers),
                'dynamicMemberInvocationClaim': False, 'effectiveMultiReleaseClasses': sorted({r['entry'] for r in natural if r['entry'].startswith('META-INF/versions/')})}
            require({'META-INF/versions/10/org/codehaus/plexus/util/BaseIOUtil.class', 'META-INF/versions/11/org/codehaus/plexus/util/BaseFileUtils.class'} <= set(result['variants'][variant]['coverage']['effectiveMultiReleaseClasses']), 'natural JDK21 utility overlay witness missing')
        check_seal(seal, complete=True); result.update(status='PASSED', inputSealsUnchanged=True)
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        signal.alarm(0)
        for sig, handler in previous.items(): signal.signal(sig, handler)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('ASSEMBLY_PLEXUS_RESULT ' + json.dumps({'status': result['status'], 'path': str(output / 'result.json'), 'error': result.get('error')}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-seal', type=Path); parser.add_argument('--execute-seal', type=Path)
    for name in ('source', 'maven', 'jdk', 'repository', 'output'): parser.add_argument('--' + name)
    args = parser.parse_args()
    if args.prepare_seal:
        require(not args.execute_seal and not args.prepare_seal.exists(), 'fresh preparation seal')
        seal = prepare(vars(args)); args.prepare_seal.write_text(json.dumps(seal, indent=2) + '\n')
        print(json.dumps({'status': 'PREPARED_NOT_EXECUTED', 'seal': str(args.prepare_seal), 'sourceHashes': seal['sourceHashes']}))
    elif args.execute_seal:
        before = digest(args.execute_seal)
        try: execute(json.loads(args.execute_seal.read_text()))
        finally: require(digest(args.execute_seal) == before, 'reviewed seal file changed during execution')
    else: print(json.dumps(PLAN))


if __name__ == '__main__': main()
