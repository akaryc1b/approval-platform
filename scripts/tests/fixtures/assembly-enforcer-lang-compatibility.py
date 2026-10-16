#!/usr/bin/env python3
"""Bounded actual Assembly/Enforcer Lang pairs. Import/plan/prepare starts no JVM."""
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
import struct
import sys
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
from checkstyle_live_support import Commands, digest, inside, isolated_env, require, normalized, NS, P
from run_boot_live_compatibility import runtime_files, python_origins, hashes, cache_snapshot
from jar_plugin_storage import StorageGuard

A = run_path(str(HERE / 'assembly-plexus-compatibility.py'))
# Isolated globals preserve the existing Plexus inventory's meaning.
inspect_class = run_path(str(HERE / 'assembly_plexus_inventory.py'))['inspect_class']
inspect_class.__globals__['PREFIX'] = 'org/apache/commons/lang3/'
PINS = HERE / 'assembly-enforcer-lang-pins.json'
CONSUMERS = HERE / 'assembly-enforcer-lang-consumers.json'
OBSERVER = HERE / 'PluginLangRealmObserver.java'
SPY = HERE / 'PluginLangRealmCompletionSpy.java'
LANG_PREFIX = 'org.apache.commons:commons-lang3:jar:'
VERSIONS = {'baseline': '3.14.0', 'candidate': '3.18.0'}
OWNERS = {'assembly': 'org.apache.maven.plugins:maven-assembly-plugin:3.7.1',
          'enforcer': 'org.apache.maven.plugins:maven-enforcer-plugin:3.5.0'}
GOALS = {'assembly': OWNERS['assembly'] + ':single',
         'enforcer': OWNERS['enforcer'] + ':enforce@enforce-toolchain'}
CASES = {'assembly': A['CASES'], 'enforcer': ('success', 'bad-maven', 'bad-java')}
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'ASSEMBLY_ENFORCER_LANG_COMPATIBILITY=true',
        'offline': True, 'sockets': False, 'goals': GOALS, 'variants': VERSIONS, 'cases': CASES,
        'actualGoalCount': 18, 'compileCount': 1, 'commandTimeoutSeconds': 60,
        'compileTimeoutSeconds': 30, 'aggregateTimeoutSeconds': 1200, 'outerTimeoutSeconds': 1230,
        'classLoadMaximumBytes': 4 * 1024 * 1024, 'classLoadTotalMaximumBytes': 72 * 1024 * 1024,
        'classLoadMaximumEntries': 18, 'classLoadPollIntervalSeconds': 0.1, 'classLoadBoundIsSampled': True,
        'budgetReason': '18 goals at 60s + one compilation at 30s = 1110s; 90s for bounded seals and packaging; 30s outer cleanup margin.',
        'scannerEvidence': False, 'productionActivationClaim': False,
        'evidencePhases': ['natural goal loads', 'controlled API calls after goal', 'symbolic member resolution']}
# JSON round trips turn tuple case sets into lists, including a reviewed seal.
PLAN = json.loads(json.dumps(PLAN))
artifact_path = A['artifact_path']


def read_pins():
    pins = json.loads(PINS.read_text())
    require(pins['schemaVersion'] == 1 and set(pins['owners']) == set(OWNERS), 'exact two owners')
    union = set()
    for owner, count in (('assembly', 21), ('enforcer', 11)):
        before, after = (pins['owners'][owner][v] for v in VERSIONS)
        require(len(before) == len(set(before)) == count, 'complete owner inventory')
        require(after == [c.replace(LANG_PREFIX + '3.14.0', LANG_PREFIX + '3.18.0') for c in before], 'only private Lang replacement')
        plexus = 'org.codehaus.plexus:plexus-utils:jar:' + ('4.0.3' if owner == 'assembly' else '4.0.1')
        require(plexus in before and plexus in after and before.count(LANG_PREFIX + '3.14.0') == 1, 'fixed owner Plexus and one Lang')
        union.update(before + after)
    require(union == set(pins['artifactHashes']) and len(union) == 29
            and all(re.fullmatch('[0-9a-f]{64}', h) for h in pins['artifactHashes'].values()), 'exact artifact union')
    return pins


def inventory(repository):
    consumers, uses, total_bytes = [], [], 0
    for owner, variants in read_pins()['owners'].items():
        for coordinate in variants['baseline']:
            if coordinate.startswith(LANG_PREFIX): continue
            with zipfile.ZipFile(artifact_path(repository, coordinate)) as archive:
                require(len(archive.infolist()) <= 20000, 'JAR entry budget')
                entries = {}
                multi = 'META-INF/MANIFEST.MF' in archive.namelist() and b'multi-release: true' in archive.read('META-INF/MANIFEST.MF').lower()
                for entry in archive.infolist():
                    if not entry.filename.endswith('.class'): continue
                    match = re.fullmatch(r'META-INF/versions/(\d+)/(.*)', entry.filename)
                    version, name = (int(match[1]), match[2]) if match else (0, entry.filename)
                    if match and (not multi or version > 21): continue
                    if name not in entries or version > entries[name][0]: entries[name] = (version, entry)
                for _, entry in entries.values():
                    require(0 <= entry.file_size <= 2 * 1024 * 1024, 'class size budget')
                    total_bytes += entry.file_size
                    require(total_bytes <= 256 * 1024 * 1024, 'class inventory byte budget')
                    data = archive.read(entry); name, refs, members, found = inspect_class(data)
                    if not refs: continue
                    consumers.append({'owner': owner, 'coordinate': coordinate, 'classEntry': entry.filename,
                        'classSha256': hashlib.sha256(data).hexdigest(), 'classAndDescriptorReferences': refs, 'memberReferences': members})
                    uses.extend([owner, name, *row] for row in found)
    consumers.sort(key=lambda c: (c['owner'], c['coordinate'], c['classEntry'])); uses.sort()
    require(len(consumers) == 10 and len(uses) == 13 and len({tuple(r[2:]) for r in uses}) == 10, 'exact complete Lang consumer set')
    return {'consumers': consumers, 'memberUses': uses}


