"""Fresh reconstruction of bounded Checkstyle helpers; import starts no tools.

Pinned artifacts are expected bytes from the earlier design, not evidence that
the restored machine has those bytes. Every live run must verify its own inputs.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
from runpy import run_path
import selectors
import signal
import subprocess
import time
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
PROP = 'maven.checkstyle.commons-lang3.version'
OWNER = 'org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0'
ENGINE = 'com.puppycrawl.tools:checkstyle:jar:9.3'
LANG = 'org.apache.commons:commons-lang3:jar:3.18.0'
GOAL = 'org.apache.maven.plugins:maven-checkstyle-plugin:check@checkstyle'
REPORT_GOAL = 'org.apache.maven.plugins:maven-checkstyle-plugin:checkstyle'
MODEL_GOAL = 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom'
PREPARE_GOAL = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve'
REPORT_SKIN = {'org.apache.maven.skins:maven-fluido-skin:jar:2.0.0-M9':
               'b3bd3ed2ced9ecd1c88ac58295d6c91ab3967ffcf8bbae3d4f629f2e61584e05'}
CENTRAL_SETTINGS = ('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror>'
    '<id>central</id><mirrorOf>*</mirrorOf>'
    '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n')
REFERENCE_HASH = 'd205262d8477778809dc6c3b8eea2a0edb047ca26223ae71fd19aa0677fe0d21'
CASES = ('passing', 'unchanged-cache', 'same-timestamp-cache', 'changed-main', 'restored-main',
         'deleted-main', 'missing-newline', 'tabs', 'wildcard-import', 'unused-import',
         'illegal-import', 'test-source-violation', 'invalid-configuration', 'report-passing', 'report-violation')
# Maven 3.9.16 metadata predicted these five imports; live observations must
# independently verify the remaining 54 private URLs and the actual imports.
CORE_IMPORTS = frozenset(('javax.inject:javax.inject:jar:1',
    'org.apache.maven.resolver:maven-resolver-api:jar:1.4.1',
    'org.codehaus.plexus:plexus-classworlds:jar:2.6.0',
    'org.eclipse.sisu:org.eclipse.sisu.plexus:jar:0.9.0.M3', 'org.slf4j:slf4j-api:jar:1.7.36'))
ARTIFACT_HASHES = {
    'com.github.cliftonlabs:json-simple:jar:3.0.2': 'fda65a9ad0e1ac0c88987106e89aa4d8b2a2495e7e042371efa83813f65b7295',
    'com.github.luben:zstd-jni:jar:1.5.5-11': 'd75b2ced6059f81ad23e021c554259b906b6c4f2991cb772409827569ead4c1a',
    'com.google.code.findbugs:jsr305:jar:3.0.2': '766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7',
    'com.google.errorprone:error_prone_annotations:jar:2.7.1': 'cd5257c08a246cf8628817ae71cb822be192ef91f6881ca4a3fcff4f1de1cff3',
    'com.google.guava:failureaccess:jar:1.0.1': 'a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26',
    'com.google.guava:guava:jar:31.0.1-jre': 'd5be94d65e87bd219fb3193ad1517baa55a3b88fc91d21cf735826ab5af087b9',
    'com.google.guava:listenablefuture:jar:9999.0-empty-to-avoid-conflict-with-guava': 'b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99',
    'com.google.j2objc:j2objc-annotations:jar:1.3': '21af30c92267bd6122c0e0b4d20cccb6641a37eaf956c6540ec471d584e64a7b',
    'com.puppycrawl.tools:checkstyle:jar:9.3': '0463e304980f5460b964f481ccc25a10fb253b60100c19e50fc992892895977f',
    'commons-beanutils:commons-beanutils:jar:1.9.4': '7d938c81789028045c08c065e94be75fc280527620d5bd62b519d5838532368a',
    'commons-codec:commons-codec:jar:1.16.1': 'ec87bfb55f22cbd1b21e2190eeda28b2b312ed2a431ee49fbdcc01812d04a5e4',
    'commons-collections:commons-collections:jar:3.2.2': 'eeeae917917144a68a741d4c0dff66aa5c5c5fd85593ff217bced3fc8ca783b8',
    'commons-io:commons-io:jar:2.17.0': '4aa4ca48f3dfd30b78220b7881d8cb93eac4093ec94361b6befa9487998a550b',
    'commons-logging:commons-logging:jar:1.2': 'daddea1ea0be0f56978ab3006b8ac92834afeefbd9b7e4e6316fca57df0fa636',
    'info.picocli:picocli:jar:4.6.2': '4754d96eaf5314f35a287da5c67848b285067868fca6d1f5121a74f67f065b61',
    'javax.activation:javax.activation-api:jar:1.2.0': '43fdef0b5b6ceb31b0424b208b930c74ab58fac2ceeb7b3f6fd3aeb8b5ca4393',
    'javax.inject:javax.inject:jar:1': '91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff',
    'javax.xml.bind:jaxb-api:jar:2.3.1': '88b955a0df57880a26a74708bc34f74dcaf8ebf4e78843a28b50eae945732b06',
    'net.sf.saxon:Saxon-HE:jar:10.6': '6d08df82e4ed86b6abb1a02c7b74a268fcfc5e004e83bb4ff006ec3a509bd356',
    'org.antlr:antlr4-runtime:jar:4.9.3': '131a6594969bc4f321d652ea2a33bc0e378ca312685ef87791b2c60b29d01ea5',
    'org.apache.commons:commons-compress:jar:1.26.1': '27bb5d40f37c3bb7205b4a0540247df057715e9f6cbbd97d626ab8b50318bb04',
    'org.apache.commons:commons-digester3:jar:3.2': '1c150e3d2df4b4237b47e28fea2079fb0da324578d5cca6a5fed2e37a62082ec',
    'org.apache.commons:commons-lang3:jar:3.18.0': '4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720',
    'org.apache.commons:commons-text:jar:1.12.0': 'de023257ff166044a56bd1aa9124e843cd05dac5806cc705a9311f3556d5a15f',
    'org.apache.maven.doxia:doxia-core:jar:2.0.0': '939183cf5ced6741745b2475a4adf78ca85885ee0dad6dae28dd3f25bd447ff3',
    'org.apache.maven.doxia:doxia-integration-tools:jar:2.0.0': '4aee72f9b30b507964c2f52b63f70e7b41fb9d957359cb5dc13c428abb4b6189',
    'org.apache.maven.doxia:doxia-module-apt:jar:2.0.0': 'f4a846c448ca85358279184a310f6ee3f46fa39688f74a72961c1bfe222f28a6',
    'org.apache.maven.doxia:doxia-module-xdoc:jar:2.0.0': '7956aca14f8adbc48bac86b218701dd44cc990063a69edbfca363b105994a474',
    'org.apache.maven.doxia:doxia-module-xhtml5:jar:2.0.0': 'c91557679a0eb9fde3175055628ceb7b8fd5ab6d308340770d236fb06265dc26',
    'org.apache.maven.doxia:doxia-sink-api:jar:2.0.0': 'fba33eaee3b01547bcd14b05ebc37f7dacef1819ad9ee7a5b27899afd3472cf4',
    'org.apache.maven.doxia:doxia-site-model:jar:2.0.0': 'f6ec9ef75a41d1b826e5ecf02d92c5de90a6bc70ea93d5340988703223bf2205',
    'org.apache.maven.doxia:doxia-site-renderer:jar:2.0.0': '6cdee370194f4b9f742d12ef46528042f480d9bdf3de832de2792e1ae9ffc68d',
    'org.apache.maven.doxia:doxia-skin-model:jar:2.0.0': '3ced0d90353f49e8eb1458f54664b93ec117d79b9789a576da41e2f6f99723e0',
    'org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0': '2e88e24b0313e32ac9cc2a0466f569c74988413dfdd87c6347a3ac9d4c570db1',
    'org.apache.maven.reporting:maven-reporting-api:jar:4.0.0': 'cb2cbde3c9c7288f7398a250dcf3c90cf92714cff301f22b298e1091b5def33c',
    'org.apache.maven.reporting:maven-reporting-impl:jar:4.0.0': 'e9e70fdb26ff8b1f15435e3a68866a25c85b1694007e0fbdfe84e48e946fe463',
    'org.apache.maven.resolver:maven-resolver-api:jar:1.4.1': '33dc67306cc95da14e5444e8b494d967924abf1d01bae1894676164cbd3f6112',
    'org.apache.maven.shared:maven-shared-utils:jar:3.4.2': 'b613357e1bad4dfc1dead801691c9460f9585fe7c6b466bc25186212d7d18487',
    'org.apache.maven:maven-archiver:jar:3.6.2': '1f895a587df4844d9b7565e8e9a6352afe1d55532458a0dbeb746bc1d02e9216',
    'org.apache.velocity.tools:velocity-tools-generic:jar:3.1': '8258cfdcaa16127f35ffe610a3fa4f76b7ebe51b88922c73c4ee39ce8f378ce5',
    'org.apache.velocity:velocity-engine-core:jar:2.4': '1bf78c2ade46f209bf93ebe72ed2af5b989ca7a1de0a015fc1b92a62f56b6549',
    'org.checkerframework:checker-qual:jar:3.12.0': 'ff10785ac2a357ec5de9c293cb982a2cbb605c0309ea4cc1cb9b9bc6dbe7f3cb',
    'org.codehaus.plexus:plexus-archiver:jar:4.9.2': 'a837bd7d73291564dc8e8c826de0fede75896527a35bdcddb77b0545ee656a4c',
    'org.codehaus.plexus:plexus-classworlds:jar:2.6.0': '52f77c5ec49f787c9c417ebed5d6efd9922f44a202f217376e4f94c0d74f3549',
    'org.codehaus.plexus:plexus-component-annotations:jar:2.2.0': '50edb93c73786e62822b4fe1336e22880fdf147191373cf5c911370e16748fcf',
    'org.codehaus.plexus:plexus-i18n:jar:1.0-beta-10': 'b87f25b512ffafcafbf4a05ab943812e9c6915291370c6b46016eb3836886c41',
    'org.codehaus.plexus:plexus-interpolation:jar:1.27': '3fb4fb6143fdf964024c3cb738551524b9ea84e5c211cd660c559ad0703e5230',
    'org.codehaus.plexus:plexus-io:jar:3.4.2': '6ba7fb0db6bfa348c248df3f983ae31318e9c14f35a86a932af5ffd7450aa62a',
    'org.codehaus.plexus:plexus-resources:jar:1.3.0': '827baaa0215caedcdca59d091f04480566f7a43996183b9f9871fecff7abebea',
    'org.codehaus.plexus:plexus-utils:jar:4.0.1': '96b9cc44439191d2d0635974e2d44e768736b4fb2abcb65f94cd95e41912fa8b',
    'org.codehaus.plexus:plexus-velocity:jar:2.2.0': '3e7e902f492c973cf210ddb8267843a3b65e83f5067467e2f4d9af0051f6b8b9',
    'org.codehaus.plexus:plexus-xml:jar:3.0.1': 'c1a510a87a62bd2d74ac1472dd31c3f9e9b0b8b8568f37d77c0f135415bebd05',
    'org.eclipse.sisu:org.eclipse.sisu.inject:jar:0.9.0.M3': '15335c4dcf082f599fb8eddcfb58d6a7e9a9c97de2883c257089a479b9b24522',
    'org.eclipse.sisu:org.eclipse.sisu.plexus:jar:0.9.0.M3': 'c99674d3773e26154885661711f0b6d63aa5008f5cc99227a236756d4ad9de5e',
    'org.iq80.snappy:snappy:jar:0.4': '46a0c87d504ce9d6063e1ff6e4d20738feb49d8abf85b5071a7d18df4f11bac9',
    'org.javassist:javassist:jar:3.28.0-GA': '57d0a9e9286f82f4eaa851125186997f811befce0e2060ff0a15a77f5a9dd9a7',
    'org.reflections:reflections:jar:0.10.2': '938a2d08fe54050d7610b944d8ddc3a09355710d9e6be0aac838dbc04e9a2825',
    'org.slf4j:slf4j-api:jar:1.7.36': 'd3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0',
    'org.tukaani:xz:jar:1.9': '211b306cfc44f8f96df3a0a3ddaf75ba8c5289eed77d60d72f889bb855f535e5',
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inside(file, sandbox):
    require(file.is_absolute() and file != sandbox and file.is_relative_to(sandbox), 'path outside sandbox')
    require(not file.is_symlink() and file.resolve() != sandbox and file.resolve().is_relative_to(sandbox), 'resolved path outside sandbox')
    return file


def normalized(element):
    return element.tag, (element.text or '').strip(), tuple(sorted(element.attrib.items())), tuple(normalized(c) for c in element)


def transition_spec(name='lang'):
    require(name in ('lang', 'guava'), 'only named lang/guava transitions admitted')
    return {
        'lang': {'contract': 'checkstyle_plugin_contract.py', 'probe': 'CheckstyleLangProbe',
                 'property': PROP, 'prefix': 'org.apache.commons:commons-lang3:jar:',
                 'versions': ('3.17.0', '3.18.0'), 'memberField': 'langMemberReferences',
                 'memberCount': 58, 'referenceHash': REFERENCE_HASH, 'artifactUnionCount': 60},
        'guava': {'contract': 'checkstyle_guava_contract.py', 'probe': 'CheckstyleGuavaProbe',
                  'property': 'maven.checkstyle.guava.version', 'prefix': 'com.google.guava:guava:jar:',
                  'versions': ('31.0.1-jre', '32.0.1-jre'), 'memberField': 'guavaMemberReferences',
                  'memberCount': 4, 'referenceHash': 'e2c5e81005d7620b6fa48d4157f9e7413b05f9f8e07176c7e461ac9af2d2b833',
                  'artifactUnionCount': 63},
    }[name]


# Exact candidate bytes verified during separate Central plugins-only provisioning.
GUAVA_REPLACEMENTS = {
    'com.google.guava:guava:jar:31.0.1-jre': ('com.google.guava:guava:jar:32.0.1-jre', 'bd7fa227591fb8509677d0d1122cf95158f3b8a9f45653f58281d879f6dc48c5'),
    'org.checkerframework:checker-qual:jar:3.12.0': ('org.checkerframework:checker-qual:jar:3.33.0', 'e316255bbfcd9fe50d165314b85abb2b33cb2a66a93c491db648e498a82c2de1'),
    'com.google.errorprone:error_prone_annotations:jar:2.7.1': ('com.google.errorprone:error_prone_annotations:jar:2.18.0', '9e6814cb71816988a4fd1b07a993a8f21bb7058d522c162b1de849e19bea54ae'),
    'com.google.j2objc:j2objc-annotations:jar:1.3': ('com.google.j2objc:j2objc-annotations:jar:2.8', 'f02a95fa1a5e95edb3ed859fd0fb7df709d121a35290eff8b74dce2ab7f4d6ed'),
}


def load_contract(source, transition='lang'):
    return run_path(str(source / 'scripts/ci' / transition_spec(transition)['contract']))


def source_inputs(source, contract):
    projects = contract['verify_source'](source)
    require(len(projects) == 26, '26 verified reactor POMs required')
    root = projects[0][1]
    selected = [p for p in root.findall('m:build/m:plugins/m:plugin', NS)
                if p.findtext('m:artifactId', namespaces=NS) == 'maven-checkstyle-plugin']
    require(len(selected) == 1, 'one active source Checkstyle declaration required')
    hashes = {}
    for file in [p for p, _ in projects] + [source / p for p in contract['OVERLAYS']]:
        require(not file.is_symlink() and file.resolve().is_relative_to(source)
                and file.stat().st_size < 1024 * 1024 and not (file.parent / '.mvn').exists(), 'POM/bootstrap boundary')
        hashes[str(file.relative_to(source))] = digest(file)
    hashes['config/checkstyle/checkstyle.xml'] = digest(source / 'config/checkstyle/checkstyle.xml')
    return root, selected[0], hashes


def parent_xml(root, plugin, baseline=False, transition='lang'):
    spec = transition_spec(transition)
    parent = ET.Element(P + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.checkstyle.compatibility'),
                        ('artifactId', 'checkstyle-parent'), ('version', '1'), ('packaging', 'pom')]:
        ET.SubElement(parent, P + name).text = value
    properties = ET.SubElement(parent, P + 'properties')
    for name in ('maven.checkstyle.version', *((PROP,) if transition == 'guava' else ()),
                 *(() if baseline else (spec['property'],)), 'project.build.sourceEncoding'):
        matches = root.findall('m:properties/m:' + name, NS)
        require(len(matches) == 1, 'one source property required')
        properties.append(copy.deepcopy(matches[0]))
    ET.SubElement(ET.SubElement(parent, P + 'build'), P + 'plugins').append(copy.deepcopy(plugin))
    return ET.tostring(parent, encoding='unicode', default_namespace=NS['m'])


def isolated_env(jdk, sandbox):
    return {'JAVA_HOME': str(jdk), 'PATH': str(jdk / 'bin') + ':/usr/bin:/bin', 'LANG': 'C.UTF-8',
            'MAVEN_SKIP_RC': '1', 'HOME': str(sandbox / 'home'), 'TMPDIR': str(sandbox / 'tmp'),
            'XDG_CONFIG_HOME': str(sandbox / 'home')}


def execution_mode(environment, repository):
    require(environment.get('CHECKSTYLE_PLUGIN_COMPATIBILITY') == 'true', 'explicit reviewed Checkstyle opt-in required')
    ci = environment.get('GITHUB_ACTIONS') == 'true'
    require(ci or repository is not None, 'local execution requires explicit repository')
    return not ci


def prepare_repository(repository, source, offline):
    require(not source.is_relative_to(repository) and not repository.is_relative_to(source), 'repository/source overlap')
    if not offline:
        repository.mkdir(parents=True, exist_ok=True)
    require(repository.is_dir(), 'existing repository required for local offline execution')


def maven_command(maven, sandbox, repository, offline, stage, project, model_output=None):
    require(stage in ('models', 'fixtures', 'report'), 'only model or direct Checkstyle goals admitted')
    if stage != 'models':
        inside(project, sandbox)
    settings = sandbox / 'empty-settings.xml'
    base = project.parent if stage == 'models' else sandbox
    command = [str(maven), *(['-o'] if offline else []), '-B', '-ntp', '-s', str(settings), '-gs', str(settings),
               '-Dmaven.repo.local=' + str(repository), '-Dstyle.color=never',
               '-Dmaven.multiModuleProjectDirectory=' + str(base), '-f', str(project)]
    if stage == 'models':
        output = inside(model_output or sandbox / 'effective-pom.xml', sandbox)
        return command + [MODEL_GOAL, '-Doutput=' + str(output)]
    require(model_output is None, 'model output only belongs to model capture')
    return command + ['-N', '-X', *([REPORT_GOAL, '-DlinkXRef=false'] if stage == 'report' else [GOAL])]


def artifact_hashes(baseline=False, transition='lang'):
    transition_spec(transition)
    result = dict(ARTIFACT_HASHES)
    if transition == 'guava':
        if not baseline:
            for old, (new, expected) in GUAVA_REPLACEMENTS.items():
                del result[old]; result[new] = expected
    elif baseline:
        del result[LANG]
        result['org.apache.commons:commons-lang3:jar:3.17.0'] = '6ee731df5c8e5a2976a1ca023b6bb320ea8d3539fbe64c8a1d5cb765127c33b4'
    return result


def preparation_xml(baseline, transition='lang'):
    """Separate same-GA versions; no project inheritance or lifecycle."""
    prior, candidate = artifact_hashes(True, transition), artifact_hashes(False, transition)
    artifacts = prior if baseline else {**{c: h for c, h in candidate.items() if c not in prior}, **REPORT_SKIN}
    require(len(artifacts) == (59 if baseline else transition_spec(transition)['artifactUnionCount'] - 58),
            'exact preparation artifacts required')
    project = ET.Element(P + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.checkstyle.compatibility'),
                        ('artifactId', 'checkstyle-cache-' + ('baseline' if baseline else 'extras')),
                        ('version', '1'), ('packaging', 'pom')]:
        ET.SubElement(project, P + name).text = value
    dependencies = ET.SubElement(project, P + 'dependencies')
    for coordinate in sorted(artifacts):
        group, artifact, kind, version = coordinate.split(':')
        require(kind == 'jar', 'only the verified JAR union may be prepared')
        dependency = ET.SubElement(dependencies, P + 'dependency')
        for name, value in [('groupId', group), ('artifactId', artifact), ('version', version), ('type', kind)]:
            ET.SubElement(dependency, P + name).text = value
        exclusion = ET.SubElement(ET.SubElement(dependency, P + 'exclusions'), P + 'exclusion')
        ET.SubElement(exclusion, P + 'groupId').text = '*'
        ET.SubElement(exclusion, P + 'artifactId').text = '*'
    return ET.tostring(project, encoding='unicode', default_namespace=NS['m'])


def preparation_command(maven, sandbox, repository, offline, baseline):
    require(not offline, 'dependency provisioning is CI-only; local execution stays offline')
    project = inside(sandbox / ('cache-preparation-' + ('baseline' if baseline else 'extras') + '.xml'), sandbox)
    settings = inside(sandbox / 'central-settings.xml', sandbox)
    return [str(maven), '-B', '-ntp', '-C', '-N', '-s', str(settings), '-gs', str(settings),
            '-Dmaven.repo.local=' + str(repository), '-Dstyle.color=never',
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-f', str(project), PREPARE_GOAL, '-DexcludeTransitive=true']


def cached_artifacts(repository, baseline=False, allow_missing=False, transition='lang'):
    hashes = artifact_hashes(baseline, transition)
    require(len(hashes) == 59 and all(re.fullmatch('[0-9a-f]{64}', h) for h in hashes.values()), '59 valid expected artifact hashes required')
    return cached_files(repository, hashes, allow_missing)


def cached_report_skin(repository, allow_missing=False):
    return cached_files(repository, REPORT_SKIN, allow_missing)


def cached_files(repository, hashes, allow_missing):
    result = {}
    for coordinate, expected in hashes.items():
        group, artifact, kind, version = coordinate.split(':')
        file = repository / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.{kind}'
        if allow_missing and not file.exists() and not file.is_symlink():
            continue
        require(file.is_file() and file.resolve().is_relative_to(repository), 'missing/escaped artifact: ' + coordinate)
        require(file.stat().st_size <= 32 * 1024 * 1024 and digest(file) == expected, 'artifact bytes differ: ' + coordinate)
        result[coordinate] = {'path': str(file.resolve()), 'sha256': expected}
    return result


def realm_artifacts(stdout, artifacts):
    lines = stdout.splitlines()
    marker = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0'
    starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one actual Checkstyle realm required')
    coordinates = []
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not match:
            break
        coordinates.append(match[1])
    require(len(coordinates) == len(set(coordinates)) and set(coordinates) == set(artifacts) - CORE_IMPORTS, 'actual realm membership differs')
    return {c: artifacts[c] for c in coordinates}


def realm_imports(stdout):
    lines = stdout.splitlines()
    marker = '[DEBUG] Importing foreign packages into class realm plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0'
    starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one actual foreign-import block required')
    imports = []
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r'\[DEBUG\]\s+Imported:\s*(.*?)\s*', line)
        if not match:
            break
        imports.append(match[1])
    require(imports == ['< maven.api'], 'unexpected Checkstyle foreign import')
    return imports


def local_url(value):
    parsed = urlparse(value)
    require(parsed.scheme == 'file' and not parsed.netloc, 'local class origin required')
    return str(Path(unquote(parsed.path)).resolve())


def natural_origins(stdout, stderr, approved, early_failure=False, report=False, guava_discovery=False):
    pattern = r'^\[[^\n]*\]\[class,load\] '
    require(not re.search(pattern, stdout, re.M) and re.search(pattern, stderr, re.M), 'separate JVM class channel required')
    observations = []
    for line in stderr.splitlines():
        if line.startswith('CHECKSTYLE_ORIGIN\t'):
            fields = line.split('\t'); require(len(fields) == 5, 'malformed passive origin')
            observations.append(dict(zip(('class', 'loader', 'realm', 'path'), (*fields[1:4], local_url(fields[4])))))
    mojo_name = 'org/apache/maven/plugins/checkstyle/' + ('CheckstyleReport' if report else 'CheckstyleViolationCheckMojo')
    mojos = [o for o in observations if o['class'] == mojo_name]
    require(len(mojos) == 1 and 'plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0' in mojos[0]['realm'], 'natural mojo absent/wrong realm')
    identity = mojos[0]['loader']; own = [o for o in observations if o['loader'] == identity]
    urls = [local_url(line.split('\t')[2]) for line in stderr.splitlines()
            if line.startswith('CHECKSTYLE_REALM_URL\t' + identity + '\t')]
    require(len(urls) == len(set(urls)) == len(approved) and set(urls) == {v['path'] for v in approved.values()}, 'actual ordered URL inventory differs')
    required = {mojo_name}
    if not early_failure:
        required |= {'com/puppycrawl/tools/checkstyle/Checker', 'com/puppycrawl/tools/checkstyle/TreeWalker', 'org/apache/commons/lang3/StringUtils'}
    if report:
        required.add('org/apache/maven/plugins/checkstyle/CheckstyleReportRenderer')
    if guava_discovery:
        required |= {'com/puppycrawl/tools/checkstyle/utils/ModuleReflectionUtil',
                     'com/google/common/reflect/ClassPath', 'com/google/common/reflect/ClassPath$ClassInfo',
                     'com/google/common/collect/ImmutableSet'}
    require(required <= {o['class'] for o in own}, 'required naturally loaded classes missing')
    langs = [c for c in approved if c.startswith('org.apache.commons:commons-lang3:jar:')]
    require(len(langs) == 1, 'one private Lang version required')
    guavas = [c for c in approved if c.startswith('com.google.guava:guava:jar:')]
    require(len(guavas) == 1, 'one private Guava version required')
    jvm = set()
    for match in re.finditer(r'^\[[^\n]*\]\[class,load\] (\S+) source: (.+?)\s*$', stderr, re.M):
        if match[2].startswith('file:'):
            jvm.add((match[1], local_url(match[2])))
    for item in observations:
        require((item['class'].replace('/', '.'), item['path']) in jvm, 'origin lacks JVM corroboration')
    for item in own:
        name = item['class']
        coordinate = OWNER if name.startswith('org/apache/maven/plugins/checkstyle/') else ENGINE if name.startswith('com/puppycrawl/tools/checkstyle/') else langs[0] if name.startswith('org/apache/commons/lang3/') else 'com.google.guava:failureaccess:jar:1.0.1' if name.startswith('com/google/common/util/concurrent/internal/') else guavas[0] if name.startswith('com/google/common/') else None
        require(item['path'] in urls and (coordinate is None or item['path'] == approved[coordinate]['path']), 'natural class owner/version mismatch')
    return {'pluginLoader': identity, 'actualPrivateRealmUrls': urls, 'pluginRealmObservations': own,
            'otherLoaderObservations': [o for o in observations if o['loader'] != identity], 'absenceIsNotNonReachabilityEvidence': True}


def logging_import(origins, maven):
    entries = [o for o in origins['otherLoaderObservations'] if o['class'] == 'org/slf4j/LoggerFactory']
    require(len(entries) == 1, 'one observed core logging import required')
    file = Path(entries[0]['path']); coordinate = 'org.slf4j:slf4j-api:jar:1.7.36'
    require(file.is_relative_to(maven.parent.parent / 'lib') and digest(file) == ARTIFACT_HASHES[coordinate], 'core logging origin differs')
    return {'coordinate': coordinate, 'path': str(file), 'sha256': digest(file), 'observedLoader': entries[0]['loader']}


class Commands:
    """Bounded pipes/deadlines; clean the entire owned group even after leader exit."""
    def __init__(self, sandbox, environment, seconds):
        self.sandbox, self.environment = sandbox, environment
        self.deadline, self.total_bytes, self.results = time.monotonic() + seconds, 0, []

    def run(self, label, command, seconds=30, extra_env=None):
        require(re.fullmatch('[a-z0-9-]+', label) and not any(r['case'] == label for r in self.results), 'unique command label required')
        limit = min(seconds, self.deadline - time.monotonic()); require(limit > 0, 'aggregate deadline exhausted')
        files = [inside(self.sandbox / (label + suffix), self.sandbox) for suffix in ('.stdout.log', '.stderr.log')]
        record = {'case': label, 'command': command, 'status': 'STARTING', 'exitStatus': None}; self.results.append(record)
        start, process, size, cleaned = time.monotonic(), None, 0, False
        try:
            with files[0].open('xb') as out, files[1].open('xb') as err, selectors.DefaultSelector() as selector:
                process = subprocess.Popen(command, cwd=self.sandbox, env=dict(self.environment, **(extra_env or {})),
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                for pipe, stream in ((process.stdout, out), (process.stderr, err)):
                    selector.register(pipe, selectors.EVENT_READ, stream)
                record['status'] = 'RUNNING'
                while selector.get_map():
                    require(time.monotonic() - start < limit, 'command deadline exceeded')
                    for key, _ in selector.select(timeout=0.1):
                        data = os.read(key.fileobj.fileno(), 65536)
                        if not data:
                            selector.unregister(key.fileobj); key.fileobj.close(); continue
                        remaining = min(16 * 1024 * 1024 - size, 96 * 1024 * 1024 - self.total_bytes)
                        saved = data[:max(0, remaining)]; key.data.write(saved)
                        size += len(saved); self.total_bytes += len(saved)
                        require(len(saved) == len(data), 'output budget exceeded')
                process.wait(timeout=max(0.001, limit - (time.monotonic() - start)))
                record.update(status='EXITED', exitStatus=process.returncode)
        except BaseException as exc:
            record.update(status='ERROR', errorType=type(exc).__name__)
            raise
        finally:
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                cleaned = True
                process.wait(timeout=5)
                if record['exitStatus'] is None:
                    record['exitStatus'] = process.returncode
                for pipe in (process.stdout, process.stderr):
                    if pipe and not pipe.closed:
                        pipe.close()
            record.update(elapsedSeconds=round(time.monotonic() - start, 3), capturedBytes=size,
                          captureComplete=record['status'] == 'EXITED', processGroupCleaned=cleaned)
            for channel, file in zip(('stdout', 'stderr'), files):
                if file.is_file():
                    record[channel + 'Sha256'] = digest(file)
            print('CHECKSTYLE_COMMAND ' + json.dumps(record), flush=True)
        return record['exitStatus'], files[0].read_text(errors='replace'), files[1].read_text(errors='replace')
