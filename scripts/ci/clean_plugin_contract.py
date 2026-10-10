"""Strict Clean-only ownership/model/realm contract; no Maven or JAR execution."""

import copy
import hashlib
from pathlib import Path
import re
from runpy import run_path
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PREFIX = "{" + NS["m"] + "}"
GROUP = "org.apache.maven.plugins"
ARTIFACT = "maven-clean-plugin"
PINS = {"maven.clean.version": "3.2.0", "maven.clean.commons-io.version": "2.20.0"}
IO = "commons-io:commons-io"
REACTOR = {
    "pom.xml": "approval-platform",
    "server-modules/pom.xml": "approval-server-modules",
    **{f"server-modules/{name}/pom.xml": name for name in (
        "approval-domain", "approval-definition-compiler", "approval-engine-spi",
        "approval-connector-spi", "approval-ai-spi", "approval-ai-core", "approval-ai-openai",
        "approval-connector-dingtalk", "approval-connector-credential-core",
        "approval-connector-routing-core", "approval-connector-dingtalk-token",
        "approval-connector-invocation-core", "approval-connector-operations-core",
        "approval-connector-dingtalk-http", "approval-integration-core", "approval-connector-generic",
        "approval-integration-jdbc", "approval-engine-flowable", "approval-application",
        "approval-persistence-jdbc", "approval-architecture-tests",
    )},
    "integrations/host-sdk/pom.xml": "approval-host-sdk",
    "apps/server/pom.xml": "approval-server",
    "examples/generic-spring-host/pom.xml": "approval-generic-spring-host-example",
}
PROJECT_GROUP = "io.github.akaryc1b.approval"
OVERLAYS = tuple(f"integrations/ruoyi{version}-host-starter/overlay/ruoyi-extend/"
                 "ruoyi-approval-host-starter/pom.xml" for version in (5, 6))
