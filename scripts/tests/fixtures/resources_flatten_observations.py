"""Validate real ordered events, class definitions, and full symbolic receipts."""
import base64
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def require(ok, message):
    if not ok:
        raise ValueError('Resources/Flatten observation: ' + message)


def local_url(value):
    parsed = urlparse(value)
    require(parsed.scheme == 'file' and not parsed.netloc and not parsed.query and not parsed.fragment, 'local CodeSource')
    path = Path(unquote(parsed.path))
    require(path.resolve() == path and path.is_file() and not path.is_symlink(), 'real CodeSource file')
    return path


def artifact(repository, coordinate):
    group, name, kind, version = coordinate.split(':')
    return repository / group.replace('.', '/') / name / version / (name + '-' + version + '.' + kind)


def definition(path, name):
    with zipfile.ZipFile(path) as archive:
        entry = name + '.class'
        if 'META-INF/MANIFEST.MF' in archive.namelist() and b'multi-release: true' in archive.read('META-INF/MANIFEST.MF').lower():
            for version in range(21, 8, -1):
                candidate = 'META-INF/versions/' + str(version) + '/' + entry
                if candidate in archive.namelist():
                    entry = candidate
                    break
        info = archive.getinfo(entry)
        require(info.file_size <= 2 * 1024 * 1024, 'bounded effective class')
        return entry, hashlib.sha256(archive.read(entry)).hexdigest()


def source_state(data):
    root = ET.fromstring(data)
    parent = root.find('m:parent', NS)
    value = lambda node, name: node.findtext('m:' + name, namespaces=NS) if node is not None else None
    fields = {'originalGroupId': value(root, 'groupId'), 'originalArtifactId': value(root, 'artifactId'),
              'originalVersion': value(root, 'version'), 'originalPackaging': value(root, 'packaging') or 'jar',
              'originalParentGroupId': value(parent, 'groupId'), 'originalParentArtifactId': value(parent, 'artifactId'),
              'originalParentVersion': value(parent, 'version'), 'originalParentRelativePath': value(parent, 'relativePath')}
    props = root.find('m:properties', NS)
    return fields, {} if props is None else {p.tag.rsplit('}', 1)[-1]: p.text or '' for p in props}


