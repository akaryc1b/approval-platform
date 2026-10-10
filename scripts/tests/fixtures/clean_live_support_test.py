"""Pure synthetic contracts. An audit hook forbids child processes and sockets."""
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

from clean_live_support import (CONTRACT, ROOT, NS, PREFIX, PLUGIN, SHARED, IO, CLEAN_GOAL, MODEL_GOAL,
                                Commands, execution_mode, extracted_parent, inside, isolated_env, maven_command, origins,
                                source_gate, safe_diagnostics, captured_diagnostics, write_failure_receipt,
                                API_ASSERTION_LABELS, verify_logging_channels, require_separate_class_channel)


def no_execution(event, args):
    if event in {'subprocess.Popen', 'os.system', 'os.posix_spawn', 'os.posix_spawnp', 'os.fork'} or event.startswith('socket.'):
        raise AssertionError('Synthetic test attempted forbidden execution/network: ' + event)


sys.addaudithook(no_execution)


class PureContracts(unittest.TestCase):
    def setUp(self):
        self.sandbox = Path('/synthetic-clean-live-sandbox')
        self.repo = Path('/synthetic-approved-repository')
        self.maven = Path('/synthetic-maven/bin/mvn')
        self.jdk = Path('/synthetic-jdk')
        self.project = self.sandbox / 'defaults/pom.xml'

    def command(self, stage='clean', flags=(), offline=True, project=None):
        return maven_command(self.maven, self.sandbox, self.repo, offline, stage,
                             self.project if project is None else project, flags)

    def test_current_strict_source_and_complete_parent_serialization(self):
        projects, hashes = source_gate(ROOT)
        self.assertEqual(len(projects), 26)
        self.assertEqual(len(hashes), 28)
        original = projects[0][1]
        before = ET.tostring(original)
        xml = extracted_parent(original)
        self.assertEqual(before, ET.tostring(original))
        declarations = list(ET.iterparse(io.StringIO(xml), events=['start-ns']))
        self.assertEqual(declarations, [('start-ns', ('', NS['m']))])
        parsed = ET.fromstring(xml)
        self.assertEqual([p.tag for p in parsed], [PREFIX + p for p in [
            'modelVersion', 'groupId', 'artifactId', 'version', 'packaging', 'properties', 'build']])
        candidate = parsed.find('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
        source = next(p for p in original.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS) if CONTRACT['targeted'](p))
        self.assertEqual(ET.tostring(candidate), ET.tostring(source))
        self.assertNotIn('<scm', xml)
        self.assertNotIn('<distributionManagement', xml)
        self.assertNotIn('<configuration', xml)
        self.assertNotIn('<executions', xml)

    def test_parent_rejects_clean_configuration(self):
        project = copy.deepcopy(source_gate(ROOT)[0][0][1])
        selected = next(p for p in project.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS) if CONTRACT['targeted'](p))
        ET.SubElement(selected, PREFIX + 'configuration')
        with self.assertRaises(ValueError):
            extracted_parent(project)

    def test_parent_rejects_pin_drift(self):
        project = copy.deepcopy(source_gate(ROOT)[0][0][1])
        project.find('m:properties/m:maven.clean.commons-io.version', NS).text = '2.6'
        with self.assertRaises(ValueError):
            extracted_parent(project)

    def test_clean_is_versionless_fully_qualified_nonrecursive(self):
        args = self.command()
        self.assertIn(CLEAN_GOAL, args)
        self.assertIn('-N', args)
        self.assertIn('-o', args)
        self.assertEqual(args[args.index('-f') + 1], str(self.project))
        self.assertEqual(args[args.index('-s') + 1], str(self.sandbox / 'empty-settings.xml'))
        self.assertEqual(args[args.index('-gs') + 1], str(self.sandbox / 'empty-settings.xml'))
        self.assertIn('-Dmaven.repo.local=' + str(self.repo), args)
        self.assertIn('-Dmaven.clean.fastDir=' + str(self.project.parent / 'bounded-fast-directory'), args)
        self.assertFalse(any(a in {'clean', 'verify', 'deploy', 'install', 'package'} for a in args))

    def test_only_actual_models_allow_source_project(self):
        args = self.command('models', project=ROOT / 'pom.xml')
        self.assertIn(MODEL_GOAL, args)
        self.assertNotIn('-N', args)
        self.assertIn('-Dmaven.multiModuleProjectDirectory=' + str(ROOT), args)
        self.assertIn('-Doutput=' + str(self.sandbox / 'effective-pom.xml'), args)
        with self.assertRaises(ValueError):
            self.command(project=ROOT / 'pom.xml')

    def test_online_resolution_is_explicit(self):
        self.assertNotIn('-o', self.command(offline=False))
        self.assertIn('-o', self.command(offline=True))

    def test_execution_enablement_and_offline_repository_are_fail_closed(self):
        for env in [{}, {'GITHUB_ACTIONS': '1'}, {'CLEAN_PLUGIN_COMPATIBILITY': '1'}]:
            with self.subTest(env=env), self.assertRaises(ValueError):
                execution_mode(env, self.repo)
        with self.assertRaises(ValueError):
            execution_mode({'CLEAN_PLUGIN_COMPATIBILITY': 'true'}, None)
        self.assertTrue(execution_mode({'CLEAN_PLUGIN_COMPATIBILITY': 'true'}, self.repo))
        self.assertFalse(execution_mode({'GITHUB_ACTIONS': 'true'}, None))
        self.assertTrue(execution_mode({'GITHUB_ACTIONS': 'true'}, self.repo, True))

    def test_unknown_goal_and_dangerous_flags_fail_closed(self):
        for stage in ['clean-phase', 'install', 'release', 'deploy', 'help', '']:
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                self.command(stage)
        for flag in [('maven.clean.fastDir', '/outside'), ('maven.clean.directory', '/outside'),
                     ('maven.clean.skip', 'false -Dother=true'), ('maven.clean.fastMode', 'background'),
                     ('maven.ext.class.path', '/outside'), ('arbitrary', 'true')]:
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                self.command(flags=[flag])

    def test_containment_rejects_boundary_siblings_and_traversal(self):
        for path in [self.sandbox, Path('/outside'), Path('/synthetic-clean-live-sandbox-sibling/a'),
                     self.sandbox / '../outside', self.sandbox / 'child/..', Path('relative')]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                inside(path, self.sandbox)
        self.assertEqual(inside(self.sandbox / 'victim/a', self.sandbox), self.sandbox / 'victim/a')

    def test_jvm_environment_drops_inherited_options_and_credentials(self):
        poison = {key: 'do-not-inherit' for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                    'MAVEN_OPTS', 'MAVEN_ARGS', 'CLASSPATH', 'GITHUB_TOKEN', 'AWS_SECRET_ACCESS_KEY']}
        with patch.dict(os.environ, poison):
            env = isolated_env(self.jdk, self.sandbox)
        self.assertFalse(set(poison) & set(env))
        self.assertEqual(env['MAVEN_SKIP_RC'], '1')
        self.assertEqual(env['JAVA_HOME'], str(self.jdk))
        self.assertEqual(env['HOME'], str(self.sandbox / 'home'))

    def origin_fixture(self):
        approved = {c: {'path': '/synthetic-realm/' + name, 'sha256': 'unused-synthetic'} for c, name in [
            (PLUGIN, 'clean.jar'), (SHARED, 'shared.jar'), (IO, 'io.jar')]}
        classes = [('org/apache/maven/plugins/clean/CleanMojo', PLUGIN),
                   ('org/apache/maven/plugins/clean/Cleaner', PLUGIN), ('org/apache/maven/shared/utils/Os', SHARED)]
        log = ''
        for name, coordinate in classes:
            source = approved[coordinate]['path']
            log += f'CLEAN_ORIGIN\t{name}\tabc\tClassRealm[plugin>org.apache.maven.plugins:maven-clean-plugin:3.2.0]\tfile:{source}\n'
            log += f'[1.000s][info][class,load] {name.replace("/", ".")} source: file:{source}\n'
        return log, approved

    def test_natural_origins_bind_plugin_loader_and_jvm_sources(self):
        log, approved = self.origin_fixture()
        result = origins(log, approved)
        self.assertEqual(len(result['pluginRealmObservations']), 3)
        self.assertFalse(result['commonsIoNaturallyObservedInCleanLoader'])
        self.assertTrue(result['absenceIsNotNonReachabilityEvidence'])

    def test_verified_maven_stdout_and_separate_jvm_stderr_preserve_strict_realm(self):
        verify_logging_channels('org.slf4j.simpleLogger.logFile=System.out\n')
        for configuration in ['', 'org.slf4j.simpleLogger.logFile=System.err',
                              'org.slf4j.simpleLogger.logFile=System.out\n' * 2]:
            with self.assertRaises(ValueError):
                verify_logging_channels(configuration)
        stderr, approved = self.origin_fixture()
        stdout = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-clean-plugin:3.2.0\n'
        stdout += ''.join('[DEBUG]   Included: ' + coordinate + '\n' for coordinate in CONTRACT['ARTIFACT_HASHES'])
        stdout += '[DEBUG] Configuring mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean\n'
        require_separate_class_channel(stdout, stderr)
        with tempfile.TemporaryDirectory(prefix='clean-channels-synthetic-') as directory:
            log = Path(directory) / 'combined.log'
            log.write_text(stdout + '\n' + stderr)
            self.assertEqual(CONTRACT['verify_realm'](log), list(CONTRACT['ARTIFACT_HASHES']))
            self.assertEqual(len(origins(log.read_text(), approved, class_log=stderr)['pluginRealmObservations']), 3)
        with self.assertRaises(ValueError):
            require_separate_class_channel(stdout + stderr, stderr)
        with self.assertRaises(ValueError):
            require_separate_class_channel(stdout, 'missing class data')

    def test_natural_origins_reject_mismatches_and_missing_evidence(self):
        log, approved = self.origin_fixture()
        for changed in [log.replace('file:/synthetic-realm/clean.jar', 'file:/foreign/clean.jar'),
                        log.replace('plugin>org.apache.maven.plugins', 'core>org.apache.maven.plugins'),
                        log.replace('CLEAN_ORIGIN\t', 'UNKNOWN\t'),
                        log.replace('[class,load]', '[other]'), log + log,
                        log.replace('file:/synthetic-realm/shared.jar', 'https://untrusted/shared.jar')]:
            with self.subTest(changed=changed[:50]), self.assertRaises(ValueError):
                origins(changed, approved)

    def test_skip_requires_only_naturally_loaded_mojo(self):
        log, approved = self.origin_fixture()
        log = '\n'.join(line for line in log.splitlines() if 'CleanMojo' in line)
        self.assertEqual(len(origins(log, approved, skip=True)['pluginRealmObservations']), 1)
        with self.assertRaises(ValueError):
            origins(log, approved)

    def test_observer_never_transforms_or_proactively_loads_selected_classes(self):
        observer = (ROOT / 'scripts/tests/fixtures/CleanRealmObserver.java').read_text()
        self.assertIn('return null;', observer)
        self.assertIn('}, false);', observer)
        for forbidden in ['Class.forName', '.loadClass(', 'retransformClasses', 'redefineClasses', 'return bytes;']:
            self.assertNotIn(forbidden, observer)

    def test_default_plan_never_creates_sandbox_or_runs_commands(self):
        from run_clean_live_compatibility import main
        with patch.object(sys, 'argv', ['runner']), redirect_stdout(io.StringIO()) as output, \
                patch('run_clean_live_compatibility.tempfile.mkdtemp', side_effect=AssertionError('unexpected sandbox')), \
                patch.object(Commands, 'run', side_effect=AssertionError('unexpected process')):
            self.assertEqual(main(), 0)
        self.assertIn('"execution": "NOT_RUN"', output.getvalue())


