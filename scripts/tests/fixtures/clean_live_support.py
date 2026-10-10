"""Bounded live Clean test helpers. Importing this module starts no process."""
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

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
PREFIX = '{' + NS['m'] + '}'
CLEAN_GOAL = 'org.apache.maven.plugins:maven-clean-plugin:clean'
MODEL_GOAL = 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom'
PLUGIN = 'org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0'
SHARED = 'org.apache.maven.shared:maven-shared-utils:jar:3.3.4'
IO = 'commons-io:commons-io:jar:2.20.0'
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CONTRACT = run_path(str(ROOT / 'scripts/ci/clean_plugin_contract.py'))
RELEASE = run_path(str(ROOT / 'scripts/ci/verify-release-plugin-baseline.py'))
MAX_OUTPUT = 16 * 1024 * 1024
DIAGNOSTIC_SCAN_BYTES = 256 * 1024
API_ASSERTION_LABELS = frozenset([
    'IO version, directory and exactly three actual realm JARs supplied',
    'exact candidate IO version', 'new disposable directory required', 'only three observed Clean-realm artifacts',
    'Shared FileUtils delegates equal multi-buffer streams to IOUtils',
    'Shared FileUtils detects a same-length middle-byte change', 'ordinary path normalization linkage',
    'Shared XML reader preserves UTF-8 BOM text through real Commons IO',
    'Shared XML reader detects UTF-16 BOM', 'Shared XML writer links and preserves declared encoding',
    *[prefix + name for prefix in ['unique realm JAR ', 'approved artifact bytes ']
      for name in ['maven-clean-plugin-3.2.0.jar', 'maven-shared-utils-3.3.4.jar', 'commons-io-2.20.0.jar']],
    *[name + ' originates in ' + jar for name, jar in [
        ('org.apache.commons.io.IOUtils', 'commons-io-2.20.0.jar'),
        ('org.apache.commons.io.FilenameUtils', 'commons-io-2.20.0.jar'),
        ('org.apache.commons.io.input.XmlStreamReader', 'commons-io-2.20.0.jar'),
        ('org.apache.commons.io.output.XmlStreamWriter', 'commons-io-2.20.0.jar'),
        ('org.apache.maven.shared.utils.io.FileUtils', 'maven-shared-utils-3.3.4.jar'),
        ('org.apache.maven.shared.utils.xml.XmlStreamReader', 'maven-shared-utils-3.3.4.jar'),
        ('org.apache.maven.shared.utils.xml.XmlStreamWriter', 'maven-shared-utils-3.3.4.jar')]],
])


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inside(path, boundary):
    """Require both lexical and resolved containment, including dangling links."""
    require(path.is_absolute() and path != boundary and path.is_relative_to(boundary),
            'path must be a strict sandbox descendant')
    resolved = path.resolve()
    require(resolved != boundary and resolved.is_relative_to(boundary), 'resolved path escapes or equals sandbox boundary')
    return path


def source_gate(source):
    projects = RELEASE['verify_source'](source)
    files = [p for p, _ in projects] + [source / p for p in CONTRACT['OVERLAYS']]
    # No project-controlled Maven bootstrap configuration may alter these commands.
    require(not any((p.parent / '.mvn').exists() for p in files), 'unexpected source Maven bootstrap configuration')
    hashes = {str(p.relative_to(source)): digest(p) for p in files}
    return projects, hashes


def extracted_parent(project):
    """Called only after source_gate; copy exact pins and validated Clean XML."""
    plugins = project.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
    selected = [p for p in plugins if CONTRACT['targeted'](p)]
    require(len(selected) == 1, 'exactly one validated source Clean declaration required')
    CONTRACT['verify_plugin'](selected[0])
    parent = ET.Element(PREFIX + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.clean.compatibility'),
                        ('artifactId', 'clean-parent'), ('version', '1'), ('packaging', 'pom')]:
        ET.SubElement(parent, PREFIX + name).text = value
    props = ET.SubElement(parent, PREFIX + 'properties')
    source_props = project.find('m:properties', NS)
    for pin, expected in CONTRACT['PINS'].items():
        require(CONTRACT['value'](source_props, pin) == expected, 'source Clean pin changed')
        props.append(copy.deepcopy(CONTRACT['one'](source_props, pin)))
    managed = ET.SubElement(ET.SubElement(ET.SubElement(parent, PREFIX + 'build'),
                                         PREFIX + 'pluginManagement'), PREFIX + 'plugins')
    managed.append(copy.deepcopy(selected[0]))
    # Serialize the complete parent once. Nested xmlns attributes are rejected by Maven.
    return ET.tostring(parent, encoding='unicode', default_namespace=NS['m'])


