#!/usr/bin/env python3
"""Check the bounded Maven Release pin, models, descriptor and help-only realm.

The optional inputs must come from the same candidate source and actual Maven
help/effective-pom invocations. This checker executes no Maven or SCM command.
Synthetic checker tests, successful help and descriptor minimum requirements are
not release-workflow, vulnerability-applicability or scanner evidence.
"""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GROUP = "org.apache.maven.plugins"
ARTIFACT = "maven-release-plugin"
VERSION = "3.3.1"
EXPECTED_PROJECTS = 26
PINS = {
    "spring-boot.version": "4.0.8",
    "jackson2-bom.version": "2.21.7",
    "jackson3-bom.version": "3.1.7",
    "tomcat-embed.version": "11.0.26",
    "opentelemetry.version": "1.62.0",
    "flowable.version": "8.0.0",
    "archunit.version": "1.4.2",
    "testcontainers.version": "2.0.5",
    "maven.compiler.version": "3.14.0",
    "maven.surefire.version": "3.5.5",
    "maven.enforcer.version": "3.5.0",
    "maven.checkstyle.version": "3.6.0",
    "maven.release.version": VERSION,
    "maven.release.jgit.version": "5.13.5.202508271544-r",
    "maven.release.sshd.version": "2.16.0",
    "maven.release.commons-io.version": "2.20.0",
    "maven.release.plexus-utils.version": "4.0.3",
    "maven.site.version": "3.22.0",
    "maven.dependency.version": "3.11.0",
    "maven.site.jsoup.version": "1.23.2",
    "flatten.maven.version": "1.7.3",
    "jacoco.version": "0.8.15",
}
MANAGED = {
    f"{GROUP}:{ARTIFACT}": "maven.release.version",
    f"{GROUP}:maven-site-plugin": "maven.site.version",
    f"{GROUP}:maven-dependency-plugin": "maven.dependency.version",
    f"{GROUP}:maven-compiler-plugin": "maven.compiler.version",
    f"{GROUP}:maven-surefire-plugin": "maven.surefire.version",
    "org.springframework.boot:spring-boot-maven-plugin": "spring-boot.version",
}
ACTIVE = {
    f"{GROUP}:maven-enforcer-plugin": "maven.enforcer.version",
    f"{GROUP}:maven-checkstyle-plugin": "maven.checkstyle.version",
    "org.jacoco:jacoco-maven-plugin": "jacoco.version",
}