def classutils_static(repository):
    """Conservative exact-method byte inspection; no class loading or recursion.

    Absence of an invokestatic+index byte sequence even among operands proves
    absence at instruction boundaries too. A possible sequence fails closed.
    """
    path = artifact_path(repository, LANG_PREFIX + VERSIONS['candidate'])
    with zipfile.ZipFile(path) as archive: data = archive.read('org/apache/commons/lang3/ClassUtils.class')
    require(len(data) <= 2 * 1024 * 1024 and data[:4] == b'\xca\xfe\xba\xbe', 'bounded ClassUtils bytes')
    pos = 8
    def read(fmt):
        nonlocal pos
        size = struct.calcsize(fmt); value = struct.unpack_from(fmt, data, pos); pos += size
        return value[0] if len(value) == 1 else value
    count = read('>H'); pool = [None] * count; tags = [0] * count; index = 1
    while index < count:
        tag = tags[index] = read('>B')
        if tag == 1:
            size = read('>H'); pool[index] = data[pos:pos + size].decode('utf-8', errors='replace'); pos += size
        elif tag in (3, 4): read('>I')
        elif tag in (5, 6): read('>Q'); index += 1
        elif tag in (7, 8, 16, 19, 20): pool[index] = read('>H')
        elif tag in (9, 10, 11, 12, 17, 18): pool[index] = read('>HH')
        elif tag == 15: read('>BH')
        else: raise ValueError('unknown class constant')
        index += 1
    read('>H'); own = pool[pool[read('>H')]]; read('>H')
    descriptor = '(Ljava/lang/ClassLoader;Ljava/lang/String;Z)Ljava/lang/Class;'
    targets = []
    for i, tag in enumerate(tags):
        if tag != 10: continue
        owner, member = pool[i]; name, desc = pool[member]
        if pool[pool[owner]] == own and pool[name] == 'getClass' and pool[desc] == descriptor: targets.append(i)
    for _ in range(read('>H')): read('>H')
    codes = []
    for methods in (False, True):
        for _ in range(read('>H')):
            access, name, desc = read('>HHH')
            for _ in range(read('>H')):
                attribute, size = read('>HI'); start = pos; pos += size
                require(pos <= len(data), 'bounded class attribute')
                if methods and pool[name] == 'getClass' and pool[desc] == descriptor and pool[attribute] == 'Code':
                    require(access & 0x0009 == 0x0009, 'public static ClassUtils method')
                    length = struct.unpack_from('>I', data, start + 4)[0]
                    code = data[start + 8:start + 8 + length]
                    require(len(code) == length <= 65535 and start + 8 + length <= pos, 'bounded method code')
                    require(not any(b'\xb8' + struct.pack('>H', index) in code for index in targets), 'candidate directly invokes itself')
                    codes.append(hashlib.sha256(code).hexdigest())
    require(own == 'org/apache/commons/lang3/ClassUtils' and len(codes) == 1, 'one exact candidate ClassUtils method')
    return {'classSha256': hashlib.sha256(data).hexdigest(), 'method': 'getClass', 'descriptor': descriptor,
            'codeSha256': codes[0], 'directSelfInvocation': False, 'execution': 'NOT_RUN'}


def fixture_plugins(source):
    root = ET.parse(source / 'pom.xml').getroot()
    properties = {p.tag.removeprefix(P): (p.text or '').strip() for p in root.find('m:properties', NS)}
    result = {}
    for owner in OWNERS:
        location = 'm:build/m:pluginManagement/m:plugins/m:plugin' if owner == 'assembly' else 'm:build/m:plugins/m:plugin'
        selected = [p for p in root.findall(location, NS) if p.findtext('m:artifactId', namespaces=NS) == 'maven-' + owner + '-plugin']
        require(len(selected) == 1, 'one source owner declaration')
        plugin = copy.deepcopy(selected[0])
        for node in plugin.iter():
            if not node.text: continue
            for _ in range(10):
                if '${' not in node.text: break
                node.text = re.sub(r'\$\{([^}]+)\}', lambda m: properties[m[1]], node.text)
            require('${' not in node.text, 'unresolved source plugin property')
        require(plugin.findtext('m:version', namespaces=NS) == OWNERS[owner].split(':')[-1], 'fixed owner version')
        deps = plugin.findall('m:dependencies/m:dependency', NS)
        lang = [d for d in deps if d.findtext('m:groupId', namespaces=NS) == 'org.apache.commons'
                and d.findtext('m:artifactId', namespaces=NS) == 'commons-lang3']
        require(len(lang) == 1 and lang[0].findtext('m:version', namespaces=NS) == '3.18.0', 'one source candidate private Lang override')
        if owner == 'assembly':
            require(len(deps) == 2 and any(d.findtext('m:artifactId', namespaces=NS) == 'plexus-utils'
                    and d.findtext('m:version', namespaces=NS) == '4.0.3' for d in deps), 'accepted Assembly Plexus dependency')
            require(plugin.find('m:configuration', NS) is None and plugin.find('m:executions', NS) is None, 'inactive plain Assembly source declaration')
        else:
            require(len(deps) == 1 and plugin.find('m:configuration', NS) is None, 'only Enforcer Lang dependency and execution configuration')
            executions = plugin.findall('m:executions/m:execution', NS)
            require(len(executions) == 1 and executions[0].findtext('m:id', namespaces=NS) == 'enforce-toolchain', 'actual toolchain execution')
            require([g.text for g in executions[0].findall('m:goals/m:goal', NS)] == ['enforce'], 'exact Enforcer goal')
            rules = executions[0].find('m:configuration/m:rules', NS)
            require(rules is not None and [(r.tag.removeprefix(P), r.findtext('m:version', namespaces=NS)) for r in rules]
                    == [('requireMavenVersion', '[3.9.6,)'), ('requireJavaVersion', '[21,22)')], 'actual two source toolchain rules')
            require(len(list(executions[0].find('m:configuration', NS))) == 1, 'no extra Enforcer execution configuration')
        result[owner] = plugin
    return result