ARTIFACT_HASHES = {
    "org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0":
        "b657bef2e1eb11e029a70cd688bde6adad29e4e99dacb18516bf651ecca32435",
    "org.apache.maven.shared:maven-shared-utils:jar:3.3.4":
        "7925d9c5a0e2040d24b8fae3f612eb399cbffe5838b33ba368777dc7bddf6dda",
    "commons-io:commons-io:jar:2.20.0":
        "df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def one(element, name, required=True):
    found = element.findall("m:" + name, NS)
    require(len(found) == 1 if required else len(found) <= 1, f"missing/duplicate {name}")
    return found[0] if found else None


def scalar(element):
    require(not element.attrib and not list(element), f"non-scalar {element.tag}")
    return (element.text or "").strip()


def value(element, name, required=True):
    child = one(element, name, required)
    return scalar(child) if child is not None else ""


def fields(element, names):
    require(not element.attrib and sorted(c.tag for c in element) == sorted(PREFIX + n for n in names),
            f"unexpected/duplicate fields or attributes in {element.tag}")
    require(not (element.text or "").strip() and all(not (c.tail or "").strip() for c in element),
            "mixed XML content is forbidden")


def coordinate(element, default_group=""):
    return (value(element, "groupId", False) or default_group) + ":" + value(element, "artifactId")


def targeted(plugin):
    # Do not silently ignore a foreign group masquerading as the Clean artifact.
    coordinate(plugin, GROUP)
    return value(plugin, "artifactId") == ARTIFACT


def structure(project):
    require(project.tag == PREFIX + "project", "Maven project namespace required")
    singular = {"properties", "build", "pluginManagement", "plugins", "dependencies",
                "dependencyManagement", "profiles", "modules", "reporting", "parent"}
    for element in project.iter():
        require(element.tag.startswith(PREFIX), "foreign XML namespace/element rejected")
        for name in singular:
            child = one(element, name, False)
            if child is not None:
                require(not child.attrib, f"unexpected attributes in {name}")
        if element.tag == PREFIX + "module":
            require(bool(scalar(element)), "empty reactor module")
        if element.tag == PREFIX + "properties":
            require(len(element) == len({c.tag for c in element}), "duplicate property")
            for prop in element:
                scalar(prop)
        if element.tag in {PREFIX + n for n in ("plugin", "dependency", "project", "parent")}:
            for name in ("groupId", "artifactId", "version"):
                value(element, name, False)
        if element.tag in {PREFIX + "plugin", PREFIX + "dependency"}:
            # This reactor uses literal owner identities throughout. Resolving
            # arbitrary profile/module aliases would make ownership ambiguous;
            # fail closed instead of admitting an unrecognized IO/Clean alias.
            require(all("${" not in value(element, name, False) for name in ("groupId", "artifactId")),
                    "property expressions in plugin/dependency identities require explicit ownership")


def verify_properties(project, root_pins):
    properties = one(project, "properties", root_pins)
    for container in project.findall(".//m:properties", NS):
        for prop in container:
            name = prop.tag.removeprefix(PREFIX)
            if name.startswith(("maven.clean.", "clean.")):
                require(root_pins and container is properties and name in PINS,
                        f"Clean property outside exact root pins: {name}")
                require(scalar(prop) == PINS[name], f"Clean pin drift: {name}")
    if root_pins:
        for name, expected in PINS.items():
            require(value(properties, name) == expected, f"required exact Clean pin missing: {name}")


def verify_plugin(plugin, *, effective=False, active=False):
    names = ["artifactId", "version", "dependencies"]
    if not effective or one(plugin, "groupId", False) is not None:
        names.insert(0, "groupId")
    if active:
        require(effective, "source Clean executions are forbidden")
        names.append("executions")
    fields(plugin, names)
    require(value(plugin, "groupId", False) in (("", GROUP) if effective else (GROUP,)),
            "Clean groupId must be explicit in source and canonical in models")
    require(value(plugin, "artifactId") == ARTIFACT, "Clean artifact drift")
    require(value(plugin, "version") == (PINS["maven.clean.version"] if effective else "${maven.clean.version}"),
            "exact Clean version/property reference required")
    dependencies = one(plugin, "dependencies")
    fields(dependencies, ["dependency"])
    dependency = one(dependencies, "dependency")
    # Maven 3.9.16 supplies compile scope only on the active inherited entry.
    # Source and managed entries must still contain precisely three fields.
    fields(dependency, ["groupId", "artifactId", "version"] + (["scope"] if active else []))
    if active:
        require(value(dependency, "scope") == "compile", "effective active Clean IO scope drift")
    require(coordinate(dependency) == IO
            and value(dependency, "version") == (PINS["maven.clean.commons-io.version"] if effective
                                                 else "${maven.clean.commons-io.version}"),
            "exact plugin-local Commons IO version/property reference required")
    if active:
        executions = one(plugin, "executions")
        fields(executions, ["execution"])
        execution = one(executions, "execution")
        fields(execution, ["id", "phase", "goals"])
        require(value(execution, "id") == "default-clean" and value(execution, "phase") == "clean",
                "effective Clean permits only Maven's generated default-clean binding")
        goals = one(execution, "goals")
        fields(goals, ["goal"])
        require(value(goals, "goal") == "clean", "effective default-clean goal drift")
    return dependency


def verify_io_ownership(project, clean_dependencies, *, root_release, effective=False):
    allowed = set(clean_dependencies)
    if root_release:
        for plugin in project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS):
            if coordinate(plugin, GROUP) == GROUP + ":maven-release-plugin":
                for dependency in plugin.findall("m:dependencies/m:dependency", NS):
                    if coordinate(dependency) == IO:
                        fields(dependency, ["groupId", "artifactId", "version"])
                        require(value(dependency, "version") == ("2.20.0" if effective
                                                                 else "${maven.release.commons-io.version}"),
                                "retained Release IO dependency drift")
                        allowed.add(dependency)
    for dependency in project.findall(".//m:dependency", NS):
        require(coordinate(dependency) != IO or dependency in allowed,
                "IO outside validated Clean or retained Release ownership")


