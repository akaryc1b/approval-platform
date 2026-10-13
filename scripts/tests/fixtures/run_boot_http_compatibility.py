#!/usr/bin/env python3
"""Opt-in, offline Boot HTTP compatibility; imports and plan start no processes."""
import argparse
import gzip
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import socketserver
import ssl
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import Commands, digest, isolated_env, require

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OWNER = 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8'
SOURCES = [HERE / name for name in ('run_boot_http_compatibility.py', 'BootPluginHttpProbe.java',
           'BootPluginJacksonProbe.java', 'checkstyle_live_support.py')]
SOURCES += [ROOT / 'scripts/ci/maven-toolchain.mjs']
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'BOOT_HTTP_COMPATIBILITY=true',
        'offline': True, 'aggregateTimeoutSeconds': 240, 'outerTimeoutSeconds': 270,
        'commandTimeoutSeconds': {'version': 10, 'openssl': 20, 'javac': 30, 'httpProbe': 45, 'jacksonProbe': 30, 'linkageNegative': 30},
        'fixtureSocketTimeoutSeconds': 5, 'maximumRequestsPerServer': 64,
        'maximumRequestBodyBytes': 16384, 'maximumResponseBodyBytes': 32768,
        'bind': ['127.0.0.1 ephemeral TCP ports', 'owned temporary Unix socket'],
        'variants': {'baseline': ['5.5.2', '5.3.6'], 'candidate': ['5.6.3', '5.4.3']},
        'cleanup': 'Close actual clients/responses; stop and close servers; kill/reap owned command groups; remove TLS directory in finally',
        'claimsExcluded': ['Docker build-image', 'Windows named pipes', 'security fix reproduction', 'scanner clearance', 'canonical CI']}
TERMINAL_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)
FAILURE_STAGES = frozenset(('manifest', 'bindings', 'linkage', 'http', 'unix',
                            'tls-trusted', 'tls-hostname', 'tls-untrusted', 'unknown'))
FAILURE_ASSERTIONS = frozenset(('setup', 'bindings', 'symbolic-linkage', 'factory', 'get-uri', 'empty-post',
    'request-header', 'post-body', 'put-body', 'head-header', 'head-empty-response', 'delete', 'gzip-decoding',
    'chunked-response', 'docker-json-error', 'connection-reuse', 'reuse-body', 'partial-read', 'after-close',
    'complete', 'tls-trusted', 'tls-repeated', 'tls-rejection', 'client-close', 'unknown'))
FAILURE_CLASSES = frozenset(('OTHER', 'java.lang.AssertionError', 'java.lang.NullPointerException',
    'java.lang.IllegalArgumentException', 'java.lang.IllegalStateException', 'java.lang.SecurityException',
    'java.lang.UnsupportedOperationException', 'java.lang.ClassNotFoundException', 'java.lang.NoSuchFieldException',
    'java.lang.NoSuchMethodException', 'java.lang.IllegalAccessException', 'java.lang.NoClassDefFoundError',
    'java.lang.NoSuchMethodError', 'java.lang.NoSuchFieldError', 'java.lang.ExceptionInInitializerError',
    'java.lang.reflect.InaccessibleObjectException', 'java.lang.reflect.InvocationTargetException',
    'java.io.IOException', 'java.io.EOFException', 'java.net.ConnectException', 'java.net.SocketException',
    'java.net.SocketTimeoutException', 'javax.net.ssl.SSLException', 'javax.net.ssl.SSLHandshakeException',
    'javax.net.ssl.SSLPeerUnverifiedException', 'java.security.cert.CertificateException',
    'org.springframework.boot.buildpack.platform.docker.transport.DockerConnectionException',
    'org.springframework.boot.buildpack.platform.docker.transport.DockerEngineException'))