class FakePipe:
    def __init__(self, descriptor):
        self.descriptor, self.closed = descriptor, False

    def fileno(self):
        return self.descriptor

    def close(self):
        self.closed = True


class FakeSelector:
    def __init__(self):
        self.entries = {}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def register(self, pipe, event, stream):
        self.entries[pipe] = SimpleNamespace(fileobj=pipe, data=stream)

    def unregister(self, pipe):
        del self.entries[pipe]

    def get_map(self):
        return self.entries

    def select(self, timeout):
        return [(key, None) for key in list(self.entries.values())]


class SyntheticProcessReceipts(unittest.TestCase):
    """No subprocess can run: Popen and process-group signaling are mocked."""
    def exercise(self, mode):
        process = SimpleNamespace(pid=123456789, returncode=1, stdout=FakePipe(101), stderr=FakePipe(102))
        process.wait = lambda timeout: process.returncode
        process.poll = lambda: process.returncode
        data = {101: [b'hello', b''], 102: [b'negative fixture', b'']}
        def kill(*args):
            process.returncode = -9
        with tempfile.TemporaryDirectory(prefix='clean-command-synthetic-') as directory, \
                patch('clean_live_support.subprocess.Popen', return_value=process) as launch, \
                patch('clean_live_support.selectors.DefaultSelector', FakeSelector), \
                patch('clean_live_support.os.read', side_effect=lambda fd, count: data[fd].pop(0)), \
                patch('clean_live_support.os.killpg', side_effect=kill) as terminate, redirect_stdout(io.StringIO()):
            sandbox = Path(directory)
            commands = Commands(sandbox, {'ONLY': 'explicit'}, 90)
            if mode == 'exit':
                status, log, _ = commands.run('synthetic', ['/never-executed'], sandbox, class_load_stderr=True)
                self.assertEqual(status, 1)
                self.assertIn('negative fixture', log)
                terminate.assert_not_called()
            elif mode == 'output':
                with patch('clean_live_support.MAX_OUTPUT', 4), self.assertRaisesRegex(ValueError, 'OUTPUT_LIMIT'):
                    commands.run('synthetic', ['/never-executed'], sandbox, class_load_stderr=True)
                self.assertEqual(sum(p.stat().st_size for p in sandbox.glob('*.log')), 4)
                self.assertEqual(commands.results[0]['status'], 'OUTPUT_LIMIT')
                terminate.assert_called_once()
            else:
                with patch('clean_live_support.time.monotonic', side_effect=[0, 0, 31, 31]), \
                        self.assertRaisesRegex(ValueError, 'TIMEOUT'):
                    commands.run('synthetic', ['/never-executed'], sandbox, class_load_stderr=True)
                self.assertEqual(commands.results[0]['status'], 'TIMEOUT')
                terminate.assert_called_once()
            self.assertEqual(commands.results[0]['exitStatus'], 1 if mode == 'exit' else -9)
            self.assertEqual(launch.call_args.kwargs['env'], {'ONLY': 'explicit'})
            self.assertTrue(launch.call_args.kwargs['start_new_session'])
            self.assertTrue(process.stdout.closed and process.stderr.closed)
            self.assertIn('diagnostics', commands.results[0])
            self.assertEqual(commands.results[0]['captureComplete'], mode == 'exit')
            capture = commands.results[0]['classLoadCapture']
            self.assertEqual(capture['channel'], 'stderr')
            self.assertEqual(capture['sha256'], commands.results[0]['stderrSha256'])
            self.assertEqual(capture['complete'], mode == 'exit')
            self.assertFalse(capture['rotation'])
            self.assertTrue(capture['sharesExistingPipeAndAggregateBudgets'])

    def test_actual_nonzero_status_is_retained_without_becoming_a_pass(self):
        self.exercise('exit')

    def test_output_overflow_is_bounded_and_terminates_group(self):
        self.exercise('output')

    def test_timeout_retains_actual_signal_exit_and_terminates_group(self):
        self.exercise('timeout')

    def test_start_error_keeps_status_and_raw_hashes(self):
        with tempfile.TemporaryDirectory(prefix='clean-start-synthetic-') as directory, redirect_stdout(io.StringIO()), \
                patch('clean_live_support.subprocess.Popen', side_effect=FileNotFoundError(2, 'missing executable')):
            commands = Commands(Path(directory), {}, 30)
            with self.assertRaises(FileNotFoundError):
                commands.run('synthetic-start', ['/never-executed'], Path(directory))
            record = commands.results[0]
            self.assertEqual(record['status'], 'ERROR')
            self.assertIsNone(record['exitStatus'])
            self.assertEqual(record['errorType'], 'FileNotFoundError')
            self.assertEqual(record['errno'], 2)
            self.assertIn('stdoutSha256', record)
            self.assertIn('stderrSha256', record)
            self.assertEqual(record['diagnostics']['state'], 'UNAVAILABLE_NO_SAFE_CAUSE')