def verify_source(root, projects):
    """Return only the validated root Clean dependency element for Release's guard."""
    root = root.resolve()
    require([str(path.relative_to(root)) for path, _ in projects] == list(REACTOR),
            "exact 26-project source reactor path membership/order drift")
    for path, project in projects:
        parent = one(project, "parent", False)
        group = value(parent, "groupId") if parent is not None else ""
        require(coordinate(project, group) == PROJECT_GROUP + ":" + REACTOR[str(path.relative_to(root))],
                "source reactor project identity drift")
    documents = list(projects)
    for relative in OVERLAYS:
        path = root / relative
        require(path.is_file(), "missing source overlay POM: " + relative)
        documents.append((path, ET.parse(path).getroot()))
    selected_dependency = None
    for path, project in documents:
        require(path.resolve().is_relative_to(root) and not path.is_symlink(), "source POM path escape/symlink")
        structure(project)
        is_root = path == root / "pom.xml"
        verify_properties(project, is_root)
        matches = [p for p in project.findall(".//m:plugin", NS) if targeted(p)]
        dependencies = []
        references = set()
        if is_root:
            managed = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
            direct = [p for p in managed if targeted(p)]
            require(len(matches) == len(direct) == 1 and matches[0] is direct[0],
                    "Clean must occur exactly once in root pluginManagement")
            selected_dependency = verify_plugin(direct[0])
            dependencies.append(selected_dependency)
            references = {one(direct[0], "version"), one(selected_dependency, "version")}
        else:
            require(not matches, "Clean declaration outside root pluginManagement")
        verify_io_ownership(project, dependencies, root_release=is_root)
        for element in project.iter():
            if "${maven.clean." in (element.text or "") or "${clean." in (element.text or ""):
                require(element in references, "Clean property reference outside exact approved owner")
    return selected_dependency


def verify_effective(file, source_projects):
    document = ET.parse(file).getroot()
    require(document.tag in (PREFIX + "project", PREFIX + "projects", "projects"),
            "malformed effective Maven document")
    projects = [document] if document.tag == PREFIX + "project" else list(document)
    require(len(source_projects) == len(REACTOR) == 26, "full source reactor required")
    require(all(p.tag == PREFIX + "project" for p in projects), "unexpected effective model element")
    require(sorted(coordinate(p) for p in projects) == sorted(PROJECT_GROUP + ":" + a for a in REACTOR.values()),
            "effective POM must contain exactly all 26 source reactor identities")
    for project in projects:
        compiler = run_path(str(Path(__file__).with_name("compiler_plugin_contract.py")))
        project = compiler["before_compiler"](project, effective=True, required=False)
        structure(project)
        verify_properties(project, True)
        managed = [p for p in project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS) if targeted(p)]
        active = [p for p in project.findall("m:build/m:plugins/m:plugin", NS) if targeted(p)]
        require(len(managed) == 1 and len(active) <= 1, "missing/duplicate effective Clean declaration")
        allowed = managed + active
        require(all(p in allowed for p in project.findall(".//m:plugin", NS) if targeted(p)),
                "effective Clean declaration outside managed/active build")
        dependencies = [verify_plugin(managed[0], effective=True)]
        dependencies += [verify_plugin(p, effective=True, active=True) for p in active]
        verify_io_ownership(project, dependencies, root_release=True, effective=True)


