"""Composed deterministic inputs and independent byte oracles; starts no tools."""
import copy
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
GROUP = 'local.resources.flatten'
CI = {'revision': '1.2.3', 'sha1': '.g123', 'changelist': '-SNAPSHOT'}
VERSION = ''.join(CI.values())
RESOURCE_OWNER = 'org.apache.maven.plugins:maven-resources-plugin:3.4.0'
FLATTEN_OWNER = 'org.codehaus.mojo:flatten-maven-plugin:1.7.3'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def node(parent, name, text=None):
    value = ET.SubElement(parent, P + name)
    value.text = text
    return value


def xml(root):
    ET.indent(root, '  ')
    return (ET.tostring(root, encoding='unicode', default_namespace=NS['m']) + '\n').encode()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode())


def plugin(source, owner, variant, context='sdk'):
    require(variant in ('baseline', 'candidate'), 'fixed variant')
    if owner == 'resources':
        if variant == 'baseline':
            result = ET.Element(P + 'plugin')
            for key, value in [('groupId', 'org.apache.maven.plugins'), ('artifactId', 'maven-resources-plugin'), ('version', '3.4.0')]:
                node(result, key, value)
            return result
        root = ET.parse(source / 'pom.xml').getroot()
        location = 'm:build/m:pluginManagement/m:plugins/m:plugin'
    elif context == 'sdk':
        root = ET.parse(source / 'integrations/host-sdk/pom.xml').getroot()
        location = 'm:build/m:plugins/m:plugin'
    else:
        root = ET.parse(source / 'pom.xml').getroot()
        profiles = [p for p in root.findall('m:profiles/m:profile', NS)
                    if p.findtext('m:id', namespaces=NS) == 'product-readiness-demo']
        require(len(profiles) == 1, 'one exact source demo profile')
        root = profiles[0]
        location = 'm:build/m:plugins/m:plugin'
    artifact = 'maven-resources-plugin' if owner == 'resources' else 'flatten-maven-plugin'
    selected = [p for p in root.findall(location, NS) if p.findtext('m:artifactId', namespaces=NS) == artifact]
    require(len(selected) == 1, 'one source owner declaration')
    result = copy.deepcopy(selected[0])
    dependencies = result.findall('m:dependencies/m:dependency', NS)
    expected = [] if variant == 'baseline' else ['4.0.3']
    require([d.findtext('m:version', namespaces=NS) for d in dependencies
             if d.findtext('m:artifactId', namespaces=NS) == 'plexus-utils'] == expected,
            'source variant private Utils differs')
    return result


def project(artifact, packaging='jar', parent=False):
    root = ET.Element(P + 'project')
    root.append(ET.Comment(' fixture comment survives flatten; 非 CI 属性 ${ordinary} '))
    node(root, 'modelVersion', '4.0.0')
    if parent:
        p = node(root, 'parent')
        for k, v in [('groupId', GROUP), ('artifactId', 'fixture-parent'),
                     ('version', '${revision}${sha1}${changelist}'), ('relativePath', '../pom.xml')]:
            node(p, k, v)
    else:
        node(root, 'groupId', GROUP)
    node(root, 'artifactId', artifact)
    if not parent:
        node(root, 'version', '${revision}${sha1}${changelist}')
    node(root, 'packaging', packaging)
    if not parent:
        props = node(root, 'properties')
        for k, v in {**CI, 'project.build.sourceEncoding': 'UTF-8', 'flatten.maven.version': '1.7.3',
                     'ordinary': '保留', 'fixture.name': '你好', 'fixture.destination': 'nested/final'}.items():
            node(props, k, v)
    node(root, 'description', 'ordinary remains ${ordinary}; CI becomes ${revision}${sha1}${changelist}')
    return root


def resource(parent, directory, target=None, filtered=False, includes=None, excludes=None):
    r = node(parent, 'resource')
    node(r, 'directory', directory)
    if target:
        node(r, 'targetPath', target)
    node(r, 'filtering', str(filtered).lower())
    for key, values in [('includes', includes), ('excludes', excludes)]:
        if values is not None:
            q = node(r, key)
            for value in values:
                node(q, key[:-1], value)
    return r


SELECTOR_FILES = ('a', 'b', 'ab', '%ant[a]', '%regex[a]', '%ant[ab]', '%regex[ab]', 'nested/a')
SELECTOR_CASES = (
    ('ant-one', ['%ant[a]'], None, {'baseline': ['%ant[a]'], 'candidate': ['a']}),
    ('regex-one', ['%regex[a]'], None, {'baseline': ['%regex[a]'], 'candidate': ['a']}),
    ('ant-two', ['%ant[ab]'], None, {'baseline': ['ab'], 'candidate': ['ab']}),
    ('regex-two', ['%regex[ab]'], None, {'baseline': ['ab'], 'candidate': ['ab']}),
    ('exclude-ant', ['**/*'], ['%ant[a]'],
     {'baseline': [n for n in SELECTOR_FILES if n != '%ant[a]'], 'candidate': [n for n in SELECTOR_FILES if n != 'a']}),
    ('exclude-regex', ['**/*'], ['%regex[a]'],
     {'baseline': [n for n in SELECTOR_FILES if n != '%regex[a]'], 'candidate': [n for n in SELECTOR_FILES if n != 'a']}),
)


