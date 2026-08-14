#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

import { verifyWorkflowSupplyChainRemediation as verifyHistoricalR2B } from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-legacy.mjs';
import { verifyWorkflowSupplyChainRemediation as verifyAcceptedR2B } from './m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';

const SHA64 = /^[0-9a-f]{64}$/;
const H5_GRAPH = 'e4cffa00582d61a62f5c41548f8da4b8bfb28dd50b7db3aa5d1aa42cd503ddfd';
const HISTORICAL_CONTRACT_SHA256 = 'f317b8f6568100c5da132a46c9cd4162851c60dfc36dbc9cd00916e2303832f5';
const RETAINED_OSV_SET_SHA256 = '42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6';
const RETAINED_OSV_COUNT = 117;
const MAX_UNREVIEWED_OSV_ADDITIONS = 64;
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const findingSetSha256 = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);
const historicalContract = JSON.parse(readFileSync(
  new URL('../../docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json', import.meta.url),
  'utf8',
));
const retainedOsvIds = Object.freeze(`006aa8e8e0a959126b850d5f6c725366e46f30d26e52b1c04eba6f258d842f6e
01ab1aaf7afb2d29a255b1b1053e4a4fc603a478e8ab00cedb41a96e9db170cf
04b955111b4540651332f88b779aa13e53314719b6135a4984372c1c1f693984
0666a26f8788d477072c49cfa360616a39694b42a5fab42b309da3d77532c132
0690af9c8c33e4d6fd65a580e9f548b88e1059b4db4c35f61b4e7b9c2e3bf1bf
06bfc35e5ee11c652806ded3cc6cc8d796f59bce6ac6968a903354ce5ff96d8d
0932ffdd89844a2090f0ccc222abdd48b0f557b591eb4e5949ccbde7f24c60a4
0c57b5f1dd63e29888e840797f56c2a9e4afdaeb56ce50c27a39b9044039311b
0fd1d101aaf2ec8e29cec82180e30d81e4ff42c7dc96e7f2f1a2cf4364397f1e
1062828407c53bc3262cccdc31837cdf6b552604646d131944f8d291331a0748
11978dddbde9b5280e6989bfbed2f638e04da4ba2e0053650fae45a0019c349c
1583ad45f3ee66829aa18f24e5fc7c7b5472862dba413f1b85c7f95710156f8c
1584c1a266be132c5d9919683ca4e8ceba78504490b507c621789e182aac52ef
15f5e868eb39604a79e77be0451ee39d6f5cd5aa1597949d9dbd7bf22c1e6b2d
1648f3797968d7f2d106dad17a61d5a7c3cb66cf827c0827e0d5448bb97ad6b2
1680ca10dc2bbeea5513c287eba68a50b1c02ba541336e83722f01ac25cd9a3d
17beddba593cf4d22edec85208b1022caa6bb0955f27a5c49778dd588092445e
1d98e5a3475203a743506fa8f95023c582421b1d31b906d029e94a4e6ff8dc71
1ff51151fa4041a3ab5ec1ebb02d6396c79b77fea32435c2f1ea86e17dc5e1bd
20ceccd4098ed6de51e879965677d3db8b88fdf41a41ef64de623dc10f80ebd1
20e716596249abaafbd544f31ee0e90949bfd892684a3f57381e69755ad1c15a
2168f8cff47cd5da94e98006c8cdfa45b36b1f0edd3a67e56432eeb5ea017cb1
21825dce4ce50e452a5163170163f96f89b82c24d2e0015edc3d1706089a0243
2843db89cdf3ad16874fce82c81807e2e40cd5c7a5c658d9d59abc040ccbc809
29a3b051d8dd0e7a7de6a89da09cf4169190513bafd9ec4e8bcf02bc4f31d736
2a0762447820fabb9614592c66dc6e15749af3a0c61a685be2af386350cb2725
2a51e87b26d0980da65d8468e9e83e771e8c2fe5f919c7372e900a5603c4a6af
2be18564b2b9040fc793a8572f57979772d19840389e6778a9cdd300fe7b85c7
2c31b0ceabf2e8d6bcb8c4cc19796cd2e31033967712a415f20a4bb19f50a93a
2f0d29dd75ba96ea6ffabcf62e1564bbdc2280f52cc0daad4d257140ea201f83
2f4564d12f468262a89f22ffdba329df0c09144a51fc8101046dabbe846de46e
313423bc033967b9d86719e736febc0b74e0f5cae5f240e8d9163baa00aa2930
330d0386349dbc5bcd714ccbf1c628e69e0f0b23b3d53bd8b984a476f0988e51
35b0f199dd9812e9158d079aabcca9cf9450850f0fa0d01c4dfe03b5d2d94a79
35fb6917af4dcd633580647f3c9ffc2d2bd5e97f296634c1d8f3515a40b8413e
36365712da812532fa5a0e3ed4c7008a3bfdade1587219a7d38e489380118021
37fcee866705cccdddcc572ca21640c9fc1cbd8d6093bd9be0a99d50205d31ea
38340ba2b2ffedb1459d97b3e5bf976fba7e74096a21d8d6b7b936cf80d9ed37
3ad9bdfd90d5aaeabeecac2709dfd90a944875243f4357db8cc55071f20a9bf7
3bfcd40c67ace8f6b4de1ba07bd95ccbee875267a156df36e2922ada0a999301
3cc19d957ee259967044b82359a0c880b3cf3d266cb83c827891c0a669d613b2
3d20d4701a12f3002ee5fb691d2b255ab1309e913d1471fd58aac792670ea616
4108d5e4d951b188a0040fd611d3b5bc694f6a31c5ff74c9b3267b8e716a7f36
449ca7026b7fa0199831bf5a0ee058c5984283e85200b8916457f23009be5d35
4df647bbd165e91d5422620b9bdb67e3ecc1424a7b560d86912ecb0b9b27d416
4e2acb5df4537f0aa02efbd52da9f008a2b937ba9c06952f0d0bd308fac0a841
4f17df58d4cc0ea34be7302cd3dd7bf60153e39323996540c6c7af51fe21119e
5241d79f2a28456263052a47d4b4818bac59c074845e44267013e9bc4a2a12b7
544d0654a6214c9578e0faabeb78ab79969a79618cedc1841a823ab731f77e88
5e63544c0ea7c20e2d9b793649021cdfc3cfbaf5253dc618b7dd04824bd1118f
6192b7b4190ec5c127643af47caa02163fadf66755486c2c8b4667c244a4a33e
64b8645b41bbfc917e33010fe8e01d757be2909a40cd0fdeb16e268685d002e8
675ece92dd52d54b079d9251d4c2ed643c4ad8c8f580260c2620e3ceb92fc5ff
67e0d50c0747a7e6236b42381d4f5232a15b0f2c5d6bfef4cc598a792256162b
69c5e3563ec1b0c17880981e8ed1e0402b827c06ccf3fe82daaa4479f9c11005
6f8f8e4ee9de4732973c0bd01d7a363dcb1d0b36477f73f0b89d135547cdee26
703504f305dc26d34de00678822d949bfd88037bcdf9eee3b14db6891ff4a8dd
78700407f6c464b94d131c0cc4456e98c28c660ec11170a4b53686b06983dcde
793b7068f27b2161f26b2bd5ef53263f1f6b3f4c5fe4ae586aba50c86c53bf9e
7e50c9552899e3e8a4ad9eb87b598302c7239d60a2694985fe57432219430a85
7eeb4b05e0cc7191b7a76b59d3ae5a9ac883bea2f24b1410066ee02a86763def
820d44f7c567032ca5bbf5a746450c05165a3ebf6c02db09d36100239b4aad6d
8419f0d874794fc97d2c94877e9c5aa5bdd86f8ac76e6de9d4a82a07daee18ea
859cdac69c95524572ea4676f4452a651f5ebdcb1d0226ec0a641e72f8ce6959
86659412c0581cc1b5b2000d28c5fc0051345f799ac7730a54cc5d06fc57a086
86a8f96d610bb54ba2f38a338f9987643efa3320313c41a151d8895e79c3397a
89892f018e0b0ecd5f2b82f14829ed499966d2a77e446d90ef74fbb98d63c0c9
8c78c6087536da587e41019aff6bea94c0667d9f3580a6b17fa193726004cdc8
8cc5d90d2f9ccea915df2a1e43ef05320388efef6764fb96cf4119fccab38369
90108d88e16fc9137cfcb43c06075aa7ae8bf5965e8f61d0e7724f62a51b078e
90fda8b3ff693b394c9071f98450e88fbfd9bf6de8ff69d1aebf5510ec072af3
962f63e037d3a2cf9a244e90406d1bc935984917257850aaf015ac2923f5ac45
9971a2e150425a991c20d1fd0deb68a4210d58205dfadab7e6b89e016dbefa08
9b7c8a074f6028f8fdb56007728bc06c06929b4e77bfdc0e2887c8195b7d9b4d
9c4768849f079027b590e7886787cba30786a8b99ca90580edb348cf17925793
a03fd978115ed940b4821a454e285b0b02444f7a91626aa5fa71c8d809c6f018
a6e60fdee738abc7a3abcd34931dd4f0cd4628901a3d5d1656cc236f87e1f52c
a6fe3debb758e300d304c41a8e03dffb9dccdaac7c8a940535cf554351eb1030
a849a72857482c58c443790d03fd06a3731ad417e06e9a351ce5307dfef461fe
ac5ca7f10efa04bd4cde8e5b27dab22a7fc1e44e47c8fd38b9abf37564f3f57b
aefd79759073cdf382bb52f3c32da820e0c86f1392d220d7c661f746c0ce8fb0
b2fcf4ce44c7cae1d7a6eb32fd02460d7e17f6ca533dbdbfb2fee943ee989043
b7ad0df70c29afe4aec38661a172bb8474649f178c8e757a36275aea87bc4426
bb3e674315526c33ff67dcb6a6e5b03052d18470440bc558420888523b0e0ed8
bbb01154ee915faf077544b1c3aee306a537d75cd15ca31b9c746f9894473242
be755e476c89fdeb6fe35b7566a1a400ad1ace7cc338253975e757c7599e7fe4
c081e688c9aecf67be4f9669ae374c71b3b828aff8f087e182d3e53ebab46c06
c4084cd23d7a6081e6d203996c2e3a30facea06929b4282260dee942cddf37fa
c5597a14d04fcdc486119f0f160eca2668e518c8973bc07693639cbe8325d3c8
c8c97fd64a4710b62e46560162f784f46a6cea929dab8831df3e7f055bb9c12c
c91896f0a86008fc50c5e6bfea46c043f4bd69789447cfc7afd0b6e71cea1cba
c9290ef39e54e8600014f7ca4beb61b6adf1a7f73caafdd5cb0be770cc3dc156
cbb4a51e019f7260f8ba761af1a0108655d99fe07ea9ba9f192122fc3db0bca4
cc06b13e4d588baa0fbc33fa968d3057d1e525de03726cb9abffa2e5a6395209
d68382b9e1f5984db7a547f572e4c5e9f3b6b06dc816685c7e874bb4b719824a
d74b59eb58135c8f54f2db85120d9175c1417b44f9e5583bd122ce395d7e228a
d9da8f946e404e7a537c9b74b61dcfe38e7f36421bbb4483c750dfe03683a567
da908e912ed20918f5691b5b129cd601734f78ed3050a5ccf3deea94adc7c883
e09c3ade220682da83907d4917453c882950a81e9d61baff72b0f493045cd0f1
e16078f79faff26b491300b8254c1f392999bb3a2beee8b16d5b2fa9377e83ff
e1ef6e9c9e24695b0d76113dfc60f852e0cd2e2dbc930140c9f0854b1efdc837
e49d640c3d7ace3024c287fcb45044d026615d2f5d211e70c5462bafdae85c6b
e62149def48380eb329e1410adfb335573bc667ce2a50b8811209807ae011420
e63f726ae4934fad9a9223c0301cbca004bedcf201dba74cb35ab9952941c0ea
e6addd49c1ea8eddec10d654318f6cb52f458e139900b8e612c59fbc690b0b79
e7c9a650e123abc1c450b3d0b13e723a3495f3709b00c2941b6e74c13d31a190
e8495e74597c5bddbafdde30029187931fa49316b60d4981df839ec8e49605ee
ecc3acc78edd4c92c36efb1499f1fea53e67631d8165c5031ac9f9745c6f8121
ef8c6012565dac2cf90df4b927a9f1b6ab965095e54c12925c17cc837eeb1ad7
f27db0832b2f2c3cdf768b5399acb39fdc5af7d59f3203a74f6da7c6d0f240b4
f4a5ebef79345ae0dc47d54dbecedc7e933890a406316c04bbcc622e4e2062d8
f70df9116e5087d4b3553d6181acb465ecf17a6ea1759c6685f1b7d7c0500a1e
f92fe8e8fd90cbfc27ba09cc54e91da48ac95a55232762b85bbb2b1503f9cd9c
f9ced388beec85a0bbfcb5053dfc888275627a96284ff524c1801fa50c438391
f9f5fee239f1cf1def3477ecfe196725b65414d3637440b074e75df4a248d480
fce31ce12ad398147c01f2908fe8ffeb88de7de2180ac27de0452ecefa0d969c
fff2a532282d7ba3122beaaa1d1b54e4f2a1cadbb8b5a9c64887db862852ec2f`.split(/\r?\n/).filter(Boolean).sort());