def isolated_env(jdk, sandbox):
    # Intentionally do not inherit JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS,
    # MAVEN_ARGS, MAVEN_OPTS, CLASSPATH, agents, credentials or personal settings.
    return {'JAVA_HOME': str(jdk), 'PATH': str(jdk / 'bin') + ':/usr/bin:/bin',
            'LANG': 'C.UTF-8', 'MAVEN_SKIP_RC': '1', 'HOME': str(sandbox / 'home'),
            'TMPDIR': str(sandbox / 'tmp'), 'XDG_CONFIG_HOME': str(sandbox / 'home')}


def execution_mode(environment, repository, force_offline=False):
    ci = environment.get('GITHUB_ACTIONS') == 'true'
    require(ci or environment.get('CLEAN_PLUGIN_COMPATIBILITY') == 'true', 'live execution is not enabled')
    require(ci or repository is not None, 'local live validation requires an explicit pinned repository')
    return force_offline or not ci


def maven_command(maven, sandbox, repository, offline, stage, project=None, flags=()):
    settings = sandbox / 'empty-settings.xml'
    # Preserve the actual reactor's path-valued model properties (for example
    # checkstyle configLocation). The launcher still uses only sandbox .mvn/RC.
    model_directory = project.parent if stage == 'models' and project is not None else sandbox
    args = [str(maven), *(['-o'] if offline else []), '-B', '-ntp', '-s', str(settings),
            '-gs', str(settings), '-Dmaven.repo.local=' + str(repository), '-Dstyle.color=never',
            '-Dmaven.multiModuleProjectDirectory=' + str(model_directory)]
    if stage == 'version':
        require(project is None and not flags, 'version command takes no project/flags')
        return args + ['--version']
    if stage == 'models':
        require(project is not None and not flags, 'model capture requires only actual root POM')
        return args + ['-f', str(project), MODEL_GOAL, '-Doutput=' + str(sandbox / 'effective-pom.xml')]
    require(stage == 'clean' and project is not None, 'only models or bounded direct Clean are supported')
    inside(project, sandbox)
    fixed = {'maven.clean.fast': 'false', 'maven.clean.followSymLinks': 'false',
             'maven.clean.skip': 'false', 'maven.clean.excludeDefaultDirectories': 'false',
             'maven.clean.failOnError': 'true', 'maven.clean.retryOnError': 'true',
             'maven.clean.fastDir': str(inside(project.parent / 'bounded-fast-directory', sandbox))}
    allowed = set(fixed) - {'maven.clean.fastDir'}
    for key, value in flags:
        require(key in allowed or key == 'maven.clean.fastMode', 'unexpected Clean fixture property')
        require(value in ({'invalid-fixture-value'} if key == 'maven.clean.fastMode' else {'true', 'false'}),
                'unbounded Clean property value')
        fixed[key] = value
    return args + ['-f', str(project), '-N', '-X', CLEAN_GOAL, *['-D' + k + '=' + v for k, v in fixed.items()]]


def artifacts(log_file, repository):
    coordinates = CONTRACT['verify_realm'](log_file, repository)
    result = {}
    for coordinate in coordinates:
        group, name, kind, version = coordinate.split(':')
        file = repository / group.replace('.', '/') / name / version / f'{name}-{version}.{kind}'
        result[coordinate] = {'path': str(file.resolve()), 'sha256': digest(file)}
    return coordinates, result