class SanitizedFailureDiagnostics(unittest.TestCase):
    def test_probe_failure_retains_only_exact_reviewed_assertion_labels(self):
        self.assertEqual(len(API_ASSERTION_LABELS), 23)
        prior = ['exact candidate IO version', 'new disposable directory required']
        failed = 'Shared FileUtils detects a same-length middle-byte change'
        text = '\n'.join('PASS ' + label for label in prior)
        text += '\nPASS arbitrary-private-value\nException in thread "main" java.lang.AssertionError: ' + failed
        result = safe_diagnostics(text)
        self.assertEqual(result['approvedProbePassLabels'], prior)
        self.assertEqual(result['approvedProbePassCount'], 2)
        self.assertEqual(result['causes'][0]['approvedProbeAssertion'], failed)
        self.assertNotIn('arbitrary-private-value', json.dumps(result))
        for suffix in [' arbitrary-private-value', ' token=DO_NOT_EMIT_123']:
            forged = safe_diagnostics('Exception in thread "main" java.lang.AssertionError: ' + failed + suffix)
            self.assertNotIn('approvedProbeAssertion', forged['causes'][0])
            self.assertNotIn('DO_NOT_EMIT_123', json.dumps(forged))

    def test_resolution_cause_retains_coordinate_status_without_url(self):
        result = safe_diagnostics('[ERROR] Could not transfer artifact org.example:demo:jar:1.0 from/to central '
                                  '(https://repo.maven.apache.org/path): status code: 503, reason phrase: unavailable')
        self.assertEqual(result['causes'][0]['kind'], 'MAVEN_RESOLUTION')
        self.assertEqual(result['causes'][0]['coordinate'], 'org.example:demo:jar:1.0')
        self.assertEqual(result['causes'][0]['httpStatus'], 503)
        self.assertNotIn('https://', json.dumps(result))

    def test_parser_cause_drops_source_excerpt_and_credentials(self):
        result = safe_diagnostics('[ERROR] Non-parseable POM: Unknown attribute \'xmlns\' for tag \'plugin\' '
                                  '(position: START_TAG seen <password>do-not-emit</password> @ line 9, column 7)')
        self.assertEqual(result['causes'][0]['kind'], 'MAVEN_XML_PARSE')
        self.assertEqual(result['causes'][0]['line'], 9)
        self.assertEqual(result['causes'][0]['column'], 7)
        self.assertNotIn('do-not-emit', json.dumps(result))
        self.assertNotIn('<password>', json.dumps(result))
        self.assertEqual(result['sensitiveLinesWithheld'], 1)

    def test_compiler_and_api_causes_drop_code_and_arbitrary_messages(self):
        text = '/source/Probe.java:42: error: cannot find symbol\n    superSecretSourceCall();\n'
        text += 'Exception in thread "main" java.lang.NoSuchMethodError: org.apache.maven.shared.utils.io.FileUtils.contentEquals\n'
        result = safe_diagnostics(text)
        self.assertEqual([c['kind'] for c in result['causes']], ['JAVA_COMPILER', 'JAVA_EXCEPTION'])
        self.assertEqual(result['causes'][1]['exceptionType'], 'java.lang.NoSuchMethodError')
        self.assertNotIn('superSecretSourceCall', json.dumps(result))
        self.assertNotIn('/source', json.dumps(result))

    def test_credential_like_values_never_enter_diagnostics(self):
        for label in ['Authorization: Bearer', 'password=', 'api_key=', 'secret=', 'token=', 'credential=']:
            result = safe_diagnostics('[ERROR] Could not transfer artifact org.example:demo:jar:1 '
                                      + label + ' DO_NOT_EMIT_123 https://username:passvalue@host/path?q=hidden')
            text = json.dumps(result)
            for value in ['DO_NOT_EMIT_123', 'username', 'passvalue', 'hidden']:
                self.assertNotIn(value, text)
            self.assertEqual(result['sensitiveLinesWithheld'], 1)
        unknown = safe_diagnostics('[ERROR] An arbitrary message includes DO_NOT_EMIT_123')
        self.assertEqual(unknown['state'], 'UNAVAILABLE_NO_SAFE_CAUSE')
        self.assertNotIn('DO_NOT_EMIT_123', json.dumps(unknown))
        secret_class = safe_diagnostics('Exception in thread "main" java.lang.Token_DO_NOT_EMIT_123Exception: failure')
        self.assertNotIn('DO_NOT_EMIT_123', json.dumps(secret_class))
        self.assertNotIn('exceptionType', secret_class['causes'][0])

    def test_diagnostic_count_scan_and_unavailable_states_are_explicit(self):
        result = safe_diagnostics('\n'.join('[ERROR] Failed to delete fixture ' + str(n) for n in range(100)))
        self.assertEqual(len(result['causes']), 32)
        self.assertTrue(result['truncated'])
        self.assertEqual(result['omittedDiagnosticLines'], 68)
        with tempfile.TemporaryDirectory(prefix='clean-diagnostics-synthetic-') as directory:
            file = Path(directory) / 'output.log'
            file.write_text('[ERROR] Failed to delete fixture\n' + ('x' * 300000))
            self.assertTrue(captured_diagnostics([file])['truncated'])
        self.assertEqual(safe_diagnostics('unrecognized raw output')['state'], 'UNAVAILABLE_NO_SAFE_CAUSE')

    def test_very_long_lines_and_exception_types_are_bounded(self):
        text = '[ERROR] ' + ('a' * 262144)
        text += '\nException in thread "main" java.lang.' + ('X' * 262144) + 'Exception: detail'
        result = safe_diagnostics(text)
        self.assertTrue(result['truncated'])
        self.assertEqual(result['truncatedLongLines'], 2)
        self.assertLess(len(json.dumps(result)), 2000)
        self.assertTrue(all(len(c.get('exceptionType', '')) <= 200 for c in result['causes']))


