#!/usr/bin/env python3
"""Sealed, offline utility consumer probes; no sockets, Maven goals or downloads."""
import argparse
import io
import json
import os
from pathlib import Path
import signal
import sys
import tarfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import Commands, digest, isolated_env, require
import run_boot_http_compatibility as http

HERE = Path(__file__).resolve().parent
MEMBERS = HERE / 'boot-utilities-members.tsv'
JAVA = HERE / 'BootPluginUtilitiesProbe.java'
MAIN = 'org.springframework.boot.maven.BootPluginUtilitiesProbe'
SUPPORT = ('maven-core', 'maven-model', 'maven-artifact', 'maven-plugin-api', 'maven-builder-support', 'org.eclipse.sisu.plexus')
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'BOOT_UTILITIES_COMPATIBILITY=true',
        'offline': True, 'sockets': False, 'aggregateTimeoutSeconds': 180, 'outerTimeoutSeconds': 210,
        'commandTimeoutSeconds': {'javac': 30, 'probe': 45, 'negative': 20},
        'variants': {'baseline': ['3.5.1', '3.16.0'], 'candidate': ['3.6.1', '3.18.0']},
        'memberCount': 31, 'compiledAgainst': 'baseline',
        'scope': 'Observed-artifact classpath replay of actual in-process consumers; no natural Mojo reachability claim',
        'claimsExcluded': ['HTTP/TLS/Unix acceptance', 'Maven ClassRealm import fidelity', 'Docker build-image',
                           'vulnerability fix reproduction', 'scanner clearance', 'canonical CI']}


def sources():
    return [Path(__file__).resolve(), JAVA, MEMBERS, *http.SOURCES]


def support_artifacts(maven):
    lib = maven.parent.parent / 'lib'
    return [{'coordinate': ('org.eclipse.sisu:org.eclipse.sisu.plexus:jar:1.0.0' if name == 'org.eclipse.sisu.plexus'
                            else 'org.apache.maven:' + name + ':jar:3.9.16'),
             'path': str(lib / (name + ('-1.0.0.jar' if name == 'org.eclipse.sisu.plexus' else '-3.9.16.jar')))} for name in SUPPORT]


def validate_seal(seal):
    http.check_seal(seal)
    require({str(path.resolve()) for path in sources()} <= seal['sourceHashes'].keys(), 'utility source closure missing')
    config = seal['config']; jdk = Path(config['jdk']).resolve(); maven = Path(config['maven']).resolve()
    require(all(str(jdk / item) in seal['runtimeHashes'] for item in ('bin/java', 'bin/javac', 'lib/modules', 'conf/security/java.security')),
            'JDK runtime binding missing')
    require(str(Path(sys.executable).resolve()) in seal['runtimeHashes'], 'Python runtime binding missing')
    for item in support_artifacts(maven):
        require(item['path'] in seal['runtimeHashes'], 'Maven consumer support not bound')
    for variant in ('baseline', 'candidate'):
        require(config[variant + 'Realm'] in seal['inputHashes'], 'utility realm input not sealed')


def verify_tar(path, empty=False):
    require(path.is_file() and path.stat().st_size <= 32768, 'bounded independent TAR required')
    data = path.read_bytes()
    require(len(data) % 512 == 0 and data[-1024:] == bytes(1024), 'independent EOF/padding mismatch')
    expected = {} if empty else {'normal.txt': b'ordinary', 'café.txt': '非ASCII'.encode(),
                                 'nested/' + 'n' * 120 + '.txt': b'long name', 'empty.txt': b''}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as archive:
        members = archive.getmembers()
        require(len(members) == len(expected) and {entry.name for entry in members} == set(expected), 'independent TAR member mismatch')
        for entry in members:
            require(entry.isfile() and entry.uid == entry.gid == 0 and entry.size == len(expected[entry.name]), 'independent TAR metadata mismatch')
            require(archive.extractfile(entry).read(1024) == expected[entry.name], 'independent TAR content mismatch')
    return {'sha256': digest(path), 'size': len(data), 'memberNames': [entry.name for entry in members],
            'parser': 'Python standard-library tarfile; read-only, no extraction'}


def probe_result(stdout, stderr, variant):
    origins = [line.split('\t')[1:] for line in stdout.splitlines() if line.startswith('BOOT_UTIL_ORIGIN\t')]
    members = [line.split('\t', 1)[1] for line in stdout.splitlines() if line.startswith('BOOT_UTIL_MEMBER\t')]
    expected_members = MEMBERS.read_text().splitlines()
    require(members == expected_members, 'exact 31-member result absent or changed')
    require(len(origins) >= 20 and len({row[0] for row in origins}) == len(origins), 'complete distinct origins missing')
    for name, filename, _ in origins:
        require(name + ' source: file:' + filename in stderr, 'JVM origin corroboration missing: ' + name)
    completions = [line.split('\t') for line in stdout.splitlines() if line.startswith('BOOT_UTIL_COMPLETE\t')]
    require(len(completions) == 1 and completions[0][1] == variant and int(completions[0][2]) >= 200, 'utility completion missing')
    scanner = 'BOOT_UTIL_SCANNER\t' + ('3.6.1' if variant == 'candidate' else '3.5.1') + '\t.gitignore=true\t.cvsignore=' + str(variant == 'candidate').lower()
    require(stdout.splitlines().count(scanner) == 1, 'intentional scanner difference missing')
    require('BOOT_UTIL_LINKAGE\t31\t' + digest(MEMBERS) in stdout, 'linkage contract hash missing')
    return {'status': 'PASSED', 'origins': origins, 'members': members, 'memberSetSha256': digest(MEMBERS),
            'assertions': int(completions[0][2]), 'compiledAgainst': 'baseline',
            'scannerDefaults': {'gitignoreIncluded': True, 'cvsignoreIncluded': variant == 'candidate'},
            'passedAssertions': [line[5:] for line in stdout.splitlines() if line.startswith('PASS ')]}


