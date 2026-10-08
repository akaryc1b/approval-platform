#!/usr/bin/env python3
"""Check source precedence and, when supplied, real Maven effective dependency management.

This contract neither resolves a graph nor accepts scanner findings. Generate the
optional input with Maven's help:effective-pom against the same candidate source.
"""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PINS = {
    "java.version": "21",
    "spring-boot.version": "4.0.8",
    "jackson2-bom.version": "2.21.7",
    "jackson3-bom.version": "3.1.7",
    "tomcat-embed.version": "11.0.26",
    "opentelemetry.version": "1.62.0",
    "flowable.version": "8.0.0",
}
TOMCAT = ["tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def value(element, name):
    return element.findtext(f"m:{name}", default="", namespaces=NS).strip()


def coordinate(element):
    return f"{value(element, 'groupId')}:{value(element, 'artifactId')}"


def management(project):
    entries = project.findall("m:dependencyManagement/m:dependencies/m:dependency", NS)
    identities = [(coordinate(item), value(item, "type") or "jar", value(item, "classifier")) for item in entries]
    require(len(identities) == len(set(identities)), "duplicate managed dependency")
    primary = {coordinate(item): item for item in entries
               if not value(item, "classifier") and (value(item, "type") in ("", "jar")
                                                     or value(item, "scope") == "import")}
    return entries, primary


def verify_source(root):
    project = ET.parse(root / "pom.xml").getroot()
    require(project.find("m:parent", NS) is None, "Boot must remain an imported BOM")
    properties = project.find("m:properties", NS)
    for name, expected in PINS.items():
        require(value(properties, name) == expected, f"source pin drift: {name}")
    entries, by_id = management(project)
    expected_imports = [
        ("io.opentelemetry:opentelemetry-bom", "${opentelemetry.version}"),
        ("com.fasterxml.jackson:jackson-bom", "${jackson2-bom.version}"),
        ("tools.jackson:jackson-bom", "${jackson3-bom.version}"),
        ("org.springframework.boot:spring-boot-dependencies", "${spring-boot.version}"),
        ("org.flowable:flowable-bom", "${flowable.version}"),
        ("org.testcontainers:testcontainers-bom", "${testcontainers.version}"),
    ]
    imports = [item for item in entries if value(item, "scope") == "import"]
    require([(coordinate(item), value(item, "version")) for item in imports] == expected_imports,
            "BOM identities, versions or precedence drift")
    require(all(value(item, "type") == "pom" for item in imports), "BOM import must use type pom")
    direct = {coordinate(item): value(item, "version") for item in entries if item not in imports}
    expected_direct = {"org.postgresql:postgresql": "42.7.13"}
    expected_direct.update({f"org.apache.tomcat.embed:{name}": "${tomcat-embed.version}" for name in TOMCAT})
    require(direct == expected_direct, "direct management must pin pgjdbc and the whole embedded Tomcat family")
    boot_index = entries.index(by_id["org.springframework.boot:spring-boot-dependencies"])
    require(all(entries.index(by_id[name]) < boot_index for name in expected_direct),
            "direct overrides must precede imported Boot")
    plugins = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
    boot_plugins = [p for p in plugins if coordinate(p) == "org.springframework.boot:spring-boot-maven-plugin"]
    require(len(boot_plugins) == 1 and value(boot_plugins[0], "version") == "${spring-boot.version}",
            "Boot Maven plugin must track the Boot BOM")
    host = ET.parse(root / "integrations/host-sdk/pom.xml").getroot()
    require(host.findtext("m:properties/m:java.version", namespaces=NS) == "17", "host SDK bytecode pin drift")


def verify_effective(path):
    document = ET.parse(path).getroot()
    projects = [document] if document.tag.endswith("}project") else document.findall("m:project", NS)
    require(bool(projects), "effective POM contains no projects")
    required = {
        "org.springframework.boot:spring-boot": "4.0.8",
        "org.springframework.boot:spring-boot-starter-web": "4.0.8",
        "org.springframework:spring-core": "7.0.9",
        "org.springframework:spring-context": "7.0.9",
        "org.springframework:spring-webmvc": "7.0.9",
        "com.fasterxml.jackson.core:jackson-annotations": "2.21",
        "com.fasterxml.jackson.core:jackson-core": "2.21.7",
        "com.fasterxml.jackson.core:jackson-databind": "2.21.7",
        "com.fasterxml.jackson.datatype:jackson-datatype-jsr310": "2.21.7",
        "tools.jackson.core:jackson-core": "3.1.7",
        "tools.jackson.core:jackson-databind": "3.1.7",
        "org.flowable:flowable-engine": "8.0.0",
        "org.postgresql:postgresql": "42.7.13",
        "io.opentelemetry:opentelemetry-api": "1.62.0",
        "io.opentelemetry:opentelemetry-sdk": "1.62.0",
        "io.opentelemetry:opentelemetry-exporter-otlp": "1.62.0",
    }
    required.update({f"org.apache.tomcat.embed:{name}": "11.0.26" for name in TOMCAT})
    for project in projects:
        _, by_id = management(project)
        for name, expected in required.items():
            require(name in by_id and value(by_id[name], "version") == expected,
                    f"effective dependency drift: {name}; expected {expected}")
        for name, item in by_id.items():
            group = value(item, "groupId")
            expected = None
            if group.startswith("com.fasterxml.jackson."):
                expected = "2.21" if name.endswith(":jackson-annotations") else "2.21.7"
            elif group.startswith("tools.jackson."):
                expected = "3.1.7"
            if expected:
                require(value(item, "version") == expected, f"effective Jackson family mismatch: {name}")
        plugins = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
        boot = [p for p in plugins if coordinate(p) == "org.springframework.boot:spring-boot-maven-plugin"]
        require(len(boot) == 1 and value(boot[0], "version") == "4.0.8", "effective Boot plugin drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--effective-pom", type=Path)
    args = parser.parse_args()
    verify_source(args.root)
    print("Server dependency source precedence verified; this is not resolved graph or scanner evidence.")
    if args.effective_pom:
        verify_effective(args.effective_pom)
        print("Supplied Maven effective dependency management and Boot plugin verified.")


if __name__ == "__main__":
    main()