def verify_realm(file, repository=None):
    log = re.sub(r"\x1b\[[0-9;]*m", "", file.read_text())
    lines = log.splitlines()
    marker = f"[DEBUG] Populating class realm plugin>{GROUP}:{ARTIFACT}:3.2.0"
    starts = [i for i, line in enumerate(lines)
              if f"Populating class realm plugin>{GROUP}:{ARTIFACT}:" in line]
    require(len(starts) == 1 and lines[starts[0]].strip() == marker,
            "missing/duplicate/wrong actual Clean class realm")
    coordinates = []
    terminated = False
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r"\[DEBUG\]\s+Included: (\S+)\s*", line)
        if match is None:
            # Never accept a truncated block or stop at an arbitrary blank/error
            # line that could hide further included artifacts in this realm.
            require(re.fullmatch(r"\[DEBUG\] (?:Configuring|Loading) mojo(?: execution)? ['\"]?"
                                 r"org\.apache\.maven\.plugins:maven-clean-plugin:3\.2\.0:clean(?:[ '\"].*)?", line)
                    or re.fullmatch(r"\[DEBUG\] Populating class realm plugin>[^\s]+", line),
                    "malformed/interrupted Clean realm block or missing recognized terminator")
            terminated = True
            break
        coordinates.append(match[1])
    require(terminated, "truncated Clean realm block without recognized terminator")
    require(len(coordinates) == 3 and set(coordinates) == set(ARTIFACT_HASHES),
            "Clean realm must contain exactly Clean 3.2.0, Shared Utils 3.3.4 and IO 2.20.0")
    if repository is not None:
        repository = repository.resolve()
        for item in coordinates:
            group, artifact, kind, version = item.split(":")
            file = repository / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.{kind}"
            require(file.is_file() and file.resolve().is_relative_to(repository), "realm JAR missing/escapes repository")
            require(hashlib.sha256(file.read_bytes()).hexdigest() == ARTIFACT_HASHES[item],
                    "approved Clean realm JAR SHA-256 mismatch: " + item)
    return coordinates


def verify_model_delta(candidate_file, baseline_file, candidate_root, baseline_root):
    """Reverse only the approved additions, preserving every other model field.

    The caller also runs both retained Release model gates and the candidate
    Clean model gate. Absolute checkout prefixes necessarily differ between two
    captures; normalize only those explicitly supplied roots. Preserve ordering,
    attributes, configuration, dependencies, executions and significant text.
    """
    candidate = ET.parse(candidate_file).getroot()
    baseline = ET.parse(baseline_file).getroot()

    def projects(document):
        items = [document] if document.tag == PREFIX + "project" else list(document)
        require(all(p.tag == PREFIX + "project" for p in items), "unexpected model comparison element")
        identities = [coordinate(p) for p in items]
        require(sorted(identities) == sorted(PROJECT_GROUP + ":" + a for a in REACTOR.values()),
                "model comparison requires exactly all 26 source identities")
        return dict(zip(identities, items))

    before, after = projects(baseline), projects(candidate)

    def normalize(element, root):
        def text(value):
            value = (value or "").strip()
            return "${SOURCE_ROOT}" if value == root else value.replace(root + "/", "${SOURCE_ROOT}/")
        return (element.tag, tuple(sorted((k, text(v)) for k, v in element.attrib.items())),
                text(element.text), text(element.tail), tuple(normalize(c, root) for c in element))

    for identity, project in after.items():
        reversed_project = copy.deepcopy(project)
        properties = one(reversed_project, "properties")
        for name in PINS:
            require(value(properties, name) == PINS[name], "unvalidated candidate model pin")
            properties.remove(one(properties, name))
        managed = reversed_project.find("m:build/m:pluginManagement/m:plugins", NS)
        require(managed is not None, "missing candidate management for semantic reversal")
        matches = [p for p in managed if targeted(p)]
        require(len(matches) == 1, "ambiguous candidate Clean management for semantic reversal")
        verify_plugin(matches[0], effective=True)
        managed.remove(matches[0])
        for plugin in reversed_project.findall("m:build/m:plugins/m:plugin", NS):
            if targeted(plugin):
                verify_plugin(plugin, effective=True, active=True)
                plugin.remove(one(plugin, "dependencies"))
        require(normalize(reversed_project, str(candidate_root.resolve()))
                == normalize(before[identity], str(baseline_root.resolve())),
                "effective model changes semantics beyond exact Clean additions: " + identity)
    return len(after)
