"""Exact Compiler-owned IO successor; reverse only validated additions for prior gates."""
import copy
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
PROP = 'maven.compiler.commons-io.version'
PLUGIN = 'maven-compiler-plugin'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def one(node, name, required=True):
    matches = node.findall('m:' + name, NS)
    require(len(matches) == 1 if required else len(matches) <= 1, 'Compiler missing/duplicate ' + name)
    return matches[0] if matches else None


def value(node, name, required=True):
    item = one(node, name, required)
    if item is None:
        return ''
    require(not list(item) and not item.attrib, 'Compiler non-scalar ' + name)
    return (item.text or '').strip()


def fields(node, names):
    require(not node.attrib and sorted(c.tag for c in node) == sorted(P + n for n in names),
            'Compiler unexpected/duplicate fields')
    require(not (node.text or '').strip() and all(not (c.tail or '').strip() for c in node), 'Compiler mixed XML')


def targeted(plugin):
    return value(plugin, 'artifactId', False) == PLUGIN


def verify_plugin(plugin, effective=False, active=False, java_release='21'):
    names = ['artifactId', 'version', 'dependencies', 'configuration']
    if not effective or one(plugin, 'groupId', False) is not None:
        names.insert(0, 'groupId')
    if active:
        names.append('executions')
    fields(plugin, names)
    require(value(plugin, 'groupId', False) in (('', 'org.apache.maven.plugins') if effective else ('org.apache.maven.plugins',)), 'Compiler group drift')
    require(value(plugin, 'version') == ('3.14.0' if effective else '${maven.compiler.version}'), 'Compiler retained version drift')
    config = one(plugin, 'configuration')
    fields(config, ['release', 'parameters', 'showWarnings'])
    require(java_release in ('17', '21') and value(config, 'release') == (java_release if effective else '${java.version}')
            and value(config, 'parameters') == value(config, 'showWarnings') == 'true', 'Compiler retained configuration drift')
    deps = one(plugin, 'dependencies'); fields(deps, ['dependency'])
    dep = one(deps, 'dependency')
    fields(dep, ['groupId', 'artifactId', 'version'] + (['scope'] if active else []))
    require(value(dep, 'groupId') == value(dep, 'artifactId') == 'commons-io'
            and value(dep, 'version') == ('2.20.0' if effective else '${' + PROP + '}'), 'Compiler requires exact local IO override')
    if active:
        require(value(dep, 'scope') == 'compile', 'Compiler active IO scope drift')
        executions = one(plugin, 'executions')
        require(len(executions) == 2, 'Compiler requires exactly Maven default compile/testCompile executions')
        expected = [('default-compile', 'compile', 'compile'), ('default-testCompile', 'test-compile', 'testCompile')]
        for execution, (identity, phase, goal) in zip(executions, expected):
            fields(execution, ['id', 'phase', 'goals', 'configuration'])
            require(value(execution, 'id') == identity and value(execution, 'phase') == phase, 'Compiler default execution drift')
            goals = one(execution, 'goals'); fields(goals, ['goal'])
            require(value(goals, 'goal') == goal, 'Compiler default goal drift')
            require(semantic(one(execution, 'configuration')) == semantic(config), 'Compiler execution configuration drift')
    return dep


def semantic(element, root=''):
    def text(value):
        text = (value or '').strip()
        return text.replace(root + '/', '${SOURCE_ROOT}/') if root else text
    return (element.tag, tuple(sorted((k, text(v)) for k, v in element.attrib.items())),
            text(element.text), text(element.tail), tuple(semantic(c, root) for c in element))


def before_compiler(project, effective=False, required=True):
    """Keep all old semantics after removing exactly one property and approved dependencies."""
    project = copy.deepcopy(project)
    properties = one(project, 'properties', required)
    pin = one(properties, PROP, False) if properties is not None else None
    matches = [p for p in project.findall('.//m:plugin', NS) if targeted(p)]
    has_dependency = any(one(p, 'dependencies', False) is not None for p in matches)
    if pin is None and not required:
        require(not has_dependency, 'Compiler dependency without exact property')
        return project
    require(pin is not None and value(properties, PROP) == '2.20.0', 'Compiler IO property drift')
    managed = [p for p in project.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS) if targeted(p)]
    active = [p for p in project.findall('m:build/m:plugins/m:plugin', NS) if targeted(p)]
    require(len(managed) == 1 and (len(active) <= 1 if effective else not active)
            and len(matches) == len(managed) + len(active), 'Compiler declaration escaped exact managed/default active owner')
    references = {pin}
    for plugin in managed + active:
        dependency = verify_plugin(plugin, effective, plugin in active,
                                   value(properties, 'java.version') if effective else '21')
        references.add(one(dependency, 'version'))
    for container in project.findall('.//m:properties', NS):
        for prop in container:
            if prop.tag == P + PROP:
                require(container is properties and prop is pin, 'Compiler IO property outside root/inherited properties')
    for element in project.iter():
        if '${' + PROP + '}' in (element.text or ''):
            require(element in references, 'Compiler IO property reference escaped owner')
    properties.remove(pin)
    for plugin in managed + active:
        plugin.remove(one(plugin, 'dependencies'))
    return project


def verify_source(root, projects):
    documents = list(projects)
    for version in (5, 6):
        path = root / f'integrations/ruoyi{version}-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml'
        documents.append((path, ET.parse(path).getroot()))
    for path, project in documents:
        for container in project.findall('.//m:properties', NS):
            for prop in container:
                name = prop.tag.removeprefix(P)
                if name.startswith('maven.compiler.') or name in ('maven.main.skip', 'maven.test.skip'):
                    require(path == root / 'pom.xml' and container is one(project, 'properties')
                            and name in ('maven.compiler.version', 'maven.compiler.release', PROP),
                            'Compiler behavior property outside exact root pins')
        if path == root / 'pom.xml':
            before_compiler(project)
        else:
            require(not any(targeted(p) for p in project.findall('.//m:plugin', NS)), 'Compiler declaration outside root')
            require(not any(e.tag == P + PROP or '${' + PROP + '}' in (e.text or '') for e in project.iter()),
                    'Compiler IO pin/reference outside root')


def verify_effective(file, baseline=None, source_root=None, baseline_root=None):
    document = ET.parse(file).getroot()
    projects = list(document)
    require(len(projects) == 26 and all(p.tag == P + 'project' for p in projects), 'Compiler requires all 26 actual models')
    reversed_projects = [before_compiler(p, effective=True) for p in projects]
    if baseline:
        prior = list(ET.parse(baseline).getroot())
        require(len(prior) == 26, 'Compiler baseline model membership drift')
        for before, after in zip(prior, reversed_projects):
            require(semantic(before, str(baseline_root.resolve())) == semantic(after, str(source_root.resolve())),
                    'Compiler models changed beyond exact IO additions: ' + value(after, 'artifactId'))
    return len(projects)
