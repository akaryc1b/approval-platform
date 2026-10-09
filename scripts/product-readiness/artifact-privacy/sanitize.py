#!/usr/bin/env python3
"""Fail-closed publication codec for the pinned Playwright v8 trace format.

This is a publication transform, never a credential or authentication mechanism.
No input-controlled text, filenames, parser exceptions or secret digests are logged.
"""
import base64
import binascii
from contextlib import contextmanager
from enum import Enum
import hashlib
import html
import io
import json
import math
import re
import stat
import struct
import sys
import urllib.parse
import zipfile
import zlib

POLICY = 'approval-mock-auth-publication-v1'
REDACTED = '[REDACTED-MOCK-AUTH]'
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
MAX_EXPANDED = 192 * 1024 * 1024
MAX_ENTRIES = 8192
MAX_DEPTH = 80
JWT = re.compile(r'(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}(?![A-Za-z0-9_-])')
SENSITIVE = {'password', 'passwd', 'pwd', 'accesstoken', 'refreshtoken', 'idtoken',
             'jwt', 'authorization', 'proxyauthorization', 'cookie', 'setcookie',
             'apikey', 'clientsecret', 'sessiontoken', 'sessionid', 'csrftoken', 'xsrftoken'}
# Generic key/token are deliberately excluded: approval/business tokens are evidence.
QUERY_SENSITIVE = SENSITIVE | {'token', 'auth', 'session'}
ENCODED = re.compile(r'(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{16,}={0,2}(?![A-Za-z0-9+/_=-])')
HEADER_SENSITIVE = {'authorization', 'proxyauthorization', 'cookie', 'setcookie',
                    'xapikey', 'xauthtoken', 'xcsrftoken', 'xxsrftoken'}
IDENTITY_FIELDS = {'businesskey', 'taskdefinitionkey', 'tenantid', 'actorid', 'operatorid',
                   'userid', 'username', 'instanceid', 'taskid', 'requestid', 'traceid',
                   'idempotencykey', 'eventid', 'providerid', 'callid', 'pageid', 'frameid',
                   'contextid', 'snapshotname'}
TRACE_TYPES = {'context-options', 'before', 'after', 'event', 'console', 'log',
               'input', 'frame-snapshot', 'screencast-frame', 'resource-snapshot',
               'stdout', 'stderr', 'error', 'attachment'}
TEXT_EXT = {'.json', '.html', '.txt', '.css', '.js', '.svg', '.dat'}
BINARY_EXT = {'.png', '.jpeg', '.jpg', '.webp', '.ttf', '.woff', '.woff2'}


class RejectionCategory(Enum):
    REQUEST_INVALID = 'REQUEST_INVALID'
    INPUT_READ_FAILED = 'INPUT_READ_FAILED'
    ARCHIVE_INVALID = 'ARCHIVE_INVALID'
    CONTENT_INVALID = 'CONTENT_INVALID'
    BINARY_INVALID = 'BINARY_INVALID'
    REFERENCE_INVALID = 'REFERENCE_INVALID'
    DISCOVERY_REJECTED = 'DISCOVERY_REJECTED'
    TRANSFORM_REJECTED = 'TRANSFORM_REJECTED'
    IDENTITY_CHANGE = 'IDENTITY_CHANGE'
    ASSERTION_CHANGE = 'ASSERTION_CHANGE'
    RESOURCE_LIMIT = 'RESOURCE_LIMIT'
    SANITIZER_INTERNAL = 'SANITIZER_INTERNAL'


class UnsafeEvidence(Exception):
    def __init__(self, category=None):
        super().__init__()
        self.category = category


@contextmanager
def rejection_stage(category, expected=()):
    """Only trusted code stages select diagnostics; never inspect error text."""
    try:
        yield
    except UnsafeEvidence as error:
        if error.category is not None:
            raise
        raise UnsafeEvidence(category) from None
    except expected:
        raise UnsafeEvidence(category) from None


def require(condition, category=None):
    if not condition:
        raise UnsafeEvidence(category)


