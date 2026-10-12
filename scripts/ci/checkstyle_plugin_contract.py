"""Bounded Checkstyle Lang successor; reads source/models, never executes Maven."""
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
PROP = 'maven.checkstyle.commons-lang3.version'
PLUGIN = 'maven-checkstyle-plugin'
OVERLAYS = tuple(f'integrations/ruoyi{version}-host-starter/overlay/ruoyi-extend/'
                 'ruoyi-approval-host-starter/pom.xml' for version in (5, 6))
PRIOR_POM = '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a'
CURRENT_POM = 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb'
RULES_SHA256 = 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
PROPERTY_ADDITION = '        <' + PROP + '>3.18.0</' + PROP + '>\n'
DEPENDENCY_ADDITION = ('                <dependencies>\n'
    '                    <dependency>\n'
    '                        <groupId>org.apache.commons</groupId>\n'
    '                        <artifactId>commons-lang3</artifactId>\n'
    '                        <version>${' + PROP + '}</version>\n'
    '                    </dependency>\n'
    '                </dependencies>\n')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def one(node, name, required=True):
    matches = node.findall('m:' + name, NS)
    require(len(matches) == 1 if required else len(matches) <= 1, 'Checkstyle missing/duplicate ' + name)
    return matches[0] if matches else None


def value(node, name, required=True):
    item = one(node, name, required)
    if item is None:
        return ''
    require(not list(item) and not item.attrib, 'Checkstyle non-scalar ' + name)
    return (item.text or '').strip()


def fields(node, names):
    require(not node.attrib and sorted(c.tag for c in node) == sorted(P + n for n in names),
            'Checkstyle unexpected/duplicate fields')
    require(not (node.text or '').strip() and all(not (c.tail or '').strip() for c in node), 'Checkstyle mixed XML')


def targeted(plugin):
    return value(plugin, 'artifactId', False) == PLUGIN


def semantic(element, root=''):
    def text(v):
        result = (v or '').strip()
        return result.replace(root + '/', '${SOURCE_ROOT}/') if root else result
    return (element.tag, tuple(sorted((k, text(v)) for k, v in element.attrib.items())),
            text(element.text), text(element.tail), tuple(semantic(c, root) for c in element))


def verify_plugin(plugin, effective=False):
    names = ['artifactId', 'version', 'dependencies', 'executions', 'configuration']
    if not effective or one(plugin, 'groupId', False) is not None:
        names.insert(0, 'groupId')
    fields(plugin, names)
    require(value(plugin, 'groupId', False) in (('', 'org.apache.maven.plugins') if effective
            else ('org.apache.maven.plugins',)), 'Checkstyle group drift')
    require(value(plugin, 'version') == ('3.6.0' if effective else '${maven.checkstyle.version}'),
            'Checkstyle retained plugin version drift')
    deps = one(plugin, 'dependencies'); fields(deps, ['dependency'])
    dep = one(deps, 'dependency')
    fields(dep, ['groupId', 'artifactId', 'version'] + (['scope'] if effective else []))
    require(value(dep, 'groupId') == 'org.apache.commons' and value(dep, 'artifactId') == 'commons-lang3'
            and value(dep, 'version') == ('3.18.0' if effective else '${' + PROP + '}'),
            'Checkstyle requires exact local Lang override')
    if effective:
        require(value(dep, 'scope') == 'compile', 'Checkstyle effective Lang scope drift')
        # The paired baseline below checks all retained generated fields. Do not
        # infer Maven's effective defaults or execution configuration from source.
        return dep
    configuration = one(plugin, 'configuration')
    fields(configuration, ['configLocation', 'includeTestSourceDirectory', 'consoleOutput', 'failOnViolation'])
    for name in ('includeTestSourceDirectory', 'consoleOutput', 'failOnViolation'):
        require(value(configuration, name) == 'true', 'Checkstyle retained configuration drift')
    require(bool(value(configuration, 'configLocation')), 'Checkstyle rules path missing')
    if not effective:
        require(value(configuration, 'configLocation') == '${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml',
                'Checkstyle source rules path drift')
    executions = one(plugin, 'executions'); fields(executions, ['execution'])
    execution = one(executions, 'execution')
    fields(execution, ['id', 'phase', 'goals'] + (['configuration'] if effective else []))
    require(value(execution, 'id') == 'checkstyle' and value(execution, 'phase') == 'validate', 'Checkstyle execution drift')
    goals = one(execution, 'goals'); fields(goals, ['goal'])
    require(value(goals, 'goal') == 'check', 'Checkstyle goal drift')
    if effective:
        require(semantic(one(execution, 'configuration')) == semantic(configuration), 'Checkstyle inherited execution configuration drift')
    return dep