function requireH5ExtensionContract() {
  const { contentSha256, ...payload } = historicalContract;
  if (contentSha256 !== HISTORICAL_CONTRACT_SHA256 || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('H5 OSV historical identity contract mismatch');
  }
  if (historicalContract.repository !== 'akaryc1b/approval-platform') {
    throw new Error('H5 OSV historical repository mismatch');
  }
  const current = historicalContract.expectedCurrentIdentitySets || {};
  if (current.osv?.findingCount !== RETAINED_OSV_COUNT
    || current.osv?.findingSetSha256 !== RETAINED_OSV_SET_SHA256
    || retainedOsvIds.length !== RETAINED_OSV_COUNT
    || new Set(retainedOsvIds).size !== retainedOsvIds.length
    || retainedOsvIds.some((id) => !SHA64.test(id))
    || findingSetSha256(retainedOsvIds) !== RETAINED_OSV_SET_SHA256) {
    throw new Error('H5 OSV retained identity baseline mismatch');
  }
  return historicalContract;
}

function scannerIds(name, scanner, sourceClass) {
  if (!scanner
    || scanner.scanCompleted !== true
    || scanner.rawReportRetained !== false
    || !Array.isArray(scanner.findings)
    || scanner.findings.length !== scanner.findingCount) {
    throw new Error(`H5 OSV complete current ${name} identity evidence required`);
  }
  const ids = scanner.findings.map((finding) => {
    if (finding.sourceClass !== sourceClass || !SHA64.test(finding.findingId || '')) {
      throw new Error(`H5 OSV ${name} finding identity drift`);
    }
    return finding.findingId;
  });
  if (new Set(ids).size !== ids.length) throw new Error(`H5 OSV ${name} duplicate finding identity`);
  return ids.sort();
}

