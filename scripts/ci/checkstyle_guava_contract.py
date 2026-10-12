"""Exact Checkstyle Guava successor; preserves the byte-pinned Lang contract."""
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path
import xml.etree.ElementTree as ET

LANG = run_path(str(Path(__file__).with_name('checkstyle_plugin_contract.py')))
NS, P, OVERLAYS = LANG['NS'], LANG['P'], LANG['OVERLAYS']
require, one, value, fields = (LANG[name] for name in ('require', 'one', 'value', 'fields'))
targeted, semantic = LANG['targeted'], LANG['semantic']
PROP = 'maven.checkstyle.guava.version'
PRIOR_POM = LANG['CURRENT_POM']
CURRENT_POM = 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5'
PROPERTY_ADDITION = '        <' + PROP + '>32.0.1-jre</' + PROP + '>\n'
DEPENDENCY_ADDITION = ('                    <dependency>\n'
    '                        <groupId>com.google.guava</groupId>\n'
    '                        <artifactId>guava</artifactId>\n'
    '                        <version>${' + PROP + '}</version>\n'
    '                    </dependency>\n')


def before_checkstyle(project, effective=False, required=True):
    """Remove only the exact Guava addition, leaving the accepted Lang override."""
    project = copy.deepcopy(project)
    properties = one(project, 'properties')
    pin = one(properties, PROP, False)
    if pin is None and not required:
        LANG['before_checkstyle'](project, effective=effective)
        return project
    require(pin is not None and value(properties, PROP) == '32.0.1-jre', 'Checkstyle Guava property drift')
    matches = [p for p in project.findall('.//m:plugin', NS) if targeted(p)]
    active = [p for p in project.findall('m:build/m:plugins/m:plugin', NS) if targeted(p)]
    require(len(matches) == len(active) == 1 and matches[0] is active[0],
            'Checkstyle Guava declaration escaped exact active owner')
    dependencies = one(active[0], 'dependencies')
    fields(dependencies, ['dependency', 'dependency'])
    retained, addition = list(dependencies)
    fields(addition, ['groupId', 'artifactId', 'version'] + (['scope'] if effective else []))
    require(value(addition, 'groupId') == 'com.google.guava' and value(addition, 'artifactId') == 'guava'
            and value(addition, 'version') == ('32.0.1-jre' if effective else '${' + PROP + '}'),
            'Checkstyle requires exact local Guava override after retained Lang')
    if effective:
        require(value(addition, 'scope') == 'compile', 'Checkstyle effective Guava scope drift')
    for container in project.findall('.//m:properties', NS):
        for prop in container:
            if prop.tag == P + PROP:
                require(container is properties and prop is pin, 'Checkstyle Guava property escaped root/inherited pins')
    reference = one(addition, 'version')
    for element in project.iter():
        if '${' + PROP + '}' in (element.text or ''):
            require(element is reference, 'Checkstyle Guava property reference escaped owner')
        require('${' + PROP + '}' not in (element.tail or '')
                and all('${' + PROP + '}' not in v for v in element.attrib.values()),
                'Checkstyle Guava property reference escaped scalar version')
    properties.remove(pin)
    dependencies.remove(addition)
    # Retains every original Lang/plugin/configuration/execution ownership gate.
    LANG['before_checkstyle'](project, effective=effective)
    return project


def reverse_source(raw):
    require(hashlib.sha256(raw).hexdigest() == CURRENT_POM, 'Checkstyle Guava exact candidate source bytes changed')
    text = raw.decode('utf8')
    for addition in (PROPERTY_ADDITION, DEPENDENCY_ADDITION):
        require(text.count(addition) == 1, 'Checkstyle Guava ambiguous/missing exact source addition')
        text = text.replace(addition, '', 1)
    prior = text.encode('utf8')
    require(hashlib.sha256(prior).hexdigest() == PRIOR_POM,
            'Checkstyle Guava source changed beyond exact root additions')
    LANG['reverse_source'](prior)
    return prior


def verify_source(root, projects=None):
    root = root.resolve()
    if projects is None:
        release = run_path(str(Path(__file__).with_name('verify-release-plugin-baseline.py')))
        projects = release['verify_source'](root)
    clean = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))
    require([str(p.relative_to(root)) for p, _ in projects] == list(clean['REACTOR']),
            'Checkstyle Guava source reactor path/order drift')
    before_checkstyle(projects[0][1])
    reverse_source((root / 'pom.xml').read_bytes())
    # The retained Lang contract is receipt-pinned and remains byte-identical.
    # Reuse its inventory and byte proof for every unchanged source input.
    witness = Path(__file__).resolve().parents[2] / 'docs/operations/compiler-plugin-evidence/source-witness.json'
    raw = witness.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a',
            'Checkstyle Guava historical Compiler source witness changed')
    rows = json.loads(raw)['sourcePomFiles']
    require(len(rows) == 28 and {row['path'] for row in rows} == set(clean['REACTOR']) | set(OVERLAYS),
            'Checkstyle Guava retained source inventory drift')
    for row in rows:
        path = root / row['path']
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'Checkstyle Guava source POM path escape')
        if row['path'] != 'pom.xml':
            require(hashlib.sha256(path.read_bytes()).hexdigest() == row['candidateSha256'],
                    'Checkstyle Guava changed another source POM: ' + row['path'])
    rules = root / 'config/checkstyle/checkstyle.xml'
    require(not rules.is_symlink() and rules.resolve().is_relative_to(root)
            and hashlib.sha256(rules.read_bytes()).hexdigest() == LANG['RULES_SHA256'],
            'Checkstyle Guava rule bytes/path changed')
    return projects


def verify_effective(source_root, file, baseline=None, baseline_root=None):
    """Compare all paired actual emitted models after the sole Guava removal."""
    require(baseline is not None and source_root is not None and baseline_root is not None,
            'Checkstyle Guava full model verification requires paired actual baseline and source roots')
    clean = run_path(str(Path(__file__).with_name('clean_plugin_contract.py')))
    def models(path):
        document = ET.parse(path).getroot()
        require(document.tag in (P + 'projects', 'projects'), 'Checkstyle Guava malformed effective model document')
        rows = list(document)
        require(len(rows) == 26 and all(p.tag == P + 'project' for p in rows), 'Checkstyle Guava requires all 26 models')
        identities = [(value(p, 'groupId'), value(p, 'artifactId'), value(p, 'version')) for p in rows]
        require(len(set(identities)) == 26
                and {(g, a) for g, a, _ in identities} == {(clean['PROJECT_GROUP'], a) for a in clean['REACTOR'].values()},
                'Checkstyle Guava duplicate model identity or membership drift')
        return rows, identities
    prior, prior_ids = models(baseline)
    projects, current_ids = models(file)
    require(current_ids == prior_ids, 'Checkstyle Guava paired actual model order/identity drift')
    for before, after in zip(prior, projects):
        LANG['before_checkstyle'](before, effective=True)
        require(semantic(before, str(baseline_root.resolve())) == semantic(before_checkstyle(after, effective=True), str(source_root.resolve())),
                'Checkstyle models changed beyond exact Guava additions: ' + value(after, 'artifactId'))
    return len(projects)
