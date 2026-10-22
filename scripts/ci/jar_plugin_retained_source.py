"""Sealed JAR, Lang and Resources/Flatten successors to Assembly; no project execution.

Historical contracts retain their original, strict input identities. This adapter
copies one bounded historical input closure. Resources/Flatten reverses exactly
three blocks in two POMs before the existing root-only Lang/JAR reversals.
Each reversal is compared with independently pinned predecessor bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

CURRENT_POM = '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f'
PRIOR_POM = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629'
LANG_POM = 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932'
RF_POM = '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab'
RF_SDK_POM = '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078'
SDK_PATH = 'integrations/host-sdk/pom.xml'
SDK_POM = 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701'
LANG_ARCHIVE = 'docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml'
SDK_ARCHIVE = 'docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml'
ARCHIVE = 'docs/operations/assembly-plexus-evidence/candidate-root-pom.xml'
JAR_ARCHIVE = 'docs/operations/jar-plugin-evidence/candidate-root-pom.xml'
WITNESS = 'docs/operations/jacoco-antrun-plugin-evidence/source-witness.json'
WITNESS_SHA = '56e5701e73c25262887edcf73663cf9f7eff870a34891927107dfeceec174971'
PROPERTY = b'        <maven.jar.version>3.5.1</maven.jar.version>\n'
DECLARATION = b'''                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>${maven.jar.version}</version>
                </plugin>
'''
LANG_EDITS = (
    (b'        <maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>\n', b''),
    (b'        <maven.assembly.commons-lang3.version>3.18.0</maven.assembly.commons-lang3.version>\n', b''),
    (b'                <!-- Keep Assembly inactive; constrain its private utility dependencies. -->\n',
     b'                <!-- Keep Assembly inactive; constrain only its private Plexus Utils dependency. -->\n'),
    (b'''                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>${maven.assembly.commons-lang3.version}</version>
                        </dependency>
''', b''),
    (b'''                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>${maven.enforcer.commons-lang3.version}</version>
                    </dependency>
                </dependencies>
''', b''),
)
RF_ADDITIONS = {
    'pom.xml': (
        b'''                <!-- Preserve the lifecycle version; constrain Resources' private utility dependency. -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-resources-plugin</artifactId>
                    <version>3.4.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.codehaus.plexus</groupId>
                            <artifactId>plexus-utils</artifactId>
                            <version>4.0.3</version>
                        </dependency>
                    </dependencies>
                </plugin>
''',
        b'''                        <dependencies>
                            <dependency>
                                <groupId>org.codehaus.plexus</groupId>
                                <artifactId>plexus-utils</artifactId>
                                <version>4.0.3</version>
                            </dependency>
                        </dependencies>
''',
    ),
    'integrations/host-sdk/pom.xml': (
        b'''                <dependencies>
                    <dependency>
                        <groupId>org.codehaus.plexus</groupId>
                        <artifactId>plexus-utils</artifactId>
                        <version>4.0.3</version>
                    </dependency>
                </dependencies>
''',
    ),
}
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
HISTORICAL_FILES = {
    ARCHIVE: PRIOR_POM,
    WITNESS: WITNESS_SHA,
    'config/checkstyle/checkstyle.xml': 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0',
    'scripts/ci/assembly_plexus_contract.py': 'a96e9b3433b75d7e68a1b5dbe641956a091a70f403df88ece25bc632dbf123c7',
    'scripts/ci/assembly_plexus_retained_source.py': 'd9a7224b2e1656361e6154c490e01dd49d68e4bdcd28b5b98250189211ef2e4a',
    'scripts/ci/jacoco_antrun_contract.py': 'a3ea83d6d5127cfa32cdac1ed5ed62cdc48dd1d468a40a767570e6cf3edbae9e',
    'scripts/ci/boot_utilities_contract.py': '6ef0328fc5fb015e9a9f13a7999236275aaead5d0364fa0ed7ec2296bbced39f',
    'docs/operations/boot-utilities-plugin-evidence/candidate-root-pom.xml': '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579',
    'docs/operations/checkstyle-guava-evidence/candidate-root-pom.xml': 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5',
    'docs/operations/checkstyle-plugin-evidence/candidate-root-pom.xml': 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb',
    'docs/operations/compiler-plugin-evidence/candidate-root-pom.xml': '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a',
    'docs/operations/compiler-plugin-evidence/source-witness.json': '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a',
    'scripts/ci/checkstyle_guava_contract.py': '3a638ea0678150388f7b9b1b21d7fcb649e1c5541250c8c51dafc0e0c2a48a7d',
    'scripts/ci/checkstyle_plugin_contract.py': 'd5d1927221ae93a27dc07ace72cc8d48b2e0af1b9cae55ee2c6d34434902c0cf',
    'scripts/ci/clean_plugin_contract.py': 'b48a8ad2d3baf4d8da714bf6017dcbdf6bbd19eed500576a17fdf08b43eaa75e',
    'scripts/ci/compiler_plugin_contract.py': '03f19ef2c91bebc41ef52e4c5822e2287173452f7545daec34048a12140344c1',
    'scripts/ci/verify-checkstyle-plugin-baseline.py': 'dcc78fdac45c3814893efc81d1c771bfe47da2083a6585034822591e4a6938c0',
    'scripts/ci/verify-clean-plugin-baseline.py': 'ae248f133cd747b655030d4d3858d87f715dbd791b11e605206e032b1c62e433',
    'scripts/ci/verify-compiler-plugin-baseline.py': '79e06c0a0eea3a4e19626e6c256796423078d62348a70e7cf08f47683e5de0d6',
    'scripts/ci/verify-release-plugin-baseline.py': '2fb1c9b8552c0ce913842550e2a3bf9c8f890ccda7fdac11bcebb618284e9e28',
    'scripts/ci/boot_http_contract.py': 'f87c8effab9a8707821bed67c7dfaa7c8378388e98c2ad607b10f8258511220c',
    'docs/operations/boot-http-plugin-evidence/candidate-root-pom.xml': 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1',
}


def require(ok, message):
    if not ok:
        raise ValueError('JAR historical source ' + message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def real_root(root):
    root = Path(root)
    require(root.is_absolute() and root.resolve() == root and root.is_dir()
            and not root.is_symlink(), 'requires a canonical real source directory')
    return root


def checked(root, name, expected=None):
    require(isinstance(name, str) and not name.startswith('/') and '\\' not in name
            and all(part not in ('', '.', '..') for part in name.split('/')),
            'unsafe member path')
    file = root / name
    require(file.is_file() and not file.is_symlink() and file.resolve() == file
            and file.is_relative_to(root) and stat.S_ISREG(file.stat().st_mode)
            and file.stat().st_size <= MAX_FILE_BYTES, 'requires bounded real file: ' + name)
    raw = file.read_bytes()
    require(len(raw) <= MAX_FILE_BYTES and (expected is None or digest(raw) == expected),
            'bytes drift: ' + name)
    return raw


def is_jar_source(root):
    """Dispatch hint only. Source inventory verification must follow."""
    raw = checked(real_root(root), 'pom.xml')
    return b'<maven.jar.version>' in raw or b'<artifactId>maven-jar-plugin</artifactId>' in raw


def reverse_lang_source(raw):
    """Reverse the exact two-owner Lang edits; retain JAR's historical identity."""
    require(digest(raw) == LANG_POM, 'Lang root bytes changed')
    for addition, previous in LANG_EDITS:
        require(raw.count(addition) == 1, 'exact Lang edit missing or ambiguous')
        raw = raw.replace(addition, previous, 1)
    require(digest(raw) == CURRENT_POM, 'root changed beyond exact Lang edits')
    return raw