def variant_plugin(source, owner, variant):
    require(owner in OWNERS and variant in VERSIONS, 'fixed owner and variant')
    plugin = fixture_plugins(source)[owner]
    for dep in plugin.findall('m:dependencies/m:dependency', NS):
        if dep.findtext('m:artifactId', namespaces=NS) == 'commons-lang3': dep.find('m:version', NS).text = VERSIONS[variant]
    return plugin


def enforcer_case(directory, plugin, case):
    require(case in CASES['enforcer'], 'fixed Enforcer case'); directory.mkdir()
    plugin = copy.deepcopy(plugin)
    rules = plugin.find('m:executions/m:execution/m:configuration/m:rules', NS)
    if case == 'bad-maven': rules.find('m:requireMavenVersion/m:version', NS).text = '[4,)'
    if case == 'bad-java': rules.find('m:requireJavaVersion/m:version', NS).text = '[22,23)'
    ET.register_namespace('', NS['m']); root = ET.Element(P + 'project')
    for key, value in [('modelVersion', '4.0.0'), ('groupId', 'fixture'), ('artifactId', 'enforcer-fixture'), ('version', '1'), ('packaging', 'pom')]: A['child'](root, P + key, value)
    A['child'](A['child'](root, P + 'build'), P + 'plugins').append(plugin)
    path = directory / 'pom.xml'; path.write_bytes(A['xml'](root))
    return {str(path): digest(path)}


def source_hashes(source):
    fixture_plugins(source)
    poms = sorted(source.rglob('pom.xml'))
    require(1 <= len(poms) <= 64 and all(p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(source)
            and p.stat().st_size <= 1024 * 1024 and not (p.parent / '.mvn').exists() for p in poms), 'bounded source POM inventory')
    files = poms + [Path(__file__).resolve(), PINS, CONSUMERS, OBSERVER, SPY,
        HERE / 'assembly-plexus-compatibility.py', HERE / 'assembly_plexus_inventory.py',
        HERE / 'assembly-plexus-artifact-pins.json', HERE / 'assembly-plexus-consumers.json',
        HERE / 'checkstyle_live_support.py', HERE / 'run_boot_live_compatibility.py',
        ROOT / 'scripts/ci/jar_plugin_retained_source.py',
        ROOT / 'scripts/ci/assembly_plexus_contract.py', HERE.parent / 'assembly-enforcer-lang-harness.test.mjs']
    # Seal every repository Python module actually imported by the reused helpers.
    files += [p for p in python_origins()[1] if p.is_relative_to(ROOT)]
    return hashes(files)


def checked_paths(config):
    require(set(config) == {'source', 'maven', 'jdk', 'repository', 'output'}, 'exact configuration keys')
    paths = {k: Path(v) for k, v in config.items()}
    require(all(p.is_absolute() and p.resolve() == p and not any(c.isspace() or ord(c) < 32 for c in str(p)) for p in paths.values()), 'canonical JVM-safe paths')
    require(paths['output'].parent.is_dir(), 'existing output parent')
    for base in [paths[k] for k in ('source', 'jdk', 'repository')] + [paths['maven'].parent.parent]:
        require(not paths['output'].is_relative_to(base) and not base.is_relative_to(paths['output']), 'output/input overlap')
    require(not any((p / '.mvn').exists() for k in ('source', 'output') for p in [paths[k], *paths[k].parents]), 'unexpected Maven bootstrap configuration')
    return paths


def runtime_hashes(maven, jdk):
    require(maven.name == 'mvn' and (maven.parent.parent / 'lib/maven-core-3.9.16.jar').is_file(), 'Maven 3.9.16 required')
    require('JAVA_VERSION="21.' in (jdk / 'release').read_text(), 'JDK 21 required')
    # The fixture itself is bound by sourceHashes. Exclude only the caller's
    # __main__ file so an inert preparation wrapper and the direct CLI seal the
    # same runtime; imported modules, Python, JDK and Maven remain fully bound.
    main = getattr(sys.modules['__main__'], '__file__', None)
    caller = Path(main).resolve() if main else None
    imported = [p for p in python_origins()[1] if p != caller]
    return hashes(runtime_files(jdk, maven, imported))


def prepare(config):
    paths = checked_paths(config); require(not paths['output'].exists(), 'fresh output required')
    pins = read_pins()
    for coordinate, expected in pins['artifactHashes'].items():
        path = artifact_path(paths['repository'], coordinate)
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(paths['repository']) and digest(path) == expected, 'pinned cache artifact: ' + coordinate)
    observed = inventory(paths['repository']); expected = json.loads(CONSUMERS.read_text())
    require(all(observed[k] == expected[k] for k in observed), 'complete current consumer inventory differs')
    return {'schemaVersion': 1, 'plan': PLAN, 'config': config, 'inventory': observed,
        'sourceHashes': source_hashes(paths['source']), 'runtimeHashes': runtime_hashes(paths['maven'], paths['jdk']),
        'cacheHashes': cache_snapshot(paths['repository']), 'classUtilsStatic': classutils_static(paths['repository'])}


def check_seal(seal, complete=False):
    require(seal['schemaVersion'] == 1 and seal['plan'] == PLAN, 'reviewed bounded seal required')
    paths = checked_paths(seal['config']); expected = json.loads(CONSUMERS.read_text())
    require(seal['inventory'] == {k: expected[k] for k in ('consumers', 'memberUses')}, 'sealed inventory differs')
    require(source_hashes(paths['source']) == seal['sourceHashes'], 'source changed')
    if complete:
        require(cache_snapshot(paths['repository']) == seal['cacheHashes'], 'cache changed')
        require(runtime_hashes(paths['maven'], paths['jdk']) == seal['runtimeHashes'], 'runtime changed')
        for coordinate, expected in read_pins()['artifactHashes'].items(): require(digest(artifact_path(paths['repository'], coordinate)) == expected, 'artifact changed')
        require(inventory(paths['repository']) == seal['inventory'] and classutils_static(paths['repository']) == seal['classUtilsStatic'], 'static inputs changed')