def before_checkstyle(project, effective=False, required=True):
    """Remove only the exact property and active plugin dependency; keep all else."""
    project = copy.deepcopy(project)
    properties = one(project, 'properties', required)
    pin = one(properties, PROP, False) if properties is not None else None
    matches = [p for p in project.findall('.//m:plugin', NS) if targeted(p)]
    has_dependency = any(one(p, 'dependencies', False) is not None for p in matches)
    if pin is None and not required:
        require(not has_dependency, 'Checkstyle dependency without exact property')
        return project
    require(pin is not None and value(properties, PROP) == '3.18.0', 'Checkstyle Lang property drift')
    active = [p for p in project.findall('m:build/m:plugins/m:plugin', NS) if targeted(p)]
    require(len(matches) == len(active) == 1 and matches[0] is active[0], 'Checkstyle declaration escaped exact active owner')
    dep = verify_plugin(active[0], effective)
    references = {one(dep, 'version')}
    for container in project.findall('.//m:properties', NS):
        for prop in container:
            name = prop.tag.removeprefix(P)
            if name.startswith(('maven.checkstyle.', 'checkstyle.')):
                require(container is properties and name in ('maven.checkstyle.version', PROP),
                        'Checkstyle behavior property outside exact root/inherited pins')
    require(value(properties, 'maven.checkstyle.version') == '3.6.0', 'Checkstyle plugin pin drift')
    for element in project.iter():
        if '${' + PROP + '}' in (element.text or ''):
            require(element in references, 'Checkstyle Lang property reference escaped owner')
    properties.remove(pin)
    active[0].remove(one(active[0], 'dependencies'))
    return project


def reverse_source(raw):
    require(hashlib.sha256(raw).hexdigest() == CURRENT_POM, 'Checkstyle exact candidate source bytes changed')
    text = raw.decode('utf8')
    for addition in (PROPERTY_ADDITION, DEPENDENCY_ADDITION):
        require(text.count(addition) == 1, 'Checkstyle ambiguous/missing exact source addition')
        text = text.replace(addition, '', 1)
    result = text.encode('utf8')
    require(hashlib.sha256(result).hexdigest() == PRIOR_POM, 'Checkstyle source changed beyond exact root additions')
    return result


def verify_source(root, projects=None):
    root = root.resolve()
    if projects is None:
        release = run_path(str(Path(__file__).with_name('verify-release-plugin-baseline.py')))
        projects = release['verify_source'](root)
    clean = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))
    require([str(p.relative_to(root)) for p, _ in projects] == list(clean['REACTOR']),
            'Checkstyle source reactor path/order drift')
    before_checkstyle(projects[0][1])
    reverse_source((root / 'pom.xml').read_bytes())
    witness_path = Path(__file__).resolve().parents[2] / 'docs/operations/compiler-plugin-evidence/source-witness.json'
    witness_bytes = witness_path.read_bytes()
    require(hashlib.sha256(witness_bytes).hexdigest() == '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a',
            'Checkstyle historical Compiler source witness changed')
    rows = json.loads(witness_bytes)['sourcePomFiles']
    expected_paths = set(clean['REACTOR']) | set(clean['OVERLAYS'])
    require(len(rows) == 28 and {row['path'] for row in rows} == expected_paths, 'Checkstyle retained source inventory drift')
    for row in rows:
        path = root / row['path']
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'Checkstyle source POM path escape')
        if row['path'] != 'pom.xml':
            require(hashlib.sha256(path.read_bytes()).hexdigest() == row['candidateSha256'],
                    'Checkstyle changed another source POM: ' + row['path'])
    require(hashlib.sha256((root / 'config/checkstyle/checkstyle.xml').read_bytes()).hexdigest() == RULES_SHA256,
            'Checkstyle rule bytes changed')
    return projects


def verify_effective(source_root, file, baseline=None, baseline_root=None):
    """Compare paired emitted order; source traversal order is not model order."""
    require(baseline is not None and source_root is not None and baseline_root is not None,
            'Checkstyle full model verification requires paired actual baseline and source roots')
    def models(path):
        document = ET.parse(path).getroot()
        require(document.tag in (P + 'projects', 'projects'), 'Checkstyle malformed effective model document')
        rows = list(document)
        require(len(rows) == 26 and all(p.tag == P + 'project' for p in rows), 'Checkstyle requires all 26 models')
        identities = [(value(p, 'groupId'), value(p, 'artifactId'), value(p, 'version')) for p in rows]
        require(len(set(identities)) == 26, 'Checkstyle duplicate model identity')
        clean = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))
        require({(g, a) for g, a, _ in identities} == {(clean['PROJECT_GROUP'], a) for a in clean['REACTOR'].values()},
                'Checkstyle effective model membership drift')
        return rows, identities
    prior, prior_ids = models(baseline)
    projects, current_ids = models(file)
    require(current_ids == prior_ids, 'Checkstyle paired actual model order/identity drift')
    for before, after in zip(prior, projects):
        require(semantic(before, str(baseline_root.resolve())) == semantic(before_checkstyle(after, effective=True), str(source_root.resolve())),
                'Checkstyle models changed beyond exact Lang additions: ' + value(after, 'artifactId'))
    return len(projects)