def verify_cached_artifacts(repository, require_present):
    """Before deletion, reject changed cached JARs; CI may fetch missing official artifacts."""
    for coordinate, expected in CONTRACT['ARTIFACT_HASHES'].items():
        group, name, kind, version = coordinate.split(':')
        file = repository / group.replace('.', '/') / name / version / f'{name}-{version}.{kind}'
        if not file.exists():
            require(not require_present, 'approved offline Clean realm artifact is missing: ' + coordinate)
            continue
        require(file.is_file() and file.resolve().is_relative_to(repository), 'cached realm artifact escapes repository')
        require(digest(file) == expected, 'cached realm artifact SHA-256 differs: ' + coordinate)


def origins(log, approved, *, class_log=None, skip=False, selectors_used=False, early_invalid=False):
    observations = []
    for line in log.splitlines():
        if not line.startswith('CLEAN_ORIGIN\t'):
            continue
        fields = line.split('\t')
        require(len(fields) == 5, 'malformed natural class-origin observation')
        _, name, loader, realm, location = fields
        parsed = urlparse(location)
        require(parsed.scheme == 'file' and not parsed.netloc, 'nonlocal/missing natural class source')
        observations.append({'class': name, 'loader': loader, 'realm': realm,
                             'path': str(Path(unquote(parsed.path)).resolve())})
    mojos = [o for o in observations if o['class'] == 'org/apache/maven/plugins/clean/CleanMojo']
    require(len(mojos) == 1, 'missing/duplicate naturally loaded CleanMojo')
    mojo = mojos[0]
    require('plugin>org.apache.maven.plugins:maven-clean-plugin:3.2.0' in mojo['realm'],
            'CleanMojo not in the selected Maven plugin realm')
    own = [o for o in observations if o['loader'] == mojo['loader']]
    required = {'org/apache/maven/plugins/clean/CleanMojo'}
    if not skip and not early_invalid:
        required |= {'org/apache/maven/plugins/clean/Cleaner', 'org/apache/maven/shared/utils/Os'}
    if selectors_used:
        required |= {'org/apache/maven/plugins/clean/GlobSelector', 'org/apache/maven/shared/utils/io/DirectoryScanner',
                     'org/apache/maven/shared/utils/io/SelectorUtils'}
    require(required <= {o['class'] for o in own}, 'required naturally exercised class absent from Clean loader')
    jvm = set()
    for match in re.finditer(r'^\[[^\n]*\]\[class,load\] (\S+) source: (\S+)\s*$',
                             log if class_log is None else class_log, re.M):
        parsed = urlparse(match[2])
        if parsed.scheme == 'file' and not parsed.netloc:
            jvm.add((match[1], str(Path(unquote(parsed.path)).resolve())))
    for observation in own:
        name = observation['class']
        coordinate = PLUGIN if name.startswith('org/apache/maven/plugins/clean/') else SHARED if name.startswith('org/apache/maven/shared/utils/') else IO
        require(observation['path'] == approved[coordinate]['path'], 'natural class origin differs: ' + name)
        require((name.replace('/', '.'), observation['path']) in jvm,
                'passive observation not corroborated by JVM class-load log: ' + name)
    return {'pluginLoader': mojo['loader'], 'pluginRealmObservations': own,
            'otherLoaderObservations': [o for o in observations if o['loader'] != mojo['loader']],
            'commonsIoNaturallyObservedInCleanLoader': any(o['class'].startswith('org/apache/commons/io/') for o in own),
            'absenceIsNotNonReachabilityEvidence': True}