def linkage_rows(seal, owner):
    selected = [copy.deepcopy(c) for c in seal['inventory']['consumers'] if c['owner'] == owner]
    for c in selected: c['sourcePath'] = str(artifact_path(Path(seal['config']['repository']), c['coordinate']))
    return A['linkage_rows']({'inventory': {'consumers': selected},
        'memberUses': [row[1:] for row in seal['inventory']['memberUses'] if row[0] == owner]})


def maven_command(config, sandbox, project, owner):
    require(owner in GOALS, 'only exact owner direct goals'); inside(project, sandbox)
    return [config['maven'], '-o', '-B', '-ntp', '-N', '-X', '--strict-checksums',
        '-s', str(sandbox / 'empty-settings.xml'), '-gs', str(sandbox / 'empty-settings.xml'),
        '-t', str(sandbox / 'empty-toolchains.xml'), '-gt', str(sandbox / 'empty-toolchains.xml'),
        '-Dmaven.repo.local=' + config['repository'], '-Dstyle.color=never',
        '-Dmaven.ext.class.path=' + str(sandbox / 'completion-spy.jar'),
        '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-f', str(project), GOALS[owner]]


def controlled_resource_result(stdout, stderr, directory):
    """Require the real ResourcesFiltering call and its exact logging branch.

    Assembly archive filtering uses MavenReaderFilter; none of these receipts
    are natural goal evidence. Keep the copied file outside archive outputs.
    """
    message = '[INFO] Copying 1 resource from filter to controlled-resources/nested'
    copying = [line for line in stdout.splitlines() if re.match(r'^\[INFO\] Copying [0-9]+ resources?(?: |$)', line)]
    require(copying == [message], 'exact controlled from/to log required; fallback may conceal StringUtils exception')
    relative = 'controlled-resources/nested/value.txt'; output = directory / relative
    require(output.is_file() and not output.is_symlink(), 'controlled resource output required')
    payload = output.read_bytes(); expected = (A['TEXT'] + '\n').encode()
    require(payload == expected and sorted(p.relative_to(directory / 'controlled-resources').as_posix()
            for p in (directory / 'controlled-resources').rglob('*') if p.is_file()) == ['nested/value.txt'], 'exact controlled resource payload and membership')
    lines = stderr.splitlines()
    terminal = 'PLUGIN_LANG_GOAL_TERMINAL\tMojoSucceeded'
    begin = 'PLUGIN_LANG_CONTROLLED_RESOURCE_BEGIN\tfilter/value.txt\t' + relative
    end = 'PLUGIN_LANG_CONTROLLED_RESOURCE_END\t' + relative + '\t' + hashlib.sha256(payload).hexdigest()
    complete = 'PLUGIN_LANG_CONTROLLED_COMPLETE\tPASSED'
    for value in (terminal, begin, end, complete): require(lines.count(value) == 1, 'one controlled resource phase receipt')
    require(lines.index(terminal) < lines.index(begin) < lines.index(end) < lines.index(complete), 'resource call must occur after real goal and before controlled completion')
    require(any(line.startswith('PLUGIN_LANG_TYPE\tCONTROLLED\torg/apache/commons/lang3/StringUtils\t')
                for line in lines[lines.index(begin) + 1:lines.index(end)]), 'controlled resource Lang origin required')
    return {'phase': 'controlled API after real goal', 'log': message, 'path': relative,
            'sha256': hashlib.sha256(payload).hexdigest(), 'bytes': len(payload)}


def enforcer_result(stdout, status, case, config):
    require(case in CASES['enforcer'], 'fixed Enforcer case')
    rules = ['org.apache.maven.enforcer.rules.version.RequireMavenVersion', 'org.apache.maven.enforcer.rules.version.RequireJavaVersion']
    outcomes = []
    for index, rule in enumerate(rules):
        failed = (case == 'bad-maven' and index == 0) or (case == 'bad-java' and index == 1)
        label = 'Rule ' + str(index) + ': ' + rule + (' failed' if failed else ' passed')
        expected = re.escape(label) + (r'[^\n]*' if failed else '')
        found = re.findall(r'^\[(?:INFO|ERROR)\] ' + expected + r'$', stdout, re.M)
        require(len(found) == 1, 'exact expected rule outcome: ' + label)
        opposite = 'Rule ' + str(index) + ': ' + rule + (' passed' if failed else ' failed')
        require(opposite not in stdout, 'contradictory rule outcome')
        outcomes.append({'rule': rule, 'status': 'failed' if failed else 'passed'})
    if case == 'success':
        require(status == 0 and 'BUILD SUCCESS' in stdout and 'BUILD FAILURE' not in stdout and '[ERROR]' not in stdout, 'both real rules must pass')
        message = None
    else:
        require(status == 1 and 'BUILD FAILURE' in stdout and 'BUILD SUCCESS' not in stdout, 'rejected toolchain must fail')
        if case == 'bad-maven': message = 'Detected Maven Version: 3.9.16 is not in the allowed range [4,).'
        else:
            version = re.search(r'^JAVA_VERSION="([^"]+)"$', (Path(config['jdk']) / 'release').read_text(), re.M)[1]
            parts = re.findall(r'\d+', version)[:4]
            normalized_version = '.'.join(parts[:3]) + ('-' + parts[3] if len(parts) > 3 else '')
            message = 'Detected JDK version ' + normalized_version + ' (JAVA_HOME=' + config['jdk'] + ') is not in the allowed range [22,23).'
        require('[ERROR] ' + message in stdout, 'exact rejected version, home and range diagnostic')
    return {'exitStatus': status, 'rules': outcomes, 'diagnostic': message}