def reverse_resources_flatten(name, raw):
    """Pure byte reversal only; this does not accept a source pair."""
    require(name in RF_ADDITIONS, 'unrecognized Resources/Flatten member')
    expected = RF_POM if name == 'pom.xml' else RF_SDK_POM
    previous = LANG_POM if name == 'pom.xml' else SDK_POM
    require(digest(raw) == expected, 'Resources/Flatten bytes drift: ' + name)
    for addition in RF_ADDITIONS[name]:
        require(raw.count(addition) == 1, 'exact Resources/Flatten addition missing or ambiguous: ' + name)
        raw = raw.replace(addition, b'', 1)
    require(digest(raw) == previous, 'unrelated Resources/Flatten source change: ' + name)
    return raw


def checked_source_pair(source):
    """Fresh pair acceptance; RF reads exactly both POMs and both originals.

    Every old Python source closure already includes SDK. Check its unchanged
    bytes even when the root is historical, before any root-only fast path.
    """
    raw = checked(source, 'pom.xml')
    if digest(raw) == RF_POM:
        sdk = checked(source, SDK_PATH, RF_SDK_POM)
        root_original = checked(source, LANG_ARCHIVE, LANG_POM)
        sdk_original = checked(source, SDK_ARCHIVE, SDK_POM)
        require(reverse_resources_flatten('pom.xml', raw) == root_original
                and reverse_resources_flatten(SDK_PATH, sdk) == sdk_original,
                'Resources/Flatten reversal differs from independent originals')
    else:
        checked(source, SDK_PATH, SDK_POM)
    return raw