def safe_diagnostics(text, *, scan_truncated=False):
    """Extract bounded causes, never raw log lines, XML, source or secret values.

    Unknown messages get a category/hash, rather than a permissive string scrub.
    Coordinates/class names are accepted only through narrow character grammars.
    """
    causes, redacted, omitted, long_lines, probe_passes = [], 0, 0, 0, []
    text = re.sub(r'\x1b\[[0-9;]*m', '', text)
    for raw in text.splitlines():
        if raw.startswith('PASS ') and raw[5:] in API_ASSERTION_LABELS and raw[5:] not in probe_passes:
            probe_passes.append(raw[5:])
        line_hash = hashlib.sha256(raw.encode()).hexdigest()
        if len(raw) > 2048:
            raw = raw[:1024] + ' [long line omitted] ' + raw[-1024:]
            long_lines += 1
        if not (re.search(r'\[(?:ERROR|WARNING)\]', raw)
                or re.search(r'\.java:\d+: error:', raw)
                or re.match(r'(?:Exception in thread |Caused by: |\s*[\w.$]{1,200}(?:Exception|Error)\b|[Ee]rror:|Error opening zip)', raw)):
            continue
        sensitive = bool(re.search(r'(?i)password|passwd|secret|token|credential|authorization|bearer|api[_-]?key|private[_ -]?key', raw))
        if sensitive:
            redacted += 1
        cause = {'kind': 'UNRECOGNIZED_ERROR', 'detail': 'Raw detail withheld',
                 'rawLineSha256': line_hash}
        # Inspect fixed phrases even when a line also contains sensitive material;
        # never copy its arbitrary values or its URLs into the result.
        if re.search(r'Non-parseable POM|Malformed POM|Unknown attribute|Unrecognised tag|expected START_TAG', raw):
            cause.update(kind='MAVEN_XML_PARSE', detail='Maven could not parse the POM')
            position = re.search(r'line\s+(\d+).*?column\s+(\d+)', raw)
            if position:
                cause['line'], cause['column'] = int(position[1]), int(position[2])
            field = re.search(r"Unknown attribute '([A-Za-z_][A-Za-z0-9_.-]{0,60})' for tag '([A-Za-z_][A-Za-z0-9_.-]{0,60})'", raw)
            if field and not sensitive:
                cause.update(attribute=field[1], tag=field[2])
        elif re.search(r'Could not (?:resolve|find|transfer)|could not be resolved|PluginResolutionException', raw):
            cause.update(kind='MAVEN_RESOLUTION', detail='Maven artifact/plugin resolution failed')
            coordinate = re.search(r'\b(?:artifact|Plugin|project)\s+([A-Za-z0-9_.-]{1,80}:[A-Za-z0-9_.-]{1,80}(?::[A-Za-z0-9_.-]{1,80}){1,3})(?=[\s,;]|$)', raw)
            if coordinate and not sensitive:
                cause['coordinate'] = coordinate[1]
            status = re.search(r'status code:\s*(\d{3})\b', raw)
            if status:
                cause['httpStatus'] = int(status[1])
        elif 'Failed to delete' in raw:
            cause.update(kind='CLEAN_DELETE_FAILURE', detail='Clean could not delete a fixture target')
        elif 'Missing base directory' in raw:
            cause.update(kind='CLEAN_MISSING_BASE', detail='Clean fileset base directory is missing')
        elif 'Invalid base directory' in raw:
            cause.update(kind='CLEAN_INVALID_BASE', detail='Clean fileset base is not a directory')
        elif 'for fastMode' in raw:
            cause.update(kind='CLEAN_INVALID_FAST_MODE', detail='Clean rejected the configured fastMode')
        elif 'Failed to execute goal' in raw:
            cause.update(kind='MAVEN_GOAL_FAILURE', detail='Maven goal execution failed')
        elif re.search(r'\.java:\d+: error:', raw):
            cause.update(kind='JAVA_COMPILER', detail='Java compilation failed')
            for phrase in ['cannot find symbol', 'does not exist', 'illegal start', "';' expected", 'incompatible types']:
                if phrase in raw:
                    cause['detail'] = 'Java compilation failed: ' + phrase
                    break
            cause['line'] = int(re.search(r'\.java:(\d+): error:', raw)[1])
        elif re.search(r'release version .* not supported|invalid target release|invalid flag', raw):
            cause.update(kind='JAVA_TOOL_OPTIONS', detail='Java compiler rejected the required options')
        elif 'Error opening zip file' in raw or 'agent library failed to init' in raw:
            cause.update(kind='JAVA_AGENT_INIT', detail='JVM could not initialize the passive observer')
        elif re.search(r'[\w.$]{1,200}(?:Exception|Error)\b', raw):
            exception = re.search(r'\b((?:java|javax|jdk|org\.apache)\.[A-Za-z0-9_.$]{1,180}(?:Exception|Error))\b', raw)
            cause.update(kind='JAVA_EXCEPTION', detail='Java process raised an exception/error')
            if exception and not sensitive:
                cause['exceptionType'] = exception[1]
            assertion = re.search(r'\bjava\.lang\.AssertionError: (.+)$', raw)
            if assertion and assertion[1] in API_ASSERTION_LABELS:
                cause['approvedProbeAssertion'] = assertion[1]
            target = re.search(r'\borg\.apache\.(?:maven|commons)\.[A-Za-z0-9_.$]{1,180}', raw)
            if target and not sensitive:
                cause['target'] = target[0]
        if len(causes) < 32:
            if cause not in causes:
                causes.append(cause)
        else:
            omitted += 1
    return {'state': 'AVAILABLE' if any(c['kind'] != 'UNRECOGNIZED_ERROR' for c in causes) else 'UNAVAILABLE_NO_SAFE_CAUSE',
            'causes': causes, 'sensitiveLinesWithheld': redacted,
            'truncated': scan_truncated or omitted > 0 or long_lines > 0,
            'omittedDiagnosticLines': omitted, 'truncatedLongLines': long_lines,
            'approvedProbePassLabels': probe_passes, 'approvedProbePassCount': len(probe_passes),
            'rawDetailsIncluded': False}


