"""Exact owner-only source reversal; no execution or vulnerability proof."""
import hashlib

COMBINED_POM = '9e91a467ef498a2b20c6cd971a336ea643d9c911f6dff9c651d07742ad778b7b'
PREDECESSOR_POM = '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab'
CHANGES = {
    'maven-assembly-plugin': [('io.airlift', 'aircompressor', '2.0.3')],
    'maven-checkstyle-plugin': [('org.codehaus.plexus', 'plexus-archiver', '4.10.4'),
                              ('io.airlift', 'aircompressor', '2.0.3'),
                              ('commons-beanutils', 'commons-beanutils', '1.11.0')],
    'maven-site-plugin': [('commons-beanutils', 'commons-beanutils', '1.11.0')],
    'maven-dependency-plugin': [('commons-beanutils', 'commons-beanutils', '1.11.0')],
}


def reverse_combined_owner_source(raw):
    if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != COMBINED_POM:
        raise ValueError('Combined owner current POM digest mismatch')
    text = raw.decode('utf-8')
    for owner, dependencies in CHANGES.items():
        marker = '<artifactId>' + owner + '</artifactId>'
        if text.count(marker) != 1:
            raise ValueError('Combined owner unique declaration required')
        pos = text.index(marker)
        start, end = text.rfind('<plugin>', 0, pos), text.index('</plugin>', pos) + len('</plugin>')
        block = text[start:end]
        indent = ' ' * (16 if owner == 'maven-checkstyle-plugin' else 20)
        for group, artifact, version in dependencies:
            addition = ('\n' + indent + '    <dependency>\n' + indent + '        <groupId>' + group
                        + '</groupId>\n' + indent + '        <artifactId>' + artifact + '</artifactId>\n'
                        + indent + '        <version>' + version + '</version>\n' + indent + '    </dependency>')
            if block.count(addition) != 1:
                raise ValueError('Combined owner exact dependency required')
            block = block.replace(addition, '', 1)
        if owner == 'maven-dependency-plugin':
            block = block.replace('\n' + indent + '<dependencies>\n' + indent + '</dependencies>', '', 1)
        if owner == 'maven-assembly-plugin':
            block = block.replace('<version>3.8.0</version>', '<version>3.7.1</version>', 1)
        text = text[:start] + block + text[end:]
    prior = text.encode('utf-8')
    if hashlib.sha256(prior).hexdigest() != PREDECESSOR_POM:
        raise ValueError('Combined owner exact predecessor mismatch')
    return prior