def probe_failure(stdout):
    """Keep bounded fixed diagnostic fields, never arbitrary subprocess output."""
    lines = [line for line in stdout.splitlines() if line.startswith('BOOT_HTTP_FAILURE\t')]
    if not lines:
        return {'markerStatus': 'ABSENT'}
    if len(lines) != 1 or len(lines[0]) > 1024:
        return {'markerStatus': 'INVALID'}
    fields = lines[0].split('\t')
    if len(fields) != 4:
        return {'markerStatus': 'INVALID'}
    _, stage, assertion, chain = fields
    classes = chain.split(',')
    if stage not in FAILURE_STAGES or assertion not in FAILURE_ASSERTIONS or not 1 <= len(classes) <= 8 \
            or not all(name in FAILURE_CLASSES for name in classes):
        return {'markerStatus': 'INVALID'}
    return {'markerStatus': 'RECORDED', 'stage': stage, 'assertion': assertion, 'exceptionClasses': classes}


def ignore_terminal_signals():
    return {signum: signal.signal(signum, signal.SIG_IGN) for signum in TERMINAL_SIGNALS}


def restore_signals(previous):
    for signum, handler in previous.items():
        signal.signal(signum, handler)


def install_deadline(seconds):
    def interrupted(signum, frame):
        ignore_terminal_signals()
        raise TimeoutError('aggregate deadline') if signum == signal.SIGALRM else InterruptedError('interrupted signal ' + str(signum))
    previous = {signum: signal.signal(signum, interrupted) for signum in (*TERMINAL_SIGNALS, signal.SIGALRM)}
    signal.alarm(seconds)
    return previous