class FinalReceiptOrdering(unittest.TestCase):
    def test_failure_receipt_refuses_changed_identity_and_existing_targets(self):
        with tempfile.TemporaryDirectory(prefix='clean-receipt-write-synthetic-') as directory:
            sandbox = Path(directory)
            identity = (sandbox.stat().st_dev, sandbox.stat().st_ino)
            report = {'status': 'FAILED'}
            write_failure_receipt(sandbox, (identity[0], identity[1] + 1), report)
            self.assertEqual(report['diskReceipt']['status'], 'OMITTED_UNSAFE_OR_UNAVAILABLE')
            self.assertFalse((sandbox / 'execution.json').exists())
            write_failure_receipt(sandbox, identity, report)
            self.assertEqual(report['diskReceipt']['status'], 'WRITTEN')
            original = (sandbox / 'execution.json').read_bytes()
            write_failure_receipt(sandbox, identity, report)
            self.assertEqual(report['diskReceipt']['status'], 'OMITTED_UNSAFE_OR_UNAVAILABLE')
            self.assertEqual((sandbox / 'execution.json').read_bytes(), original)

    def test_failure_receipt_refuses_symlinks_without_opening_them(self):
        with patch('clean_live_support.Path.is_symlink', return_value=True), \
                patch('clean_live_support.os.open', side_effect=AssertionError('unsafe open')):
            report = {'status': 'FAILED'}
            write_failure_receipt(Path('/synthetic-sandbox'), (1, 2), report)
            self.assertEqual(report['diskReceipt']['status'], 'OMITTED_UNSAFE_OR_UNAVAILABLE')

    def test_final_containment_failure_cannot_emit_a_passed_receipt(self):
        from run_clean_live_compatibility import main
        class FakeRun:
            def __init__(self, *args):
                self.report = {'stage': 'models', 'status': 'RUNNING', 'cases': {}}

            def toolchain(self):
                pass

            def models(self):
                self.report['status'] = 'PASSED'

            def containment(self):
                raise ValueError('synthetic final containment failure')

        with tempfile.TemporaryDirectory(prefix='clean-final-receipt-synthetic-') as directory:
            base = Path(directory)
            sandbox = base / 'sandbox'; sandbox.mkdir()
            jdk = base / 'jdk'; (jdk / 'bin').mkdir(parents=True)
            for name in ['java', 'javac']:
                (jdk / 'bin' / name).write_text('synthetic placeholder, never executed')
            with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true', 'JAVA_HOME': str(jdk)}), \
                    patch.object(sys, 'argv', ['runner', '--execute', '--stage=models']), \
                    patch('run_clean_live_compatibility.shutil.which', return_value='/synthetic/mvn'), \
                    patch('run_clean_live_compatibility.tempfile.mkdtemp', return_value=str(sandbox)), \
                    patch('run_clean_live_compatibility.FixtureRun', FakeRun), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(main(), 1)
            results = [json.loads(line.removeprefix('CLEAN_LIVE_RESULT ')) for line in output.getvalue().splitlines()
                       if line.startswith('CLEAN_LIVE_RESULT ')]
            self.assertEqual(len(results), 1)
            self.assertEqual(results[0]['status'], 'FAILED')
            self.assertEqual(json.loads((sandbox / 'execution.json').read_text())['status'], 'FAILED')
            self.assertNotIn('CLEAN_LIVE_STAGE_PASSED', output.getvalue())


if __name__ == '__main__':
    unittest.main(verbosity=2)