function requireIdentitySet(label, ids, expected) {
  if (!expected
    || ids.length !== expected.findingCount
    || findingSetSha256(ids) !== expected.findingSetSha256) {
    throw new Error(`H5 OSV ${label} identity-set drift`);
  }
}

function unresolvedAddition(finding) {
  if (!finding
    || finding.sourceClass !== 'E4_OSV_SCANNER'
    || !SHA64.test(finding.findingId || '')
    || typeof finding.upstreamFindingId !== 'string'
    || !finding.upstreamFindingId
    || typeof finding.package?.ecosystem !== 'string'
    || typeof finding.package?.name !== 'string'
    || typeof finding.package?.version !== 'string'
    || !Array.isArray(finding.componentRefs)
    || finding.componentRefs.length === 0
    || !Array.isArray(finding.scopes)) {
    throw new Error(`H5 OSV unresolved addition evidence incomplete ${finding?.findingId || 'unknown'}`);
  }
  return stable({
    sourceClass: finding.sourceClass,
    findingId: finding.findingId,
    upstreamFindingId: finding.upstreamFindingId,
    aliases: [...new Set(finding.aliases || [])].map(String).sort(),
    package: finding.package,
    componentRefs: [...new Set(finding.componentRefs)].map(String).sort(),
    scopes: [...new Set(finding.scopes)].map(String).sort(),
    upstreamSeverity: finding.upstreamSeverity || [],
    fixedVersions: [...new Set(finding.fixedVersions || [])].map(String).sort(),
    disposition: 'UNRESOLVED',
    reviewRequired: true,
  });
}

