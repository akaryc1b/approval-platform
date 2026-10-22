"""Bounded Resources/Flatten source and paired-model contract; no tool execution.

The accepted main905 anchor describes 32 model-source files, not the current
repository tree. Current source is read directly. Historical admission is never
called. Only materialize_baseline writes, into a new caller-owned directory.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
from runpy import run_path
import stat
import xml.etree.ElementTree as ET

HEAD = '905cd30fde430972da58203a61136d50a28f81e8'
TREE = '8b5d522974a5547a7d990f42b6ce386dadad0941'
EVIDENCE = 'docs/operations/resources-flatten-evidence/'
MANIFEST = EVIDENCE + 'accepted-main905-source-manifest.json'
MANIFEST_SHA = 'f28e7012dd8638a9b219e00aa16ade8357c771ff249f2b753755579df838120c'
ORIGINAL_MANIFEST_SHA = '920e81c1d2cb1a1377aba9107619ea8fa331578476d8e275722a592bcef79fd2'
ADDITIONS = EVIDENCE + 'approved-additions.json'
ADDITIONS_SHA = 'dffad78082b0e7d4d7b1e743c4d8da28265daf3ae7aad0666a7176cb2a2ae7ae'
NEW = {'pom.xml': '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab',
       'integrations/host-sdk/pom.xml': '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078'}
OLD = {'pom.xml': 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932',
       'integrations/host-sdk/pom.xml': 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701'}
NEW_BYTES = {'pom.xml': 22565, 'integrations/host-sdk/pom.xml': 2226}
ARCHIVES = {'pom.xml': 'docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml',
            'integrations/host-sdk/pom.xml': EVIDENCE + 'accepted-main905-host-sdk-pom.xml'}
PARSER = 'scripts/ci/checkstyle_plugin_contract.py'
REACTOR = 'scripts/ci/clean_plugin_contract.py'
WITNESS = 'docs/operations/assembly-enforcer-lang-evidence/source-witness.json'
RULES = 'config/checkstyle/checkstyle.xml'
AUXILIARY = {
    PARSER: 'd5d1927221ae93a27dc07ace72cc8d48b2e0af1b9cae55ee2c6d34434902c0cf',
    REACTOR: 'b48a8ad2d3baf4d8da714bf6017dcbdf6bbd19eed500576a17fdf08b43eaa75e',
    WITNESS: '7e5b4ee22444b7f76334027fcb878e777854d05502ba24312dcb8e20be000a38',
    RULES: 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'}
PYTHON_HELPERS = {
    'scripts/tests/fixtures/checkstyle_live_support.py': '35ec676d427cc424e4cadbded2dc99f42500623d9d99824d7d423dc5d40449c2',
    'scripts/tests/fixtures/run_boot_live_compatibility.py': '5257158869e64f0dd82dc680a93db7b55196225f454d1efbc9933276210e62be',
    'scripts/tests/fixtures/run_boot_http_compatibility.py': 'b57ef86a8ed16b6a481d534dc64720016621125824988ddeaf7f005c86dfec30',
    'scripts/tests/fixtures/run_boot_goal_compatibility.py': '2cc13bcc31b171cf6f3fc6f50516895a64db05bc29dc31f868c41991a2c03c4c',
    'scripts/tests/fixtures/run_boot_utilities_compatibility.py': 'b4da6836364caac77760b32740e485ea23853ffd16fd77f4bc323c31caf7e273',
    'scripts/tests/fixtures/jar_plugin_storage.py': 'e0f9f0f518cda8ff5a9358d54db9cfcd7d797a64dadeb652936c4a3780653770',
    'scripts/tests/fixtures/assembly_plexus_inventory.py': 'c32e5892dd495e8ac1cd6c5127781c549597edde4762d60f605f6c8ff061783e',
    'scripts/ci/boot_utilities_contract.py': '6ef0328fc5fb015e9a9f13a7999236275aaead5d0364fa0ed7ec2296bbced39f',
    'scripts/ci/jacoco_antrun_contract.py': 'a3ea83d6d5127cfa32cdac1ed5ed62cdc48dd1d468a40a767570e6cf3edbae9e'}
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_SOURCE_BYTES = 8 * 1024 * 1024
MAX_MODEL_BYTES = 16 * 1024 * 1024
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
TARGETS = {'maven-resources-plugin': ('org.apache.maven.plugins', '3.4.0'),
           'flatten-maven-plugin': ('org.codehaus.mojo', '1.7.3')}


def require(condition, message):
    if not condition:
        raise ValueError('Resources/Flatten: ' + message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(raw):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, 'duplicate JSON member: ' + key)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda value: require(False, 'nonfinite JSON value'))


def real_root(path):
    path = Path(path).absolute()
    require(path.is_dir() and path.resolve() == path and not path.is_symlink(), 'real root required')
    return path


def safe_member(name):
    require(isinstance(name, str) and name and not name.startswith('/') and '\\' not in name
            and not any(ord(c) < 32 for c in name)
            and all(p not in ('', '.', '..') for p in name.split('/')), 'unsafe member')
    return name


def read(root, name, expected, maximum=MAX_FILE_BYTES):
    path = root / safe_member(name)
    require(path.is_file() and not path.is_symlink() and path.resolve() == path
            and path.is_relative_to(root) and stat.S_ISREG(path.stat().st_mode)
            and path.stat().st_size <= maximum, 'bounded real file required: ' + name)
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum and sha(data) == expected, 'fresh bytes changed: ' + name)
    return data


def anchors(candidate):
    manifest = strict_json(read(candidate, MANIFEST, MANIFEST_SHA))
    additions = strict_json(read(candidate, ADDITIONS, ADDITIONS_SHA))
    require(set(manifest) == {'schemaVersion', 'head', 'tree', 'extractedFromManifestSha256',
                             'sourcePomCount', 'checkedFileCount', 'files'}, 'manifest fields')
    require(manifest['schemaVersion'] == 'RESOURCES_FLATTEN_ACCEPTED_MAIN905_SOURCE_CLOSURE_V1'
            and manifest['head'] == HEAD and manifest['tree'] == TREE
            and manifest['extractedFromManifestSha256'] == ORIGINAL_MANIFEST_SHA
            and manifest['sourcePomCount'] == 28 and manifest['checkedFileCount'] == 32
            and len(manifest['files']) == 32, 'independent accepted-main identity')
    files = manifest['files']
    require(all(set(row) == {'sha256', 'bytes'} and type(row['bytes']) is int
                and 0 < row['bytes'] <= MAX_FILE_BYTES for row in files.values()), 'manifest file records')
    require(all(files[p]['sha256'] == h for p, h in {**OLD, **AUXILIARY}.items()), 'fixed independent anchors')
    witness = strict_json(read(candidate, WITNESS, AUXILIARY[WITNESS]))
    rows = witness['sourcePomFiles']
    paths = [safe_member(row['path']) for row in rows]
    require(len(paths) == len(set(paths)) == 28 and set(NEW).issubset(paths)
            and set(files) == set(paths) | set(AUXILIARY), 'exact 28-POM source closure')
    require(all(files[row['path']]['sha256'] == row['candidateSha256'] for row in rows),
            'independent witness hashes differ')
    require(set(additions) == set(NEW) and [len(additions[k]) for k in NEW] == [2, 1]
            and all(isinstance(x, str) and x for blocks in additions.values() for x in blocks),
            'exact three additions')
    return files, additions


def python_helper_state(candidate):
    """Independent finite execution-helper seal; never part of the32/34 source API."""
    candidate = real_root(candidate)
    return {str(candidate / name): sha(read(candidate, name, expected))
            for name, expected in sorted(PYTHON_HELPERS.items())}


def reverse(name, raw, additions):
    require(name in NEW and sha(raw) == NEW[name], 'exact current two-POM bytes')
    for addition in additions[name]:
        block = addition.encode()
        require(raw.count(block) == 1, 'missing or duplicate approved block: ' + name)
        raw = raw.replace(block, b'', 1)
    require(sha(raw) == OLD[name], 'unrelated two-POM change')
    return raw


def no_maven_configuration(root, files):
    directories = {root, *root.parents}
    for name in files:
        if name.endswith('/pom.xml') or name == 'pom.xml':
            directories.update((root / name).parent.parents)
            directories.add((root / name).parent)
    require(all(not (p / '.mvn').exists() and not (p / '.mvn').is_symlink() for p in directories),
            'unreviewed Maven configuration')


def source_state(candidate):
    candidate = real_root(candidate)
    files, additions = anchors(candidate)
    hashes, originals, total = {}, {}, 0
    for name, archive in ARCHIVES.items():
        originals[name] = read(candidate, archive, OLD[name])
        require(len(originals[name]) == files[name]['bytes'], 'independent original length')
        hashes[archive] = sha(originals[name]); total += len(originals[name])
    for name, row in sorted(files.items()):
        raw = read(candidate, name, NEW.get(name, row['sha256']))
        require(len(raw) == NEW_BYTES.get(name, row['bytes']), 'source member length changed: ' + name)
        if name in NEW:
            require(reverse(name, raw, additions) == originals[name], 'independent original differs: ' + name)
        hashes[name] = sha(raw); total += len(raw)
        require(total <= MAX_SOURCE_BYTES, 'source byte budget')
    no_maven_configuration(candidate, files)
    return {'schemaVersion': 'RESOURCES_FLATTEN_CURRENT_SOURCE_STATE_V1', 'sourceRoot': str(candidate),
            'acceptedSourceHead': HEAD, 'acceptedSourceTree': TREE, 'sourcePomCount': 28,
            'sourceFileCount': 32, 'independentOriginalCount': 2, 'checkedFileCount': 34,
            'sourceHashes': dict(sorted(hashes.items())), 'sourceBytes': total,
            'anchorHashes': {'manifest': MANIFEST_SHA, 'approvedAdditions': ADDITIONS_SHA},
            'sourceScope': 'TWENTY_EIGHT_POMS_AND_EXACT_MODEL_HELPERS', 'historicalValidation': 'NOT_RUN'}


def source_pair(baseline, candidate):
    baseline, candidate = real_root(baseline), real_root(candidate)
    require(baseline != candidate and not baseline.is_relative_to(candidate)
            and not candidate.is_relative_to(baseline), 'distinct source roots required')
    current = source_state(candidate)
    files, additions = anchors(candidate)
    hashes, total = {}, 0
    for name, row in sorted(files.items()):
        old = read(baseline, name, row['sha256'])
        new = read(candidate, name, NEW.get(name, row['sha256']))
        require(len(old) == row['bytes'], 'accepted member length changed')
        if name in NEW:
            original = read(candidate, ARCHIVES[name], OLD[name])
            require(old == original == reverse(name, new, additions), 'independent old archive differs: ' + name)
        else:
            require(old == new, 'unrelated source changed: ' + name)
        hashes[name] = sha(old); total += len(old)
        require(total <= MAX_SOURCE_BYTES, 'source byte budget')
    no_maven_configuration(baseline, files)
    require(source_state(candidate) == current, 'current source changed during paired read')
    return {'schemaVersion': 'RESOURCES_FLATTEN_BOUNDED_SOURCE_PAIR_V1', 'sourceHead': HEAD, 'sourceTree': TREE,
            'sourceRoots': {'baseline': str(baseline), 'candidate': str(candidate)}, 'sourcePomCount': 28,
            'checkedFileCount': 32, 'baselineCheckedFileCount': 32, 'candidateCheckedFileCount': 34,
            'independentOriginalCount': 2, 'sourceHashes': {'baseline': hashes, 'candidate': current['sourceHashes']},
            'sourceBytes': {'baseline': total, 'candidate': current['sourceBytes']},
            'anchorHashes': current['anchorHashes'], 'fullAcceptedSourceView': False,
            'sourceScope': current['sourceScope'], 'historicalValidation': 'NOT_RUN', 'candidateCompatibility': 'NOT_RUN'}


def baseline_membership(baseline, expected):
    baseline = real_root(baseline)
    allowed_dirs = {str(p) for name in expected for p in PurePosixPath(name).parents if str(p) != '.'}
    found, count = set(), 0
    for directory, folders, names in os.walk(baseline, followlinks=False):
        require(len(Path(directory).relative_to(baseline).parts) <= 16, 'baseline depth exceeded')
        for name in folders + names:
            file = Path(directory) / name; relative = file.relative_to(baseline).as_posix(); count += 1
            require(count <= 256 and not file.is_symlink() and file.resolve() == file, 'baseline membership boundary')
            if name in folders:
                require(relative in allowed_dirs, 'unexpected baseline directory: ' + relative)
            else:
                require(file.is_file() and relative in expected, 'unexpected baseline file: ' + relative)
                found.add(relative)
    require(found == set(expected), 'baseline exact32 membership')


def materialize_baseline(candidate, destination):
    candidate = real_root(candidate); destination = Path(destination).absolute()
    require(not destination.exists() and not destination.is_symlink()
            and destination.parent.is_dir() and destination.parent.resolve() == destination.parent
            and destination.resolve() == destination and not destination.is_relative_to(candidate)
            and not candidate.is_relative_to(destination), 'new independent baseline directory required')
    before = source_state(candidate); files, additions = anchors(candidate)
    destination.mkdir(mode=0o700)
    for name, row in sorted(files.items()):
        raw = read(candidate, name, NEW.get(name, row['sha256']))
        if name in NEW:
            old = read(candidate, ARCHIVES[name], OLD[name])
            require(reverse(name, raw, additions) == old, 'baseline independent reversal mismatch')
            raw = old
        target = destination / name; target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(raw)
    baseline_membership(destination, files)
    pair = source_pair(destination, candidate)
    require(source_state(candidate) == before, 'source changed during baseline materialization')
    return {'schemaVersion': 'RESOURCES_FLATTEN_BASELINE_MATERIALIZATION_V1',
            'suppliedSourceRoot': str(candidate), 'retainedSourceRoot': str(destination),
            'materializedFileCount': 32, 'sourcePair': pair}


def helpers(baseline):
    for name in (PARSER, REACTOR):
        read(baseline, name, AUXILIARY[name])
    parser = run_path(str(baseline / PARSER))
    reactor = run_path(str(baseline / REACTOR))
    for name in (PARSER, REACTOR):
        read(baseline, name, AUXILIARY[name])
    return parser, reactor


def checked_model_roots(roots):
    require(isinstance(roots, dict) and set(roots) == {'baseline', 'candidate'}, 'exact model source roots')
    for value in roots.values():
        require(isinstance(value, str) and value.startswith('/') and value != '/'
                and value == str(PurePosixPath(value)) and len(PurePosixPath(value).parts) >= 3
                and all(p not in ('.', '..') for p in value.split('/')[1:])
                and not any(c.isspace() or ord(c) < 32 for c in value), 'bounded lexical model source root')
    before, after = (PurePosixPath(roots[k]) for k in ('baseline', 'candidate'))
    require(before != after and not before.is_relative_to(after) and not after.is_relative_to(before),
            'distinct lexical model source roots')
    return dict(roots)


def containers(project):
    result = {}
    for part, parent in [('project', project)] + [
            ('profile:' + (p.findtext('m:id', namespaces=NS) or ''), p)
            for p in project.findall('m:profiles/m:profile', NS)]:
        for location in ('build/pluginManagement/plugins', 'build/plugins'):
            matches = parent.findall('/'.join('m:' + x for x in location.split('/')), NS)
            require(len(matches) <= 1 and part + '/' + location not in result, 'ambiguous plugin container')
            if matches:
                result[part + '/' + location] = matches[0]
    return result


def project_projection(prior, current, parser):
    value, one, fields = (parser[k] for k in ('value', 'one', 'fields'))
    reverted = copy.deepcopy(current)
    old_containers, new_containers = containers(prior), containers(reverted)
    require(set(old_containers) == set(new_containers), 'plugin container membership changed')
    counts = {}
    for location, container in new_containers.items():
        old = old_containers[location]
        for artifact, (group, version) in TARGETS.items():
            selected = [p for p in container if value(p, 'artifactId') == artifact]
            previous = [p for p in old if value(p, 'artifactId') == artifact]
            require(len(selected) <= 1 and len(previous) <= 1, 'duplicate target owner')
            if not selected:
                require(not previous, 'owner removed')
                continue
            plugin = selected[0]
            require(value(plugin, 'groupId', False) in ('', group) and value(plugin, 'version') == version,
                    'fixed plugin coordinate drift')
            deps = one(plugin, 'dependencies')
            additions = [d for d in deps if value(d, 'groupId') == 'org.codehaus.plexus'
                         and value(d, 'artifactId') == 'plexus-utils']
            require(len(additions) == 1, 'one scoped Utils dependency required')
            dep = additions[0]
            fields(dep, ['groupId', 'artifactId', 'version'] + (['scope'] if one(dep, 'scope', False) is not None else []))
            require(value(dep, 'version') == '4.0.3' and value(dep, 'scope', False) in ('', 'compile'),
                    'exact scoped Utils version and scope')
            key = location + ':' + artifact
            counts[key] = counts.get(key, 0) + 1
            if not previous:
                require(artifact == 'maven-resources-plugin' and location == 'project/build/pluginManagement/plugins',
                        'unexpected new owner declaration')
                fields(plugin, ['artifactId', 'version', 'dependencies']
                       + (['groupId'] if one(plugin, 'groupId', False) is not None else []))
                fields(deps, ['dependency'])
                container.remove(plugin)
                continue
            require(not [d for d in previous[0].findall('m:dependencies/m:dependency', NS)
                         if value(d, 'groupId') == 'org.codehaus.plexus' and value(d, 'artifactId') == 'plexus-utils'],
                    'unexpected old target override')
            deps.remove(dep)
            if one(previous[0], 'dependencies', False) is None:
                require(len(deps) == 0, 'other new plugin dependency')
                plugin.remove(deps)
    return reverted, counts


def bounded_models(path, parser, reactor):
    path = Path(path).absolute()
    require(path.is_file() and not path.is_symlink() and path.resolve() == path
            and path.stat().st_size <= 16 * 1024 * 1024, 'bounded real model document')
    with path.open('rb') as stream:
        data = stream.read(MAX_MODEL_BYTES + 1)
    require(len(data) <= MAX_MODEL_BYTES, 'model document grew beyond bound')
    require(b'<!DOCTYPE' not in data and b'<!ENTITY' not in data, 'XML declaration not accepted')
    root = ET.fromstring(data)
    require(root.tag in (P + 'projects', 'projects'), 'reactor effective document required')
    rows = list(root)
    require(len(rows) == 26 and all(p.tag == P + 'project' for p in rows), 'all 26 actual effective models required')
    value = parser['value']
    ids = [(value(p, 'groupId'), value(p, 'artifactId'), value(p, 'version')) for p in rows]
    require(len(set(ids)) == 26 and {(g, a) for g, a, v in ids}
            == {(reactor['PROJECT_GROUP'], a) for a in reactor['REACTOR'].values()}, 'reactor identity drift')
    return rows, ids, sha(data)


def verify_models(baseline, candidate, paths, *, model_source_roots=None):
    baseline, candidate = real_root(baseline), real_root(candidate)
    before_sources = source_pair(baseline, candidate)
    model_roots = checked_model_roots(model_source_roots or {'baseline': str(baseline), 'candidate': str(candidate)})
    require(set(paths) == {'baseline-normal', 'candidate-normal', 'baseline-demo', 'candidate-demo'}, 'exact four model paths')
    parser, reactor = helpers(baseline)
    result = {}
    for profile in ('normal', 'demo'):
        before, ids, old_sha = bounded_models(paths['baseline-' + profile], parser, reactor)
        after, new_ids, new_sha = bounded_models(paths['candidate-' + profile], parser, reactor)
        require(ids == new_ids, 'paired effective order/version drift: ' + profile)
        counts = {}
        for prior, current in zip(before, after):
            reverted, local = project_projection(prior, current, parser)
            require(parser['semantic'](prior, model_roots['baseline']) == parser['semantic'](reverted, model_roots['candidate']),
                    'full model differs beyond exact owner additions: ' + profile + ':' + parser['value'](current, 'artifactId'))
            for key, count in local.items():
                counts[key] = counts.get(key, 0) + count
        require(counts.get('project/build/pluginManagement/plugins:maven-resources-plugin') == 26,
                'Resources managed coverage must include all models')
        active_flatten = counts.get('project/build/plugins:flatten-maven-plugin', 0)
        require(active_flatten == (1 if profile == 'normal' else 26), 'Flatten active profile coverage')
        result[profile] = {'modelCount': 26, 'baselineSha256': old_sha, 'candidateSha256': new_sha,
                           'ownerDeclarationCounts': counts, 'allOtherModelFieldsUnchanged': True}
    require(source_pair(baseline, candidate) == before_sources, 'source changed during model verification')
    # Re-open all four outputs; a successful earlier parse is never a cache hit.
    for profile in ('normal', 'demo'):
        for variant in ('baseline', 'candidate'):
            require(bounded_models(paths[variant + '-' + profile], parser, reactor)[2]
                    == result[profile][variant + 'Sha256'], 'model changed during verification')
    return {'status': 'PASSED_CURRENT_SOURCE_AND_FOUR_SUPPLIED_PAIRED_MODEL_DOCUMENTS',
            'source': before_sources, 'modelSourceRoots': model_roots, 'models': result, 'nonReactorOverlaysSourceBoundOnly': 2,
            'captureProvenance': 'CALLER_MUST_VERIFY_ACTUAL_CAPTURE_RECEIPTS_AND_OUTPUT_SOURCE_HASH_BINDING',
            'historicalValidation': 'NOT_RUN', 'graphValidation': 'NOT_RUN', 'compatibility': 'NOT_RUN'}


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--baseline', type=Path, required=True)
    cli.add_argument('--candidate', type=Path, required=True)
    cli.add_argument('--model-directory', type=Path)
    args = cli.parse_args()
    if args.model_directory:
        paths = {v + '-' + p: args.model_directory / (v + '-' + p + '-effective-pom.xml')
                 for v in ('baseline', 'candidate') for p in ('normal', 'demo')}
        output = verify_models(args.baseline, args.candidate, paths)
    else:
        output = source_pair(args.baseline, args.candidate)
    print(json.dumps(output, indent=2, sort_keys=True))
