"""Pure fresh inventory API. Reads only; no subprocess, network or filesystem writes."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

def sha(data):
    return hashlib.sha256(data).hexdigest()


def artifact(repository, c):
    g, a, kind, v = c.split(':')
    assert kind == 'jar'
    return repository / g.replace('.', '/') / a / v / (a + '-' + v + '.jar')


def effective_entry(archive, name):
    entry = name + '.class'
    manifest = archive.read('META-INF/MANIFEST.MF') if 'META-INF/MANIFEST.MF' in archive.namelist() else b''
    if b'multi-release: true' in manifest.lower():
        for version in range(21, 8, -1):
            candidate = 'META-INF/versions/' + str(version) + '/' + entry
            if candidate in archive.namelist():
                return candidate
    assert entry in archive.namelist(), (name, archive.filename)
    return entry


def fresh_inventory(source, repository, maven, pins_path=None):
    """Return JSON-normalized {pins, owners} rebuilt from current pinned JAR bytes.

    maven is the Maven3.9.16 bin/mvn path. Default pins_path is resources-flatten-artifact-pins.json
    beside this helper. The retained Jar seal is read from the supplied source.
    No repository bytecode cache is created when importing the reviewed parser.
    """
    SOURCE = Path(source).resolve()
    REPO = Path(repository).resolve()
    LIB = Path(maven).resolve().parent.parent / 'lib'
    PARSER = SOURCE / 'scripts/tests/fixtures/assembly_plexus_inventory.py'
    if pins_path is None:
        pins_path = Path(__file__).resolve().parent / 'resources-flatten-artifact-pins.json'
    artifact = lambda coordinate: globals()['artifact'](REPO, coordinate)
    seal = json.loads((SOURCE / 'docs/operations/jar-plugin-evidence/compatibility-local-seal.json').read_text())
    expected_pins = json.loads(Path(pins_path).read_text())
    assert expected_pins['schemaVersion'] == 'RESOURCES_FLATTEN_COMPATIBILITY_PINS_V1'
    review = {'affectedOwners': [{'owner': row['coordinate'], 'dependencies': row['baseline']} for row in expected_pins['owners'].values()]}
    assert set(expected_pins['owners']) == {'resources', 'flatten'}
    assert len(expected_pins['artifactHashes']) == 13
    for key, row in expected_pins['owners'].items():
        assert row['candidate'] == [c.replace('plexus-utils:jar:4.0.2', 'plexus-utils:jar:4.0.3') for c in row['baseline']]
        assert row['coordinate'] == ('org.apache.maven.plugins:maven-resources-plugin:jar:3.4.0' if key == 'resources' else 'org.codehaus.mojo:flatten-maven-plugin:jar:1.7.3')
    retained = {p.split('/m2-repository/', 1)[1]: h for p, h in seal['cacheHashes'].items() if '/m2-repository/' in p}
    coordinates = sorted({c for owner in review['affectedOwners'] for c in owner['dependencies']}
                         | {'org.codehaus.plexus:plexus-utils:jar:4.0.3'})
    artifacts = []
    for c in coordinates:
        file = artifact(c)
        assert file.is_file() and not file.is_symlink()
        data = file.read_bytes()
        digest = sha(data)
        assert digest == retained[file.relative_to(REPO).as_posix()] == expected_pins['artifactHashes'][c], c
        artifacts.append({'coordinate': c, 'path': str(file), 'bytes': len(data), 'sha256': digest,
                          'retainedPinMatches': True})
    runtime = []
    for name in ('maven-core-3.9.16.jar', 'plexus-utils-3.6.1.jar'):
        file = LIB / name
        digest = sha(file.read_bytes())
        expected = {h for p, h in seal['runtimeHashes'].items() if p.endswith('/' + name)}
        assert expected == {digest}, name
        assert {r['sha256'] for r in expected_pins['runtime'] if r['name'] == name} == {digest}
        runtime.append({'path': str(file), 'sha256': digest, 'retainedPinMatches': True})
    with zipfile.ZipFile(LIB / 'maven-core-3.9.16.jar') as z:
        extension_bytes = z.read('META-INF/maven/extension.xml')
        extension = ET.fromstring(extension_bytes)
    xml_imports = sorted(x.text.replace('.', '/') for x in extension.findall('exportedPackages/exportedPackage')
                         if x.text.startswith('org.codehaus.plexus.util.xml.'))
    shared_artifacts = sorted(x.text for x in extension.findall('exportedArtifacts/exportedArtifact'))
    assert sha(extension_bytes) == expected_pins['mavenCoreExtensionSha256']
    assert xml_imports == expected_pins['coreXmlExports']
    assert shared_artifacts == expected_pins['coreExportedArtifacts']
    pins = {'schemaVersion': 'RESOURCES_FLATTEN_COMPATIBILITY_PINS_V1', 'owners': {},
            'artifactHashes': {r['coordinate']: r['sha256'] for r in artifacts}, 'artifacts': artifacts,
            'runtime': runtime, 'mavenCoreExtensionSha256': sha(extension_bytes),
            'coreXmlExports': xml_imports, 'coreExportedArtifacts': shared_artifacts,
            'status': 'STATIC_READ_ONLY_NOT_RUNTIME_ACCEPTANCE'}

    spec = importlib.util.spec_from_file_location('reviewed_classfile_parser', PARSER)
    parser = importlib.util.module_from_spec(spec)
    old_no_bytecode = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        spec.loader.exec_module(parser)
    finally:
        sys.dont_write_bytecode = old_no_bytecode
    records = {}
    for owner in review['affectedOwners']:
        key = 'resources' if ':maven-resources-plugin:' in owner['owner'] else 'flatten'
        baseline = owner['dependencies']
        candidate = [c.replace('plexus-utils:jar:4.0.2', 'plexus-utils:jar:4.0.3') for c in baseline]
        pins['owners'][key] = {'coordinate': owner['owner'], 'baseline': baseline, 'candidate': candidate,
                              'expectedPrivateRealmBaseline': [c for c in baseline if ':'.join(c.split(':')[:2]) not in shared_artifacts],
                              'expectedPrivateRealmCandidate': [c for c in candidate if ':'.join(c.split(':')[:2]) not in shared_artifacts]}
        raw, member_uses = parser.inventory(baseline, artifact)
        raw.pop('directAssemblyConsumerCount')
        raw['directOwnerConsumerCount'] = sum(c['coordinate'] == owner['owner'] for c in raw['consumers'])
        for consumer in raw['consumers']:
            file = artifact(consumer['coordinate'])
            with zipfile.ZipFile(file) as z:
                data = z.read(consumer['classEntry'])
            name, _, _, _ = parser.inspect_class(data)
            consumer.update(owner=key, className=name, sourcePath=str(file), classSha256=sha(data),
                            effectiveClassEntry=consumer['classEntry'], loaderRole='selected-plugin-realm')
        effective = copy.deepcopy(raw)
        effective_uses = [tuple(r) for r in member_uses]
        replacements = []
        for consumer in effective['consumers']:
            if consumer['className'] not in xml_imports:
                continue
            core = LIB / 'plexus-utils-3.6.1.jar'
            with zipfile.ZipFile(core) as z:
                entry = effective_entry(z, consumer['className'])
                data = z.read(entry)
            own, refs, members, uses = parser.inspect_class(data)
            assert own == consumer['className']
            replacements.append({'className': own, 'declaredCoordinate': consumer['coordinate'],
                'declaredPath': consumer['sourcePath'], 'declaredClassSha256': consumer['classSha256'],
                'declaredTypeReferences': consumer['classAndDescriptorReferences'],
                'declaredMemberReferences': consumer['memberReferences'],
                'effectivePath': str(core), 'effectiveClassEntry': entry, 'effectiveClassSha256': sha(data),
                'effectiveTypeReferences': refs, 'effectiveMemberReferences': members})
            consumer.update(sourcePath=str(core), classSha256=sha(data), effectiveClassEntry=entry,
                            classAndDescriptorReferences=refs, memberReferences=members, loaderRole='maven-core-import')
            effective_uses = [r for r in effective_uses if r[0] != own]
            effective_uses.extend((own, *r) for r in uses)
        effective_uses.sort()

        def linkage_rows(inv, uses):
            invocation = {}
            for use in uses:
                k = tuple(use[:-1])
                assert k not in invocation or invocation[k] == use[-1], k
                invocation[k] = use[-1]
            rows = []
            for c in inv['consumers']:
                name = c['className']
                rows.append('\t'.join(('consumer', name, Path(c['sourcePath']).as_uri(), c['classSha256'])))
                for target in c['classAndDescriptorReferences']:
                    rows.append('\t'.join(('type', name, target)))
                for m in c['memberReferences']:
                    k = (name, m['kind'], m['owner'], m['name'], m['descriptor'])
                    assert k in invocation, ('no static/instance bytecode use', k)
                    rows.append('\t'.join(('member', name, m['owner'], m['kind'], m['name'], m['descriptor'], invocation[k])))
            assert len(rows) == len(set(rows))
            return rows

        declared_rows = linkage_rows(raw, member_uses)
        effective_rows = linkage_rows(effective, effective_uses)
        providers = {}
        for variant, closure in [('baseline', baseline), ('candidate', candidate)]:
            defs = []
            for consumer in effective['consumers']:
                for name in consumer['classAndDescriptorReferences']:
                    if consumer['loaderRole'] == 'maven-core-import' or name in xml_imports:
                        provider = LIB / 'plexus-utils-3.6.1.jar'
                        role = 'maven-core-import'
                    else:
                        matches = []
                        for coordinate in closure:
                            file = artifact(coordinate)
                            with zipfile.ZipFile(file) as z:
                                try:
                                    effective_entry(z, name)
                                except AssertionError:
                                    continue
                            matches.append(file)
                        assert len(matches) == 1, (key, name, matches)
                        provider = matches[0]
                        role = 'selected-plugin-realm'
                    with zipfile.ZipFile(provider) as z:
                        entry = effective_entry(z, name)
                        data = z.read(entry)
                    defs.append({'consumer': consumer['className'], 'className': name, 'sourcePath': str(provider),
                                 'effectiveClassEntry': entry, 'classSha256': sha(data), 'loaderRole': role})
            providers[variant] = defs
        records[key] = {'owner': owner['owner'], 'status': 'STATIC_READ_ONLY_NOT_JVM_LINKAGE_ACCEPTANCE',
            'declaredInventory': raw, 'declaredMemberUses': member_uses, 'declaredLinkageRows': declared_rows,
            'effectiveInventory': effective, 'effectiveMemberUses': effective_uses, 'effectiveLinkageRows': effective_rows,
            'coreImportReplacements': replacements, 'expectedTypeDefinitions': providers,
            'declaredRowCounts': {k: sum(r.startswith(k + '\t') for r in declared_rows) for k in ('consumer', 'type', 'member')},
            'effectiveRowCounts': {k: sum(r.startswith(k + '\t') for r in effective_rows) for k in ('consumer', 'type', 'member')}}

    return json.loads(json.dumps({'pins': pins, 'owners': records}))
