"""Bounded constant-pool inventory; no subprocesses or network activity.

The consumer definition intentionally includes descriptor-only types and the
separate plexus-xml artifact. It is not dynamic coverage.
"""
import re
import struct
import zipfile
from collections import Counter

PREFIX = 'org/codehaus/plexus/util/'


def inspect_class(data):
    assert len(data) <= 2 * 1024 * 1024 and data[:4] == b'\xca\xfe\xba\xbe'
    pos = 8
    def read(fmt):
        nonlocal pos
        size = struct.calcsize(fmt)
        value = struct.unpack_from(fmt, data, pos); pos += size
        return value[0] if len(value) == 1 else value
    count = read('>H'); pool = [None] * count; tags = [0] * count; index = 1
    while index < count:
        tag = tags[index] = read('>B')
        if tag == 1:
            size = read('>H'); pool[index] = data[pos:pos + size].decode('utf-8', errors='replace'); pos += size
        elif tag in (3, 4): read('>I')
        elif tag in (5, 6): read('>Q'); index += 1
        elif tag in (7, 8, 16, 19, 20): pool[index] = read('>H')
        elif tag in (9, 10, 11, 12, 17, 18): pool[index] = read('>HH')
        elif tag == 15: pool[index] = read('>BH')
        else: raise ValueError('unknown class constant')
        index += 1
    read('>H'); own = pool[pool[read('>H')]]; read('>H')
    refs = set()
    members = []
    targets = {}
    for i, tag in enumerate(tags):
        if tag == 7:
            name = pool[pool[i]]
            if name.startswith(PREFIX): refs.add(name)
        if tag == 1:
            refs.update(re.findall(r'L(' + PREFIX + r'[^;<]+)', pool[i]))
        if tag in (9, 10, 11):
            owner_index, member_index = pool[i]; owner = pool[pool[owner_index]]
            if owner.startswith(PREFIX) and owner != own:
                name_index, descriptor_index = pool[member_index]
                value = {'kind': {9: 'field', 10: 'method', 11: 'interfaceMethod'}[tag],
                         'owner': owner, 'name': pool[name_index], 'descriptor': pool[descriptor_index]}
                members.append(value); targets[i] = value
    refs.discard(own)
    uses = set()
    def use(index, static):
        if index in targets:
            m = targets[index]
            uses.add((m['kind'], m['owner'], m['name'], m['descriptor'], 'static' if static else 'instance'))
    for i, tag in enumerate(tags):
        if tag == 15:
            kind, target = pool[i]; assert 1 <= kind <= 9
            use(target, kind in (2, 4, 6))
    for _ in range(read('>H')): read('>H')
    for is_method in (False, True):
        for _ in range(read('>H')):
            read('>HHH')
            for _ in range(read('>H')):
                attribute = pool[read('>H')]; size = read('>I'); start = pos; pos += size
                assert pos <= len(data)
                if not is_method or attribute != 'Code': continue
                length = struct.unpack_from('>I', data, start + 4)[0]
                code = data[start + 8:start + 8 + length]; assert len(code) == length <= 65535
                offset = 0
                while offset < length:
                    op = code[offset]
                    if 178 <= op <= 185: use(struct.unpack_from('>H', code, offset + 1)[0], op in (178, 179, 184))
                    if op in (170, 171):
                        aligned = (offset + 4) & ~3
                        if op == 170:
                            low, high = struct.unpack_from('>ii', code, aligned + 4); n = high - low + 1
                            size = aligned - offset + 12 + n * 4
                        else:
                            n = struct.unpack_from('>i', code, aligned + 4)[0]; size = aligned - offset + 8 + n * 8
                        assert 0 <= n <= 65536
                    elif op == 196: size = 6 if code[offset + 1] == 132 else 4
                    elif op in (16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188): size = 2
                    elif op in (17, 19, 20, 132, *range(153, 169), *range(178, 185), 187, 189, 192, 193, 198, 199): size = 3
                    elif op == 197: size = 4
                    elif op in (185, 186, 200, 201): size = 5
                    else: size = 1
                    assert op <= 201 and offset + size <= length
                    offset += size
    return own, sorted(refs), members, sorted(uses)


def inventory(coordinates, artifact_path):
    consumers, uses = [], []
    for coordinate in sorted(coordinates):
        if ':plexus-utils:' in coordinate: continue
        with zipfile.ZipFile(artifact_path(coordinate)) as archive:
            assert len(archive.infolist()) <= 20000
            entries = {}
            multi = 'META-INF/MANIFEST.MF' in archive.namelist() and b'multi-release: true' in archive.read('META-INF/MANIFEST.MF').lower()
            for entry in archive.namelist():
                if not entry.endswith('.class'): continue
                match = re.fullmatch(r'META-INF/versions/(\d+)/(.*)', entry)
                version, name = (int(match[1]), match[2]) if match else (0, entry)
                if match and (not multi or version > 21): continue
                if name not in entries or version > entries[name][0]: entries[name] = (version, entry)
            for _, entry in entries.values():
                own, refs, members, member_uses = inspect_class(archive.read(entry))
                if not refs: continue
                consumers.append({'coordinate': coordinate, 'classEntry': entry,
                                  'classAndDescriptorReferences': refs, 'memberReferences': members})
                uses.extend((own, *row) for row in member_uses)
    return {'consumerCount': len(consumers), 'directAssemblyConsumerCount': sum(':maven-assembly-plugin:' in c['coordinate'] for c in consumers),
            'consumerCountsByJar': dict(Counter(c['coordinate'] for c in consumers)), 'consumers': consumers}, sorted(uses)
