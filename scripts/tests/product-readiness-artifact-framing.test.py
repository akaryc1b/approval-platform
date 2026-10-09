"""Synthetic publication framing fixtures; no captured credentials or artifacts."""
import base64
import gzip
import importlib.util
import io
import pathlib
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

MODULE = pathlib.Path(__file__).resolve().parents[1] / 'product-readiness/artifact-privacy/sanitize.py'
spec = importlib.util.spec_from_file_location('privacy_framing', MODULE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
CANARY = 'Synthetic-Framing-Credential-Canary!42'
TRACE = b'{"type":"context-options","version":8,"playwrightVersion":"1.60.0"}\n'
# Generated 1 x 1 solid-color images, unrelated to any captured evidence.
JPEG = base64.b64decode('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDjqKKK8c98/9k=')
WEBP = base64.b64decode('UklGRjYAAABXRUJQVlA4ICoAAACQAQCdASoBAAEAAUAmJaACdLoAA5gA/vIiX/gN18W0y/+N462+tvZQAAA=')


def chunk(kind, body):
    return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xffffffff)


def png(extra=b'', encoded=None):
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + extra +
            chunk(b'IDAT', zlib.compress(b'\x00\x80\x40\x20') if encoded is None else encoded) + chunk(b'IEND', b''))


def archive(entries=None, descriptor=False, method=zipfile.ZIP_DEFLATED, extra=b''):
    class Unseekable(io.BytesIO):
        def seekable(self):
            return False

        def seek(self, *args):
            raise OSError()
    output = Unseekable() if descriptor else io.BytesIO()
    with zipfile.ZipFile(output, 'w', method) as z:
        for name, data in entries or [('test.trace', TRACE)]:
            info = zipfile.ZipInfo(name)
            info.compress_type = method
            info.extra = extra
            z.writestr(info, data)
    return output.getvalue()


def font_tables(name='Synthetic Font'):
    name = name.encode('utf-16-be')
    head = bytearray(54)
    struct.pack_into('>I', head, 0, 0x10000)
    struct.pack_into('>I', head, 12, 0x5f0f3cf5)
    struct.pack_into('>H', head, 18, 1000)
    hhea = bytearray(36)
    struct.pack_into('>I', hhea, 0, 0x10000)
    struct.pack_into('>H', hhea, 34, 1)
    maximum = bytearray(32)
    struct.pack_into('>IH', maximum, 0, 0x10000, 1)
    return {b'cmap': b'\x00' * 12, b'head': bytes(head), b'hhea': bytes(hhea), b'hmtx': b'\x00' * 4,
            b'maxp': bytes(maximum), b'name': struct.pack('>3H6H', 0, 1, 18, 3, 1, 0x409, 1, len(name), 0) + name,
            b'OS/2': b'\x00' * 78, b'post': struct.pack('>I', 0x30000) + b'\x00' * 28,
            b'glyf': b'\x00' * 12, b'loca': struct.pack('>2H', 0, 6)}


def checksum(tag, data):
    if tag == b'head':
        data = data[:8] + b'\x00' * 4 + data[12:]
    data += b'\x00' * (-len(data) % 4)
    return sum(item[0] for item in struct.iter_unpack('>I', data)) & 0xffffffff