def reverse_source(raw):
    # Source acceptance must call checked_source_pair; this is pure bytes only.
    identity = digest(raw)
    require(identity in (RF_POM, LANG_POM, CURRENT_POM, PRIOR_POM), 'root bytes changed')
    if identity == RF_POM:
        raw = reverse_resources_flatten('pom.xml', raw)
        identity = LANG_POM
    if identity == PRIOR_POM:
        return raw
    if identity == LANG_POM:
        raw = reverse_lang_source(raw)
    require(raw.count(PROPERTY) == raw.count(DECLARATION) == 1,
            'exact property or managed declaration missing or ambiguous')
    prior = raw.replace(PROPERTY, b'', 1).replace(DECLARATION, b'', 1)
    require(digest(prior) == PRIOR_POM, 'root changed beyond exact JAR additions')
    return prior


def expected_inventory(root, root_sha):
    rows = json.loads(checked(root, WITNESS, WITNESS_SHA))['sourcePomFiles']
    require(isinstance(rows, list) and len(rows) == 28
            and len({row['path'] for row in rows}) == 28
            and sum(row['path'] == 'pom.xml' for row in rows) == 1,
            'requires exact retained 28-POM inventory')
    result = {row['path']: root_sha if row['path'] == 'pom.xml' else row['candidateSha256'] for row in rows}
    require(not set(result) & set(HISTORICAL_FILES), 'historical inventory overlap')
    result.update(HISTORICAL_FILES)
    require(len(result) == 50, 'historical closure membership changed')
    if root_sha in (LANG_POM, RF_POM):
        require(JAR_ARCHIVE not in result, 'Lang archive inventory overlap')
        result[JAR_ARCHIVE] = CURRENT_POM
        require(len(result) == 51, 'Lang closure membership changed')
    if root_sha == RF_POM:
        require(result.get(SDK_PATH) == SDK_POM and LANG_ARCHIVE not in result
                and SDK_ARCHIVE not in result, 'Resources/Flatten inventory overlap or SDK baseline drift')
        result[SDK_PATH] = RF_SDK_POM
        result.update({LANG_ARCHIVE: LANG_POM, SDK_ARCHIVE: SDK_POM})
        require(len(result) == 53, 'Resources/Flatten closure membership changed')
    return result


def check_inventory(root, expected):
    total = 0
    for name, sha in expected.items():
        total += len(checked(root, name, sha))
        require(total <= MAX_TOTAL_BYTES, 'total input bound exceeded')
    roots = {root, *root.parents}
    for name in expected:
        if name.endswith('pom.xml'):
            directory = (root / name).parent
            roots.update([directory, *[p for p in directory.parents if p.is_relative_to(root)]])
    require(all(not (p / '.mvn').exists() and not (p / '.mvn').is_symlink() for p in roots),
            'unreviewed Maven configuration')


def source_inventory(source):
    source = real_root(source)
    raw = checked_source_pair(source)
    prior = reverse_source(raw)
    expected = expected_inventory(source, digest(raw))
    check_inventory(source, expected)
    if digest(raw) in (LANG_POM, RF_POM):
        lang = reverse_resources_flatten('pom.xml', raw) if digest(raw) == RF_POM else raw
        require(reverse_lang_source(lang) == checked(source, JAR_ARCHIVE, CURRENT_POM),
                'Lang reversal differs from accepted JAR archive')
    require(prior == checked(source, ARCHIVE, PRIOR_POM), 'reversal differs from accepted archive')
    return expected