def resource_expected(variant, changed=False):
    files = {
        'target/classes/plain/welcome.txt': ('changed=你好\n' if changed else 'name=你好; escaped=${fixture.name}; unresolved=${missing}\n').encode(),
        'target/classes/plain/你好.txt': '文件名过滤\n'.encode(),
        'target/classes/plain/nested/final.txt': '路径过滤\n'.encode(),
        'target/classes/plain/pixel.png': b'\x89PNG\r\n\x1a\n\x00${fixture.name}\xff',
        'target/test-classes/tests/message.txt': 'test=你好; unknown=${unknown}\n'.encode(),
        'target/copied/copy/message.txt': 'copy=你好\n'.encode(),
    }
    for label, _, _, expected in SELECTOR_CASES:
        for name in expected[variant]:
            files['target/classes/selector/' + label + '/' + name] = ('selected:' + name + '\n').encode()
    return files


def resources_case(directory, source, variant):
    directory.mkdir()
    root = project('resources-fixture')
    build = node(root, 'build')
    main = node(build, 'resources')
    resource(main, '${project.basedir}/inputs/plain', 'plain', True, ['**/*'], ['excluded/**'])
    for label, includes, excludes, _ in SELECTOR_CASES:
        resource(main, '${project.basedir}/inputs/selector', 'selector/' + label, False, includes, excludes)
    tests = node(build, 'testResources')
    r = resource(tests, '${project.basedir}/inputs/test', 'tests', True)
    r.tag = P + 'testResource'
    owner = plugin(source, 'resources', variant)
    config = node(owner, 'configuration')
    for k, v in [('encoding', 'UTF-8'), ('propertiesEncoding', 'UTF-8'), ('escapeString', '\\'),
                 ('fileNameFiltering', 'true'), ('includeEmptyDirs', 'true'), ('overwrite', 'true')]:
        node(config, k, v)
    executions = node(owner, 'executions')
    for identifier, goal in [('main-fixture', 'resources'), ('test-fixture', 'testResources'),
                             ('copy-fixture', 'copy-resources'), ('bad-encoding', 'copy-resources')]:
        execution = node(executions, 'execution'); node(execution, 'id', identifier)
        node(node(execution, 'goals'), 'goal', goal)
        if goal == 'copy-resources':
            conf = node(execution, 'configuration')
            node(conf, 'outputDirectory', '${project.basedir}/target/' + ('invalid' if identifier == 'bad-encoding' else 'copied'))
            resource(node(conf, 'resources'), '${project.basedir}/inputs/copy', 'copy', True)
            if identifier == 'bad-encoding':
                node(conf, 'encoding', 'BOGUS-FIXTURE-CHARSET')
    node(build, 'plugins').append(owner)
    write(directory / 'pom.xml', xml(root))
    input_files = {
        'plain/welcome.txt': r'name=${fixture.name}; escaped=\${fixture.name}; unresolved=${missing}' + '\n',
        'plain/${fixture.name}.txt': '文件名过滤\n',
        'plain/${fixture.destination}.txt': '路径过滤\n',
        'plain/pixel.png': b'\x89PNG\r\n\x1a\n\x00${fixture.name}\xff',
        'plain/excluded/no.txt': 'explicitly excluded\n',
        'plain/.git/config': 'default excluded\n', 'plain/backup~': 'default excluded\n',
        'test/message.txt': 'test=${fixture.name}; unknown=${unknown}\n',
        'copy/message.txt': 'copy=${fixture.name}\n',
    }
    for name, data in input_files.items():
        write(directory / 'inputs' / name, data)
    for name in SELECTOR_FILES:
        write(directory / 'inputs/selector' / name, 'selected:' + name + '\n')
    (directory / 'inputs/copy/empty/nested').mkdir(parents=True)
    write(directory / 'neighbor-sentinel.txt', b'keep neighbor\n')
    return {'sourcePom': directory / 'pom.xml', 'expected': resource_expected(variant),
            'goals': [RESOURCE_OWNER + ':' + goal + '@' + identifier for identifier, goal in
                      [('main-fixture', 'resources'), ('test-fixture', 'testResources'), ('copy-fixture', 'copy-resources')]],
            'terminals': [('resources', goal, identifier, 'resources-fixture', 'MojoSucceeded') for identifier, goal in
                          [('main-fixture', 'resources'), ('test-fixture', 'testResources'), ('copy-fixture', 'copy-resources')]]}


def check_resources(directory, variant, changed=False):
    expected = resource_expected(variant, changed)
    actual = {p.relative_to(directory).as_posix(): p.read_bytes()
              for output in ('classes', 'test-classes', 'copied')
              for p in (directory / 'target' / output).rglob('*') if p.is_file()}
    require(actual == expected, 'exact independent resource paths/bytes differ: missing=' + str(sorted(set(expected) - set(actual)))
            + ' extra=' + str(sorted(set(actual) - set(expected)))
            + ' bytes=' + str([p for p in set(actual) & set(expected) if actual[p] != expected[p]]))
    require((directory / 'target/copied/copy/empty/nested').is_dir(), 'empty directory not copied')
    require((directory / 'neighbor-sentinel.txt').read_bytes() == b'keep neighbor\n', 'neighbor changed')
    return {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for name, data in actual.items()}