def font(extension, name='Synthetic Font', trailing=b''):
    tables = font_tables(name)
    directory = bytearray()
    body = bytearray()
    count = len(tables)
    sfnt_size = 12 + 16 * count + sum((len(data) + 3) // 4 * 4 for data in tables.values())
    if extension in ('.ttf', '.woff'):
        offset = (12 + 16 * count) if extension == '.ttf' else (44 + 20 * count)
        for tag, data in sorted(tables.items()):
            packed = data if extension == '.ttf' else zlib.compress(data)
            if len(packed) >= len(data):
                packed = data
            if extension == '.ttf':
                directory.extend(struct.pack('>4sIII', tag, checksum(tag, data), offset + len(body), len(data)))
            else:
                directory.extend(struct.pack('>4sIIII', tag, offset + len(body), len(packed), len(data), checksum(tag, data)))
            body.extend(packed)
            body.extend(b'\x00' * (-len(body) % 4))
        if extension == '.ttf':
            header = struct.pack('>I4H', 0x10000, count, 128, 3, count * 16 - 128)
        else:
            header = struct.pack('>4sIIHHIHH5I', b'wOFF', 0x10000, offset + len(body), count, 0, sfnt_size, 1, 0, 0, 0, 0, 0, 0)
        return header + directory + body + trailing
    tags = [b'cmap', b'head', b'hhea', b'hmtx', b'maxp', b'name', b'OS/2', b'post', b'cvt ', b'fpgm', b'glyf', b'loca']
    def integer(number):
        result = bytes([number & 127])
        number >>= 7
        while number:
            result = bytes([(number & 127) | 128]) + result
            number >>= 7
        return result
    for tag, data in sorted(tables.items()):
        flag = tags.index(tag) | (192 if tag in (b'glyf', b'loca') else 0)
        directory.extend(bytes([flag]) + integer(len(data)))
        body.extend(data)
    packed = subprocess.run(['node', '-e', "const z=require('node:zlib'),f=require('node:fs');process.stdout.write(z.brotliCompressSync(f.readFileSync(0)))"], input=body, capture_output=True, check=True).stdout + trailing
    header = struct.pack('>4sIIHHIIHH5I', b'wOF2', 0x10000, 48 + len(directory) + len(packed), count, 0, sfnt_size, len(packed), 1, 0, 0, 0, 0, 0, 0)
    return header + directory + packed


class ArchiveFramingTests(unittest.TestCase):
    def reject(self, data):
        with self.assertRaises(Exception):
            p.archive(data)

    def test_native_and_stored_zip_framing(self):
        for descriptor in (False, True):
            for method in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                self.assertEqual(p.archive(archive(descriptor=descriptor, method=method)), {'test.trace': TRACE})

    def test_rejects_trailing_prefix_concatenated_and_comments(self):
        raw = archive()
        for data in (raw + CANARY.encode(), b'prefix' + raw, raw + raw, raw[:-22], raw[:-2] + b'\x01\x00' + b'x'):
            self.reject(data)

    def test_rejects_extras_even_well_formed(self):
        self.reject(archive(extra=struct.pack('<HH', 0xcafe, len(CANARY)) + CANARY.encode()))

    def test_rejects_header_mismatch_flags_methods_modes_and_disk(self):
        raw = archive()
        central = raw.index(b'PK\x01\x02')
        mutations = [(6, '<H', 1), (8, '<H', 99), (30, '<B', ord('x')),
                     (central + 8, '<H', 1), (central + 10, '<H', 99),
                     (central + 34, '<H', 1), (central + 38, '<I', 0o120777 << 16),
                     (len(raw) - 18, '<H', 1), (central + 42, '<I', 1)]
        for offset, form, value in mutations:
            with self.subTest(offset=offset):
                data = bytearray(raw)
                struct.pack_into(form, data, offset, value)
                self.reject(data)

    def test_rejects_aliases_and_nul_names(self):
        for second in ('TEST.trace', './test.trace', '../test.trace', 'nested\\test.trace'):
            self.reject(archive([('test.trace', TRACE), (second, TRACE)]))
        raw = archive()
        self.reject(raw.replace(b'test.trace', b'tes\x00.trace'))

    def test_rejects_bad_crc_descriptor_and_undeclared_member_data(self):
        raw = archive(descriptor=True)
        position = raw.index(b'PK\x07\x08')
        data = bytearray(raw)
        data[position + 4] ^= 1
        self.reject(data)
        data = bytearray(archive(method=zipfile.ZIP_STORED))
        data[30 + len('test.trace')] ^= 1
        self.reject(data)
        # A deflate stream with hidden bytes still inside the declared member.
        raw = archive()
        central = raw.index(b'PK\x01\x02')
        data = bytearray(raw[:central] + b'hidden' + raw[central:])
        packed = struct.unpack_from('<I', data, 18)[0]
        struct.pack_into('<I', data, 18, packed + 6)
        struct.pack_into('<I', data, central + 6 + 20, packed + 6)
        struct.pack_into('<I', data, len(data) - 6, central + 6)
        self.reject(data)


class BinaryFramingTests(unittest.TestCase):
    def reject(self, data, extension):
        with self.assertRaises(Exception):
            p.validate_binary(data, extension)

    def test_supported_binaries_are_unchanged(self):
        for extension, data in [('.png', png()), ('.jpeg', JPEG), ('.webp', WEBP),
                                *[(ext, font(ext)) for ext in ('.ttf', '.woff', '.woff2')]]:
            with self.subTest(extension=extension):
                original = bytes(data)
                p.validate_binary(data, extension)
                self.assertEqual(data, original)

    def test_suffix_spoofing_truncation_and_opaque_trailing_data(self):
        fixtures = {'.png': png(), '.jpeg': JPEG, '.webp': WEBP,
                    **{ext: font(ext) for ext in ('.ttf', '.woff', '.woff2')}}
        for extension, data in fixtures.items():
            for bad in (gzip.compress(b'{"password":"' + CANARY.encode() + b'"}'),
                        b'<svg><text>' + CANARY.encode() + b'</text></svg>', data[:-7], data + b'opaque'):
                with self.subTest(extension=extension):
                    self.reject(bad, extension)

    def test_png_rejects_all_text_metadata_bad_crc_and_hidden_deflate_tail(self):
        for kind, content in [(b'tEXt', b'password\x00' + CANARY.encode()),
                              (b'zTXt', b'password\x00\x00' + zlib.compress(CANARY.encode())),
                              (b'iTXt', b'password\x00\x00\x00\x00\x00' + CANARY.encode()),
                              (b'eXIf', CANARY.encode()), (b'zzZz', b'unknown')]:
            self.reject(png(chunk(kind, content)), '.png')
        data = bytearray(png())
        data[-1] ^= 1
        self.reject(data, '.png')
        self.reject(png(encoded=zlib.compress(b'\x00\x80\x40\x20') + CANARY.encode()), '.png')
        self.reject(png(encoded=zlib.compress(b'\x00\x80\x40\x20' + CANARY.encode())), '.png')
        p.validate_binary(png(chunk(b'sRGB', b'\x00') + chunk(b'sBIT', b'\x08\x08\x08')), '.png')

    def test_jpeg_and_webp_reject_metadata_and_unknown_chunks(self):
        for marker in (b'\xff\xfe', b'\xff\xe1', b'\xff\xe2'):
            self.reject(JPEG[:2] + marker + struct.pack('>H', len(CANARY) + 2) + CANARY.encode() + JPEG[2:], '.jpeg')
        for tag in (b'EXIF', b'XMP ', b'ICCP', b'JUNK'):
            data = bytearray(WEBP + tag + struct.pack('<I', len(CANARY)) + CANARY.encode() + b'\x00' * (len(CANARY) % 2))
            struct.pack_into('<I', data, 4, len(data) - 8)
            self.reject(data, '.webp')

    def test_fonts_decode_name_records_and_compression_before_secret_check(self):
        scanner = p.Sanitizer()
        scanner.remember(CANARY)
        scanner.freeze()
        for extension in ('.ttf', '.woff', '.woff2'):
            with self.subTest(extension=extension):
                data = font(extension, CANARY)
                with self.assertRaises(Exception):
                    p.validate_binary(data, extension, scanner)
                self.reject(font(extension, 'password=' + CANARY), extension)
        self.reject(font('.woff2', trailing=CANARY.encode()), '.woff2')

    def test_fonts_reject_unknown_tables_optional_metadata_and_invalid_ranges(self):
        data = font('.ttf')
        self.reject(data.replace(b'cmap', b'SVG ', 1), '.ttf')
        damaged = bytearray(data)
        struct.pack_into('>I', damaged, 20, 0)
        self.reject(damaged, '.ttf')
        for extension, offset in (('.woff', 24), ('.woff2', 28)):
            damaged = bytearray(font(extension))
            struct.pack_into('>I', damaged, offset, 1)
            self.reject(damaged, extension)

    def test_woff_expansion_budget_is_checked_before_decompression(self):
        from unittest.mock import patch
        data = bytearray(font('.woff'))
        # Enough for the directory, but no decompressed font table budget.
        struct.pack_into('>I', data, 16, 12 + 16 * len(font_tables()))
        with patch.object(p, 'inflate', side_effect=AssertionError('must not decompress')) as decoder:
            self.reject(data, '.woff')
            decoder.assert_not_called()

    def test_outer_png_and_trace_binary_call_sites_enforce_validation(self):
        disguised = b'\x89PNG\r\n\x1a\n' + gzip.compress(CANARY.encode())
        with self.assertRaises(Exception):
            p.trace_objects({'test.trace': TRACE, 'resources/fake.png': disguised})
        with tempfile.TemporaryDirectory() as tmp:
            file = pathlib.Path(tmp) / 'screenshot.png'
            file.write_bytes(disguised)
            with self.assertRaises(Exception):
                p.prepare({'paths': [str(file)]})


if __name__ == '__main__':
    unittest.main(verbosity=2)
