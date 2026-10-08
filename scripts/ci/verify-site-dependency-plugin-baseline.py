#!/usr/bin/env python3
"""Verify the bounded Site/Dependency source contract and optional actual Maven models.

This checks configuration and compatibility, not vulnerability applicability or
scanner evidence. No Maven goal is run by this checker.
"""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PINS = {
    "maven.site.version": "3.22.0",
    "maven.dependency.version": "3.11.0",
    "maven.site.jsoup.version": "1.23.2",
}
PLUGINS = {"maven-site-plugin": "maven.site.version",
           "maven-dependency-plugin": "maven.dependency.version"}
EXPECTED_PROJECTS = 26


def require(condition, message):
    if not condition:
        raise ValueError(message)


def value(element, name):
    entries = element.findall(f"m:{name}", NS)
    require(len(entries) <= 1, f"duplicate element: {name}")
    return (entries[0].text or "").strip() if entries else ""


def coordinate(element, default_group=""):
    return f"{value(element, 'groupId') or default_group}:{value(element, 'artifactId')}"


def targeted(plugin):
    return (value(plugin, "groupId") in ("", "org.apache.maven.plugins")
            and value(plugin, "artifactId") in PLUGINS)


def verify_plugin(plugin, effective=False):
    name = value(plugin, "artifactId")
    expected = PINS[PLUGINS[name]] if effective else "${" + PLUGINS[name] + "}"
    require(value(plugin, "version") == expected, f"plugin version drift: {name}")
    dependencies = plugin.findall("m:dependencies/m:dependency", NS)
    expected_version = PINS["maven.site.jsoup.version"] if effective else "${maven.site.jsoup.version}"
    expected_deps = [("org.jsoup:jsoup", expected_version)] if name == "maven-site-plugin" else []
    require([(coordinate(item), value(item, "version")) for item in dependencies] == expected_deps,
            f"plugin dependency realm drift: {name}")
    for dependency in dependencies:
        require(value(dependency, "type") in ("", "jar")
                and not value(dependency, "classifier")
                and value(dependency, "scope") in ("", "compile")
                and value(dependency, "optional") in ("", "false")
                and dependency.find("m:exclusions", NS) is None
                and dependency.find("m:systemPath", NS) is None,
                "Site jsoup dependency semantics drift")
    require(value(plugin, "extensions") in ("", "false")
            and value(plugin, "inherited") in ("", "true"), "plugin inheritance/extension drift")
    if not effective:
        allowed = {"groupId", "artifactId", "version", "dependencies"}
        require(all(child.tag in {f"{{{NS['m']}}}{tag}" for tag in allowed} for child in plugin),
                "Site/Dependency source must not introduce configuration or lifecycle bindings")
        require(len(plugin.findall("m:dependencies", NS)) <= 1, "duplicate plugin dependencies container")


def reactor(root):
    projects, visited = [], set()

    def visit(file):
        file = file.resolve()
        require(file.is_relative_to(root.resolve()), "module escapes reactor source")
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


def verify_source(root):
    projects = reactor(root)
    root_project = projects[0][1]
    properties = root_project.findall("m:properties", NS)
    require(len(properties) == 1, "missing/duplicate root properties")
    for name, pin in PINS.items():
        require(value(properties[0], name) == pin, f"source pin drift: {name}")
    managed = root_project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
    expected = [plugin for plugin in managed if targeted(plugin)]
    require(sorted(value(p, "artifactId") for p in expected) == sorted(PLUGINS),
            "missing/duplicate managed Site/Dependency plugin")
    for plugin in expected:
        verify_plugin(plugin)
    allowed_jsoup = expected[[value(p, "artifactId") for p in expected].index("maven-site-plugin")].findall(
        "m:dependencies/m:dependency", NS)
    for file, project in projects:
        for plugin in project.findall(".//m:plugin", NS):
            require(not targeted(plugin) or (project is root_project and plugin in expected),
                    f"Site/Dependency declaration outside root pluginManagement: {file.relative_to(root)}")
        for dependency in project.findall(".//m:dependency", NS):
            require(coordinate(dependency) != "org.jsoup:jsoup" or dependency in allowed_jsoup,
                    "jsoup override must remain Site-plugin-local")
        if project is not root_project:
            for properties in project.findall(".//m:properties", NS):
                require(not any(properties.find(f"m:{name}", NS) is not None for name in PINS),
                        f"module overrides managed plugin property: {file.relative_to(root)}")
        else:
            for properties in project.findall("m:profiles/m:profile/m:properties", NS):
                require(not any(properties.find(f"m:{name}", NS) is not None for name in PINS),
                        "profile overrides managed plugin property")
    return projects


def verify_effective(file, source_projects):
    document = ET.parse(file).getroot()
    projects = [document] if document.tag == f"{{{NS['m']}}}project" else document.findall("m:project", NS)
    expected_ids = sorted(coordinate(project, value(project.find("m:parent", NS), "groupId")
                                     if project.find("m:parent", NS) is not None else "")
                          for _, project in source_projects)
    require(sorted(coordinate(project) for project in projects) == expected_ids,
            f"effective POM must contain exactly the {EXPECTED_PROJECTS} source reactor projects")
    for project in projects:
        managed = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
        plugins = [plugin for plugin in managed if targeted(plugin)]
        require(sorted(value(p, "artifactId") for p in plugins) == sorted(PLUGINS),
                "missing/duplicate effective managed Site/Dependency plugin")
        for plugin in plugins:
            verify_plugin(plugin, effective=True)
            require(plugin.find("m:executions", NS) is None, "new managed lifecycle binding")
        active = [plugin for plugin in project.findall("m:build/m:plugins/m:plugin", NS)
                  if targeted(plugin)]
        require(len(active) == len({value(plugin, "artifactId") for plugin in active}),
                "duplicate active Site/Dependency plugin")
        for plugin in active:
            verify_plugin(plugin, effective=True)
            executions = plugin.findall("m:executions/m:execution", NS)
            if value(plugin, "artifactId") == "maven-site-plugin":
                # Maven's super POM already supplies these two default site-lifecycle
                # bindings. They are not newly introduced default-build bindings.
                actual = [(value(item, "id"), value(item, "phase"),
                           [goal.text for goal in item.findall("m:goals/m:goal", NS)]) for item in executions]
                require(sorted(actual) == sorted([("default-site", "site", ["site"]),
                                                 ("default-deploy", "site-deploy", ["deploy"])]),
                        "effective Site default lifecycle binding drift")
            else:
                require(not executions, "new Dependency lifecycle binding")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--effective-pom", type=Path)
    args = parser.parse_args()
    projects = verify_source(args.root)
    print(f"Site/Dependency source contract verified across {len(projects)} projects; not scanner evidence.")
    if args.effective_pom:
        verify_effective(args.effective_pom, projects)
        print(f"Supplied effective Maven models verified across {len(projects)} projects.")


if __name__ == "__main__":
    main()