def execute(seal):
    require(os.environ.get('BOOT_UTILITIES_COMPATIBILITY') == 'true', 'explicit reviewed utility opt-in required')
    validate_seal(seal)
    config = seal['config']; output = Path(config['output']).resolve(); jdk = Path(config['jdk']).resolve()
    require(not output.exists(), 'fresh utility output required')
    manifests = {variant: Path(config[variant + 'Realm']) for variant in ('baseline', 'candidate')}
    artifacts = {variant: http.realm(path, variant, profile='utilities') for variant, path in manifests.items()}
    support = support_artifacts(Path(config['maven']).resolve())
    for item in support:
        item['sha256'] = digest(Path(item['path']))
    output.mkdir(parents=True)
    for name in ('home', 'tmp', 'classes', 'baseline', 'candidate'):
        (output / name).mkdir()
    commands = Commands(output, isolated_env(jdk, output), 180)
    result = {'status': 'RUNNING', 'plan': PLAN, 'sourceHashes': seal['sourceHashes'], 'runtimeHashes': seal['runtimeHashes'],
              'inputHashes': seal['inputHashes'], 'commands': commands.results, 'variants': {},
              'mavenSupport': support, 'naturalGoalReachabilityClaim': False, 'scannerEvidence': False, 'canonicalCiEvidence': False}
    def run(label, command, seconds, expected=0):
        validate_seal(seal)
        status, stdout, stderr = commands.run(label, command, seconds)
        require(status == expected, 'utility command failed: ' + label)
        validate_seal(seal)
        return stdout, stderr
    previous = http.install_deadline(180)
    try:
        cp = os.pathsep.join(item['path'] for item in [*artifacts['baseline'], *support])
        run('compile-baseline', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21', '-cp', cp,
            '-d', str(output / 'classes'), str(JAVA)], 30)
        compiled = {str(path): digest(path) for path in sorted((output / 'classes').rglob('*.class'))}
        require(2 <= len(compiled) <= 4, 'bounded compiled probe closure required')
        result['compiledClasses'] = compiled
        for variant in ('baseline', 'candidate'):
            require(all(digest(Path(path)) == value for path, value in compiled.items()), 'compiled probe drift')
            identity = output / (variant + '-identities.tsv')
            selected = [*artifacts[variant], *support]
            identity.write_text(''.join(item['coordinate'].split(':')[1] + '\t' + item['path'] + '\t' + item['sha256'] + '\n' for item in selected))
            cp = os.pathsep.join([str(output / 'classes'), *(item['path'] for item in selected)])
            command = [str(jdk / 'bin/java'), '-Xmx128m', '-Duser.home=' + str(output / 'home'), '-Djava.io.tmpdir=' + str(output / 'tmp'),
                       '-XX:-UsePerfData', '-Xlog:class+load=info:stderr', '-cp', cp, MAIN, str(identity), str(MEMBERS), variant, str(output / variant)]
            stdout, stderr = run(variant + '-utilities', command, 45)
            result['variants'][variant] = probe_result(stdout, stderr, variant)
            result['variants'][variant]['independentTar'] = [verify_tar(output / variant / 'boot.tar'), verify_tar(output / variant / 'empty.tar', True)]
            result['variants'][variant]['identitySha256'] = digest(identity)
            if variant == 'candidate':
                stdout, stderr = run('candidate-missing-member', command + ['missing-member'], 20, expected=1)
                require('NoSuchMethodException' in stderr and 'missingFixtureMethod' in stderr and 'BOOT_UTIL_COMPLETE' not in stdout,
                        'nonexistent utility member not rejected')
                result['missingMemberNegativeControl'] = 'PASSED'
        result['status'] = 'PASSED'
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
        raise
    finally:
        http.ignore_terminal_signals(); signal.alarm(0)
        try:
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('BOOT_UTILITIES_RESULT ' + json.dumps(result), flush=True)
        finally:
            http.restore_signals(previous)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--execute', action='store_true'); parser.add_argument('--seal', type=Path)
    args = parser.parse_args()
    if args.execute:
        require(args.seal is not None, 'reviewed utility seal required'); execute(json.loads(args.seal.read_text()))
    else:
        print(json.dumps(PLAN, indent=2))