def compare_pairs(variants):
    for owner in OWNERS:
        for case in CASES[owner]:
            require(variants['baseline'][owner][case]['behavior'] == variants['candidate'][owner][case]['behavior'],
                    'exact paired output changed: ' + owner + '/' + case)
    before, after = (variants[v]['assembly']['archives']['controlledResourceCopy'] for v in VERSIONS)
    require(before == after, 'controlled resource copy changed between private Lang variants')
    return {'allNinePairsExactlyEqual': True, 'archiveBytesAndMetadataEqual': True, 'controlledResourceCopyEqual': True}


class ClassBytesMismatch(ValueError):
    def __init__(self, diagnostic):
        super().__init__('observed effective class bytes differ')
        self.diagnostic = diagnostic


def require_class_hash(observed, expected, variant, owner, case, phase, name, origin, entry):
    if observed == expected: return
    require(variant in VERSIONS and owner in OWNERS and case in CASES[owner]
            and phase in ('NATURAL', 'CONTROLLED', 'SYMBOLIC') and re.fullmatch('[0-9a-f]{64}', expected),
            'fixed class mismatch diagnostic context')
    # Only approved relative artifact/class identifiers may reach the receipt.
    class_pattern = r'[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*'
    safe_class = len(name.encode('utf-8')) <= 1024 and re.fullmatch(class_pattern, name)
    safe_origin = len(origin.encode('utf-8')) <= 2048 and re.fullmatch(
        r'(?:repository|maven-lib)/(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.jar', origin)
    safe_entry = safe_class and entry in [name + '.class',
        *['META-INF/versions/' + str(v) + '/' + name + '.class' for v in range(9, 22)]]
    value = observed.encode('utf-8')
    diagnostic = {'variant': variant, 'owner': owner, 'case': case, 'phase': phase,
        'class': name if safe_class else 'REDACTED_INVALID_CLASS',
        'origin': origin if safe_origin else 'REDACTED_INVALID_ORIGIN',
        'entry': entry if safe_entry else 'REDACTED_INVALID_ENTRY', 'expectedSha256': expected,
        'expectedPrefixMatches': observed.startswith(expected)}
    if re.fullmatch('[0-9a-f]{64}', observed):
        diagnostic.update(observedSha256=observed, category='different-sha256')
    else:
        diagnostic.update(observedFieldBytes=len(value), observedFieldSha256=hashlib.sha256(value).hexdigest(),
            category='expected-digest-with-suffix' if observed.startswith(expected) else 'malformed-digest')
    raise ClassBytesMismatch(diagnostic)


def class_log_path(output, variant, owner, case):
    require(variant in VERSIONS and owner in OWNERS and case in CASES[owner], 'fixed class-load case')
    directory = output / 'class-load'
    require(directory.is_dir() and not directory.is_symlink() and directory.resolve() == directory,
            'owned class-load directory')
    path = directory / (variant + '-' + owner + '-' + case + '.log')
    require(not path.exists() and not path.is_symlink(), 'fresh separate JVM class-load log')
    return path


def read_class_log(path):
    maximum = PLAN['classLoadMaximumBytes']
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, 'bounded regular JVM class-load log')
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        opened = os.fstat(stream.fileno()); data = stream.read(maximum + 1); after = os.fstat(stream.fileno())
    identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
    require(stat.S_ISREG(opened.st_mode) and identity(before) == identity(opened) == identity(after)
            == identity(path.lstat()) and not path.is_symlink() and len(data) == before.st_size <= maximum,
            'class-load log changed during bounded read')
    return data.decode('utf-8'), {'path': 'class-load/' + path.name, 'bytes': len(data),
        'sha256': hashlib.sha256(data).hexdigest()}


def require_class_corroboration(class_log, name, uri):
    pattern = r'\[\d+(?:\.\d+)?s\]\[info\]\[class,load\] ' + re.escape(name.replace('/', '.') + ' source: ' + uri)
    require(any(re.fullmatch(pattern, line) for line in class_log.splitlines()), 'JVM class-load corroboration missing')


def verify_class_logs(output, commands, variants):
    expected = {variant + '-' + owner + '-' + case: (variant, owner, case)
        for variant in VERSIONS for owner in OWNERS for case in CASES[owner]}
    recorded = [command for command in commands if 'classLoadCorroborationLog' in command]
    require(len(recorded) == len(expected) and {command['case'] for command in recorded} == set(expected),
            'all 18 class-load command receipts required')
    directory = output / 'class-load'
    require(directory.is_dir() and not directory.is_symlink()
            and {path.name for path in directory.iterdir()} == {label + '.log' for label in expected},
            'exact final class-load file set')
    for command in recorded:
        label = command['case']; variant, owner, case = expected[label]
        require(read_class_log(directory / (label + '.log'))[1] == command['classLoadCorroborationLog']
                == variants[variant][owner][case]['classLoadCorroborationLog'], 'retained class-load evidence changed')