export function retainedH5OsvIdentityIds() { return [...retainedOsvIds]; }

export function reconcileH5OsvFindings(osv) {
  requireH5ExtensionContract();
  const ids = scannerIds('OSV', osv, 'E4_OSV_SCANNER');
  const baseline = new Set(retainedOsvIds);
  const current = new Set(ids);
  const missing = retainedOsvIds.filter((id) => !current.has(id));
  if (missing.length) throw new Error(`H5 OSV retained identity missing ${missing[0]}`);
  const additions = osv.findings
    .filter((finding) => !baseline.has(finding.findingId))
    .map(unresolvedAddition)
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
  if (!additions.length) throw new Error('H5 OSV extension requires current additions');
  if (additions.length > MAX_UNREVIEWED_OSV_ADDITIONS) {
    throw new Error(`H5 OSV unresolved addition bound exceeded ${additions.length}`);
  }
  if (RETAINED_OSV_COUNT + additions.length !== ids.length) throw new Error('H5 OSV identity partition mismatch');
  return stable({
    retainedOsvFindingCount: RETAINED_OSV_COUNT,
    retainedOsvFindingSetSha256: RETAINED_OSV_SET_SHA256,
    addedOsvFindingCount: additions.length,
    addedOsvFindings: additions,
    currentOsvFindingSetSha256: findingSetSha256(ids),
  });
}