def check(stdout, stderr, class_log, config, pins, inventory, variant, owner, terminal_rows,
          work, fixture=None, replay='none', successful=True, natural_goals=()):
    lines = stderr.splitlines()
    require('RF_FAILURE' not in stdout + stderr, 'observer failure')
    require('Failed to notify spy ResourcesFlattenCompletionSpy' not in stdout + stderr,
            'Maven swallowed a selected observer exception')
    require(not any(line.startswith('RF_') and '\ufffd' in line for line in lines), 'corrupt receipt text')
    repo = Path(config['repository']); lib = Path(config['maven']).parent.parent / 'lib'
    coordinates = pins['owners'][owner][variant]
    selected_expected = [c for c in coordinates if ':'.join(c.split(':')[:2]) not in pins['coreExportedArtifacts']]
    expected_paths = {artifact(repo, c) for c in selected_expected}
    approved = {artifact(repo, c) for c in pins['artifactHashes']}
    rows = inventory['effectiveLinkageRows']
    row_data = ('\n'.join(rows) + '\n').encode()
    terminal_data = ('\n'.join(terminal_rows) + '\n').encode()
    version = '4.0.2' if variant == 'baseline' else '4.0.3'
    plan = 'RF_PLAN\t' + owner + '\t' + version + '\t' + replay + '\t' + str(len(terminal_rows))
    plan += '\t' + hashlib.sha256(terminal_data).hexdigest() + '\t' + hashlib.sha256(row_data).hexdigest()
    require([l for l in lines if l.startswith('RF_PLAN\t')] == [plan], 'exact reviewed plan receipt')
    realms = {}
    for line in lines:
        if line.startswith('RF_REALM_URL\t'):
            p = line.split('\t'); require(len(p) == 4, 'realm URL record shape')
            realms.setdefault(p[1], {'name': p[2], 'urls': []})['urls'].append(local_url(p[3]))
    require(1 <= len(realms) <= 8, 'bounded selected native realms')
    debug = stdout.splitlines()
    for realm in realms.values():
        require(len(realm['urls']) == len(set(realm['urls'])) and set(realm['urls']) == expected_paths,
                'complete selected realm membership')
        match = re.search(r'ClassRealm\[([^,]+),', realm['name'])
        require(match and match[1].startswith('plugin>' + pins['owners'][owner]['coordinate'].replace(':jar:', ':')), 'native owner realm name')
        marker = '[DEBUG] Populating class realm ' + match[1]
        starts = [i for i, text in enumerate(debug) if text.strip() == marker]
        require(len(starts) == 1, 'exact native realm debug block')
        included = []
        for text in debug[starts[0] + 1:]:
            found = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', text)
            if not found:
                break
            included.append(found[1])
        require(set(included) == set(selected_expected) and len(included) == len(set(included)), 'debug closure equals selected owner')
        require([artifact(repo, c) for c in included] == realm['urls'], 'debug order equals actual native URLs')

    def origin(phase, name, loader, loader_name, uri, expected_hash):
        path = local_url(uri)
        require(path in approved or path.parent == lib, 'unexplained class provider')
        entry, actual_hash = definition(path, name)
        require(expected_hash == actual_hash, 'effective definition byte mismatch')
        pattern = re.compile(r'\[\d+(?:\.\d+)?s\]\[info\]\[class,load\] '
                             + re.escape(name.replace('/', '.') + ' source: ' + uri))
        require(any(pattern.fullmatch(line) for line in class_log.splitlines()), 'independent JVM class-load corroboration')
        if loader in realms:
            require(loader_name == realms[loader]['name'] and path in expected_paths, 'selected defining realm/provider')
        return {'phase': phase, 'class': name, 'loader': loader, 'loaderName': loader_name,
                'path': str(path), 'entry': entry, 'sha256': actual_hash}

    observed = {phase: [] for phase in ('NATURAL', 'CONTROLLED', 'SYMBOLIC', 'AFTER')}
    for index, line in enumerate(lines):
        for phase in observed:
            if line.startswith('RF_' + phase + '_LOAD\t'):
                p = line.split('\t'); require(len(p) == 6, 'definition record shape')
                row = origin(phase, *p[1:]); row['line'] = index; observed[phase].append(row)
    typed = []
    for index, line in enumerate(lines):
        if line.startswith('RF_TYPE\t'):
            p = line.split('\t')
            require(len(p) == 8 and p[1] in ('CONTROLLED', 'SYMBOLIC') and p[2] in realms, 'explicit type record shape')
            row = origin(p[1], *p[3:]); row['selectedRealm'] = p[2]; row['line'] = index; typed.append(row)

    events = [l.split('\t') for l in lines if l.startswith('RF_EVENT\t')]
    require(len(events) == 2 * len(terminal_rows), 'every actual goal started and terminated')
    states = []
    for index, expected in enumerate(terminal_rows):
        p = expected.split('\t'); start, end = events[index * 2:index * 2 + 2]
        for event, event_type in [(start, 'MojoStarted'), (end, p[5])]:
            require(len(event) == 11 and event[1] == str(index) and event[2] == event_type
                    and event[3:8] == p[:5]
                    and (event[8] in realms or event_type == 'MojoStarted' and event[8] == 'PENDING'),
                    'exact event identity/order/outcome')
        require(start[9] == end[9] and (start[8] == 'PENDING' or start[8] == end[8]),
                'terminal binds pending or identical native realm and same implementation')
        a, z = [json.loads(base64.b64decode(e[10], validate=True)) for e in (start, end)]
        require(a['project']['projectIdentity'] == z['project']['projectIdentity'], 'same MavenProject object')
        require(a['project']['projectId'] == z['project']['projectId'], 'stable same-process project identity')
        if states and states[-1]['expected'][4] == p[4]:
            require(states[-1]['after'] == a, 'same-project next goal does not observe prior actual state')
        if owner == 'flatten' and fixture is not None:
            directory = work if p[4] == '.' else work / p[4]
            expected_project = fixture['projects'][str(directory)]
            if p[2] == 'flatten' and p[5] == 'MojoSucceeded':
                require(z['project']['projectFile'] == expected_project['target'], 'same-process project.file switch')
                require(a['project']['originalModelIdentity'] != z['project']['originalModelIdentity'], 'same-process originalModel replacement')
                wanted, properties = source_state(expected_project['flattened'])
                require(all(z['project'][k] == v for k, v in wanted.items()) and z['originalProperties'] == properties,
                        'actual originalModel equals full expected CI-only source model fields')
            elif p[2] == 'clean':
                require(a == z, 'clean changed MavenProject state')
            elif p[5] == 'MojoFailed':
                require(a == z, 'failed flatten changed MavenProject state')
        elif owner == 'resources':
            require(a == z, 'resources changed MavenProject state')
        states.append({'index': index, 'expected': p, 'before': a, 'after': z,
                       'realmAtStarted': start[8], 'realmAtTerminal': end[8]})

    outputs = [l.split('\t') for l in lines if l.startswith('RF_OUTPUT\t')]
    expected_output_indices = [i for i, r in enumerate(terminal_rows) if r.split('\t')[2] == 'flatten' and r.endswith('\tMojoSucceeded')]
    require([int(r[1]) for r in outputs] == expected_output_indices, 'one output snapshot per successful flatten')
    for row in outputs:
        require(len(row) == 7, 'output snapshot record shape')
        p = terminal_rows[int(row[1])].split('\t')
        directory = work if p[4] == '.' else work / p[4]
        expected = fixture['projects'][str(directory)]
        data = base64.b64decode(row[6], validate=True)
        require(row[2] == p[4] and base64.b64decode(row[3], validate=True).decode() == expected['target']
                and len(data) == int(row[4]) <= 262144 and hashlib.sha256(data).hexdigest() == row[5]
                and data == expected['flattened'], 'actual full-byte flattened output snapshot')

    goal_receipt = 'RF_GOALS_COMPLETE\t' + str(len(terminal_rows)) + '\t' + str(len(realms))
    controlled = 'RF_CONTROLLED_COMPLETE\t' + ('PASSED' if replay == 'api' else 'SKIPPED')
    complete = 'RF_COMPLETE\t' + owner + '\t' + str(len(terminal_rows)) + '\t' + str(len(realms))
    session = 'RF_SESSION_COMPLETE\t' + owner + '\t' + str(len(terminal_rows))
    require(all(lines.count(x) == 1 for x in (goal_receipt, controlled, complete, session)), 'complete phase/session receipts')
    require(lines.index(goal_receipt) < lines.index(controlled) < lines.index(complete) < lines.index(session), 'phase order')
    require(all(r['line'] < lines.index(goal_receipt) for r in observed['NATURAL']), 'natural load after replay boundary')
    require(all(lines.index(goal_receipt) < r['line'] < lines.index(controlled) for r in observed['CONTROLLED']), 'controlled phase contamination')
    require(all(lines.index(controlled) < r['line'] < lines.index(complete) for r in observed['SYMBOLIC']), 'symbolic phase contamination')
    for row in typed:
        lo, hi = ((lines.index(goal_receipt), lines.index(controlled)) if row['phase'] == 'CONTROLLED'
                  else (lines.index(controlled), lines.index(complete)))
        require(lo < row['line'] < hi, 'explicit type receipt outside its phase')
    controlled_passes = [line for line in lines if line.startswith('RF_CONTROLLED_PASS\t')]
    require(controlled_passes == (['RF_CONTROLLED_PASS\t' + realm + '\tStringUtils-and-SelectorUtils-bounded-API'
                                  for realm in realms] if replay == 'api' else []), 'exact per-realm controlled API receipts')
    require(all(lines.index(goal_receipt) < lines.index(line) < lines.index(controlled)
                for line in controlled_passes), 'controlled API receipt phase')
    expected_types = inventory['expectedTypeDefinitions'][variant]
    consumers = {c['className']: c for c in inventory['effectiveInventory']['consumers']}
    target_by_consumer = {(r['consumer'], r['className']): r for r in expected_types}
    for realm in realms:
        received_consumers = [l.split('\t', 2)[2] for l in lines if l.startswith('RF_CONSUMER\t' + realm + '\t')]
        require(received_consumers == [r for r in rows if r.startswith('consumer\t')], 'complete consumer receipts')
        received_members = [l.split('\t', 2)[2].rsplit('\t', 1)[0] for l in lines if l.startswith('RF_MEMBER\t' + realm + '\t')]
        require(received_members == [r for r in rows if r.startswith('member\t')], 'complete exact member descriptors and invocation kinds')
        received_types = [l.split('\t') for l in lines if l.startswith('RF_TYPE_LINK\t' + realm + '\t')]
        require(all(len(r) == 6 and re.fullmatch('[0-9a-f]+', r[-1]) for r in received_types), 'descriptor-only type receipt shape')
        require(['\t'.join(r[2:-1]) for r in received_types] == [r for r in rows if r.startswith('type\t')], 'complete descriptor-only type linkage receipts')
        for prefix in ('RF_CONSUMER\t', 'RF_MEMBER\t', 'RF_TYPE_LINK\t', 'RF_MISSING_MEMBER\t', 'RF_LINKAGE_COMPLETE\t'):
            require(all(lines.index(controlled) < index < lines.index(complete) for index, line in enumerate(lines)
                        if line.startswith(prefix + realm + '\t')), 'symbolic receipt outside its phase')
        receipt = 'RF_LINKAGE_COMPLETE\t' + realm + '\t' + '\t'.join(str(sum(r.startswith(k + '\t') for r in rows)) for k in ('consumer', 'type', 'member'))
        receipt += '\t' + hashlib.sha256(row_data).hexdigest()
        require(lines.count(receipt) == 1 and lines.count('RF_MISSING_MEMBER\t' + realm + '\tPASSED') == 1, 'full symbolic linkage/negative receipt')
        symbolic = [r for r in typed if r['phase'] == 'SYMBOLIC' and r['selectedRealm'] == realm]
        expected_sequence = []
        for row in rows:
            p = row.split('\t')
            expected_sequence.append(consumers[p[1]])
            if p[0] != 'consumer':
                expected_sequence.append(target_by_consumer[(p[1], p[2])])
        require(len(symbolic) == len(expected_sequence) + 1 and symbolic[-1]['class'] == 'org/codehaus/plexus/util/StringUtils', 'exact symbolic origin coverage')
        type_definers = []
        position = 0
        for row in rows:
            kind = row.split('\t', 1)[0]
            if kind == 'type':
                type_definers.append(symbolic[position + 1]['loader'])
            position += 1 if kind == 'consumer' else 2
        require([r[-1] for r in received_types] == type_definers, 'type-link actual target defining loader differs from observed definition')
        private_utils = artifact(repo, 'org.codehaus.plexus:plexus-utils:jar:' + version)
        require(symbolic[-1]['path'] == str(private_utils) and symbolic[-1]['loader'] == realm, 'negative member control private Utils provider')
        controlled_types = [r for r in typed if r['phase'] == 'CONTROLLED' and r['selectedRealm'] == realm]
        require([r['class'] for r in controlled_types] == (['org/codehaus/plexus/util/StringUtils',
                 'org/codehaus/plexus/util/SelectorUtils'] if replay == 'api' else []), 'exact controlled API type set')
        require(all(r['path'] == str(private_utils) and r['loader'] == realm for r in controlled_types), 'controlled API private Utils provider')
        for actual, expected in zip(symbolic, expected_sequence):
            require(actual['class'] == expected['className'] and actual['path'] == expected['sourcePath']
                    and actual['sha256'] == expected['classSha256'], 'actual defining provider/class bytes differ from semantic inventory')
            require((actual['loader'] == realm) == (expected['loaderRole'] == 'selected-plugin-realm'), 'core/private defining loader mismatch')
    natural = {r['class'] for r in observed['NATURAL'] if r['loader'] in realms}
    mojos = {'resources': 'org/apache/maven/plugins/resources/ResourcesMojo',
             'testResources': 'org/apache/maven/plugins/resources/TestResourcesMojo',
             'copy-resources': 'org/apache/maven/plugins/resources/CopyResourcesMojo',
             'flatten': 'org/codehaus/mojo/flatten/FlattenMojo', 'clean': 'org/codehaus/mojo/flatten/CleanMojo'}
    required = {mojos[g] for g in natural_goals}
    if owner == 'resources' and successful:
        required |= {'org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering',
                     'org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering$1',
                     *('org/codehaus/plexus/util/' + c for c in ('DirectoryScanner', 'AbstractScanner', 'MatchPatterns', 'MatchPattern', 'SelectorUtils'))}
    if owner == 'flatten' and 'flatten' in natural_goals:
        required.add('org/codehaus/plexus/util/StringUtils')
    require(required <= natural, 'required real goal/consumer chain not naturally loaded: ' + str(sorted(required - natural)))
    return {'nativeRealms': {k: {'name': v['name'], 'urls': [str(p) for p in v['urls']]} for k, v in realms.items()},
            'actualSelectedTerminalCount': len(terminal_rows), 'states': states,
            'naturalDefinitions': observed['NATURAL'], 'explicitDefinitions': typed,
            'requiredNaturalClasses': sorted(required), 'symbolicMemberCountPerRealm': sum(r.startswith('member\t') for r in rows),
            'symbolicResolutionIsInvocationClaim': False, 'naturalLoadingIsMemberInvocationClaim': False,
            'outputSnapshotCount': len(outputs), 'sessionComplete': True}