def projection_identity(root_sha):
    if root_sha == RF_POM:
        return {
            'schemaVersion': 'RESOURCES_FLATTEN_RETAINED_ASSEMBLY_SOURCE_VIEW_V1',
            'reversalApplied': True,
            'sourcePomReversals': [
                {'path': 'pom.xml', 'fromSha256': RF_POM, 'toSha256': LANG_POM,
                 'archivePath': LANG_ARCHIVE, 'archiveSha256': LANG_POM, 'removedBlockCount': 2},
                {'path': SDK_PATH, 'fromSha256': RF_SDK_POM, 'toSha256': SDK_POM,
                 'archivePath': SDK_ARCHIVE, 'archiveSha256': SDK_POM, 'removedBlockCount': 1},
            ],
            'reversalSteps': [
                {'fromRootPomSha256': RF_POM, 'toRootPomSha256': LANG_POM,
                 'archivePath': LANG_ARCHIVE, 'archiveSha256': LANG_POM},
                {'fromRootPomSha256': LANG_POM, 'toRootPomSha256': CURRENT_POM,
                 'archivePath': JAR_ARCHIVE, 'archiveSha256': CURRENT_POM},
                {'fromRootPomSha256': CURRENT_POM, 'toRootPomSha256': PRIOR_POM,
                 'archivePath': ARCHIVE, 'archiveSha256': PRIOR_POM},
            ],
            'sdkPomPath': SDK_PATH, 'suppliedSdkPomSha256': RF_SDK_POM,
            'retainedSdkPomSha256': SDK_POM, 'retainedSdkArchivePath': SDK_ARCHIVE,
            'retainedSdkArchiveSha256': SDK_POM,
            'scope': 'Exact Resources/Flatten two-POM additions then existing Lang/JAR root additions reversed to accepted Assembly bytes; no current Resources/Flatten compatibility claim.',
        }
    if root_sha == LANG_POM:
        return {
            'schemaVersion': 'ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1',
            'reversalApplied': True,
            'reversalSteps': [
                {'fromRootPomSha256': LANG_POM, 'toRootPomSha256': CURRENT_POM,
                 'archivePath': JAR_ARCHIVE, 'archiveSha256': CURRENT_POM},
                {'fromRootPomSha256': CURRENT_POM, 'toRootPomSha256': PRIOR_POM,
                 'archivePath': ARCHIVE, 'archiveSha256': PRIOR_POM},
            ],
            'scope': 'Exact two-owner Lang edits then JAR additions reversed to accepted Assembly bytes; no current Lang or JAR compatibility claim.',
        }
    return {
        'schemaVersion': 'JAR_PLUGIN_RETAINED_ASSEMBLY_SOURCE_VIEW_V1',
        'reversalApplied': root_sha == CURRENT_POM,
        'scope': 'Exact JAR root additions reversed to accepted Assembly bytes; no current JAR compatibility claim.',
    }


def materialize_accepted_source(source, destination):
    source, destination = real_root(source), Path(destination)
    require(destination.is_absolute() and destination.resolve() == destination
            and destination.parent.is_dir() and not destination.exists() and not destination.is_symlink()
            and not destination.is_relative_to(source) and not source.is_relative_to(destination),
            'projection requires fresh real path outside source')
    supplied = source_inventory(source)
    destination.mkdir(parents=False, exist_ok=False)
    retained = {**supplied, 'pom.xml': PRIOR_POM}
    if supplied['pom.xml'] == RF_POM:
        retained[SDK_PATH] = SDK_POM
    for name, sha in supplied.items():
        raw = checked(source, name, sha)
        if name == 'pom.xml':
            raw = reverse_source(raw)
        elif name == SDK_PATH and supplied['pom.xml'] == RF_POM:
            raw = reverse_resources_flatten(name, raw)
        file = destination / name
        file.parent.mkdir(parents=True, exist_ok=True)
        with file.open('xb') as stream:
            stream.write(raw)
    result = {
        **projection_identity(supplied['pom.xml']),
        'suppliedSourceRoot': str(source), 'retainedSourceRoot': str(destination),
        'suppliedSourceHashes': supplied, 'retainedSourceHashes': retained,
        'suppliedRootPomSha256': supplied['pom.xml'], 'retainedRootPomSha256': PRIOR_POM,
        'retainedArchivePath': ARCHIVE, 'retainedArchiveSha256': PRIOR_POM,
        'sourcePomCount': 28, 'sourceFileCount': len(supplied),
        'adapterSha256': digest(Path(__file__).read_bytes()),
    }
    verify_projection(result)
    return result


