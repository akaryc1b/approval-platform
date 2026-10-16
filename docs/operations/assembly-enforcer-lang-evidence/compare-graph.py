"""Strict two-owner Commons Lang delta and complete E2 equality."""
from pathlib import Path
import copy
import hashlib
import json
import re
ROOT = Path(__file__).resolve().parent
SOURCE = Path('/workspace/scratch/fb685c03ae3c/approval-platform-assembly-enforcer-lang')
sha = lambda raw: hashlib.sha256(raw).hexdigest()
canonical = lambda value: json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()

def owners(raw):
    result, occurrences, ordered = {}, {}, {}
    owner = None; coordinates = []
    def finish():
        if owner is None: return
        value = sorted(coordinates)
        assert owner in value and len(set(value)) == len(value)
        assert owner not in result or result[owner] == value
        result[owner] = value; occurrences[owner] = occurrences.get(owner, 0) + 1
        ordered.setdefault(owner, list(coordinates))
    for line in raw.decode().splitlines():
        if not line.strip() or line == 'The following plugins have been resolved:': continue
        match = re.fullmatch(r'( {3}| {6})([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})', line)
        assert match, line
        if len(match[1]) == 3: finish(); owner = match[2]; coordinates = []
        else: assert owner; coordinates.append(match[2])
    finish()
    return {'owners': result, 'occurrences': occurrences, 'orderedOwners': ordered}

BASELINE_SHA = '858adce49f6911cd8be21d9a22ef11e9368472182cf07b48f42980c0c9efbb07'
REPORT_SHA = '62a2e33f82d1511df25e57774127d6580f5e751a059adffd8dc63ef01f874482'

def main():
    baseline = ROOT / 'accepted-main-E2.json'
    candidate = ROOT / 'graph-01/e2.stdout'
    before = SOURCE / 'docs/operations/jar-plugin-evidence/plugin-owner-report.txt'
    after = ROOT / 'graph-01/plugin-owner-report.txt'
    assert sha(baseline.read_bytes()) == BASELINE_SHA
    assert sha(before.read_bytes()) == REPORT_SHA
    b, c = (json.loads(path.read_bytes()) for path in (baseline, candidate))
    ob, oc = (owners(path.read_bytes()) for path in (before, after))
    for graph in (b, c):
        assert graph['contentSha256'] == sha(canonical({k: v for k, v in graph.items() if k != 'contentSha256'}))
    assert b['commitSha'] == '9e3489f2aef3ffb36508367591bc0d77ceb99bd1'
    old, new = 'org.apache.commons:commons-lang3:jar:3.14.0', 'org.apache.commons:commons-lang3:jar:3.18.0'
    changed = {'org.apache.maven.plugins:maven-assembly-plugin:jar:3.7.1',
               'org.apache.maven.plugins:maven-enforcer-plugin:jar:3.5.0'}
    assert len(ob['owners']) == len(oc['owners']) == 17
    assert set(ob['owners']) == set(oc['owners'])
    assert ob['occurrences'] == oc['occurrences']
    assert {o for o,v in ob['owners'].items() if old in v} == changed
    for owner in ob['owners']:
        expected = sorted(new if item == old else item for item in ob['owners'][owner]) if owner in changed else ob['owners'][owner]
        assert oc['owners'][owner] == expected, owner
    for graph, report, file in [(b, ob, before), (c, oc, after)]:
        assert graph['maven']['resolvedPluginCoordinates'] == sorted({item for values in report['owners'].values() for item in values})
        assert graph['maven']['pluginResolutionSha256'] == sha(file.read_bytes())
    assert c['maven']['resolvedPluginCoordinates'] == sorted(set(b['maven']['resolvedPluginCoordinates']) - {old})
    assert new in c['maven']['resolvedPluginCoordinates']
    reverted = copy.deepcopy(c)
    reverted['commitSha'] = b['commitSha']
    reverted['maven']['resolvedPluginCoordinates'] = b['maven']['resolvedPluginCoordinates']
    reverted['maven']['pluginResolutionSha256'] = b['maven']['pluginResolutionSha256']
    reverted['contentSha256'] = b['contentSha256']
    assert reverted == b, 'E2 changed beyond exact two-owner Lang replacement and capture source-head label'
    assert (len(c['maven']['components']),len(c['maven']['edges']),len(c['maven']['importedBoms']),len(c['maven']['reactorRoots'])) == (237,345,6,26)
    result={'status':'PASSED_EXACT_TWO_OWNER_LANG_AND_FULL_GRAPH_DELTA','sourceHead':c['commitSha'],
            'sourceFiles':{str(p):sha(p.read_bytes()) for p in (baseline,candidate,before,after)},
            'ownerCount':17,'unchangedOtherPluginOwnerCount':15,'changedOwners':sorted(changed),
            'removedCoordinate':old,'candidateCoordinate':new,'ownerOccurrences':oc['occurrences'],
            'runtimeComponentsEdgesScopesLicensesBomsUnchanged':True,'pnpmActionsToolchainUnchanged':True,
            'fullE2EqualAfterExactDeltaReversal':True,'componentCount':237,'edgeCount':345,'importedBomCount':6,
            'reactorProjectCount':26,'canonicalCiEvidence':False,'scannerDispositionClaimed':False,'releaseBlocked':True}
    (ROOT/'graph-comparison.json').write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    print(json.dumps(result,sort_keys=True))

if __name__ == '__main__': main()