def normalized(value):
    return re.sub(r'[-_\s]', '', value.lower())


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def parse(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    def finite_float(text):
        value = float(text)
        require(math.isfinite(value))
        return value
    return json.loads(text, object_pairs_hook=pairs, parse_float=finite_float,
                      parse_constant=lambda _: (_ for _ in ()).throw(UnsafeEvidence()))


def utf8(data):
    return data.decode('utf-8', errors='strict')


def safe_name(name):
    require(isinstance(name, str) and 0 < len(name) < 1024)
    require(not name.startswith(('/', '\\')) and '\\' not in name and '\x00' not in name)
    require(all(part not in ('', '.', '..') for part in name.split('/')))
    require(not re.search(r'[\x00-\x1f\x7f:]', name))


class Sanitizer:
    def __init__(self):
        self.secrets = set()
        self.pattern = None
        self.changes = 0
        self.secret_bytes = 0
        self.work_bytes = 0

    def budget(self, text):
        self.work_bytes += len(text)
        require(self.work_bytes <= 1024 * 1024 * 1024, RejectionCategory.RESOURCE_LIMIT)

    def remember(self, value):
        if isinstance(value, (dict, list)):
            for item in (value.values() if isinstance(value, dict) else value):
                self.remember(item)
        elif isinstance(value, str) and value and value != REDACTED:
            require(len(value) <= 128 * 1024, RejectionCategory.RESOURCE_LIMIT)
            if value not in self.secrets:
                self.secret_bytes += len(value)
                require(self.secret_bytes <= 512 * 1024, RejectionCategory.RESOURCE_LIMIT)
            self.secrets.add(value)
            require(len(self.secrets) <= 16384, RejectionCategory.RESOURCE_LIMIT)
            if value.lower().startswith('basic '):
                decoded = base64.b64decode(value.split(None, 1)[1], validate=True)
                self.remember(utf8(decoded).split(':', 1)[-1])

    def encoded_text(self, value):
        try:
            decoded = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
        except ValueError:
            return None
        require(not decoded.startswith((b'PK\x03\x04', b'\x1f\x8b')))
        require(not (len(decoded) >= 2 and decoded[0] == 0x78 and
                     (decoded[0] * 256 + decoded[1]) % 31 == 0))
        for signature, extension in ((b'\x89PNG\r\n\x1a\n', '.png'), (b'\xff\xd8', '.jpeg'),
                                     (b'RIFF', '.webp'), (b'wOFF', '.woff'),
                                     (b'wOF2', '.woff2'), (b'\x00\x01\x00\x00', '.ttf')):
            if decoded.startswith(signature):
                validate_binary(decoded, extension, self)
                return None
        try:
            text = utf8(decoded)
        except UnicodeError:
            return None
        if text.strip().startswith(('{', '[')) or JWT.search(text) or re.search(r'password|access.?token|refresh.?token|authorization|cookie', text, re.I):
            return text
        return None

    def data_url_content(self, meta, encoded):
        data = base64.b64decode(encoded, validate=True) if meta.endswith(';base64') else urllib.parse.unquote_to_bytes(encoded)
        require(len(data) <= MAX_FILE, RejectionCategory.RESOURCE_LIMIT)
        mime = meta.split(';', 1)[0].lower()
        if mime == 'application/json' or mime == 'image/svg+xml' or mime.startswith('text/'):
            return utf8(data)
        binary = {'image/png': '.png', 'image/jpeg': '.jpeg', 'image/webp': '.webp',
                  'font/ttf': '.ttf', 'font/woff': '.woff', 'font/woff2': '.woff2',
                  'application/font-woff': '.woff', 'application/x-font-ttf': '.ttf'}
        require(mime in binary)
        validate_binary(data, binary[mime], self)
        return None

    def decoded_layers(self, text):
        # These encodings are inspected recursively. Arbitrary encoded text is
        # rejected if redaction would be required; guessing its output codec
        # could change URLs, source semantics or an assertion's meaning.
        decoded = html.unescape(text)
        if decoded != text:
            yield decoded
        if re.search(r'%[0-9A-Fa-f]{2}', text):
            decoded = urllib.parse.unquote(text, errors='strict')
            if decoded != text:
                yield decoded
        if re.search(r'\\(?:u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2})', text):
            decoded = re.sub(r'\\(?:u([0-9a-fA-F]{4})|x([0-9a-fA-F]{2}))',
                             lambda m: chr(int(m.group(1) or m.group(2), 16)), text)
            if decoded != text:
                yield decoded

    def named_query(self, text, transform=False):
        def replace(match):
            prefix, name, value = match.groups()
            if normalized(urllib.parse.unquote_plus(name)) not in QUERY_SENSITIVE:
                return match.group()
            if transform:
                return prefix + name + '=' + urllib.parse.quote(REDACTED)
            self.remember(urllib.parse.unquote_plus(value))
            return match.group()
        return re.sub(r'([?&#])([^=\s&#]+)=([^\s&#\"\'<>]+)', replace, text)

    def userinfo(self, text, transform=False):
        def replace(match):
            scheme, userinfo = match.groups()
            if transform:
                return scheme + urllib.parse.quote(REDACTED) + '@'
            if ':' in userinfo:
                self.remember(urllib.parse.unquote(userinfo.split(':', 1)[1]))
            return match.group()
        return re.sub(r'(https?://)([^/\s\"\'<>@]+)@', replace, text, flags=re.I)

    def html(self, text, transform=False):
        def element(match):
            tag = match.group()
            attributes = re.compile(r'''(\b[\w-]+\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))''')
            values = {normalized(item.group(1).split('=')[0].strip()):
                      html.unescape(next(part for part in item.groups()[1:] if part is not None))
                      for item in attributes.finditer(tag)}
            if not self.password_input(values):
                return tag
            def attribute(item):
                prefix = item.group(1)
                if normalized(prefix.split('=')[0].strip()) not in {'value', 'playwrightvalue'}:
                    return item.group()
                secret = next(part for part in item.groups()[1:] if part is not None)
                if transform:
                    return prefix + '"' + REDACTED + '"'
                self.remember(secret)
                self.remember(html.unescape(secret))
                return item.group()
            return attributes.sub(attribute, tag)
        return re.sub(r'<input\b[^>]*>', element, text, flags=re.I)

    def password_input(self, value):
        return (str(value.get('type', '')).lower() == 'password'
                or str(value.get('name', '')).lower() == 'password'
                or str(value.get('autocomplete', '')).lower() in ('current-password', 'new-password'))

    def discover_text(self, text, depth=0):
        require(depth <= MAX_DEPTH, RejectionCategory.RESOURCE_LIMIT)
        self.budget(text)
        self.named_query(text)
        self.userinfo(text)
        self.html(text)
        for decoded in self.decoded_layers(text):
            self.discover_text(decoded, depth + 1)
        for match in re.finditer(r"\b([A-Za-z][A-Za-z_-]*)['\"]?\s*[:=]\s*(['\"])(.*?)\2", text, re.I):
            if normalized(match.group(1)) in SENSITIVE:
                self.remember(match.group(3))
        for match in re.finditer(r'(?im)^\s*(authorization|proxy-authorization|cookie|set-cookie|x-api-key)\s*:\s*([^\r\n]+)', text):
            self.remember(match.group(2).strip())
            if match.group(2).lower().startswith('basic '):
                decoded = base64.b64decode(match.group(2).split(None, 1)[1], validate=True)
                self.remember(utf8(decoded).split(':', 1)[-1])
        for match in ENCODED.finditer(text):
            decoded = self.encoded_text(match.group())
            if decoded is not None:
                self.discover_text(decoded, depth + 1)
        for match in JWT.finditer(text):
            self.remember(match.group())
        # Embedded source and error snippets can survive an early failed login.
        for match in re.finditer(r'\b(?:password|passwd|pwd)\s*\.\s*fill\(\s*([\'\"])(.*?)\1', text, re.I):
            self.remember(match.group(2))
        for match in re.finditer(r'(?i)(?:[?&#]|\b)(password|passwd|access_token|refresh_token|id_token|token|jwt|api_key|client_secret)=([^\s&#\"\'<>]+)', text):
            self.remember(urllib.parse.unquote_plus(match.group(2)))
        stripped = text.strip()
        if stripped.startswith(('{', '[')):
            try:
                obj = parse(stripped)
            except (ValueError, UnsafeEvidence):
                pass
            else:
                self.discover(obj, depth + 1)
        for match in re.finditer(r'data:([^,\s]+),([^\s\"\'<>]+)', text):
            meta, encoded = match.groups()
            decoded = self.data_url_content(meta, encoded)
            if decoded is not None:
                self.discover_text(decoded, depth + 1)

    def discover(self, value, depth=0, parent=''):
        require(depth <= MAX_DEPTH, RejectionCategory.RESOURCE_LIMIT)
        if isinstance(value, dict):
            name = value.get('name')
            if self.password_input(value):
                for field in ('value', '__playwright_value_'):
                    self.remember(value.get(field))
            if isinstance(name, str) and (normalized(name) in SENSITIVE or parent == 'cookies' or (parent in ('localStorage', 'sessionStorage') and normalized(name) in QUERY_SENSITIVE) or (parent == 'headers' and normalized(name) in HEADER_SENSITIVE) or (parent in ('queryString', 'params') and normalized(name) in QUERY_SENSITIVE)):
                self.remember(value.get('value'))
            params = value.get('params')
            if value.get('method') in ('fill', 'type', 'pressSequentially', 'insertText') and isinstance(params, dict) and re.search(r'password|passwd|\bpwd\b', str(params.get('selector', '')), re.I):
                self.remember(params.get('value', params.get('text')))
            for key, item in value.items():
                self.discover_text(key, depth + 1)
                if normalized(key) in SENSITIVE or (normalized(parent) in ('headers', 'extrahttpheaders') and normalized(key) in HEADER_SENSITIVE):
                    self.remember(item)
                self.discover(item, depth + 1, key)
        elif isinstance(value, list):
            for item in value:
                self.discover(item, depth + 1, parent)
        elif isinstance(value, str):
            self.discover_text(value, depth + 1)

    def freeze(self):
        values = set()
        for secret in self.secrets:
            # Cookies/auth headers contain additional credential atoms.
            atoms = {secret}
            if secret.lower().startswith(('bearer ', 'basic ')):
                atoms.add(secret.split(' ', 1)[1])
            if '=' in secret:
                atoms.update(m.group(2).strip() for m in re.finditer(r'(?:^|[;,]\s*)([^=;, ]+)=([^;,]+)', secret) if normalized(m.group(1)) not in {'path', 'domain', 'maxage', 'expires', 'samesite'})
            for atom in atoms:
                if not atom or atom == REDACTED:
                    continue
                values.update((atom, urllib.parse.quote(atom, safe=''), urllib.parse.quote_plus(atom),
                               base64.b64encode(atom.encode()).decode(),
                               base64.urlsafe_b64encode(atom.encode()).decode().rstrip('='),
                               json.dumps(atom, ensure_ascii=True)[1:-1]))
        # Boundaries avoid corrupting IDs/timings containing a short fixture password.
        alternatives = sorted(values, key=lambda value: (-len(value), value))
        require(sum(len(value) for value in alternatives) <= 4 * 1024 * 1024, RejectionCategory.RESOURCE_LIMIT)
        self.pattern = re.compile('|'.join(r'(?<![\w])' + re.escape(v) + r'(?![\w])' if len(v) < 12 else re.escape(v) for v in alternatives)) if alternatives else None

    def text(self, text, depth=0):
        require(depth <= MAX_DEPTH, RejectionCategory.RESOURCE_LIMIT)
        self.budget(text)
        original = text
        stripped = text.strip()
        if stripped.startswith(('{', '[')):
            try:
                obj = parse(stripped)
            except (ValueError, UnsafeEvidence):
                pass
            else:
                changed = self.value(obj, depth + 1)
                if changed != obj:
                    text = dumps(changed)
        # Decode structured data URLs before plain replacement, preserving the codec.
        def data_url(match):
            meta, encoded = match.groups()
            decoded = self.data_url_content(meta, encoded)
            if decoded is None:
                return match.group()
            sanitized = self.text(decoded, depth + 1).encode()
            result = base64.b64encode(sanitized).decode() if meta.endswith(';base64') else urllib.parse.quote_from_bytes(sanitized)
            return 'data:' + meta + ',' + result
        text = re.sub(r'data:([^,\s]+),([^\s\"\'<>]+)', data_url, text)
        def encoded(match):
            decoded = self.encoded_text(match.group())
            if decoded is None:
                return match.group()
            cleaned = self.text(decoded, depth + 1)
            if cleaned == decoded:
                return match.group()
            encoded = base64.b64encode(cleaned.encode()).decode()
            if '-' in match.group() or '_' in match.group():
                encoded = encoded.replace('+', '-').replace('/', '_')
            return encoded if match.group().endswith('=') else encoded.rstrip('=')
        text = ENCODED.sub(encoded, text)
        text = self.named_query(text, True)
        text = self.userinfo(text, True)
        text = self.html(text, True)
        text = JWT.sub(REDACTED, text)
        if self.pattern:
            text = self.pattern.sub(REDACTED, text)
        text = re.sub(r'(?i)((?:[?&#]|\b)(?:password|passwd|access_token|refresh_token|id_token|token|jwt|api_key|client_secret)=)[^\s&#\"\'<>]+', lambda m: m.group(1) + urllib.parse.quote(REDACTED), text)
        self.validate_encoded(text, depth + 1)
        self.changes += text != original
        return text

    def validate_encoded(self, text, depth):
        require(depth <= MAX_DEPTH, RejectionCategory.RESOURCE_LIMIT)
        for decoded in self.decoded_layers(text):
            self.budget(decoded)
            require(not JWT.search(decoded) and not (self.pattern and self.pattern.search(decoded)))
            self.validate_encoded(decoded, depth + 1)

    def value(self, value, depth=0, parent=''):
        require(depth <= MAX_DEPTH, RejectionCategory.RESOURCE_LIMIT)
        if isinstance(value, dict):
            result = {}
            name = value.get('name')
            named_secret = isinstance(name, str) and (normalized(name) in SENSITIVE or parent == 'cookies' or (parent in ('localStorage', 'sessionStorage') and normalized(name) in QUERY_SENSITIVE) or (parent == 'headers' and normalized(name) in HEADER_SENSITIVE) or (parent in ('queryString', 'params') and normalized(name) in QUERY_SENSITIVE))
            secret_input = value.get('method') in ('fill', 'type', 'pressSequentially', 'insertText') and isinstance(value.get('params'), dict) and re.search(r'password|passwd|\bpwd\b', str(value['params'].get('selector', '')), re.I)
            for key, item in value.items():
                require(self.text(key, depth + 1) == key, RejectionCategory.IDENTITY_CHANGE)
                if normalized(key) in SENSITIVE or (normalized(parent) in ('headers', 'extrahttpheaders') and normalized(key) in HEADER_SENSITIVE) or (key == 'value' and named_secret) or (self.password_input(value) and key in ('value', '__playwright_value_')):
                    result[key] = REDACTED if item is not None else None
                    self.changes += item != result[key]
                elif key == 'params' and secret_input:
                    result[key] = self.value(item, depth + 1, key)
                    for field in ('value', 'text'):
                        if field in result[key]:
                            result[key][field] = REDACTED
                else:
                    result[key] = self.value(item, depth + 1, key)
                    if normalized(key) in IDENTITY_FIELDS:
                        require(result[key] == item, RejectionCategory.IDENTITY_CHANGE)
            return result
        if isinstance(value, list):
            return [self.value(item, depth + 1, parent) for item in value]
        if isinstance(value, str):
            return self.text(value, depth + 1)
        return value


def inflate(data, limit, raw=False):
    """Decode exactly one bounded stream, rejecting concatenation and junk."""
    import zlib
    decoder = zlib.decompressobj(-15 if raw else 15)
    content = decoder.decompress(data, limit + 1)
    require(len(content) <= limit and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail)
    return content


@rejection_stage(RejectionCategory.ARCHIVE_INVALID, (struct.error, zlib.error, UnicodeError))
def archive(data):
    import struct
    import unicodedata
    import zlib
    require(22 <= len(data) <= MAX_FILE and data.startswith(b'PK\x03\x04'))
    # Playwright emits ordinary single-disk ZIPs. Require exact framing rather
    # than zipfile's permissive search for an EOCD before arbitrary trailing data.
    end = struct.unpack_from('<4s4H2IH', data, len(data) - 22)
    require(end[0] == b'PK\x05\x06' and end[1:3] == (0, 0) and end[3] == end[4])
    count, directory_size, directory_offset, comment_size = end[4:]
    require(0 < count <= MAX_ENTRIES and not comment_size)
    require(directory_offset + directory_size == len(data) - 22)
    entries = {}
    aliases = set()
    total = 0
    local_end = 0
    position = directory_offset
    for _ in range(count):
        require(position + 46 <= len(data) - 22)
        central = struct.unpack_from('<4s6H3I5H2I', data, position)
        require(central[0] == b'PK\x01\x02')
        _, _, version, flags, method, time, date, crc, packed, size, namesize, extra, comment, disk, _, attrs, offset = central
        require(version <= 20 and flags & ~0x808 == 0 and method in (0, 8))
        require(not extra and not comment and not disk and not attrs & 0x10)
        require(stat.S_IFMT(attrs >> 16) in (0, stat.S_IFREG))
        require(position + 46 + namesize <= len(data) - 22)
        namebytes = data[position + 46:position + 46 + namesize]
        name = namebytes.decode('utf-8' if flags & 0x800 else 'cp437', errors='strict')
        safe_name(name)
        alias = unicodedata.normalize('NFC', name).casefold()
        require(name not in entries and alias not in aliases)
        aliases.add(alias)
        require(size <= MAX_FILE and packed <= MAX_FILE, RejectionCategory.RESOURCE_LIMIT)
        total += size
        require(total <= MAX_EXPANDED, RejectionCategory.RESOURCE_LIMIT)
        require(offset == local_end and offset + 30 <= directory_offset)
        local = struct.unpack_from('<4s5H3I2H', data, offset)
        require(local[:6] == (b'PK\x03\x04', version, flags, method, time, date))
        require(local[9:] == (namesize, 0))
        require(data[offset + 30:offset + 30 + namesize] == namebytes)
        start = offset + 30 + namesize
        stop = start + packed
        require(stop <= directory_offset)
        if flags & 8:
            require(local[6:9] in ((0, 0, 0), (crc, packed, size)))
            signed = data[stop:stop + 4] == b'PK\x07\x08'
            descriptor = stop + (4 if signed else 0)
            require(descriptor + 12 <= directory_offset)
            require(struct.unpack_from('<3I', data, descriptor) == (crc, packed, size))
            local_end = descriptor + 12
        else:
            require(local[6:9] == (crc, packed, size))
            local_end = stop
        content = data[start:stop] if method == 0 else inflate(data[start:stop], size, raw=True)
        require(len(content) == size and zlib.crc32(content) & 0xffffffff == crc)
        entries[name] = content
        position += 46 + namesize
    require(local_end == directory_offset and position == len(data) - 22)
    require(any(name.endswith('.trace') for name in entries))
    return entries


def validate_png(data):
    import struct
    import zlib
    require(data.startswith(b'\x89PNG\r\n\x1a\n'))
    position = 8
    chunks = []
    compressed = []
    expected = None
    palette = False
    while position + 12 <= len(data):
        size, kind = struct.unpack_from('>I4s', data, position)
        require(size <= MAX_FILE and position + 12 + size <= len(data))
        body = data[position + 8:position + 8 + size]
        crc = struct.unpack_from('>I', data, position + 8 + size)[0]
        require(zlib.crc32(kind + body) & 0xffffffff == crc)
        # Text, compressed text, EXIF, ICC and unknown ancillary chunks are
        # deliberately unsupported; image bytes must never be rewritten.
        require(kind in {b'IHDR', b'PLTE', b'IDAT', b'IEND', b'sRGB', b'sBIT', b'gAMA', b'cHRM', b'pHYs', b'tRNS'})
        if not chunks:
            require(kind == b'IHDR' and size == 13)
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', body)
            channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
            depths = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
            require(width and height and color in channels and depth in depths[color])
            require(compression == filtering == interlace == 0)
            stride = (width * channels[color] * depth + 7) // 8 + 1
            expected = height * stride
            require(expected <= MAX_EXPANDED, RejectionCategory.RESOURCE_LIMIT)
        elif kind == b'IDAT':
            require(chunks[-1] in (b'IDAT', b'IHDR', b'PLTE', b'sRGB', b'sBIT', b'gAMA', b'cHRM', b'pHYs', b'tRNS'))
            require(color != 3 or palette)
            compressed.append(body)
        elif kind == b'IEND':
            require(size == 0 and compressed and position + 12 == len(data))
            pixels = inflate(b''.join(compressed), expected)
            require(len(pixels) == expected and all(pixels[row] <= 4 for row in range(0, expected, stride)))
            return
        else:
            require(kind != b'IHDR' and kind not in chunks and not compressed)
            if kind == b'PLTE':
                require(color not in (0, 4) and 0 < size <= 768 and size % 3 == 0)
                require(color != 3 or size // 3 <= 2 ** depth)
                palette = True
            elif kind == b'sRGB':
                require(size == 1 and body[0] <= 3)
            elif kind == b'sBIT':
                require(size == (3 if color == 3 else channels[color]))
                require(all(0 < item <= (8 if color == 3 else depth) for item in body))
            elif kind == b'gAMA':
                require(size == 4 and any(body))
            elif kind == b'cHRM':
                require(size == 32)
            elif kind == b'pHYs':
                require(size == 9 and body[-1] <= 1)
            elif kind == b'tRNS':
                require((color == 0 and size == 2) or (color == 2 and size == 6) or
                        (color == 3 and palette and 0 < size <= 256))
        chunks.append(kind)
        require(len(chunks) <= MAX_ENTRIES, RejectionCategory.RESOURCE_LIMIT)
        position += 12 + size
    require(False)


def validate_jpeg(data):
    import struct
    require(data.startswith(b'\xff\xd8'))
    position = 2
    frame = False
    scans = 0
    while position + 2 <= len(data):
        require(data[position] == 255)
        marker = data[position + 1]
        position += 2
        if marker == 0xd9:
            require(frame and scans and position == len(data))
            return
        require(marker in {0xe0, 0xdb, 0xc0, 0xc1, 0xc2, 0xc4, 0xdd, 0xda})
        require(position + 2 <= len(data))
        size = struct.unpack_from('>H', data, position)[0]
        require(size >= 2 and position + size <= len(data))
        body = data[position + 2:position + size]
        position += size
        if marker == 0xe0:
            # JFIF's fixed header only; thumbnails and all EXIF/comments/APP
            # metadata are not part of the supported screenshot profile.
            require(not frame and len(body) == 14 and body[:5] == b'JFIF\x00')
            require(body[5] == 1 and body[7] <= 2 and body[-2:] == b'\x00\x00')
        elif marker in (0xc0, 0xc1, 0xc2):
            require(not frame and len(body) >= 6 and body[0] in (8, 12))
            height, width = struct.unpack_from('>HH', body, 1)
            require(width and height and width * height * 4 <= MAX_EXPANDED)
            require(body[5] in (1, 3, 4) and len(body) == 6 + 3 * body[5])
            frame = True
        elif marker == 0xdb:
            cursor = 0
            while cursor < len(body):
                require(body[cursor] >> 4 in (0, 1) and body[cursor] & 15 <= 3)
                cursor += 1 + 64 * (1 + (body[cursor] >> 4))
            require(cursor == len(body) and cursor)
        elif marker == 0xc4:
            cursor = 0
            while cursor < len(body):
                require(cursor + 17 <= len(body) and body[cursor] >> 4 <= 1 and body[cursor] & 15 <= 3)
                count = sum(body[cursor + 1:cursor + 17])
                require(0 < count <= 256)
                cursor += 17 + count
            require(cursor == len(body) and cursor)
        elif marker == 0xdd:
            require(len(body) == 2)
        elif marker == 0xda:
            require(frame and body and body[0] in (1, 2, 3, 4) and len(body) == 4 + 2 * body[0])
            scans += 1
            require(scans <= 256)
            # In entropy data FF00 escapes a data byte; restart markers carry
            # no body. Every other marker returns to the framed parser.
            while position < len(data):
                found = data.find(b'\xff', position)
                require(found >= 0 and found + 1 < len(data))
                following = data[found + 1]
                if following == 0 or 0xd0 <= following <= 0xd7:
                    position = found + 2
                else:
                    position = found
                    break
    require(False)


def validate_webp(data):
    import struct
    require(len(data) >= 20 and data[:4] == b'RIFF' and data[8:12] == b'WEBP')
    require(struct.unpack_from('<I', data, 4)[0] + 8 == len(data))
    position = 12
    kinds = []
    for _ in range(4):
        if position == len(data):
            break
        require(position + 8 <= len(data))
        kind, size = struct.unpack_from('<4sI', data, position)
        require(position + 8 + size + size % 2 <= len(data))
        body = data[position + 8:position + 8 + size]
        require(kind in {b'VP8X', b'ALPH', b'VP8 ', b'VP8L'} and kind not in kinds)
        if kind == b'VP8X':
            require(not kinds and size == 10 and body[0] & ~0x10 == 0 and body[1:4] == b'\x00' * 3)
            width = int.from_bytes(body[4:7], 'little') + 1
            height = int.from_bytes(body[7:10], 'little') + 1
            require(width * height * 4 <= MAX_EXPANDED)
        elif kind == b'ALPH':
            require(kinds == [b'VP8X'] and size > 1 and body[0] & 0xc0 == 0 and body[0] & 3 <= 1)
        elif kind == b'VP8 ':
            require(size >= 10 and not body[0] & 1 and body[3:6] == b'\x9d\x01\x2a')
            require((int.from_bytes(body[:3], 'little') >> 5) + 10 <= size)
            width, height = struct.unpack_from('<HH', body, 6)
            require(width & 0x3fff and height & 0x3fff and (width & 0x3fff) * (height & 0x3fff) * 4 <= MAX_EXPANDED)
        else:
            require(size >= 5 and body[0] == 0x2f and body[4] & 0xe0 == 0 and b'ALPH' not in kinds)
            bits = int.from_bytes(body[1:5], 'little')
            require(((bits & 0x3fff) + 1) * (((bits >> 14) & 0x3fff) + 1) * 4 <= MAX_EXPANDED)
        require(not size % 2 or data[position + 8 + size] == 0)
        kinds.append(kind)
        position += 8 + size + size % 2
    require(position == len(data) and kinds[-1:] in ([b'VP8 '], [b'VP8L']))
    require(sum(kind in (b'VP8 ', b'VP8L') for kind in kinds) == 1)


FONT_TAGS = {b'cmap', b'head', b'hhea', b'hmtx', b'maxp', b'name', b'OS/2', b'post',
             b'cvt ', b'fpgm', b'glyf', b'loca', b'prep', b'gasp', b'kern', b'GDEF', b'GPOS', b'GSUB'}
FONT_REQUIRED = {b'cmap', b'head', b'hhea', b'hmtx', b'maxp', b'name', b'OS/2', b'post', b'glyf', b'loca'}


def font_names(data):
    import struct
    require(len(data) >= 6)
    version, count, storage = struct.unpack_from('>3H', data)
    require(version == 0 and count <= 4096 and storage == 6 + count * 12 and storage <= len(data))
    texts = []
    covered = bytearray(len(data) - storage)
    for index in range(count):
        platform, encoding, _, _, length, offset = struct.unpack_from('>6H', data, 6 + 12 * index)
        require(offset + length <= len(covered))
        raw = data[storage + offset:storage + offset + length]
        if platform == 0 or platform == 3 and encoding in (0, 1, 10):
            text = raw.decode('utf-16-be', errors='strict')
        else:
            require(platform == 1 and encoding == 0)
            text = raw.decode('mac_roman', errors='strict')
        texts.append(text)
        covered[offset:offset + length] = b'\x01' * length
    require(all(covered))
    return texts


def font_tables(tables):
    require(FONT_REQUIRED <= tables.keys() and tables.keys() <= FONT_TAGS)
    require(len(tables[b'head']) == 54 and tables[b'head'][12:16] == b'\x5f\x0f\x3c\xf5')
    require(len(tables[b'hhea']) == 36 and len(tables[b'maxp']) == 32)
    return [body.decode('latin-1') for body in tables.values()], font_names(tables[b'name'])


def validate_font(data, extension):
    import struct
    require(len(data) >= 12)
    tables = {}
    if extension == '.ttf':
        require(data[:4] == b'\x00\x01\x00\x00')
        count = struct.unpack_from('>H', data, 4)[0]
        require(0 < count <= len(FONT_TAGS) and 12 + count * 16 <= len(data))
        ranges = []
        for index in range(count):
            tag, checksum, offset, size = struct.unpack_from('>4sIII', data, 12 + 16 * index)
            require(tag in FONT_TAGS and tag not in tables and offset % 4 == 0 and offset + size <= len(data))
            body = data[offset:offset + size]
            check = body[:8] + b'\x00' * 4 + body[12:] if tag == b'head' else body
            check += b'\x00' * (-len(check) % 4)
            require(sum(value[0] for value in struct.iter_unpack('>I', check)) & 0xffffffff == checksum)
            tables[tag] = body
            ranges.append((offset, size))
        position = 12 + count * 16
        for offset, size in sorted(ranges):
            require(offset == (position + 3) // 4 * 4 and not any(data[position:offset]))
            position = offset + size
        require(position <= len(data) <= (position + 3) // 4 * 4 and not any(data[position:]))
    elif extension == '.woff':
        require(len(data) >= 44 and data[:4] == b'wOFF' and data[4:8] == b'\x00\x01\x00\x00')
        length, count, reserved, total = struct.unpack_from('>IHHI', data, 8)
        require(length == len(data) and not reserved and 0 < count <= len(FONT_TAGS) and total <= MAX_FILE)
        require(not any(data[24:44]))
        require(44 + count * 20 <= len(data))
        expanded = 12 + 16 * count
        require(expanded <= total)
        ranges = []
        for index in range(count):
            tag, offset, packed, size, checksum = struct.unpack_from('>4s4I', data, 44 + 20 * index)
            require(tag in FONT_TAGS and tag not in tables and 0 < packed <= size <= MAX_FILE and offset % 4 == 0 and offset + packed <= len(data))
            expanded += (size + 3) // 4 * 4
            require(expanded <= total)
            body = data[offset:offset + packed]
            if packed < size:
                body = inflate(body, size)
            require(len(body) == size)
            check = body[:8] + b'\x00' * 4 + body[12:] if tag == b'head' else body
            check += b'\x00' * (-len(check) % 4)
            require(sum(value[0] for value in struct.iter_unpack('>I', check)) & 0xffffffff == checksum)
            tables[tag] = body
            ranges.append((offset, packed))
        require(total == 12 + 16 * count + sum((len(body) + 3) // 4 * 4 for body in tables.values()))
        position = 44 + count * 20
        for offset, size in sorted(ranges):
            require(offset == (position + 3) // 4 * 4 and not any(data[position:offset]))
            position = offset + size
        require(position <= len(data) <= (position + 3) // 4 * 4 and not any(data[position:]))
    else:
        import subprocess
        require(len(data) >= 48 and data[:4] == b'wOF2' and data[4:8] == b'\x00\x01\x00\x00')
        length, count, reserved, total, packed = struct.unpack_from('>IHHII', data, 8)
        require(length == len(data) and not reserved and 0 < count <= len(FONT_TAGS) and total <= MAX_FILE)
        require(not any(data[28:48]))
        known = [b'cmap', b'head', b'hhea', b'hmtx', b'maxp', b'name', b'OS/2', b'post', b'cvt ', b'fpgm', b'glyf', b'loca', b'prep']
        known.extend([None] * 50)
        for index, tag in {17: b'gasp', 19: b'kern', 26: b'GDEF', 27: b'GPOS', 28: b'GSUB'}.items():
            known[index] = tag
        position = 48
        def integer():
            nonlocal position
            number = 0
            for index in range(5):
                require(position < len(data))
                item = data[position]
                position += 1
                require(not (index == 0 and item == 0x80) and number <= 0x1ffffff)
                number = number * 128 + (item & 127)
                if not item & 128:
                    return number
            require(False)
        directory = []
        tags = set()
        expected = 0
        for _ in range(count):
            require(position < len(data))
            flags = data[position]
            position += 1
            tag = known[flags & 63] if flags & 63 < 63 else data[position:position + 4]
            if flags & 63 == 63:
                position += 4
            require(tag in FONT_TAGS and tag not in tags)
            tags.add(tag)
            original = integer()
            transform = flags >> 6
            require(transform in ((0, 3) if tag in (b'glyf', b'loca') else ((0, 1) if tag == b'hmtx' else (0,))))
            transformed = transform != (3 if tag in (b'glyf', b'loca') else 0)
            size = integer() if transformed else original
            require(original <= MAX_FILE and size <= MAX_FILE and (tag != b'loca' or not transformed or size == 0))
            expected += size
            require(expected <= MAX_FILE)
            directory.append((tag, size))
        require(position + packed <= len(data) <= (position + packed + 3) // 4 * 4 and not any(data[position + packed:]))
        # Node is already required by the publication wrapper. Its Brotli
        # decoder supplies both an output cap and exact input consumption.
        script = "const z=require('node:zlib'),f=require('node:fs');try{const b=f.readFileSync(0);const r=z.brotliDecompressSync(b,{info:true,maxOutputLength:Number(process.argv[1])+1});if(r.engine.bytesWritten!==b.length||r.buffer.length!==Number(process.argv[1]))process.exit(1);process.stdout.write(r.buffer)}catch{process.exit(1)}"
        decoded = subprocess.run(['node', '-e', script, str(expected)], input=data[position:position + packed], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False)
        require(decoded.returncode == 0 and len(decoded.stdout) == expected)
        position = 0
        for tag, size in directory:
            tables[tag] = decoded.stdout[position:position + size]
            position += size
    return font_tables(tables)


@rejection_stage(RejectionCategory.BINARY_INVALID, (struct.error, zlib.error, UnicodeError))
def validate_binary(data, extension, sanitizer=None):
    """Supported capture formats only; no binary or pixel content is rewritten."""
    require(0 < len(data) <= MAX_FILE)
    binary_texts = [data.decode('latin-1')]
    names = []
    if extension == '.png':
        validate_png(data)
    elif extension in ('.jpg', '.jpeg'):
        validate_jpeg(data)
    elif extension == '.webp':
        validate_webp(data)
    else:
        require(extension in ('.ttf', '.woff', '.woff2'))
        decoded, names = validate_font(data, extension)
        binary_texts.extend(decoded)
    # Font names are decoded according to their declared encoding. Scan binary
    # spans only for known credential atoms/JWTs: incidental byte runs must not
    # be interpreted as text codecs or rewritten by a text sanitizer.
    local = Sanitizer()
    for name in names:
        local.discover_text(name)
    local.freeze()
    for name in names:
        require(local.text(name) == name)
        if sanitizer is not None:
            require(sanitizer.text(name) == name)
    for text in binary_texts:
        require(JWT.search(text) is None)
        if sanitizer is not None and sanitizer.pattern is not None:
            require(sanitizer.pattern.search(text) is None)


@rejection_stage(RejectionCategory.CONTENT_INVALID, (json.JSONDecodeError, UnicodeError))
def trace_objects(entries):
    objects = {}
    for name, data in entries.items():
        if name.endswith(('.trace', '.network')):
            lines = utf8(data).splitlines()
            require(all(lines))
            rows = [parse(line) for line in lines]
            for row in rows:
                require(isinstance(row, dict) and row.get('type') in TRACE_TYPES)
                if row['type'] == 'context-options':
                    require(row.get('version') == 8)
                if name.endswith('.network'):
                    require(row['type'] == 'resource-snapshot')
            if name.endswith('.trace'):
                require(rows and rows[0].get('type') == 'context-options' and rows[0].get('version') == 8)
            objects[name] = rows
        elif name.endswith('.stacks') or name == 'storage-state.json':
            objects[name] = parse(utf8(data))
        else:
            require(name.startswith('resources/'))
            extension = '.' + name.rsplit('.', 1)[-1]
            require(extension in TEXT_EXT | BINARY_EXT)
            if extension == '.json':
                objects[name] = parse(utf8(data))
            elif extension in TEXT_EXT:
                objects[name] = utf8(data)
            else:
                validate_binary(data, extension)
    return objects


def references(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in ('_sha1', 'sha1') and isinstance(item, str):
                yield item
            yield from references(item)
    elif isinstance(value, list):
        for item in value:
            yield from references(item)


@rejection_stage(RejectionCategory.REFERENCE_INVALID)
def validate_references(entries, objects):
    for obj in objects.values():
        for ref in references(obj):
            require('resources/' + ref in entries)
    for name, data in entries.items():
        match = re.fullmatch(r'resources/([0-9a-f]{40})(\.[A-Za-z0-9]+)?', name)
        if match:
            require(hashlib.sha1(data).hexdigest() == match.group(1))


@rejection_stage(RejectionCategory.TRANSFORM_REJECTED, (binascii.Error, UnicodeError))
def transform_trace(entries, objects, sanitizer):
    validate_references(entries, objects)
    require(all(sanitizer.text(name) == name for name in entries), RejectionCategory.IDENTITY_CHANGE)
    output = {}
    renamed = {}
    sources = []
    assertion_diagnostics = []
    for name, data in entries.items():
        if not name.startswith('resources/'):
            continue
        value = objects.get(name)
        if value is not None:
            updated = sanitizer.value(value)
            changed = updated != value
            content = (dumps(updated).encode() if name.endswith('.json') else updated.encode()) if changed else data
        else:
            # Pixels/font bytes are never rewritten. Any known plaintext/encoded
            # credential in a binary resource rejects the whole publication.
            validate_binary(data, '.' + name.rsplit('.', 1)[-1], sanitizer)
            content = data
        target = name
        if content != data:
            if name.startswith('resources/src@'):
                sources.append({'path': name, 'originalSha256': hashlib.sha256(data).hexdigest(),
                                'publishedSha256': hashlib.sha256(content).hexdigest(),
                                'transformation': 'credential redaction; published source is not the original source bytes'})
            else:
                match = re.fullmatch(r'resources/[0-9a-f]{40}(\.[A-Za-z0-9]+)?', name)
                require(match is not None)
                target = 'resources/' + hashlib.sha1(content).hexdigest() + (match.group(1) or '')
                renamed[name.removeprefix('resources/')] = target.removeprefix('resources/')
        require(target not in output or output[target] == content)
        output[target] = content

    def rebind(value):
        if isinstance(value, dict):
            return {k: renamed.get(v, v) if k in ('_sha1', 'sha1') and isinstance(v, str) else rebind(v) for k, v in value.items()}
        if isinstance(value, list):
            return [rebind(v) for v in value]
        return value

    for name, obj in objects.items():
        if name.startswith('resources/'):
            continue
        sanitized = rebind(sanitizer.value(obj))
        if name.endswith('.trace'):
            assertion_ids = {row['callId'] for row in obj if row.get('type') == 'before'
                             and (row.get('method') in ('expect', '_expect') or row.get('class') == 'Expect')}
            for index, (original, published) in enumerate(zip(obj, sanitized)):
                if original.get('callId') not in assertion_ids or original == published:
                    continue
                # Playwright includes the received DOM in successful assertions,
                # including password inputs. Only that diagnostic may change;
                # predicates, expected criteria, outcomes and timing stay exact.
                require(original.get('type') == 'after', RejectionCategory.ASSERTION_CHANGE)
                received = original.get('result', {}).get('received', {})
                changed_received = published.get('result', {}).get('received', {})
                require(isinstance(received, dict) and isinstance(changed_received, dict), RejectionCategory.ASSERTION_CHANGE)
                old_snapshot = received.get('ariaSnapshot')
                new_snapshot = changed_received.get('ariaSnapshot')
                require(isinstance(old_snapshot, str) and isinstance(new_snapshot, str)
                        and old_snapshot != new_snapshot and REDACTED in new_snapshot, RejectionCategory.ASSERTION_CHANGE)
                restored = parse(dumps(published))
                restored['result']['received']['ariaSnapshot'] = old_snapshot
                require(restored == original, RejectionCategory.ASSERTION_CHANGE)
                assertion_diagnostics.append({'trace': name, 'recordIndex': index,
                    'callId': original['callId'], 'field': 'result.received.ariaSnapshot',
                    'transformation': 'credential redaction in received DOM diagnostic only',
                    'assertionPredicateExpectedCriterionAndOutcome': 'unchanged'})
        if name.endswith(('.trace', '.network')):
            content = ('\n'.join(dumps(row) for row in sanitized) + '\n').encode()
        else:
            content = dumps(sanitized).encode()
        output[name] = content
    validate_references(output, trace_objects(output))
    manifest = {'schemaVersion': 1, 'policy': POLICY, 'traceSchemaVersion': 8,
                'credentialPlaceholder': REDACTED, 'changedSources': sources,
                'changedAssertionDiagnostics': assertion_diagnostics,
                'resourceReferencesRebound': len(renamed),
                'screenshotsAndFonts': 'byte-identical',
                'transportSizes': 'original observed sizes; resource hashes bind published bytes'}
    output['approval-sanitization.json'] = dumps(manifest).encode()
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, content in output.items():
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o600) << 16
            z.writestr(info, content)
    require(len(result.getvalue()) <= MAX_FILE, RejectionCategory.RESOURCE_LIMIT)
    return result.getvalue(), manifest


def prepare(request):
    with rejection_stage(RejectionCategory.REQUEST_INVALID, (KeyError, TypeError)):
        paths = request['paths']
        require(isinstance(paths, list))
        require(len(paths) <= 2048, RejectionCategory.RESOURCE_LIMIT)
    sanitizer = Sanitizer()
    loaded = []
    total = 0
    expanded_total = 0
    entry_total = 0
    for path in paths:
        require(isinstance(path, str), RejectionCategory.REQUEST_INVALID)
        with rejection_stage(RejectionCategory.INPUT_READ_FAILED, (OSError, ValueError)):
            import os
            require(os.path.abspath(path) == os.path.realpath(path))
            before = os.lstat(path)
            require(stat.S_ISREG(before.st_mode))
            require(before.st_size <= MAX_FILE, RejectionCategory.RESOURCE_LIMIT)
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, 'rb') as stream:
                opened = os.fstat(stream.fileno())
                require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                        == (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns))
                data = stream.read(MAX_FILE + 1)
                after = os.fstat(stream.fileno())
                require((opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns)
                        == (after.st_size, after.st_mtime_ns, after.st_ctime_ns))
            current = os.lstat(path)
            require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                    == (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns, current.st_ctime_ns))
        total += len(data)
        require(len(data) <= MAX_FILE and total <= MAX_TOTAL, RejectionCategory.RESOURCE_LIMIT)
        extension = '.' + path.rsplit('.', 1)[-1].lower()
        if extension == '.zip':
            entries = archive(data)
            expanded_total += sum(len(content) for content in entries.values())
            entry_total += len(entries)
            require(expanded_total <= MAX_EXPANDED and entry_total <= MAX_ENTRIES, RejectionCategory.RESOURCE_LIMIT)
            objects = trace_objects(entries)
            validate_references(entries, objects)
            with rejection_stage(RejectionCategory.DISCOVERY_REJECTED, (binascii.Error, UnicodeError)):
                for name in entries:
                    sanitizer.discover_text(name)
                for value in objects.values():
                    sanitizer.discover(value)
            loaded.append((extension, data, (entries, objects)))
        elif extension in ('.json', '.md'):
            with rejection_stage(RejectionCategory.CONTENT_INVALID, (json.JSONDecodeError, UnicodeError)):
                obj = parse(utf8(data)) if extension == '.json' else utf8(data)
            with rejection_stage(RejectionCategory.DISCOVERY_REJECTED, (binascii.Error, UnicodeError)):
                sanitizer.discover(obj)
            loaded.append((extension, data, obj))
        else:
            require(extension == '.png', RejectionCategory.CONTENT_INVALID)
            validate_binary(data, extension)
            loaded.append((extension, data, None))
    with rejection_stage(RejectionCategory.DISCOVERY_REJECTED, (UnicodeError,)):
        sanitizer.freeze()
    with rejection_stage(RejectionCategory.TRANSFORM_REJECTED, (binascii.Error, UnicodeError)):
        require(all(sanitizer.text(path) == path for path in paths), RejectionCategory.IDENTITY_CHANGE)
    files = []
    for extension, data, obj in loaded:
        manifest = {'policy': POLICY}
        if extension == '.zip':
            content, manifest = transform_trace(*obj, sanitizer)
        elif extension in ('.json', '.md'):
            with rejection_stage(RejectionCategory.TRANSFORM_REJECTED, (binascii.Error, UnicodeError)):
                updated = sanitizer.value(obj)
                content = ((dumps(updated) + '\n').encode() if extension == '.json' else updated.encode()) if updated != obj else data
        else:
            validate_binary(data, extension, sanitizer)
            content = data
        require(len(content) <= MAX_FILE, RejectionCategory.RESOURCE_LIMIT)
        files.append({'size': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
                      'base64': base64.b64encode(content).decode(), 'sanitization': manifest})
    require(sum(file['size'] for file in files) <= MAX_TOTAL, RejectionCategory.RESOURCE_LIMIT)
    return {'policy': POLICY, 'files': files}


def main():
    try:
        with rejection_stage(RejectionCategory.REQUEST_INVALID, (json.JSONDecodeError, UnicodeError)):
            request = parse(sys.stdin.read(1024 * 1024 + 1))
        result = prepare(request)
        sys.stdout.write(dumps(result))
    except Exception as error:
        # No parser detail, filename, input value, traceback or raw fallback.
        category = error.category if isinstance(error, UnsafeEvidence) else None
        if not isinstance(category, RejectionCategory):
            category = RejectionCategory.SANITIZER_INTERNAL
        sys.stderr.write('EVIDENCE_REJECTION_V1 category=' + category.value + '\n')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