def observations(stdout, stderr, class_log, config, variant, owner, rows, case):
    prefix = 'PLUGIN_LANG_'; lines = stderr.splitlines()
    require(not any(line.startswith(prefix + name) for line in lines for name in ('FAILURE', 'OBSERVER_FAILURE')), 'observer or controlled/linkage failure')
    successful = case in ('archives', 'discovery', 'success')
    terminal = prefix + 'GOAL_TERMINAL\t' + ('MojoSucceeded' if successful else 'MojoFailed')
    require([line for line in lines if line.startswith(prefix + 'GOAL_TERMINAL\t')] == [terminal], 'one actual selected goal terminal event')
    repo = Path(config['repository']); lib = Path(config['maven']).parent.parent / 'lib'
    with zipfile.ZipFile(lib / 'maven-core-3.9.16.jar') as archive: exports = ET.fromstring(archive.read('META-INF/maven/extension.xml'))
    shared = {x.text for x in exports.findall('exportedArtifacts/exportedArtifact')}
    expected = [c for c in read_pins()['owners'][owner][variant] if ':'.join(c.split(':')[:2]) not in shared]
    marker = '[DEBUG] Populating class realm plugin>' + OWNERS[owner]
    starts = [i for i, line in enumerate(stdout.splitlines()) if line.strip() == marker]
    require(len(starts) == 1, 'one natural selected owner realm')
    included = []
    for line in stdout.splitlines()[starts[0] + 1:]:
        found = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not found: break
        included.append(found[1])
    require(len(included) == len(set(included)) and set(included) == set(expected), 'exact natural owner membership')
    urls = [line.split('\t') for line in lines if line.startswith(prefix + 'REALM_URL\t')]
    require(len(urls) == len(expected) and len({row[1] for row in urls}) == 1, 'one complete loader URL set')
    realm = urls[0][1]
    def uri_path(value):
        uri = urlparse(value)
        require(uri.scheme == 'file' and not uri.netloc and not uri.query and not uri.fragment, 'local CodeSource URL')
        return Path(unquote(uri.path))
    approved = {artifact_path(repo, c) for c in expected}
    require({uri_path(row[2]) for row in urls} == approved, 'exact actual private realm URLs')
    lang = artifact_path(repo, LANG_PREFIX + VERSIONS[variant])
    records = {'NATURAL': [], 'CONTROLLED': [], 'SYMBOLIC': []}
    def origin(phase, name, loader, loader_name, uri, class_hash):
        path = uri_path(uri)
        require(path in approved or path.parent == lib, 'unexplained class origin')
        entry, expected_hash = A['effective_class'](path, name)
        relative = 'repository/' + path.relative_to(repo).as_posix() if path in approved else 'maven-lib/' + path.name
        require_class_hash(class_hash, expected_hash, variant, owner, case, phase, name, relative, entry)
        require_class_corroboration(class_log, name, uri)
        if path in approved: require(loader == realm and ('plugin>' + OWNERS[owner]) in loader_name, 'private defining loader differs')
        if loader == realm and name.startswith('org/apache/commons/lang3/'): require(path == lang, 'wrong private Lang provider')
        return {'phase': phase, 'class': name, 'loader': loader, 'realm': loader_name, 'path': str(path), 'entry': entry, 'sha256': class_hash}
    for line in lines:
        for phase in records:
            if line.startswith(prefix + phase + '_LOAD\t'):
                fields = line.split('\t'); require(len(fields) == 6, 'exact load observation shape')
                records[phase].append(origin(phase, *fields[1:]))
    mojo = 'org/apache/maven/plugins/' + ('assembly/mojos/SingleAssemblyMojo' if owner == 'assembly' else 'enforcer/EnforceMojo')
    required = {mojo}
    if owner == 'assembly':
        required.add('org/apache/maven/plugins/assembly/io/DefaultAssemblyReader')
        if successful: required |= {'org/apache/maven/plugins/assembly/format/ReaderFormatter',
            'org/apache/maven/shared/filtering/DefaultMavenReaderFilter', 'org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering'}
    else:
        required |= {'org/apache/maven/enforcer/rules/version/RequireMavenVersion',
            'org/apache/maven/enforcer/rules/version/RequireJavaVersion', 'org/apache/commons/lang3/SystemUtils'}
    natural_private = {r['class'] for r in records['NATURAL'] if r['loader'] == realm}
    require(required <= natural_private, 'required natural goal classes missing: ' + str(sorted(required - natural_private)))
    typed = []
    for line in lines:
        if line.startswith(prefix + 'TYPE\t'):
            fields = line.split('\t'); require(len(fields) == 7 and fields[1] in ('CONTROLLED', 'SYMBOLIC'), 'separate explicit origin phase')
            typed.append(origin(*fields[1:]))
    actual_consumers = [line.split('\t', 1)[1] for line in lines if line.startswith(prefix + 'CONSUMER\t')]
    require(actual_consumers == [row for row in rows if row.startswith('consumer\t')], 'all consumer source and class-byte receipts required')
    member_rows = ['\t'.join(line.split('\t')[1:-1]) for line in lines if line.startswith(prefix + 'MEMBER\t')]
    require(member_rows == [row for row in rows if row.startswith('member\t')], 'exact descriptors, access and invocation kinds required')
    receipt = prefix + 'LINKAGE_COMPLETE\t' + str(sum(r.startswith('consumer\t') for r in rows)) + '\t' + str(sum(r.startswith('type\t') for r in rows)) + '\t' + str(len(member_rows)) + '\t' + hashlib.sha256(('\n'.join(rows) + '\n').encode()).hexdigest()
    require(lines.count(receipt) == 1 and lines.count(prefix + 'MISSING_MEMBER\tPASSED') == 1, 'complete linkage and negative control')
    controlled = case in ('archives', 'success')
    complete = prefix + 'CONTROLLED_COMPLETE\t' + ('PASSED' if controlled else 'SKIPPED')
    require([line for line in lines if line.startswith(prefix + 'CONTROLLED_COMPLETE\t')] == [complete], 'controlled phase completion')
    passes = [line.split('\t', 1)[1] for line in lines if line.startswith(prefix + 'CONTROLLED_PASS\t')]
    expected_passes = ['ClassUtils int', 'ClassUtils int[]', 'ClassUtils java.lang.String[][]',
        'ClassUtils java.util.Map.Entry', 'ClassUtils null', 'ClassUtils missing and bounded invalid names'] if controlled else []
    if controlled and owner == 'assembly': expected_passes[:0] = [
        'DefaultMavenResourcesFiltering filtered UTF-8 copy and StringUtils.isEmpty log branch',
        'FilteringUtils relative path and SystemUtils.IS_OS_WINDOWS']
    require(passes == expected_passes, 'complete bounded controlled API contract')
    require(lines.index(terminal) < lines.index(complete) < lines.index(receipt), 'natural, controlled and symbolic phase order')
    terminal_at, controlled_at = lines.index(terminal), lines.index(complete)
    for index, line in enumerate(lines):
        if line.startswith(prefix + 'NATURAL_LOAD\t'): require(index < terminal_at, 'natural observation after goal')
        if line.startswith((prefix + 'CONTROLLED_LOAD\t', prefix + 'CONTROLLED_PASS\t', prefix + 'TYPE\tCONTROLLED\t')):
            require(terminal_at < index < controlled_at, 'controlled observation outside its phase')
        if line.startswith((prefix + 'SYMBOLIC_LOAD\t', prefix + 'TYPE\tSYMBOLIC\t')):
            require(index > controlled_at, 'symbolic observation before controlled completion')
    symbolic = [r for r in typed if r['phase'] == 'SYMBOLIC']
    for row in rows:
        fields = row.split('\t')
        if fields[0] == 'consumer':
            require(any(r['class'] == fields[1] and r['loader'] == realm and r['path'] == str(uri_path(fields[2]))
                        and r['sha256'] == fields[3] for r in symbolic), 'consumer origin lacks independent symbolic receipt')
    targets = {row.split('\t')[2] for row in rows if row.startswith(('type\t', 'member\t'))}
    targets.add('org/apache/commons/lang3/ClassUtils')
    require(all(any(r['class'] == name and r['loader'] == realm and r['path'] == str(lang) for r in symbolic)
                for name in targets), 'symbolic targets must resolve through selected private Lang')
    if controlled:
        require(any(r['phase'] == 'CONTROLLED' and r['class'] == 'org/apache/commons/lang3/ClassUtils'
                    and r['loader'] == realm and r['path'] == str(lang) for r in typed), 'controlled ClassUtils origin required')
        if owner == 'assembly':
            require(any(r['phase'] == 'CONTROLLED' and r['class'] == 'org/apache/commons/lang3/StringUtils'
                        and r['loader'] == realm and r['path'] == str(lang) for r in typed), 'controlled resource StringUtils origin required')
    return {'realm': included, 'naturalOrigins': records['NATURAL'], 'controlledLoads': records['CONTROLLED'],
        'symbolicLoads': records['SYMBOLIC'], 'explicitOrigins': typed, 'requiredNaturalPrivateClasses': sorted(required),
        'controlledAssertions': passes, 'linkageMemberCount': len(member_rows), 'linkageReceipt': receipt,
        'missingMemberControl': 'PASSED', 'symbolicResolutionIsInvocationClaim': False,
        'naturalResourcesFilteringLoad': 'dependency injection only; archive filtering uses MavenReaderFilter' if owner == 'assembly' else None}