def realm(path, variant):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size < 262144, 'bounded realm manifest required')
    data = json.loads(path.read_text())
    require(data['schemaVersion'] == 'BOOT_HTTP_RESOLVED_REALM_V1' and data['owner'] == OWNER, 'actual Boot realm required')
    report = data['sourceOwnerReport']; report_path = Path(report['path'])
    require(report_path.is_file() and digest(report_path) == report['sha256'], 'source owner report drift')
    artifacts = data['artifacts'] + data.get('importedArtifacts', [])
    require(5 < len(artifacts) < 100, 'bounded actual realm required')
    coordinates, names, paths = set(), set(), set()
    for artifact in artifacts:
        coordinate = artifact['coordinate']; fields = coordinate.split(':')
        require(len(fields) == 4 and fields[2] == 'jar', 'plain JAR coordinate required')
        jar = Path(artifact['path'])
        require(jar.is_absolute() and jar.is_file() and jar.stat().st_size <= 64 * 1024 * 1024, 'bounded actual JAR required')
        require(jar.name == fields[1] + '-' + fields[3] + '.jar', 'JAR filename mismatch')
        require(re.fullmatch('[0-9a-f]{64}', artifact['sha256']) and digest(jar) == artifact['sha256'], 'JAR bytes drift')
        require(coordinate not in coordinates and fields[1] not in names and str(jar.resolve()) not in paths, 'duplicate JAR identity')
        coordinates.add(coordinate); names.add(fields[1]); paths.add(str(jar.resolve()))
    client, core = PLAN['variants'][variant]
    expected = {OWNER, 'org.springframework.boot:spring-boot-buildpack-platform:jar:4.0.8',
                'org.apache.httpcomponents.client5:httpclient5:jar:' + client,
                'org.apache.httpcomponents.core5:httpcore5:jar:' + core,
                'org.apache.httpcomponents.core5:httpcore5-h2:jar:' + core,
                'tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-databind:jar:3.1.7'}
    require(expected <= coordinates, 'expected Boot/Jackson/HTTP versions absent')
    return artifacts


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def setup(self):
        self.request.settimeout(5)
        super().setup()
        with self.server.lock:
            self.server.connections += 1
            self.connection_id = self.server.connections

    def log_message(self, *args):
        pass

    def read_body(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = bytearray()
            for _ in range(64):
                line = self.rfile.readline(128)
                require(line.endswith(b'\r\n'), 'bounded chunk header')
                count = int(line.strip(), 16)
                require(0 <= count <= 16384 - len(chunks), 'request body budget')
                if count == 0:
                    require(self.rfile.readline(128) == b'\r\n', 'no fixture trailers expected')
                    return bytes(chunks)
                chunks.extend(self.rfile.read(count))
                require(self.rfile.read(2) == b'\r\n', 'chunk delimiter')
            raise ValueError('chunk count budget')
        length = int(self.headers.get('Content-Length', '0'))
        require(0 <= length <= 16384, 'request body budget')
        return self.rfile.read(length)

    def respond(self):
        try:
            with self.server.lock:
                require(len(self.server.events) < 64, 'fixture request budget')
                self.server.events.append({'method': self.command, 'path': self.path, 'connection': self.connection_id})
            request = self.read_body()
            status, content_type = 200, 'text/plain; charset=utf-8'
            body = (self.command + ' ' + self.path).encode()
            if self.path == '/auth':
                body = self.headers.get('X-Registry-Auth', '').encode()
            elif self.path == '/body':
                body = (self.command + '|' + self.headers.get('Content-Type', '') + '|').encode() + request
            elif self.path == '/gzip':
                body = gzip.compress('compressed café'.encode(), mtime=0)
            elif self.path == '/error':
                status, content_type, body = 404, 'application/json', b'{"message":"fixture missing"}'
            elif self.path == '/partial':
                body = b'x' * 8192
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('X-Fixture', 'head')
            self.send_header('X-Connection', str(self.connection_id))
            if self.path == '/gzip':
                self.send_header('Content-Encoding', 'gzip')
            if self.path == '/stream':
                self.send_header('Transfer-Encoding', 'chunked')
                self.end_headers()
                for chunk in (b'one\n', b'two\n', b'three\n'):
                    self.wfile.write(('%x\r\n' % len(chunk)).encode() + chunk + b'\r\n')
                    self.wfile.flush()
                self.wfile.write(b'0\r\n\r\n')
            else:
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(body)
            self.wfile.flush()
        except BaseException as failure:
            with self.server.lock:
                self.server.failures.append(type(failure).__name__)
            self.close_connection = True

    do_GET = do_POST = do_PUT = do_HEAD = do_DELETE = respond


class Threads(socketserver.ThreadingMixIn):
    daemon_threads = True
    block_on_close = False

    def process_request_thread(self, request, client_address):
        with self.lock:
            self.active.add(request); self.workers.add(threading.current_thread())
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self.lock:
                self.active.discard(request)

    def handle_error(self, request, client_address):
        failure = sys.exception()
        with self.lock:
            if isinstance(failure, ssl.SSLError):
                self.tls_failures.append(type(failure).__name__)
            else:
                self.failures.append(type(failure).__name__)


class TcpServer(Threads, http.server.HTTPServer):
    pass


class UnixServer(Threads, socketserver.UnixStreamServer):
    pass


class Fixtures:
    def __init__(self, temporary, trusted):
        self.servers, self.threads, self.started, self.cleanup = [], [], set(), {}
        self.unix = temporary / 'http.sock'
        try:
            for name in ('http', 'unix', 'tls'):
                server = UnixServer(str(self.unix), Handler) if name == 'unix' else TcpServer(('127.0.0.1', 0), Handler)
                self.servers.append((name, server))
                server.events, server.failures, server.connections, server.lock = [], [], 0, threading.Lock()
                server.active, server.workers, server.tls_failures = set(), set(), []
                if name == 'tls':
                    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                    context.load_cert_chain(trusted / 'cert.pem', trusted / 'key.pem')
                    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
                thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
                thread.start(); self.threads.append(thread); self.started.add(name)
        except BaseException:
            self.close(); raise

    def properties(self):
        return {name: str(server.server_address[1]) if name != 'unix' else str(self.unix) for name, server in self.servers}

    def observations(self):
        result = {}
        for name, server in self.servers:
            with server.lock:
                require(not server.failures, 'fixture handler failure: ' + name + ' ' + str(server.failures))
                events = list(server.events)
            require(len(events) == (2 if name == 'tls' else 15), 'actual fixture request count differs: ' + name)
            result[name] = {'bind': '127.0.0.1' if name != 'unix' else 'owned Unix socket',
                            'events': events, 'connections': server.connections,
                            'expectedPeerHandshakeFailures': server.tls_failures}
        return result

    def close(self):
        for name, server in self.servers:
            if name in self.started:
                server.shutdown()
            server.server_close()
            with server.lock:
                active, workers = list(server.active), list(server.workers)
            for connection in active:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
            deadline = time.monotonic() + 6
            for worker in workers:
                worker.join(timeout=max(0, deadline - time.monotonic()))
            self.cleanup[name] = {'listenerClosed': server.fileno() == -1,
                                  'workerThreadsStopped': all(not worker.is_alive() for worker in workers),
                                  'activeConnections': len(server.active)}
            require(all(self.cleanup[name][key] for key in ('listenerClosed', 'workerThreadsStopped'))
                    and not server.active, 'fixture workers did not stop: ' + name)
        for thread in self.threads:
            thread.join(timeout=2)
            require(not thread.is_alive(), 'fixture listener did not stop')
        self.unix.unlink(missing_ok=True)


def check_seal(seal):
    require(seal.get('schemaVersion') == 'BOOT_HTTP_EXECUTION_SEAL_V1', 'review seal required')
    expected_sources = {str(path.resolve()) for path in SOURCES}
    require(expected_sources <= set(seal['sourceHashes']), 'executed-source dependencies not sealed')
    for category in ('sourceHashes', 'runtimeHashes', 'inputHashes'):
        require(bool(seal[category]), 'nonempty seal category required')
        for filename, expected in seal[category].items():
            path = Path(filename)
            require(path.is_file() and digest(path) == expected, 'reviewed bytes changed: ' + filename)


def execute(seal, linkage_only=False):
    require(os.environ.get('BOOT_HTTP_COMPATIBILITY') == 'true', 'explicit reviewed execution opt-in required')
    check_seal(seal)
    config = seal['config']; jdk = Path(config['jdk']).resolve(); output = Path(config['output']).resolve()
    require(not output.exists(), 'fresh output directory required')
    require(sys.platform == 'linux', 'this fixture is Linux-only')
    require(all(str(jdk / item) in seal['runtimeHashes'] for item in ('bin/java', 'bin/javac', 'lib/modules', 'conf/security/java.security')), 'JDK runtime not fully bound')
    require('/usr/bin/openssl' in seal['runtimeHashes'] and str(Path(sys.executable).resolve()) in seal['runtimeHashes'], 'fixture runtime not bound')
    manifests = {variant: Path(config[variant + 'Realm']).resolve() for variant in ('baseline', 'candidate')}
    require(all(str(path) in seal['inputHashes'] for path in manifests.values()), 'realm input not sealed')
    artifacts = {variant: realm(path, variant) for variant, path in manifests.items()}
    output.mkdir(parents=True)
    for name in ('home', 'tmp', 'classes', 'docker'):
        (output / name).mkdir()
    (output / 'docker/config.json').write_text('{}\n')
    environment = isolated_env(jdk, output)
    environment['DOCKER_CONFIG'] = str(output / 'docker')
    environment['OPENSSL_CONF'] = '/dev/null'
    commands = Commands(output, environment, 240)
    result = {'status': 'RUNNING', 'plan': PLAN, 'linkageOnly': linkage_only,
              'scope': 'Static linkage and Jackson diagnostic only' if linkage_only else 'Full ordinary HTTP/TLS/Unix transport, static linkage and Jackson',
              'sourceHashes': seal['sourceHashes'], 'runtimeHashes': seal['runtimeHashes'],
              'inputHashes': seal['inputHashes'], 'commands': commands.results, 'variants': {},
              'scannerEvidence': False, 'canonicalCiEvidence': False, 'temporaryTlsRemoved': False}

    def run(label, command, seconds, expected=0):
        check_seal(seal)
        status, stdout, stderr = commands.run(label, command, seconds)
        if status != expected:
            result['failedCommand'] = {'case': label, 'exitStatus': status,
                                       'probeFailure': probe_failure(stdout)}
        require(status == expected, 'unexpected command status: ' + label)
        check_seal(seal)
        return stdout, stderr

    old_handlers = install_deadline(240)
    temporary = None
    try:
        java_version = run('java-version', [str(jdk / 'bin/java'), '-version'], 10)
        require(re.search(r'version "21\.', ''.join(java_version)), 'JDK21 required')
        if not linkage_only:
            run('openssl-version', ['/usr/bin/openssl', 'version'], 10)
            # A short owned /tmp path also stays below the Unix socket path limit.
            temporary = Path(tempfile.mkdtemp(prefix='boot-http-fixture-', dir='/tmp'))
            trusted, untrusted = temporary / 'trusted', temporary / 'untrusted'
            trusted.mkdir(mode=0o700); untrusted.mkdir(mode=0o700)
            for label, directory, hostname in [('trusted', trusted, 'localhost'), ('untrusted', untrusted, 'untrusted.invalid')]:
                run('tls-material-' + label, ['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1',
                    '-subj', '/CN=' + hostname, '-addext', 'subjectAltName=DNS:' + hostname,
                    '-keyout', str(directory / 'key.pem'), '-out', str(directory / 'cert.pem')], 20)
                shutil.copyfile(directory / 'cert.pem', directory / 'ca.pem')
            result['tlsCertificates'] = {'trustedSha256': digest(trusted / 'cert.pem'), 'untrustedSha256': digest(untrusted / 'cert.pem'),
                                         'scope': 'ephemeral fixture-only self-signed certificates; no host trust changes'}
        baseline_cp = os.pathsep.join(item['path'] for item in artifacts['baseline'])
        run('compile-baseline', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21', '-cp', baseline_cp,
            '-d', str(output / 'classes'), str(HERE / 'BootPluginHttpProbe.java'), str(HERE / 'BootPluginJacksonProbe.java')], 30)
        result['compiledClasses'] = {str(path.relative_to(output)): digest(path) for path in sorted((output / 'classes').glob('*.class'))}
        require(len(result['compiledClasses']) == 2, 'only two probe classes expected')
        for variant in ('baseline', 'candidate'):
            require(all(digest(output / path) == expected for path, expected in result['compiledClasses'].items()), 'compiled class drift')
            realm(manifests[variant], variant)
            identity = output / (variant + '-identities.tsv')
            identity.write_text(''.join(item['coordinate'].split(':')[1] + '\t' + item['path'] + '\t' + item['sha256'] + '\n'
                                        for item in artifacts[variant]))
            fixtures = None if linkage_only else Fixtures(temporary, trusted)
            try:
                properties = {'home': str(output / 'home'), 'docker': str(output / 'docker')}
                if fixtures is not None:
                    properties |= fixtures.properties() | {'trusted': str(trusted), 'untrusted': str(untrusted)}
                configuration = output / (variant + '-fixture.properties')
                configuration.write_text(''.join(key + '=' + value + '\n' for key, value in properties.items()))
                classpath = os.pathsep.join([str(output / 'classes')] + [item['path'] for item in artifacts[variant]])
                java = [str(jdk / 'bin/java'), '-Xmx128m', '-Djava.net.preferIPv4Stack=true',
                        '-Duser.home=' + str(output / 'home'), '-Djava.io.tmpdir=' + str(output / 'tmp'),
                        '-Xlog:class+load=info:stderr', '-cp', classpath]
                stdout, stderr = run(variant + ('-linkage' if linkage_only else '-http'), java + ['BootPluginHttpProbe', str(configuration), str(identity)]
                                    + (['linkage-only'] if linkage_only else []), 45)
                require(('BOOT_HTTP_LINKAGE_COMPLETE ' if linkage_only else 'BOOT_HTTP_COMPLETE ') in stdout, 'required probe completion missing')
                origins = [line.split('\t')[1:] for line in stdout.splitlines() if line.startswith('BOOT_HTTP_ORIGIN\t')]
                require(len(origins) >= 17, 'representative origins missing')
                for name, filename, _ in origins:
                    require(any(name + ' source: file:' + filename in line for line in stderr.splitlines()), 'JVM origin corroboration missing: ' + name)
                members = [line.split('\t')[1:] for line in stdout.splitlines() if line.startswith('BOOT_HTTP_MEMBER\t')]
                inventories = [line.split('\t')[1:] for line in stdout.splitlines() if line.startswith('BOOT_HTTP_MEMBER_INVENTORY\t')]
                require(len(inventories) == 1 and len(members) == int(inventories[0][2]), 'complete symbolic member inventory missing')
                inventory = dict(zip(('inspectedBootClassFiles', 'nonArrayHcClassReferences', 'memberReferences', 'memberSetSha256', 'staticInstanceUses'),
                                     (int(inventories[0][0]), int(inventories[0][1]), int(inventories[0][2]), inventories[0][3], int(inventories[0][4]))))
                if variant == 'candidate':
                    baseline_linkage = result['variants']['baseline']['staticLinkage']
                    require(members == baseline_linkage['members'] and inventory == baseline_linkage['inventory'], 'baseline/candidate static linkage differs')
                    negative_out, negative_err = run('candidate-linkage-negative', java + ['BootPluginHttpProbe', str(configuration), str(identity), 'missing-member'], 30, expected=1)
                    require('NoSuchMethodException' in negative_err and 'missingFixtureMethod' in negative_err
                            and 'BOOT_HTTP_COMPLETE' not in negative_out, 'missing required member was not rejected before transport')
                    result['linkageNegativeControl'] = 'PASSED: nonexistent required member rejected before any transport construction'
                observations = {} if fixtures is None else fixtures.observations()
                jackson_out, _ = run(variant + '-jackson', java + ['BootPluginJacksonProbe'], 30)
                require('PROBE_COMPLETE:' in jackson_out, 'existing Jackson completion missing')
                result['variants'][variant] = {'status': 'DIAGNOSTIC_PASSED' if linkage_only else 'PASSED', 'realmSha256': digest(manifests[variant]),
                    'artifacts': artifacts[variant], 'origins': origins, 'fixtureObservations': observations,
                    'passedAssertions': [line[5:] for line in stdout.splitlines() if line.startswith('PASS ')],
                    'staticLinkage': {'members': members, 'inventory': inventory,
                        'scope': 'All Apache HC constant-pool member references and observed bytecode/handle static-instance uses in actual Boot Maven plugin/buildpack JARs; no dynamic reachability or API-wide compatibility claim'},
                    'existingJacksonProbe': 'PASSED', 'compiledAgainst': 'baseline',
                    'fixtureManifestSha256': digest(configuration), 'identityManifestSha256': digest(identity)}
            finally:
                cleanup_handlers = ignore_terminal_signals()
                try:
                    if fixtures is not None:
                        fixtures.close()
                finally:
                    restore_signals(cleanup_handlers)
                if fixtures is not None and variant in result['variants']:
                    result['variants'][variant]['fixtureCleanup'] = fixtures.cleanup
        result['status'] = 'DIAGNOSTIC_PASSED' if linkage_only else 'PASSED'
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
        raise
    finally:
        ignore_terminal_signals(); signal.alarm(0)
        try:
            if temporary is not None:
                shutil.rmtree(temporary)
                result['temporaryTlsRemoved'] = not temporary.exists()
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('BOOT_HTTP_RESULT ' + json.dumps(result), flush=True)
        finally:
            restore_signals(old_handlers)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--linkage-only', action='store_true')
    parser.add_argument('--seal', type=Path)
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps(PLAN, indent=2))
    else:
        require(args.seal is not None and args.seal.is_file(), 'reviewed seal file required')
        execute(json.loads(args.seal.read_text()), args.linkage_only)