OVERRIDES = {
    "org.eclipse.jgit:org.eclipse.jgit": "maven.release.jgit.version",
    "org.eclipse.jgit:org.eclipse.jgit.ssh.apache": "maven.release.jgit.version",
    "org.apache.sshd:sshd-osgi": "maven.release.sshd.version",
    "org.apache.sshd:sshd-sftp": "maven.release.sshd.version",
    "org.apache.sshd:sshd-core": "maven.release.sshd.version",
    "org.apache.sshd:sshd-common": "maven.release.sshd.version",
    "commons-io:commons-io": "maven.release.commons-io.version",
    "org.codehaus.plexus:plexus-utils": "maven.release.plexus-utils.version",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def value(element, name, namespace=NS):
    entries = element.findall(f"m:{name}" if namespace else name, namespace)
    require(len(entries) <= 1, f"duplicate element: {name}")
    return (entries[0].text or "").strip() if entries else ""


def coordinate(element, default_group=""):
    return f"{value(element, 'groupId') or default_group}:{value(element, 'artifactId')}"


def release(plugin):
    return coordinate(plugin, GROUP) == f"{GROUP}:{ARTIFACT}"


def verify_release(plugin, effective=False):
    require(value(plugin, "groupId") in (("", GROUP) if effective else (GROUP,)),
            "Release groupId must be explicit in source")
    require(value(plugin, "version") == (VERSION if effective else "${maven.release.version}"),
            "Release plugin version drift")
    allowed = {f"{{{NS['m']}}}{name}" for name in ("groupId", "artifactId", "version", "dependencies")}
    require(len(plugin) == (3 if effective and not value(plugin, "groupId") else 4)
            and all(child.tag in allowed for child in plugin),
            "Release permits only coordinates and the exact approved realm: no configuration or lifecycle binding")
    containers = plugin.findall("m:dependencies", NS)
    require(len(containers) == 1, "missing/duplicate Release dependencies container")
    dependencies = plugin.findall("m:dependencies/m:dependency", NS)
    expected = [(identity, PINS[prop] if effective else "${" + prop + "}") for identity, prop in OVERRIDES.items()]
    actual = [(coordinate(dep), value(dep, "version")) for dep in dependencies]
    require(actual == expected, "Release requires exactly eight approved plugin-local dependency overrides")
    dependency_tags = {f"{{{NS['m']}}}{name}" for name in ("groupId", "artifactId", "version")}
    for dep in dependencies:
        require(len(dep) == 3 and all(child.tag in dependency_tags for child in dep),
                "Release override semantics drift: scope/type/classifier/optional/exclusion/configuration")


def reactor(root):
    root = root.resolve()
    projects, visited = [], set()

    def visit(file):
        file = file.resolve()
        require(file.is_relative_to(root), "module escapes reactor source")
        require(file not in visited, "duplicate/cyclic reactor module")
        visited.add(file)
        project = ET.parse(file).getroot()
        require(project.tag == f"{{{NS['m']}}}project", "malformed Maven project namespace")
        projects.append((file, project))
        modules = project.findall("m:modules/m:module", NS)
        modules += project.findall("m:profiles/m:profile/m:modules/m:module", NS)
        for module in modules:
            visit(file.parent / (module.text or "").strip() / "pom.xml")

    visit(root / "pom.xml")
    require(len(projects) == EXPECTED_PROJECTS, f"expected {EXPECTED_PROJECTS} source reactor projects")
    return projects


def verify_pins(project):
    properties = project.findall("m:properties", NS)
    require(len(properties) == 1, "missing/duplicate project properties")
    for name, expected in PINS.items():
        require(value(properties[0], name) == expected, f"pin drift: {name}")


def verify_other_plugins(project, effective=False):
    for selector, expected in [("m:build/m:pluginManagement/m:plugins/m:plugin", MANAGED),
                               ("m:build/m:plugins/m:plugin", ACTIVE)]:
        plugins = project.findall(selector, NS)
        for identity, property_name in expected.items():
            matches = [p for p in plugins if coordinate(p, GROUP) == identity]
            require(len(matches) == 1, f"missing/duplicate retained plugin: {identity}")
            pin = PINS[property_name] if effective else "${" + property_name + "}"
            require(value(matches[0], "version") == pin, f"retained plugin version drift: {identity}")
        for plugin in plugins:
            identity = coordinate(plugin, GROUP)
            if identity in MANAGED | ACTIVE:
                property_name = (MANAGED | ACTIVE)[identity]
                pin = PINS[property_name] if effective else "${" + property_name + "}"
                require(value(plugin, "version") == pin, f"retained plugin version drift: {identity}")
            if identity == f"{GROUP}:maven-site-plugin":
                expected_deps = [("org.jsoup:jsoup", "1.23.2" if effective else "${maven.site.jsoup.version}")]
            elif identity == "org.springframework.boot:spring-boot-maven-plugin":
                pin = "3.1.7" if effective else "${jackson3-bom.version}"
                expected_deps = [(f"tools.jackson.core:{name}", pin) for name in ("jackson-core", "jackson-databind")]
            else:
                continue
            actual = [(coordinate(dep), value(dep, "version")) for dep in
                      plugin.findall("m:dependencies/m:dependency", NS)]
            require(actual == expected_deps, f"retained plugin-local dependencies drift: {identity}")


def verify_source(root):
    root = root.resolve()
    projects = reactor(root)
    root_project = projects[0][1]
    verify_pins(root_project)
    verify_other_plugins(root_project)
    managed = root_project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
    candidates = [plugin for plugin in managed if release(plugin)]
    require(len(candidates) == 1, "missing/duplicate managed Release plugin")
    verify_release(candidates[0])
    allowed_dependencies = candidates[0].findall("m:dependencies/m:dependency", NS)
    for file, project in projects:
        for dependency in project.findall(".//m:dependency", NS):
            require(coordinate(dependency) not in OVERRIDES or dependency in allowed_dependencies,
                    f"Release overrides must remain plugin-local: {file.relative_to(root)}")
        for plugin in project.findall(".//m:plugin", NS):
            require(not release(plugin) or (project is root_project and plugin is candidates[0]),
                    f"Release declaration outside root pluginManagement: {file.relative_to(root)}")
        for properties in project.findall(".//m:properties", NS):
            if project is root_project and properties is root_project.find("m:properties", NS):
                continue
            require(not any(properties.find(f"m:{name}", NS) is not None for name in PINS),
                    f"module/profile overrides retained pin: {file.relative_to(root)}")
    return projects


def verify_effective(file, source_projects):
    document = ET.parse(file).getroot()
    require(document.tag in (f"{{{NS['m']}}}project", f"{{{NS['m']}}}projects", "projects"),
            "malformed effective Maven document")
    projects = [document] if document.tag == f"{{{NS['m']}}}project" else list(document)
    require(all(p.tag == f"{{{NS['m']}}}project" for p in projects), "unexpected effective model element")
    expected_ids = []
    for _, project in source_projects:
        parent = project.find("m:parent", NS)
        group = value(parent, "groupId") if parent is not None else ""
        expected_ids.append(coordinate(project, group))
    require(sorted(coordinate(project) for project in projects) == sorted(expected_ids),
            f"effective POM must contain exactly the {EXPECTED_PROJECTS} source reactor projects")
    for project in projects:
        verify_pins(project)
        verify_other_plugins(project, effective=True)
        managed = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
        candidates = [plugin for plugin in managed if release(plugin)]
        require(len(candidates) == 1, "missing/duplicate effective managed Release plugin")
        verify_release(candidates[0], effective=True)
        for plugin in project.findall(".//m:plugin", NS):
            require(not release(plugin) or plugin is candidates[0],
                    "effective Release declaration/binding outside pluginManagement")


def verify_descriptor(file):
    with ZipFile(file) as archive:
        require(archive.namelist().count("META-INF/maven/plugin.xml") == 1, "duplicate/missing plugin descriptor")
        plugin = ET.fromstring(archive.read("META-INF/maven/plugin.xml"))
    require(plugin.tag == "plugin", "malformed plugin descriptor")
    expected = {"groupId": GROUP, "artifactId": ARTIFACT, "version": VERSION,
                "goalPrefix": "release", "requiredJavaVersion": "1.8", "requiredMavenVersion": "3.6.3"}
    for name, pin in expected.items():
        require(value(plugin, name, {}) == pin, f"official descriptor drift: {name}")
    mojos = [m for m in plugin.findall("mojos/mojo") if value(m, "goal", {}) == "help"]
    require(len(mojos) == 1, "missing/duplicate Release help goal")
    help_goal = mojos[0]
    for name, pin in {"requiresProject": "false", "requiresOnline": "false", "threadSafe": "true",
                      "implementation": "org.apache.maven.plugins.maven_release_plugin.HelpMojo"}.items():
        require(value(help_goal, name, {}) == pin, f"read-only help descriptor drift: {name}")
    for name in ("phase", "executePhase", "executeGoal", "executeLifecycle", "requiresDependencyResolution"):
        require(help_goal.find(name) is None, f"help must not bind/fork/resolve lifecycle: {name}")
    dependencies = []
    for dep in plugin.findall("dependencies/dependency"):
        dependencies.append(":".join(value(dep, key, {}) for key in ("groupId", "artifactId", "type", "version")))
    require(len(dependencies) == len(set(dependencies)), "duplicate descriptor dependency")
    return set(dependencies)


def verify_realm(file, descriptor_dependencies):
    log = re.sub(r"\x1b\[[0-9;]*m", "", file.read_text())
    marker = f"[DEBUG] Populating class realm plugin>{GROUP}:{ARTIFACT}:{VERSION}"
    lines = log.splitlines()
    starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, "missing/duplicate actual Release class realm")
    coordinates = []
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r"\[DEBUG\]\s+Included: (\S+)\s*", line)
        if not match:
            break
        coordinates.append(match[1])
    plugin_coordinate = f"{GROUP}:{ARTIFACT}:jar:{VERSION}"
    require(len(coordinates) > 2 and plugin_coordinate in coordinates, "missing actual Release realm dependencies")
    identities = [item.rsplit(":", 1)[0] for item in coordinates]
    require(len(identities) == len(set(identities)), "duplicate/conflicting actual realm dependency")
    original_identities = {item.rsplit(":", 1)[0] for item in descriptor_dependencies}
    approved = {f"{identity}:jar:{PINS[prop]}" for identity, prop in OVERRIDES.items()}
    require(all(item.rsplit(":", 1)[0] in original_identities for item in approved),
            "approved override must replace an existing official descriptor dependency")
    expected = {item for item in descriptor_dependencies if ":".join(item.split(":")[:2]) not in OVERRIDES} | approved
    # Maven 3.9.16 imports these APIs from its parent realm rather than including
    # the plugin descriptor's versions. Everything else must appear exactly once.
    parent_imports = {"javax.inject:javax.inject:jar:1", "org.slf4j:slf4j-api:jar:1.7.36",
                      "org.apache.maven.resolver:maven-resolver-api:jar:1.9.24",
                      "org.apache.maven.resolver:maven-resolver-util:jar:1.9.24"}
    require(set(coordinates) == (expected - parent_imports) | {plugin_coordinate},
            "actual Release realm differs from descriptor plus exact approved direct overrides")
    require(approved <= set(coordinates), "missing approved Release override from actual realm")
    for name in ("maven-release-api", "maven-release-manager", "maven-release-oddeven-policy", "maven-release-semver-policy"):
        require(f"org.apache.maven.release:{name}:jar:{VERSION}" in coordinates, f"missing aligned Release component: {name}")
    for item in ("org.apache.commons:commons-lang3:jar:3.20.0", "org.apache.commons:commons-text:jar:1.14.0",
                 "org.apache.maven.scm:maven-scm-api:jar:2.2.1"):
        require(item in coordinates, f"missing official Release component: {item}")
    return coordinates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--effective-pom", type=Path)
    parser.add_argument("--plugin-jar", type=Path)
    parser.add_argument("--realm-log", type=Path)
    args = parser.parse_args()
    require(not args.realm_log or args.plugin_jar, "realm verification requires the actual plugin JAR descriptor")
    projects = verify_source(args.root)
    print(f"Release source contract verified across {len(projects)} projects; not scanner or release-readiness evidence.")
    if args.effective_pom:
        verify_effective(args.effective_pom, projects)
        print(f"Supplied effective Release models and retained pins verified across {len(projects)} projects.")
    if args.plugin_jar:
        dependencies = verify_descriptor(args.plugin_jar)
        print("Release 3.3.1 descriptor verified: Java >= 8, Maven >= 3.6.3; help requires neither project nor network.")
    if args.realm_log:
        coordinates = verify_realm(args.realm_log, dependencies)
        print(f"Actual isolated Release help realm verified: {len(coordinates)} entries match descriptor plus the eight approved overrides.")


if __name__ == "__main__":
    main()