def execute(seal):
    require(os.environ.get('ASSEMBLY_ENFORCER_LANG_COMPATIBILITY') == 'true', 'explicit independently reviewed execution opt-in required')
    check_seal(seal, complete=True)
    config = seal['config']; output = Path(config['output']); jdk = Path(config['jdk'])
    require(not output.exists(), 'fresh reviewed output'); output.mkdir()
    for name in ('home', 'tmp', 'observer', 'class-load'): (output / name).mkdir()
    (output / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    (output / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    rows = {owner: linkage_rows(seal, owner) for owner in OWNERS}
    for owner in OWNERS: (output / (owner + '-members.tsv')).write_text('\n'.join(rows[owner]) + '\n')
    fixed = {str(p): digest(p) for p in output.iterdir() if p.is_file()}
    environment = isolated_env(jdk, output); environment['MAVEN_BASEDIR'] = str(output)
    commands = Commands(output, environment, PLAN['aggregateTimeoutSeconds'])
    storage = StorageGuard(output / 'class-load', PLAN['classLoadTotalMaximumBytes'], PLAN['classLoadMaximumEntries'],
        {variant + '-' + owner + '-' + case + '.log': PLAN['classLoadMaximumBytes']
            for variant in VERSIONS for owner in OWNERS for case in CASES[owner]})
    result = {'status': 'RUNNING', 'plan': PLAN, 'commands': commands.results, 'variants': {},
        'sourceHashes': seal['sourceHashes'], 'runtimeHashes': seal['runtimeHashes'], 'cacheHashes': seal['cacheHashes'],
        'inventory': seal['inventory'], 'classUtilsStatic': seal['classUtilsStatic'], 'scannerEvidence': False,
        'productionActivationClaim': False, 'behaviorLimits': 'Pack200, vendor, checksum and dependency rules are symbolic-only; a natural load alone does not prove a method invocation.'}
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame): raise TimeoutError('bounded 18-goal aggregate deadline or termination')
    def run(label, command, seconds=60, extra_env=None):
        check_seal(seal)
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        storage.start()
        try:
            value = commands.run(label, command, seconds, extra_env)
        except BaseException as failure:
            if storage.failure is not None: raise ValueError(storage.failure) from failure
            raise
        finally:
            storage.stop()
        require(storage.failure is None, 'storage watcher violation: ' + str(storage.failure))
        storage.scan()
        check_seal(seal)
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        return value
    for sig in previous: signal.signal(sig, deadline)
    signal.alarm(PLAN['aggregateTimeoutSeconds'])
    try:
        status, _, _ = run('compile-observer', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
            '-cp', os.pathsep.join(str(p) for p in sorted((Path(config['maven']).parent.parent / 'lib').glob('*.jar'))),
            '-d', str(output / 'observer'), str(OBSERVER), str(SPY)], PLAN['compileTimeoutSeconds'])
        require(status == 0, 'observer compilation')
        with zipfile.ZipFile(output / 'observer.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: PluginLangRealmObserver\n\n')
            for path in sorted((output / 'observer').glob('PluginLangRealmObserver*.class')): archive.write(path, path.name)
        with zipfile.ZipFile(output / 'completion-spy.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
            archive.writestr('META-INF/plexus/components.xml', '<component-set><components><component>'
                '<role>org.apache.maven.eventspy.EventSpy</role><role-hint>assembly-enforcer-lang</role-hint>'
                '<implementation>PluginLangRealmCompletionSpy</implementation></component></components></component-set>')
            archive.write(output / 'observer/PluginLangRealmCompletionSpy.class', 'PluginLangRealmCompletionSpy.class')
        fixed.update({str(output / name): digest(output / name) for name in ('observer.jar', 'completion-spy.jar')})
        for variant in VERSIONS:
            result['variants'][variant] = {}
            for owner in OWNERS:
                result['variants'][variant][owner] = {}
                plugin = variant_plugin(Path(config['source']), owner, variant)
                for case in CASES[owner]:
                    label = variant + '-' + owner + '-' + case; directory = output / label
                    fixed.update(A['write_case'](directory, plugin, case) if owner == 'assembly' else enforcer_case(directory, plugin, case))
                    opts = '-Xmx256m -XX:-UsePerfData -Duser.home=' + str(output / 'home') + ' -Djava.io.tmpdir=' + str(output / 'tmp')
                    class_path = class_log_path(output, variant, owner, case)
                    opts += ' -javaagent:' + str(output / 'observer.jar') + '=' + str(output / (owner + '-members.tsv'))
                    opts += ' -Xlog:class+load=info:file=' + str(class_path) + ':uptime,level,tags:filecount=0'
                    opts += ' -Dfixture.lang.owner=' + owner + ' -Dfixture.lang.mode=' + ('api' if case in ('archives', 'success') else 'none')
                    opts += ' -Dfixture.lang.work=' + str(directory) + ' -Dfixture.lang.jar=' + str(artifact_path(Path(config['repository']), LANG_PREFIX + VERSIONS[variant]))
                    status, stdout, stderr = run(label, maven_command(config, output, directory / 'pom.xml', owner), extra_env={'MAVEN_OPTS': opts})
                    class_log, class_receipt = read_class_log(class_path)
                    commands.results[-1]['classLoadCorroborationLog'] = class_receipt
                    try:
                        observed = observations(stdout, stderr, class_log, config, variant, owner, rows[owner], case)
                    finally:
                        require(read_class_log(class_path)[1] == class_receipt, 'class-load log changed during observations')
                    observed['classLoadCorroborationLog'] = class_receipt
                    if owner == 'enforcer': behavior = enforcer_result(stdout, status, case, config)
                    elif case in ('archives', 'discovery'):
                        require(status == 0, 'real Assembly goal failed')
                        # Both variants retain the accepted Plexus 4.0.3 rules.
                        if case == 'archives':
                            behavior = A['archive_result'](directory, 'candidate')
                            observed['controlledResourceCopy'] = controlled_resource_result(stdout, stderr, directory)
                        else:
                            behavior = A['discovery_result'](directory)
                            behavior['archives'] = {name: {'sha256': digest(directory / 'target' / name),
                                **A['archive_members'](directory / 'target' / name)} for name in behavior['outputFiles']}
                    else: behavior = A['failure_result'](directory, stdout, status, case)
                    result['variants'][variant][owner][case] = {'exitStatus': status, 'behavior': behavior, **observed}
        result['comparison'] = compare_pairs(result['variants'])
        for variant in VERSIONS:
            for owner in OWNERS:
                loaded = {row['class'] for case in result['variants'][variant][owner].values() for row in case['naturalOrigins']}
                consumers = {c['classEntry'].removesuffix('.class') for c in seal['inventory']['consumers'] if c['owner'] == owner}
                result.setdefault('coverage', {}).setdefault(variant, {})[owner] = {
                    'naturallyLoadedConsumers': sorted(consumers & loaded), 'notNaturallyLoadedConsumers': sorted(consumers - loaded),
                    'symbolicallyResolvedConsumers': len(consumers), 'naturalLoadIsMemberInvocationClaim': False}
        require(len(commands.results) == 19 and all(r['captureComplete'] and r['processGroupCleaned'] for r in commands.results), 'one compile and 18 cleaned real goals')
        verify_class_logs(output, commands.results, result['variants'])
        check_seal(seal, complete=True); result.update(status='PASSED', inputSealsUnchanged=True, generatedInputHashes=fixed)
    except BaseException as failure:
        if isinstance(failure, ClassBytesMismatch): result['classObservationMismatch'] = failure.diagnostic
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        storage.stop(); result['classLoadStorage'] = storage.report()
        signal.alarm(0)
        for sig, handler in previous.items(): signal.signal(sig, handler)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('ASSEMBLY_ENFORCER_LANG_RESULT ' + json.dumps({'status': result['status'], 'path': str(output / 'result.json'), 'error': result.get('error')}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-seal', type=Path); parser.add_argument('--execute-seal', type=Path)
    for name in ('source', 'maven', 'jdk', 'repository', 'output'): parser.add_argument('--' + name)
    args = parser.parse_args()
    if args.prepare_seal:
        require(not args.execute_seal and not args.prepare_seal.exists(), 'fresh preparation seal')
        config = {k: v for k, v in vars(args).items() if k not in ('prepare_seal', 'execute_seal')}
        seal = prepare(config); args.prepare_seal.write_text(json.dumps(seal, indent=2) + '\n')
        print(json.dumps({'status': 'PREPARED_NOT_EXECUTED', 'seal': str(args.prepare_seal), 'sourceHashes': seal['sourceHashes']}))
    elif args.execute_seal:
        before = digest(args.execute_seal)
        try: execute(json.loads(args.execute_seal.read_text()))
        finally: require(digest(args.execute_seal) == before, 'reviewed seal file changed')
    else: print(json.dumps(PLAN))


if __name__ == '__main__': main()
