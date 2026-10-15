"""A fixed five-consumer subset, using the already reviewed classfile parser."""
import hashlib
import zipfile

import assembly_plexus_inventory as classfile

CONSUMERS = {
    'maven-jar-plugin': ('org/apache/maven/plugins/jar/AbstractJarMojo',
        'org/apache/maven/plugins/jar/JarMojo', 'org/apache/maven/plugins/jar/TestJarMojo'),
    'maven-archiver': ('org/apache/maven/archiver/MavenArchiver', 'org/apache/maven/archiver/BuildHelper'),
}
TARGETS = {
    'org/apache/maven/archiver/': 'maven-archiver',
    'org/codehaus/plexus/archiver/': 'plexus-archiver',
    'org/codehaus/plexus/util/': 'plexus-utils',
    'org/codehaus/plexus/interpolation/': 'plexus-interpolation',
    'org/apache/commons/io/': 'commons-io',
}


def subset_inventory(coordinates, artifact_path, effective_entry):
    consumers, uses = [], []
    for artifact, names in CONSUMERS.items():
        selected = [c for c in coordinates if c.split(':')[1] == artifact]
        if len(selected) != 1: raise ValueError('one fixed consumer artifact required: ' + artifact)
        coordinate = selected[0]; path = artifact_path(coordinate)
        with zipfile.ZipFile(path) as archive:
            for name in names:
                entry = effective_entry(archive, name); data = archive.read(entry)
                references, members, member_uses = set(), {}, set()
                previous = classfile.PREFIX
                try:
                    for prefix, provider in TARGETS.items():
                        if provider == artifact: continue
                        classfile.PREFIX = prefix
                        own, refs, found, invocation_uses = classfile.inspect_class(data)
                        if own != name: raise ValueError('effective consumer identity differs')
                        references.update(refs)
                        for member in found:
                            key = (member['kind'], member['owner'], member['name'], member['descriptor'])
                            members[key] = member
                        member_uses.update(invocation_uses)
                finally:
                    classfile.PREFIX = previous
                consumers.append({'coordinate': coordinate, 'classEntry': name + '.class',
                    'effectiveClassEntry': entry, 'sourcePath': str(path),
                    'classSha256': hashlib.sha256(data).hexdigest(),
                    'classAndDescriptorReferences': sorted(references),
                    'memberReferences': [members[k] for k in sorted(members)]})
                uses.extend((name, *row) for row in sorted(member_uses))
    return {'inventory': {'consumers': consumers}, 'memberUses': [list(row) for row in sorted(uses)],
        'scope': 'Five fixed direct JAR/MavenArchiver consumers; external changed-package references only; no invocation claim'}