def captured_diagnostics(files):
    pieces, truncated = [], False
    for file in files:
        if not file.exists():
            continue
        size = file.stat().st_size
        with file.open('rb') as stream:
            if size <= DIAGNOSTIC_SCAN_BYTES:
                data = stream.read(DIAGNOSTIC_SCAN_BYTES)
            else:
                half = DIAGNOSTIC_SCAN_BYTES // 2
                data = stream.read(half)
                stream.seek(-half, 2)
                data += b'\n' + stream.read(half)
                truncated = True
        pieces.append(data.decode(errors='replace'))
    return safe_diagnostics('\n'.join(pieces), scan_truncated=truncated)


def write_failure_receipt(sandbox, identity, report):
    """Best effort evidence only: never follow a tainted sandbox/receipt path."""
    directory_fd = None
    try:
        receipt = inside(sandbox / 'execution.json', sandbox)
        require(not sandbox.is_symlink() and not receipt.is_symlink(), 'unsafe receipt symlink')
        directory_fd = os.open(sandbox, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        observed = os.fstat(directory_fd)
        require((observed.st_dev, observed.st_ino) == identity, 'receipt sandbox identity changed')
        descriptor = os.open('execution.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=directory_fd)
        report['diskReceipt'] = {'status': 'WRITTEN'}
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(json.dumps(report, indent=2) + '\n')
    except (OSError, ValueError) as exc:
        report['diskReceipt'] = {'status': 'OMITTED_UNSAFE_OR_UNAVAILABLE', 'errorType': type(exc).__name__}
    finally:
        if directory_fd is not None:
            os.close(directory_fd)


def verify_logging_channels(configuration):
    values = re.findall(r'^org\.slf4j\.simpleLogger\.logFile\s*=\s*(\S+)\s*$', configuration, re.M)
    require(values == ['System.out'], 'selected Maven logger must explicitly target stdout')


def require_separate_class_channel(stdout, stderr):
    record = r'^\[[^\n]*\]\[class,load\] '
    require(not re.search(record, stdout, re.M), 'JVM class output interrupted Maven stdout')
    require(re.search(record, stderr, re.M) is not None, 'JVM class output missing from bounded stderr capture')


class Commands:
    """No shell; bounded process group, output, individual and aggregate deadlines."""
    def __init__(self, sandbox, env, seconds):
        self.sandbox, self.env = sandbox, env
        self.deadline = time.monotonic() + seconds
        self.results, self.total_bytes = [], 0

    def run(self, label, command, cwd, seconds=30, extra_env=None, class_load_stderr=False):
        require(re.fullmatch(r'[a-z0-9-]+', label) is not None, 'invalid command label')
        require(not any(r['case'] == label for r in self.results), 'duplicate command label')
        limit = min(seconds, self.deadline - time.monotonic())
        require(limit > 0, 'aggregate command deadline exhausted')
        stdout_file, stderr_file = [inside(self.sandbox / (label + suffix), self.sandbox)
                                    for suffix in ['.stdout.log', '.stderr.log']]
        record = {'case': label, 'command': command, 'status': 'STARTING', 'exitStatus': None}
        self.results.append(record)
        started = time.monotonic()
        process = None
        try:
            with stdout_file.open('xb') as stdout, stderr_file.open('xb') as stderr, selectors.DefaultSelector() as selector:
                process = subprocess.Popen(command, cwd=cwd, env=dict(self.env, **(extra_env or {})),
                                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           start_new_session=True)
                for pipe, stream in [(process.stdout, stdout), (process.stderr, stderr)]:
                    selector.register(pipe, selectors.EVENT_READ, stream)
                record['status'] = 'RUNNING'
                size, stop = 0, None
                def kill_group():
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                while selector.get_map():
                    if time.monotonic() - started >= limit:
                        stop = 'TIMEOUT'
                    if stop:
                        kill_group()
                        break
                    for key, _ in selector.select(timeout=min(0.1, limit)):
                        data = os.read(key.fileobj.fileno(), 65536)
                        if not data:
                            selector.unregister(key.fileobj)
                            key.fileobj.close()
                            continue
                        remaining = min(MAX_OUTPUT - size, 64 * 1024 * 1024 - self.total_bytes)
                        saved = data[:max(0, remaining)]
                        key.data.write(saved)
                        size += len(saved)
                        self.total_bytes += len(saved)
                        if len(saved) != len(data):
                            stop = 'OUTPUT_LIMIT'
                if stop is None:
                    remaining = max(0.001, limit - (time.monotonic() - started))
                    try:
                        process.wait(timeout=remaining)
                    except subprocess.TimeoutExpired:
                        stop = 'TIMEOUT'
                        kill_group()
                process.wait(timeout=5)
                record.update(status=stop or 'EXITED', exitStatus=process.returncode, capturedBytes=size)
        except BaseException as exc:
            if process is not None and process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)
            record.update(status='ERROR' if record['status'] in {'STARTING', 'RUNNING'} else record['status'],
                          exitStatus=process.returncode if process is not None else None)
            record['errorType'] = type(exc).__name__
            if isinstance(exc, OSError):
                record['errno'] = exc.errno
            raise
        finally:
            if process is not None:
                for pipe in [process.stdout, process.stderr]:
                    if pipe is not None and not pipe.closed:
                        pipe.close()
            record['elapsedSeconds'] = round(time.monotonic() - started, 3)
            for file in [stdout_file, stderr_file]:
                if file.exists():
                    record[file.suffixes[-2].removeprefix('.') + 'Sha256'] = digest(file)
            record['captureComplete'] = record['status'] == 'EXITED'
            if record['status'] != 'EXITED' or record['exitStatus'] != 0:
                record['diagnostics'] = captured_diagnostics([stdout_file, stderr_file])
            if class_load_stderr:
                record['classLoadCapture'] = {'channel': 'stderr', 'rotation': False,
                    'complete': record['captureComplete'], 'sha256': record.get('stderrSha256'),
                    'bytes': stderr_file.stat().st_size if stderr_file.exists() else 0,
                    'sharesExistingPipeAndAggregateBudgets': True}
            # Only constructed metadata goes to CI; raw output never dumps settings/source content.
            print('CLEAN_COMMAND ' + json.dumps(record), flush=True)
        require(record['status'] == 'EXITED', label + ' terminated: ' + record['status'])
        log = stdout_file.read_text(errors='replace') + '\n' + stderr_file.read_text(errors='replace')
        log_file = inside(self.sandbox / (label + '.log'), self.sandbox)
        log_file.write_text(log)
        record['logSha256'] = digest(log_file)
        return record['exitStatus'], log, log_file