export function reconcileH5OsvIdentityExtension(e4) {
  const contract = requireH5ExtensionContract();
  if (!e4 || e4.repository !== contract.repository || e4.e2GraphDigest !== H5_GRAPH) {
    throw new Error('H5 OSV exact graph evidence required');
  }
  const osv = reconcileH5OsvFindings(e4.scanners?.osv);
  const gitleaksIds = scannerIds('Gitleaks', e4.scanners?.gitleaks, 'E4_GITLEAKS');
  const semgrepIds = scannerIds('Semgrep', e4.scanners?.semgrep, 'E4_SEMGREP');
  const zizmorIds = scannerIds('zizmor', e4.scanners?.zizmor, 'E4_ZIZMOR');
  const expected = contract.expectedCurrentIdentitySets;
  requireIdentitySet('Gitleaks', gitleaksIds, expected.gitleaks);
  requireIdentitySet('Semgrep', semgrepIds, expected.semgrep);
  requireIdentitySet('zizmor', zizmorIds, expected.zizmor);
  const currentScannerCounts = stable({
    gitleaks: gitleaksIds.length,
    osv: e4.scanners.osv.findingCount,
    semgrep: semgrepIds.length,
    zizmor: zizmorIds.length,
  });
  return stable({
    schemaVersion: 'M6_PR_E_E3_R2B_H5_OSV_IDENTITY_EXTENSION_EVIDENCE_V1',
    historicalContractContentSha256: HISTORICAL_CONTRACT_SHA256,
    scopeGraphDigest: H5_GRAPH,
    ...osv,
    currentFindingSetSha256: {
      gitleaks: findingSetSha256(gitleaksIds),
      osv: osv.currentOsvFindingSetSha256,
      semgrep: findingSetSha256(semgrepIds),
      zizmor: findingSetSha256(zizmorIds),
    },
    currentScannerCounts,
    totalFindingCount: Object.values(currentScannerCounts).reduce((sum, count) => sum + count, 0),
    additionsDisposition: 'UNRESOLVED',
    reviewRequired: true,
    maxUnreviewedOsvAdditions: MAX_UNREVIEWED_OSV_ADDITIONS,
    suppressionAdded: false,
    exceptionAdded: false,
    severityDowngradeAdded: false,
    findingDeletionClaimed: false,
    releaseBlocked: true,
  });
}

export function verifyWorkflowSupplyChainRemediation(e4, plan, snapshot) {
  if (e4?.e2GraphDigest !== H5_GRAPH || e4?.scanners?.osv?.findingCount <= RETAINED_OSV_COUNT) {
    return verifyHistoricalR2B(e4, plan, snapshot);
  }
  const scannerIdentityReconciliation = reconcileH5OsvIdentityExtension(e4);
  const reconciledPlan = structuredClone(plan);
  reconciledPlan.priorNonZizmorFindingCounts = {
    gitleaks: scannerIdentityReconciliation.currentScannerCounts.gitleaks,
    osv: scannerIdentityReconciliation.currentScannerCounts.osv,
    semgrep: scannerIdentityReconciliation.currentScannerCounts.semgrep,
  };
  const acceptedEvidence = verifyAcceptedR2B(e4, reconciledPlan, snapshot);
  const { contentSha256: ignored, ...acceptedPayload } = acceptedEvidence;
  const payload = stable({
    ...acceptedPayload,
    scannerIdentityReconciliation,
    reasonCodes: [
      ...new Set([
        ...(acceptedPayload.reasonCodes || []),
        'OSV_CURRENT_ADDITIONS_RETAINED_UNRESOLVED_REVIEW_REQUIRED',
      ]),
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

export const canonicalH5OsvIdentityExtension = canonical;