def flatten_case(directory, source, variant, demo=False, invalid=False):
    directory.mkdir()
    root = project('fixture-parent', 'pom')
    modules = node(root, 'modules')
    if demo:
        node(modules, 'module', 'ordinary')
    node(modules, 'module', 'sdk')
    build = node(root, 'build')
    if variant == 'candidate':
        node(node(build, 'pluginManagement'), 'plugins').append(plugin(source, 'resources', variant))
    profiles = node(root, 'profiles'); profile = node(profiles, 'profile')
    node(profile, 'id', 'product-readiness-demo')
    node(node(profile, 'build'), 'plugins').append(plugin(source, 'flatten', variant, 'demo'))
    write(directory / 'pom.xml', xml(root))
    for name in (('ordinary', 'sdk') if demo else ('sdk',)):
        child = project(name, parent=True)
        if name == 'sdk':
            properties = node(child, 'properties'); node(properties, 'maven.compiler.release', '17')
            node(node(child, 'build'), 'plugins').append(plugin(source, 'flatten', variant, 'sdk'))
        write(directory / name / 'pom.xml', xml(child))
        write(directory / name / 'neighbor-sentinel.txt', b'keep neighbor\n')
    write(directory / 'neighbor-sentinel.txt', b'keep neighbor\n')
    expected = {}
    selected = [directory, directory / 'ordinary', directory / 'sdk'] if demo else [directory / 'sdk']
    for path in selected:
        original = (path / 'pom.xml').read_bytes()
        result = original
        for key, value in CI.items():
            result = result.replace(('${' + key + '}').encode(), value.encode())
        target = path / ('target/flattened-pom.xml' if demo else '.flattened-pom.xml')
        expected[str(path)] = {'artifact': 'fixture-parent' if path == directory else path.name,
                               'target': str(target), 'source': original, 'flattened': result}
    if invalid:
        write(directory / 'sdk/blocked', b'not a directory\n')
        expected[str(directory / 'sdk')]['target'] = str(directory / 'sdk/blocked/flattened-pom.xml')
    return {'project': directory / ('pom.xml' if demo else 'sdk/pom.xml'), 'projects': expected,
            'sourcePoms': {str(p): p.read_bytes() for p in directory.rglob('pom.xml')},
            'demo': demo, 'invalid': invalid}


def check_flatten_sources(fixture):
    for name, data in fixture['sourcePoms'].items():
        require(Path(name).read_bytes() == data, 'source POM changed during flatten/clean')
        require((Path(name).parent / 'neighbor-sentinel.txt').read_bytes() == b'keep neighbor\n', 'flatten neighbor changed')


def check_flatten_files(fixture, clean=False):
    check_flatten_sources(fixture)
    result = {}
    for row in fixture['projects'].values():
        target = Path(row['target'])
        if clean:
            require(not target.exists(), 'flatten clean left expected file')
        else:
            require(target.is_file() and not target.is_symlink() and target.read_bytes() == row['flattened'],
                    'flatten differs from independent CI-only full-byte oracle: ' + str(target))
            result[str(target)] = hashlib.sha256(target.read_bytes()).hexdigest()
    return result


def production_sdk_case(work, source, variant):
    f = globals()
    work.mkdir()
    ns=f['NS']
    actual_root=ET.parse(source/'pom.xml').getroot()
    actual_sdk=ET.parse(source/'integrations/host-sdk/pom.xml').getroot()
    revision=actual_root.findtext('m:properties/m:revision',namespaces=ns)
    require(actual_root.findtext('m:version',namespaces=ns)=='${revision}'
            and actual_sdk.findtext('m:parent/m:version',namespaces=ns)=='${revision}'
            and actual_sdk.findtext('m:parent/m:relativePath',namespaces=ns)=='../../pom.xml', 'exact production parent shape')
    for name in ('pom.xml','integrations/host-sdk/pom.xml'):
        raw=(source/name).read_bytes(); f['write'](work/name,raw)
        require((work/name).read_bytes()==raw,'exact copied production POM')
        f['write']((work/name).parent/'neighbor-sentinel.txt',b'keep neighbor\n')
    sdk_dir=work/'integrations/host-sdk'
    original=(sdk_dir/'pom.xml').read_bytes(); flattened=original.replace(b'${revision}',revision.encode())
    row={'artifact':actual_sdk.findtext('m:artifactId',namespaces=ns),'target':str(sdk_dir/'.flattened-pom.xml'),
         'source':original,'flattened':flattened}
    return {'project':sdk_dir/'pom.xml','projects':{str(sdk_dir):row},
            'sourcePoms':{str(work/name):(source/name).read_bytes() for name in ('pom.xml','integrations/host-sdk/pom.xml')},
            'demo':False,'invalid':False}
