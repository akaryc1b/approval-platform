import base64
import hashlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
import zipfile
import struct
import zlib
import urllib.parse
import html
import subprocess
from unittest.mock import patch

MODULE = pathlib.Path(__file__).resolve().parents[1] / 'product-readiness/artifact-privacy/sanitize.py'
spec = importlib.util.spec_from_file_location('privacy', MODULE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
PASSWORD = 'Synthetic-Password-Canary!42'


def synthetic_claims(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).decode().rstrip('=')


# Deliberately unusable fixture: no authentication algorithm or real signature.
TOKEN = '.'.join((synthetic_claims({'synthetic': True}),
                  synthetic_claims({'no': 'real-credential'}),
                  base64.urlsafe_b64encode(b'synthetic-not-signed').decode().rstrip('=')))
COOKIE = 'SyntheticCookieCanary-41'
def png_chunk(name, value):
    return struct.pack('>I', len(value)) + name + value + struct.pack('>I', zlib.crc32(name + value))

PNG = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
       + png_chunk(b'IDAT', zlib.compress(b'\x00\xff\xff\xff')) + png_chunk(b'IEND', b''))


def raw_zip(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, value in entries:
            z.writestr(name, value)
    return stream.getvalue()


def fixture(extra=None):
    body = json.dumps({'data': {'accessToken': TOKEN, 'password': PASSWORD, 'userId': 'user-42', 'username': 'demo-manager'}}).encode()
    resource = hashlib.sha1(body).hexdigest() + '.json'
    rows = [
        {'type': 'context-options', 'version': 8, 'playwrightVersion': '1.60.0', 'options': {}},
        {'type': 'before', 'callId': 'call-1', 'method': 'fill', 'startTime': 20.1, 'params': {'selector': "input[name='password']", 'value': PASSWORD, 'timeout': 1000}},
        {'type': 'after', 'callId': 'call-1', 'endTime': 21.5, 'result': {}},
        {'type': 'before', 'callId': 'expect-2', 'method': 'expect', 'params': {'expression': 'to.have.text', 'expectedText': [{'string': 'DEMO-PP-0001'}]}},
        {'type': 'after', 'callId': 'expect-2', 'endTime': 23, 'result': {'matches': True}},
        {'type': 'console', 'text': 'echo ' + PASSWORD + ' ' + TOKEN},
        {'type': 'frame-snapshot', 'snapshot': {'html': ['INPUT', {'type': 'password', 'value': PASSWORD}]}},
        {'type': 'screencast-frame', 'sha1': 'screen.png', 'timestamp': 22},
    ]
    network = {'type': 'resource-snapshot', 'snapshot': {
        'time': 18.3, 'timings': {'send': 2, 'wait': 12},
        'request': {'method': 'POST', 'url': 'http://127.0.0.1/api/auth/login?token=' + TOKEN + '#/route?password=' + PASSWORD,
                    'headers': [{'name': 'aUtHoRiZaTiOn', 'value': 'Bearer ' + TOKEN}, {'name': 'Cookie', 'value': 'session=' + COOKIE}, {'name': 'X-Actor-Id', 'value': 'demo-manager'}, {'name': 'X-Tenant-Id', 'value': 'tenant-42'}, {'name': 'X-Request-Id', 'value': 'request-42'}],
                    'cookies': [{'name': 'session', 'value': COOKIE}],
                    'postData': {'mimeType': 'application/json', 'text': json.dumps({'password': PASSWORD, 'username': 'demo-manager'})}},
        'response': {'status': 200, 'headers': [{'name': 'Set-Cookie', 'value': 'session=' + COOKIE + '; Path=/; HttpOnly'}],
                     'content': {'mimeType': 'application/json', 'size': len(body), '_sha1': resource}},
    }}
    entries = [('test.trace', '\n'.join(map(json.dumps, rows)) + '\n'), ('0.network', json.dumps(network) + '\n'),
               ('test.stacks', json.dumps({'files': ['test.ts'], 'stacks': []})),
               ('resources/' + resource, body), ('resources/screen.png', PNG),
               ('resources/src@fixture.txt', "await password.fill('" + PASSWORD + "');\nawait expect(task).toHaveText('DEMO-PP-0001');\n"),
               ('storage-state.json', json.dumps({'cookies': [{'name': 'session', 'value': COOKIE}], 'origins': [{'localStorage': [{'name': 'accessToken', 'value': TOKEN}, {'name': 'businessKey', 'value': 'DEMO-PP-0001'}]}]}))]
    return entries + (extra or [])


class PrivacyTests(unittest.TestCase):
    def publish(self, entries, extra_files=None):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'trace.zip'
            path.write_bytes(raw_zip(entries))
            paths = [str(path)]
            for name, data in (extra_files or {}).items():
                target = pathlib.Path(tmp) / name
                target.write_bytes(data)
                paths.append(str(target))
            return p.prepare({'paths': paths})

    def test_native_schema_secrets_references_and_semantics(self):
        before = dict(fixture())
        result = self.publish(fixture())
        data = base64.b64decode(result['files'][0]['base64'])
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            self.assertIsNone(z.testzip())
            after = {name: z.read(name) for name in z.namelist()}
        joined = b'\n'.join(after.values())
        for secret in (PASSWORD, TOKEN, COOKIE):
            self.assertNotIn(secret.encode(), joined)
            self.assertNotIn(base64.b64encode(secret.encode()), joined)
        self.assertEqual(after['resources/screen.png'], PNG)
        rows = [json.loads(line) for line in after['test.trace'].splitlines()]
        original = [json.loads(line) for line in before['test.trace'].splitlines()]
        self.assertEqual(rows[3:5], original[3:5])
        self.assertEqual(rows[1]['params']['timeout'], 1000)
        self.assertEqual(rows[1]['startTime'], original[1]['startTime'])
        network = json.loads(after['0.network'])['snapshot']
        self.assertEqual(network['response']['status'], 200)
        self.assertEqual(network['time'], 18.3)
        self.assertEqual(network['request']['headers'][2:], json.loads(before['0.network'])['snapshot']['request']['headers'][2:])
        ref = network['response']['content']['_sha1']
        self.assertEqual(ref.split('.')[0], hashlib.sha1(after['resources/' + ref]).hexdigest())
        response = json.loads(after['resources/' + ref])
        self.assertEqual(response['data']['userId'], 'user-42')
        manifest = json.loads(after['approval-sanitization.json'])
        self.assertEqual(len(manifest['changedSources']), 1)
        self.assertEqual(manifest['screenshotsAndFonts'], 'byte-identical')
        state = json.loads(after['storage-state.json'])
        self.assertEqual(state['origins'][0]['localStorage'][1]['value'], 'DEMO-PP-0001')

    def test_batch_markdown_json_and_encoded_echoes(self):
        payload = json.dumps({'password': PASSWORD, 'accessToken': TOKEN})
        encoded = base64.b64encode(payload.encode()).decode()
        files = {'diagnostics.md': ('failed with ' + PASSWORD + '\n' + encoded).encode(),
                 'diagnostics.json': json.dumps({'serialized': payload, 'encoded': encoded, 'dataUrl': 'data:application/json;base64,' + encoded, 'requestId': 'request-42', 'idempotencyKey': 'payment:42', 'token': 'business-token'}).encode()}
        result = self.publish(fixture(), files)
        for record in result['files'][1:]:
            content = base64.b64decode(record['base64'])
            self.assertNotIn(PASSWORD.encode(), content)
            self.assertNotIn(TOKEN.encode(), content)
            self.assertNotIn(encoded.encode(), content)
        obj = json.loads(base64.b64decode(result['files'][2]['base64']))
        self.assertEqual(obj['token'], 'business-token')
        self.assertEqual(obj['idempotencyKey'], 'payment:42')
        self.assertEqual(json.loads(base64.b64decode(obj['encoded']))['password'], p.REDACTED)

    def test_rejects_duplicate_unsafe_unknown_missing_and_wrong_hash_entries(self):
        mutations = [fixture([('test.trace', '{}')]), fixture([('../escape.txt', 'unsafe')]),
                     fixture([('unknown.bin', 'unsafe')]), fixture([('resources/' + '0' * 40 + '.json', '{}')]),
                     [(name, value) for name, value in fixture() if name != 'resources/screen.png'],
                     [(name, str(value).replace('"version": 8', '"version": 9') if name == 'test.trace' else value) for name, value in fixture()],
                     fixture([('resources/unsupported.zip', raw_zip([('nested', 'value')]))])]
        for entries in mutations:
            with self.subTest(case=mutations.index(entries)):
                with self.assertRaises(Exception):
                    self.publish(entries)

    def test_rejects_crc_truncation_invalid_json_and_expansion(self):
        data = raw_zip(fixture())
        for bad in (data[:-24], b'not a zip'):
            with self.assertRaises(Exception):
                p.archive(bad)
        with self.assertRaises(Exception):
            self.publish(fixture(), {'failure.json': b'{"password":"a","password":"b"}'})
        for bad in [b'{"duration":1e999}', b'{"duration":NaN}', b'{"duration":Infinity}']:
            with self.assertRaises(Exception):
                self.publish(fixture(), {'failure.json': bad})
        original = p.MAX_EXPANDED
        try:
            p.MAX_EXPANDED = 32
            with self.assertRaises(Exception):
                p.archive(data)
        finally:
            p.MAX_EXPANDED = original

    def test_opaque_nested_archives_and_binary_secret_are_rejected(self):
        nested = base64.b64encode(raw_zip([('hidden.json', json.dumps({'password': PASSWORD}))])).decode()
        with self.assertRaises(Exception):
            self.publish(fixture(), {'failure.json': json.dumps({'encoded': nested}).encode()})
        with self.assertRaises(Exception):
            self.publish(fixture(), {'failure.png': PNG + PASSWORD.encode()})

    def test_failure_cli_emits_fixed_category_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / (PASSWORD + '.zip')
            path.write_bytes(b'bad ' + TOKEN.encode())
            result = subprocess.run(['python3', str(MODULE)], input=json.dumps({'paths': [str(path)]}), text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, '')
            self.assertNotIn(PASSWORD, result.stderr)
            self.assertNotIn(TOKEN, result.stderr)
            self.assertEqual(result.stderr, 'EVIDENCE_REJECTION_V1 category=ARCHIVE_INVALID\n')

    def test_assertion_received_dom_exception_has_exact_provenance(self):
        entries = fixture()
        rows = [json.loads(line) for line in entries[0][1].splitlines()]
        rows[4]['result']['received'] = {'ariaSnapshot': 'textbox password: ' + PASSWORD}
        entries[0] = ('test.trace', '\n'.join(map(json.dumps, rows)) + '\n')
        output = self.publish(entries)['files'][0]
        z = zipfile.ZipFile(io.BytesIO(base64.b64decode(output['base64'])))
        published = [json.loads(line) for line in z.read('test.trace').splitlines()]
        self.assertEqual(rows[3], published[3])
        self.assertEqual(published[4]['result']['matches'], True)
        self.assertEqual(published[4]['endTime'], rows[4]['endTime'])
        self.assertEqual(published[4]['result']['received']['ariaSnapshot'], 'textbox password: ' + p.REDACTED)
        diagnostics = json.loads(z.read('approval-sanitization.json'))['changedAssertionDiagnostics']
        self.assertEqual(len(diagnostics), 1)
        self.assertEqual(diagnostics[0]['callId'], 'expect-2')
        self.assertEqual(diagnostics[0]['recordIndex'], 4)
        self.assertEqual(diagnostics[0]['field'], 'result.received.ariaSnapshot')
        rows[3]['params']['expectedText'][0]['string'] = PASSWORD
        entries[0] = ('test.trace', '\n'.join(map(json.dumps, rows)) + '\n')
        with self.assertRaises(p.UnsafeEvidence):
            self.publish(entries)

    def test_encoded_echoes_and_identity_collisions_fail_closed(self):
        encodings = [
            ''.join('&#' + str(ord(char)) + ';' for char in PASSWORD),
            ''.join('\\u%04x' % ord(char) for char in PASSWORD),
            ''.join('%%%02X' % ord(char) for char in PASSWORD),
            urllib.parse.quote(json.dumps({'password': PASSWORD}), safe=''),
            html.escape(json.dumps({'password': PASSWORD}), quote=True),
        ]
        for encoded in encodings:
            with self.subTest(encoding=encodings.index(encoded)):
                try:
                    published = self.publish(fixture(), {'encoded.json': json.dumps({'echo': encoded}).encode()})
                except p.UnsafeEvidence:
                    continue
                value = json.loads(base64.b64decode(published['files'][1]['base64']))['echo']
                self.assertNotIn(PASSWORD, value)
                self.assertNotIn(PASSWORD, html.unescape(urllib.parse.unquote(value)))
        for field in ['businessKey', 'actorId', 'tenantId', 'requestId', 'traceId', 'idempotencyKey']:
            with self.subTest(field=field):
                with self.assertRaises(p.UnsafeEvidence):
                    self.publish(fixture(), {'collision.json': json.dumps({field: PASSWORD}).encode()})
        with self.assertRaises(p.UnsafeEvidence):
            self.publish(fixture(), {'key.json': json.dumps({PASSWORD: 'value'}).encode()})

    def test_source_assignments_and_plain_authorization_are_sanitized(self):
        token = 'Synthetic-Opaque-Token-Canary!84'
        entries = fixture([('resources/src@extra.txt',
                           'const user = {"password": "' + PASSWORD + '"};\nconst access_token = "' + token + '";')])
        basic = base64.b64encode(('synthetic-user:' + PASSWORD).encode()).decode()
        result = self.publish(entries, {'diagnostics.md': ('Authorization: Bearer ' + token
                              + '\nAuthorization: Basic ' + basic).encode()})
        for record in result['files']:
            content = base64.b64decode(record['base64'])
            if content.startswith(b'PK'):
                z = zipfile.ZipFile(io.BytesIO(content))
                content = b'\n'.join(z.read(name) for name in z.namelist())
            self.assertNotIn(token.encode(), content)
            self.assertNotIn(basic.encode(), content)
            self.assertNotIn(PASSWORD.encode(), content)

    def test_discovery_budget_and_symlink_paths_are_rejected(self):
        sanitizer = p.Sanitizer()
        with self.assertRaises(p.UnsafeEvidence):
            for index in range(6):
                sanitizer.remember(str(index) + 'x' * (100 * 1024))
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            original = directory / 'original.json'
            original.write_text('{}')
            link = directory / 'link.json'
            link.symlink_to(original)
            with self.assertRaises(p.UnsafeEvidence):
                p.prepare({'paths': [str(link)]})

    def test_basic_header_password_echo_and_html_input_variants(self):
        secret = 'Synthetic-Variant-Password!95'
        basic = base64.b64encode(('synthetic:' + secret).encode()).decode()
        result = self.publish(fixture(), {'diagnostics.json': json.dumps({
            'headers': [{'name': 'Authorization', 'value': 'Basic ' + basic}],
            'echo': secret,
        }).encode()})
        content = base64.b64decode(result['files'][1]['base64'])
        self.assertNotIn(secret.encode(), content)
        for source in ['<input type=password value=' + secret + '>',
                       '<input autocomplete=current-password value="' + secret + '">']:
            data = source.encode()
            name = 'resources/' + hashlib.sha1(data).hexdigest() + '.html'
            output = self.publish(fixture([(name, data)]))['files'][0]
            z = zipfile.ZipFile(io.BytesIO(base64.b64decode(output['base64'])))
            self.assertNotIn(secret.encode(), b'\n'.join(z.read(name) for name in z.namelist()))

    def test_embedded_binary_data_urls_are_validated_before_publication(self):
        bad_png = PNG[:-12] + png_chunk(b'tEXt', ('password=' + PASSWORD).encode()) + PNG[-12:]
        for value in [
            'data:image/png;base64,' + base64.b64encode(bad_png).decode(),
            base64.b64encode(bad_png).decode(),
            'data:application/octet-stream;base64,' + base64.b64encode(bad_png).decode(),
        ]:
            with self.assertRaises(p.UnsafeEvidence):
                self.publish(fixture(), {'encoded.json': json.dumps({'image': value}).encode()})
        good = 'data:image/png;base64,' + base64.b64encode(PNG).decode()
        output = self.publish(fixture(), {'encoded.json': json.dumps({'image': good}).encode()})
        self.assertEqual(json.loads(base64.b64decode(output['files'][1]['base64']))['image'], good)


class RejectionDiagnosticsTests(unittest.TestCase):
    def assert_rejected(self, result, category):
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '')
        self.assertEqual(result.stderr, 'EVIDENCE_REJECTION_V1 category=' + category + '\n')
        for secret in (PASSWORD, TOKEN, COOKIE):
            self.assertNotIn(secret, result.stderr)

    def cli(self, request):
        return subprocess.run(['python3', str(MODULE)], input=request, text=True, capture_output=True)

    def reject_files(self, files, category):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for name, data in files:
                path = pathlib.Path(tmp) / name
                path.write_bytes(data)
                paths.append(str(path))
            self.assert_rejected(self.cli(json.dumps({'paths': paths})), category)
            for path, (_, data) in zip(paths, files):
                self.assertEqual(pathlib.Path(path).read_bytes(), data, 'rejection changed original evidence')

    def test_request_and_read_categories_do_not_expose_parser_or_path(self):
        for request in ('{"paths":"' + PASSWORD, json.dumps({'paths': PASSWORD}),
                        json.dumps({'category': 'ARCHIVE_INVALID'}), json.dumps({'paths': [42]})):
            self.assert_rejected(self.cli(request), 'REQUEST_INVALID')
        with tempfile.TemporaryDirectory() as tmp:
            absent = str(pathlib.Path(tmp) / (PASSWORD + '.json'))
            self.assert_rejected(self.cli(json.dumps({'paths': [absent]})), 'INPUT_READ_FAILED')

    def test_archive_content_binary_and_reference_categories(self):
        missing_resource = [(name, data) for name, data in fixture() if name != 'resources/screen.png']
        cases = [
            (PASSWORD + '.zip', b'not an archive ' + TOKEN.encode(), 'ARCHIVE_INVALID'),
            ('invalid.json', ('{"password":"' + PASSWORD).encode(), 'CONTENT_INVALID'),
            ('invalid.png', PNG + PASSWORD.encode(), 'BINARY_INVALID'),
            ('missing.zip', raw_zip(missing_resource), 'REFERENCE_INVALID'),
        ]
        for name, data, category in cases:
            with self.subTest(category=category):
                # A valid first file must never escape a rejected batch either.
                self.reject_files([('valid.json', b'{"status":"synthetic"}'), (name, data)], category)

    def test_discovery_and_transform_categories(self):
        nested = base64.b64encode(raw_zip([('hidden.json', json.dumps({'password': PASSWORD}))])).decode()
        self.reject_files([('encoded.json', json.dumps({'encoded': nested}).encode())], 'DISCOVERY_REJECTED')
        encoded = ''.join('&#' + str(ord(char)) + ';' for char in PASSWORD)
        self.reject_files([('encoded.json', json.dumps({'password': PASSWORD, 'echo': encoded}).encode())],
                          'TRANSFORM_REJECTED')

    def test_identity_and_assertion_categories_preserve_rejection(self):
        for field in ('businessKey', 'actorId', 'tenantId', 'requestId', 'traceId', 'idempotencyKey'):
            with self.subTest(field=field):
                self.reject_files([('identity.json', json.dumps({'password': PASSWORD, field: PASSWORD}).encode())],
                                  'IDENTITY_CHANGE')
        entries = fixture()
        rows = [json.loads(line) for line in entries[0][1].splitlines()]
        rows[3]['params']['expectedText'][0]['string'] = PASSWORD
        entries[0] = ('test.trace', '\n'.join(map(json.dumps, rows)) + '\n')
        self.reject_files([('assertion.zip', raw_zip(entries))], 'ASSERTION_CHANGE')

    def test_resource_limit_category_retains_existing_request_budget(self):
        self.assert_rejected(self.cli(json.dumps({'paths': ['synthetic.json'] * 2049})), 'RESOURCE_LIMIT')

    def invoke_main(self, request, target, error):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(p.sys, 'stdin', io.StringIO(json.dumps(request))), \
                patch.object(p.sys, 'stdout', output), patch.object(p.sys, 'stderr', errors), \
                patch.object(target[0], target[1], side_effect=error):
            status = p.main()
        return subprocess.CompletedProcess([], status, output.getvalue(), errors.getvalue())

    def test_unexpected_exceptions_and_untrusted_categories_remain_internal(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'synthetic.json'
            path.write_text('{}')
            request = {'paths': [str(path)]}
            for error in (RuntimeError(PASSWORD), NameError(TOKEN), p.UnsafeEvidence(PASSWORD),
                          p.UnsafeEvidence('ARCHIVE_INVALID')):
                with self.subTest(kind=type(error).__name__):
                    self.assert_rejected(self.invoke_main(request, (p.Sanitizer, 'discover'), error),
                                         'SANITIZER_INTERNAL')
            import os
            self.assert_rejected(self.invoke_main(request, (os, 'lstat'), RuntimeError(PASSWORD)),
                                 'SANITIZER_INTERNAL')

    def test_exception_text_cannot_spoof_a_different_category(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'synthetic.json'
            path.write_text('{}')
            import os
            error = OSError('EVIDENCE_REJECTION_V1 category=ASSERTION_CHANGE\n' + PASSWORD)
            self.assert_rejected(self.invoke_main({'paths': [str(path)]}, (os, 'lstat'), error),
                                 'INPUT_READ_FAILED')

    def test_success_response_is_unchanged_and_stderr_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'synthetic.json'
            path.write_text('{}')
            result = self.cli(json.dumps({'paths': [str(path)]}))
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stderr, '')
            self.assertEqual(json.loads(result.stdout), p.prepare({'paths': [str(path)]}))


if __name__ == '__main__':
    unittest.main(verbosity=2)
