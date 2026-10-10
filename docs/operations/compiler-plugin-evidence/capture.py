"""Local, offline, non-deploying candidate capture. Not canonical CI evidence."""
import hashlib, json, os, signal, subprocess, sys, time
from pathlib import Path

W = Path('/workspace/scratch/fb685c03ae3c')
S = W / 'approval-platform-compiler-io'
B = W / 'approval-platform-clean-main-0c55ff5e'
O = W / 'release-recovery-evidence/compiler-io-candidate/capture-2'
T = W / 'release-recovery-tools'
J, M = T / 'jdk-21.0.12.1+1', T / 'apache-maven-3.9.16/bin/mvn'
N = Path('/opt/codex/runtimes/codex-primary-runtime/dependencies/node/bin/node')
sys.path.insert(0, str(W / 'release-recovery-evidence/clean-plugin-remediation-execution-tooling'))
from toolchain_evidence import verify_toolchain

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def git(source, *args): return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()
assert git(S, 'rev-parse', 'HEAD') == git(B, 'rev-parse', 'HEAD') == '0c55ff5e7d974022e5c76d9a88a4312d2af81827'
assert git(B, 'status', '--porcelain=v1') == ''
toolchain = verify_toolchain()
O.mkdir()
for name in ['home', 'tmp']: (O / name).mkdir()
(O / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
settings = str(O / 'empty-settings.xml')
env = {'JAVA_HOME': str(J), 'MAVEN_SKIP_RC': '1', 'PATH': ':'.join(map(str, [J / 'bin', M.parent, N.parent])) + ':/usr/bin:/bin',
       'LANG': 'C.UTF-8', 'HOME': str(O / 'home'), 'TMPDIR': str(O / 'tmp'),
       'MAVEN_ARGS': '-o -s ' + settings + ' -gs ' + settings + ' -Dmaven.repo.local=' + str(T / 'm2/repository'),
       'M6_PR_E_E2_MAVEN_REPOSITORY': str(T / 'm2/repository'), 'MAVEN_OPTS': '-Xmx512m', 'MAVEN_BASEDIR': str(O)}
capture_inputs = [p for p in git(S, 'ls-files').splitlines() if p.endswith(('pom.xml', 'package.json')) or p.startswith(('scripts/security/', 'scripts/ci/', 'docs/m6/', '.github/workflows/'))]
input_hashes = {}
for relative in capture_inputs:
    destination = O / 'source-inputs' / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes((S / relative).read_bytes())
    input_hashes[relative] = sha(destination)
(O / 'capture.py').write_bytes(Path(__file__).read_bytes())
pom_hashes = {p: sha(S / p) for p in git(S, 'ls-files', '*pom.xml').splitlines()}
record = {'status': 'RUNNING', 'sourceHead': git(S, 'rev-parse', 'HEAD'), 'sourceTree': git(S, 'rev-parse', 'HEAD^{tree}'),
          'sourceDirty': True, 'canonicalCiEvidence': False, 'sourcePomHashes': pom_hashes, 'toolchain': toolchain, 'commands': [], 'captureInputs': input_hashes, 'collectorSha256': sha(Path(__file__)), 'collectorSnapshot': 'capture.py'}
def save(): (O / 'execution.json').write_text(json.dumps(record, indent=2) + '\n')
def run(label, command, timeout, cwd=S):
    item = {'label': label, 'command': command, 'cwd': str(cwd), 'timeoutSeconds': timeout, 'status': 'RUNNING'}
    record['commands'].append(item); save(); start = time.monotonic()
    process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        out, err = process.communicate(timeout=timeout)
    except BaseException:
        os.killpg(process.pid, signal.SIGKILL)
        out, err = process.communicate()
        (O / (label + '.stdout')).write_bytes(out); (O / (label + '.stderr')).write_bytes(err)
        item.update(status='TIMEOUT_OR_ERROR', exitStatus=process.returncode); save(); raise
    (O / (label + '.stdout')).write_bytes(out); (O / (label + '.stderr')).write_bytes(err)
    item.update(status='PASSED' if process.returncode == 0 else 'FAILED', exitStatus=process.returncode, elapsedSeconds=time.monotonic()-start)
    save(); assert process.returncode == 0, label + ' failed'
try:
    run('e2', [str(N), str(S / 'scripts/security/m6-pr-e-e2-generate-sbom.mjs'), '--full-maven', '--root=' + str(S)], 510)
    run('owners', [str(M), '-B', '-ntp', 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins', '-DappendOutput=true', '-DoutputFile=' + str(O / 'plugin-owner-report.txt')], 240)
    run('effective', [str(M), '-B', '-ntp', 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', '-Doutput=' + str(O / 'effective-pom.xml')], 240)
    run('baseline-effective', [str(M), '-B', '-ntp', 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', '-Doutput=' + str(O / 'baseline-effective-pom.xml')], 240, B)
    assert sha(O / 'plugin-owner-report.txt') == json.loads((O / 'e2.stdout').read_text())['maven']['pluginResolutionSha256']
    assert pom_hashes == {p: sha(S / p) for p in pom_hashes}
    assert input_hashes == {p: sha(S / p) for p in input_hashes}
    record['captureInputsStable'] = True
    assert git(B, 'status', '--porcelain=v1') == ''
    record.update(status='PASSED_CAPTURE_ONLY', e2Sha256=sha(O / 'e2.stdout'), ownerReportSha256=sha(O / 'plugin-owner-report.txt'), sourcePomStable=True)
finally: save()