def verify_projection(projection):
    source = real_root(projection['suppliedSourceRoot'])
    retained = real_root(projection['retainedSourceRoot'])
    supplied = source_inventory(source)
    expected = {**supplied, 'pom.xml': PRIOR_POM}
    if supplied['pom.xml'] == RF_POM:
        expected[SDK_PATH] = SDK_POM
    identity = projection_identity(supplied['pom.xml'])
    require(all(projection.get(key) == value for key, value in identity.items()
                if key != 'scope' or supplied['pom.xml'] in (LANG_POM, RF_POM))
            and projection['suppliedRootPomSha256'] == supplied['pom.xml']
            and projection['retainedRootPomSha256'] == PRIOR_POM
            and projection['retainedArchivePath'] == ARCHIVE
            and projection['retainedArchiveSha256'] == PRIOR_POM
            and projection['sourcePomCount'] == 28 and projection['sourceFileCount'] == len(supplied)
            and projection['reversalApplied'] is identity['reversalApplied']
            and projection['adapterSha256'] == digest(Path(__file__).read_bytes()),
            'projection identity changed')
    require(not retained.is_relative_to(source) and not source.is_relative_to(retained),
            'projection roots overlap')
    require(projection['suppliedSourceHashes'] == supplied and projection['retainedSourceHashes'] == expected,
            'projection inventory changed')
    check_inventory(retained, expected)
    if supplied['pom.xml'] == RF_POM:
        require(reverse_resources_flatten('pom.xml', checked(source, 'pom.xml', RF_POM))
                == checked(source, LANG_ARCHIVE, LANG_POM) == checked(retained, LANG_ARCHIVE, LANG_POM),
                'Resources/Flatten root projection reversal changed')
        require(reverse_resources_flatten(SDK_PATH, checked(source, SDK_PATH, RF_SDK_POM))
                == checked(retained, SDK_PATH, SDK_POM) == checked(source, SDK_ARCHIVE, SDK_POM)
                == checked(retained, SDK_ARCHIVE, SDK_POM), 'Resources/Flatten SDK projection reversal changed')
    if supplied['pom.xml'] in (LANG_POM, RF_POM):
        lang = checked(source, 'pom.xml', supplied['pom.xml'])
        if supplied['pom.xml'] == RF_POM:
            lang = reverse_resources_flatten('pom.xml', lang)
        require(reverse_lang_source(lang)
                == checked(source, JAR_ARCHIVE, CURRENT_POM) == checked(retained, JAR_ARCHIVE, CURRENT_POM),
                'Lang projection reversal changed')
    require(reverse_source(checked(source, 'pom.xml', supplied['pom.xml']))
            == checked(retained, 'pom.xml', PRIOR_POM) == checked(source, ARCHIVE, PRIOR_POM),
            'projection reversal changed')
    members, directories, count = set(), set(), 0
    for directory, folders, files in os.walk(retained, followlinks=False):
        require(len(Path(directory).relative_to(retained).parts) <= 16, 'retained view depth exceeded')
        for name in folders + files:
            file = Path(directory) / name
            count += 1
            require(count <= 256 and not file.is_symlink() and file.resolve() == file,
                    'retained view path or membership bound changed')
            if name in files:
                require(file.is_file(), 'retained view non-file member')
                members.add(file.relative_to(retained).as_posix())
            else:
                directories.add(file.relative_to(retained).as_posix())
    expected_directories = {parent.as_posix() for name in expected for parent in Path(name).parents if parent != Path('.')}
    require(members == set(expected) and directories == expected_directories, 'retained view membership changed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--materialize-accepted-source', type=Path)
    args = parser.parse_args()
    result = (materialize_accepted_source(args.root, args.materialize_accepted_source)
              if args.materialize_accepted_source else source_inventory(args.root))
    print(json.dumps(result, sort_keys=True, indent=2))
