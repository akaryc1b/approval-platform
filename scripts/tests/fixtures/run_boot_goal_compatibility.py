#!/usr/bin/env python3
"""Separate opt-in smoke of real Boot help and repackage Mojos on a tiny fixture."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import signal
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import Commands, digest, isolated_env, NS, P, require
from run_boot_http_compatibility import check_seal, realm, ROOT, HERE, install_deadline, ignore_terminal_signals, restore_signals
sys.path.insert(0, str(ROOT / 'scripts/ci'))
import boot_utilities_contract as utilities_contract

MAIN = 'public class BootGoalFixture { public static void main(String[] args) { System.out.println("BOOT_GOAL_MAIN_OK"); } }\n'
BOOT = 'org.springframework.boot:spring-boot-maven-plugin:4.0.8:'
JAR = 'org.apache.maven.plugins:maven-jar-plugin:3.5.0:jar'
SELECTED = {'httpclient5', 'httpcore5', 'httpcore5-h2', 'jackson-core', 'jackson-databind'}
HTTP_REPLACEMENTS = {
    'org.apache.httpcomponents.client5:httpclient5:jar:5.5.2': 'org.apache.httpcomponents.client5:httpclient5:jar:5.6.3',
    'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6': 'org.apache.httpcomponents.core5:httpcore5:jar:5.4.3',
    'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6': 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3',
}
UTILITY_REPLACEMENTS = {
    'org.codehaus.plexus:plexus-utils:jar:3.5.1': 'org.codehaus.plexus:plexus-utils:jar:3.6.1',
    'org.apache.commons:commons-lang3:jar:3.16.0': 'org.apache.commons:commons-lang3:jar:3.18.0',
}


def compare_realms(baseline, candidate, profile='http'):
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    replacements = UTILITY_REPLACEMENTS if profile == 'utilities' else HTTP_REPLACEMENTS
    require(len(baseline) == len(candidate) and {replacements.get(item, item) for item in baseline} == set(candidate),
            'actual Maven Boot realm changed beyond reviewed HTTP trio')
    before = [item for item in baseline if item not in replacements]
    after = [item for item in candidate if item not in replacements.values()]
    require(set(before) == set(after), 'non-HTTP natural realm membership drift')
    if profile == 'utilities':
        require(set(UTILITY_REPLACEMENTS) <= set(baseline) and set(UTILITY_REPLACEMENTS.values()) <= set(candidate), 'complete utility replacement pair required')
        return {'membershipChangedOnlyByUtilityPair': True, 'nonUtilityOrderEqual': before == after,
                'normalizedFullOrderEqual': [replacements.get(item, item) for item in baseline] == candidate}
    return {'membershipChangedOnlyByHttpTrio': True, 'nonHttpOrderEqual': before == after,
            'normalizedFullOrderEqual': [HTTP_REPLACEMENTS.get(item, item) for item in baseline] == candidate}


def boot_realm(stdout, artifacts, profile='http'):
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    lines = stdout.splitlines()
    marker = '[DEBUG] Populating class realm plugin>org.springframework.boot:spring-boot-maven-plugin:4.0.8'
    starts = [index for index, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one actual Boot goal realm required')
    coordinates = []
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not match:
            break
        coordinates.append(match[1])
    require(coordinates and all(len(item.split(':')) == 4 for item in coordinates), 'actual realm coordinates required')
    require(len({item.split(':')[1] for item in coordinates}) == len(coordinates), 'duplicate actual realm artifact IDs')
    expected = {item['coordinate'] for item in artifacts if item['coordinate'].split(':')[1] in SELECTED}
    actual = {item for item in coordinates if item.split(':')[1] in SELECTED}
    require(len(expected) == 5 and actual == expected, 'actual Boot goal HTTP/Jackson realm differs')
    if profile == 'utilities':
        utility_names = {'plexus-utils', 'commons-lang3'}
        expected = {item['coordinate'] for item in artifacts if item['coordinate'].split(':')[1] in utility_names}
        require(len(expected) == 2 and {item for item in coordinates if item.split(':')[1] in utility_names} == expected,
                'actual Boot goal utility realm differs')
    return coordinates


def utility_origin_observations(stdout, stderr, artifacts, maven, input_hashes):
    """Actual goal JVM observations; absence does not establish invocation or linkage."""
    selected = ('org.codehaus.plexus.util.xml.Xpp3Dom', 'org.codehaus.plexus.util.Scanner',
                'org.codehaus.plexus.util.DirectoryScanner', 'org.codehaus.plexus.util.cli.CommandLineUtils',
                'org.apache.commons.lang3.SystemProperties', 'org.apache.commons.lang3.ArrayFill',
                'org.apache.commons.lang3.reflect.FieldUtils')
    private = {item['coordinate'].split(':')[1]: item for item in artifacts}
    core = maven.parent.parent / 'lib/plexus-utils-3.6.1.jar'
    require(core.is_file(), 'pinned Maven core Plexus missing')
    observations = []
    for name in selected:
        utility = private['plexus-utils' if name.startswith('org.codehaus.') else 'commons-lang3']
        allowed = {utility['path']: ('plugin-private', utility['sha256'])}
        if name.startswith('org.codehaus.'):
            allowed[str(core)] = ('maven-core', digest(core))
        origins = sorted(set(re.findall(re.escape(name) + r' source: file:([^\s]+)', stderr)))
        # Repackage shares a JVM with the JAR goal. Its scanner may have a
        # different, separately sealed Plexus definition; do not attribute it to Boot.
        for path in origins:
            if path not in allowed:
                require(path in input_hashes and digest(Path(path)) == input_hashes[path], 'unbound other-goal utility origin')
                allowed[path] = ('other-sealed-goal-input', input_hashes[path])
        require(all(origin in allowed for origin in origins), 'unreviewed natural utility origin: ' + name)
        observations.append({'class': name, 'observed': bool(origins),
            'origins': [{'path': path, 'role': allowed[path][0], 'sha256': allowed[path][1]} for path in origins]})
    imports = [line for line in stdout.splitlines() if 'Imported:' in line and ('org.codehaus.plexus.util' in line or 'org.apache.commons.lang3' in line)]
    return {'classes': observations, 'mavenImportLogLines': imports,
            'scope': 'Classes loaded anywhere in the actual goal JVM, with Maven-core/private origins distinguished; not proof that a Mojo invoked each class'}


def fixture_pom(variant, profile='http'):
    require(profile in ('http', 'utilities') and variant in ('baseline', 'candidate'), 'fixed Boot fixture profile required')
    current = utilities_contract.is_utilities_source(ROOT)
    utilities_contract.source_inventory(ROOT, current=current)
    require(profile == 'http' or current, 'utility profile requires exact current utility source')
    raw = (ROOT / 'pom.xml').read_bytes()
    if current and (profile == 'http' or variant == 'baseline'):
        raw = utilities_contract.reverse_source(raw)
    source = ET.fromstring(raw)
    plugins = [plugin for plugin in source.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
               if plugin.findtext('m:artifactId', namespaces=NS) == 'spring-boot-maven-plugin']
    require(len(plugins) == 1, 'one actual managed Boot declaration required')
    plugin = copy.deepcopy(plugins[0])
    plugin.find('m:version', NS).text = source.findtext('m:properties/m:spring-boot.version', namespaces=NS)
    dependencies = plugin.find('m:dependencies', NS)
    for dependency in list(dependencies):
        version = dependency.find('m:version', NS)
        if version.text == '${jackson3-bom.version}':
            version.text = source.findtext('m:properties/m:jackson3-bom.version', namespaces=NS)
        if dependency.findtext('m:artifactId', namespaces=NS) == 'httpclient5' and variant == 'baseline' and profile == 'http':
            dependencies.remove(dependency)
    configuration = ET.SubElement(plugin, P + 'configuration')
    ET.SubElement(configuration, P + 'mainClass').text = 'BootGoalFixture'
    project = ET.Element(P + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.boot.compatibility'),
                        ('artifactId', 'boot-goal-fixture'), ('version', '1'), ('packaging', 'jar')]:
        ET.SubElement(project, P + name).text = value
    build = ET.SubElement(project, P + 'build')
    ET.SubElement(build, P + 'finalName').text = 'fixture'
    ET.SubElement(build, P + 'plugins').append(plugin)
    return ET.tostring(project, encoding='unicode', default_namespace=NS['m']) + '\n'


def execute(seal):
    require(os.environ.get('BOOT_HTTP_COMPATIBILITY') == 'true', 'explicit reviewed execution opt-in required')
    check_seal(seal)
    require(str(Path(__file__).resolve()) in seal['sourceHashes'] and str(ROOT / 'pom.xml') in seal['sourceHashes'], 'goal driver/POM not sealed')
    config = seal['config']; output = Path(config['output']).resolve(); jdk = Path(config['jdk']).resolve()
    profile = config.get('profile', 'http')
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    require(str(ROOT / 'scripts/ci/boot_utilities_contract.py') in seal['sourceHashes'], 'utility source reversal not sealed')
    maven, repository = Path(config['maven']).resolve(), Path(config['repository']).resolve()
    require(not output.exists() and str(maven) in seal['runtimeHashes'], 'fresh output and sealed Maven required')
    output.mkdir(parents=True)
    for name in ('home', 'tmp'):
        (output / name).mkdir()
    settings = output / 'empty-settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    toolchains = output / 'empty-toolchains.xml'; toolchains.write_text('<toolchains/>\n')
    environment = isolated_env(jdk, output)
    environment.update(MAVEN_BASEDIR=str(output), MAVEN_OPTS='-Xmx256m -Duser.home=' + str(output / 'home')
                       + ' -Djava.io.tmpdir=' + str(output / 'tmp') + ' -Xlog:class+load=info:stderr')
    commands = Commands(output, environment, 240)
    result = {'status': 'RUNNING', 'profile': profile, 'scope': 'Synthetic natural Mojo and archive smoke; no lifecycle package, application run or Docker',
              'sourceHashes': seal['sourceHashes'], 'runtimeHashes': seal['runtimeHashes'], 'inputHashes': seal['inputHashes'],
              'commands': commands.results, 'variants': {}, 'canonicalCiEvidence': False, 'scannerEvidence': False}

    def run(label, command, seconds=45):
        check_seal(seal)
        require(all(not (ancestor / '.mvn').exists() for ancestor in [output, *output.parents]), 'applicable Maven bootstrap config')
        require(settings.read_text() == '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n'
                and toolchains.read_text() == '<toolchains/>\n', 'empty Maven configuration drift')
        status, out, err = commands.run(label, command, seconds)
        require(status == 0, 'goal command failed: ' + label)
        check_seal(seal)
        return out, err

    old_handlers = install_deadline(240)
    try:
        version, _ = run('maven-version', [str(maven), '--version'], 15)
        require('Apache Maven 3.9.16' in version and 'Java version: 21.' in version, 'pinned Maven/JDK required')
        for variant in ('baseline', 'candidate'):
            artifacts = realm(Path(config[variant + 'Realm']), variant, profile)
            directory = output / variant; directory.mkdir(); classes = directory / 'target/classes'; classes.mkdir(parents=True)
            project = directory / 'pom.xml'; project_text = fixture_pom(variant, profile); project.write_text(project_text)
            source = directory / 'BootGoalFixture.java'; source.write_text(MAIN)
            run(variant + '-compile', [str(jdk / 'bin/javac'), '-J-Xmx64m', '-proc:none', '--release', '21', '-d', str(classes), str(source)], 20)
            base = [str(maven), '-o', '-B', '-ntp', '-N', '-X', '-s', str(settings), '-gs', str(settings),
                    '-t', str(toolchains), '-gt', str(toolchains),
                    '-Dmaven.repo.local=' + str(repository), '-Dmaven.multiModuleProjectDirectory=' + str(directory),
                    '-Dstyle.color=never', '-f', str(project)]
            bindings, goal_realms, utility_origins = [], {}, {}
            for label, goals, names in [('help', [BOOT + 'help'], ['org.springframework.boot.maven.HelpMojo']),
                    ('repackage', [JAR, BOOT + 'repackage'], ['org.springframework.boot.maven.RepackageMojo', 'org.springframework.boot.loader.tools.Repackager'])]:
                require(project.read_text() == project_text and not (directory / '.mvn').exists(), 'fixture project/bootstrap drift')
                stdout, stderr = run(variant + '-' + label, base + goals)
                members = boot_realm(stdout, artifacts, profile)
                goal_realms[label] = members
                if profile == 'utilities':
                    utility_origins[label] = utility_origin_observations(stdout, stderr, artifacts, maven, seal['inputHashes'])
                expected = {item['coordinate']: item for item in artifacts}
                require(all(item in expected for item in members), 'natural realm has a coordinate absent from resolved owner inventory')
                for name in names:
                    artifact_name = 'spring-boot-loader-tools' if name.endswith('.Repackager') else 'spring-boot-maven-plugin'
                    artifact = next(item for item in artifacts if item['coordinate'].split(':')[1] == artifact_name)
                    require(name + ' source: file:' + artifact['path'] in stderr, 'natural Mojo/loader origin missing: ' + name)
                    bindings.append({'class': name, 'path': artifact['path'], 'sha256': artifact['sha256']})
            jar = directory / 'target/fixture.jar'
            with zipfile.ZipFile(jar) as archive:
                require('BOOT-INF/classes/BootGoalFixture.class' in archive.namelist(), 'repackaged main missing')
                manifest = archive.read('META-INF/MANIFEST.MF').decode()
                require('Start-Class: BootGoalFixture' in manifest and 'Main-Class: org.springframework.boot.loader.launch.JarLauncher' in manifest, 'Boot manifest missing')
            out, _ = run(variant + '-archive-main', [str(jdk / 'bin/java'), '-Xmx64m', '-Duser.home=' + str(output / 'home'), '-jar', str(jar)], 20)
            require(out.strip() == 'BOOT_GOAL_MAIN_OK', 'repackaged main did not run')
            result['variants'][variant] = {'status': 'PASSED', 'pomSha256': digest(project), 'compiledMainSha256': digest(classes / 'BootGoalFixture.class'),
                                          'jarSha256': digest(jar), 'naturalOrigins': bindings, 'actualBootRealms': goal_realms,
                                          **({'utilityOriginObservations': utility_origins} if profile == 'utilities' else {}),
                                          'actualBootRealmArtifacts': {item: expected[item] for item in set(sum(goal_realms.values(), []))},
                                          'archiveMain': 'BOOT_GOAL_MAIN_OK'}
        baseline, candidate = result['variants']['baseline'], result['variants']['candidate']
        result['naturalRealmComparison'] = {goal: compare_realms(baseline['actualBootRealms'][goal], candidate['actualBootRealms'][goal], profile)
                                           for goal in ('help', 'repackage')}
        for coordinate, artifact in baseline['actualBootRealmArtifacts'].items():
            if coordinate not in (UTILITY_REPLACEMENTS if profile == 'utilities' else HTTP_REPLACEMENTS):
                require(candidate['actualBootRealmArtifacts'][coordinate] == artifact, 'non-HTTP natural realm JAR bytes/path drift')
        result['naturalRealmScope'] = 'Complete actual Maven-private Boot realm stanzas, distinct from the39-JAR resolve-plugins classpath; observed order retained, unchanged non-HTTP coordinate/byte membership required'
        result['status'] = 'PASSED'
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        ignore_terminal_signals(); signal.alarm(0)
        try:
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('BOOT_GOAL_RESULT ' + json.dumps(result), flush=True)
        finally:
            restore_signals(old_handlers)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--execute', action='store_true'); parser.add_argument('--seal', type=Path)
    args = parser.parse_args()
    if args.execute:
        require(args.seal is not None, 'reviewed seal required'); execute(json.loads(args.seal.read_text()))
    else:
        print(json.dumps({'execution': 'NOT_RUN', 'offline': True, 'goals': [BOOT + 'help', JAR, BOOT + 'repackage'], 'aggregateTimeoutSeconds': 240}))
