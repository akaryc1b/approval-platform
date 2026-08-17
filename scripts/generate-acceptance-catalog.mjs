#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = 'config/acceptance-catalog.json';
const outputRoot = 'docs/acceptance';
const checkOnly = process.argv.includes('--check');

function read(relativePath) {
  return readFileSync(path.join(root, relativePath), 'utf8');
}

function parse(relativePath) {
  return JSON.parse(read(relativePath));
}

function validate(catalog, lock) {
  assert.equal(catalog.schemaVersion, 1, 'Unsupported acceptance catalog schema version');
  assert.equal(lock.schemaVersion, 1, 'Unsupported acceptance lock schema version');
  assert.equal(catalog.lockFile, 'config/acceptance-lock.json');
  assert.ok(Array.isArray(catalog.milestones) && catalog.milestones.length > 0);

  const milestoneIds = new Set();
  const recordIds = new Set();
  const recordPaths = [];
  for (const milestone of catalog.milestones) {
    assert.match(milestone.id, /^m[0-9]+$/u);
    assert.equal(milestoneIds.has(milestone.id), false, `Duplicate milestone: ${milestone.id}`);
    milestoneIds.add(milestone.id);
    assert.ok(Array.isArray(milestone.records) && milestone.records.length > 0);
    for (const record of milestone.records) {
      assert.match(record.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
      assert.equal(recordIds.has(record.id), false, `Duplicate record id: ${record.id}`);
      recordIds.add(record.id);
      assert.match(record.kind, /^[a-z0-9]+(?:_[a-z0-9]+)*$/u);
      assert.equal(Object.hasOwn(record, 'blob'), false, `${record.id} must obtain its blob from the lock`);
      assert.ok(record.path.startsWith('docs/'), `${record.id} must reference a docs path`);
      assert.equal(
        existsSync(path.join(root, record.path)),
        true,
        `Acceptance record does not exist: ${record.path}`,
      );
      assert.match(
        lock.documents[record.path] ?? '',
        /^[0-9a-f]{40}$/u,
        `${record.path} must be registered in the acceptance lock`,
      );
      recordPaths.push(record.path);
    }
  }

  assert.deepEqual(
    [...recordPaths].sort(),
    Object.keys(lock.documents).sort(),
    'Acceptance catalog and immutable lock must cover the same records',
  );
  assert.equal(new Set(recordPaths).size, recordPaths.length, 'Acceptance paths must be unique');
}

function enrich(catalog, lock) {
  return {
    schemaVersion: 1,
    source: sourcePath,
    lock: catalog.lockFile,
    policy: catalog.policy,
    milestones: catalog.milestones.map(milestone => ({
      ...milestone,
      records: milestone.records.map(record => ({
        ...record,
        blob: lock.documents[record.path],
      })),
    })),
  };
}

function linkFrom(directory, target) {
  const relative = path.posix.relative(directory, target);
  return relative.startsWith('.') ? relative : `./${relative}`;
}

function renderRoot(catalog) {
  const rows = catalog.milestones.map(milestone =>
    `| ${milestone.title} | ${milestone.records.length} | `
      + `[åˆ†ç±»å…¥å£](${milestone.id}/README.md) | ${milestone.summary} |`);

  return [
    '# Acceptance Records',
    '',
    '> æ­¤æ–‡ä»¶ç”± `scripts/generate-acceptance-catalog.mjs` æ ¹æ® '
      + '`config/acceptance-catalog.json` å’Œ `config/acceptance-lock.json` ç”Ÿæˆã€‚ä¸è¦æ‰‹å·¥ç¼–è¾‘ã€‚',
    '',
    'Acceptance æ–‡æ¡£æ˜¯ä¸å¯å˜åŽ†å²è¯æ®ï¼Œå›žç­”â€œæŸä¸ªç²¾ç¡®èŒƒå›´åœ¨å½“æ—¶å¦‚ä½•è¢«éªŒè¯â€ã€‚'
      + 'å®ƒä»¬å¯ä»¥è®°å½• commitã€PRã€Workflow Runã€Jobã€Artifactã€æ‘˜è¦ã€å¤±è´¥ä¿®æ­£å’ŒéžæŽˆæƒè¾¹ç•Œã€‚',
    '',
    'Acceptance ä¸èƒ½å›žç­”â€œå½“å‰é»˜è®¤åˆ†æ”¯æ”¯æŒä»€ä¹ˆâ€â€œæ˜¯å¦å·²ç»å‘å¸ƒâ€æˆ–â€œæ˜¯å¦æ”¯æŒç”Ÿäº§â€ã€‚'
      + 'è¿™äº›ç»“è®ºåˆ†åˆ«ç”± Currentã€Release å’Œ Production Support å†³ç­–æ‰¿æ‹…ã€‚',
    '',
    '## åˆ†ç±»å…¥å£',
    '',
    '| Milestone | Locked records | Index | Scope |',
    '| --- | ---: | --- | --- |',
    ...rows,
    '',
    'æœºåš9cëú+îùæë¹oez)àHØØ][ÙËšœÛÛ˜JØ][ÙËšœÛÛŠxà ‰Ëˆ	ÉËˆ	ÈÈÈ9.#ycëùcæ:)á9b&IËˆ	ÉËˆ	ËHÛÛ™šYËØXØÙ\[˜ÙK[ØÚËšœÛÛ˜9¦+ùmì¹ænú+¬9c¡¹cì¹«hù¥¡ùæ¡›Øˆ:e {ï&ÉËˆ	ËHØ][ÙÈ9oázhnùk£9¥m:)¡¹æåˆØÚûï#9.%9.#yo¥ùi#yb-¹¢%¹¢bùa¦H›Ø»ï&ÉËˆ	ËH9.#yo¥úaãya¦xà yb(:fi9¢%ºgfznæ9¦í9«hùc¡¹cìºj£9¥-»ï&ÉËˆ	ËH9/ë¹«hùoázhnù¥¬9h§ˆÓÔ”‘PÕSÓ˜8à XSQS‘QS•9¢%¹¥¬9æ¡9d#¹îëzj£9¥-º+¬9oe{ï&ÉËˆ	ËHÝ\œ™[8à T™[X\Ùxà T›ØYX\9d£XØÙ\[˜ÙH9.#yo¥ù.¤¹æî9¦ïù.èøà ‰Ëˆ	ÉËˆ	ÈÈÈ:-ëùo¡9/çy£ yëe¹åiIËˆ	ÉËˆ	ù§+:f-¹«­z`&º/áÈLø $ÓMˆ9b!¹ìnùaiycèùnî¹êâú)á:# ùæë¹oe{ï#9/a¹.#yéîùbª9mìºe yk¦¹«hù¥¡øà ‰Âˆ
È	ùc¡¹cì¹«hù¥¡ù.+yæ¡9æî9kîzdï¹£©xà ymì¹§"H‹Ò\ÜÝYH:dï¹£©yd£9i%º`ê9o%yå*9.gù¦+ú+ày£k¹."¹."ù¥¡ûï&ÉÂˆ
È	ùæí9£©y¤+9aiykd9æë¹oey/&¹è-9gcú/æy.¦údï¹£©xà ‰Ëˆ	ÉËˆ	ùd#¹îëycê¹§"yg*9k£9¥m:dï¹£©y/çy£ y¥®y¨b9cëù.éz+ày¦#¹¥í»ï#9¢cya`z+®9/oùå*9/çyåfyæî9d#Ú]›Øˆ9æ¡9cåù£©ú/àyéîøà ‰Âˆ
È	ùæë¹oey¥m9ä!¹§+:.ªù.#z ïy¥.ycæ:j£9¥-¹îäú+®¸à ‰Ëˆ	ÉËˆKš›Ú[Š	×‰ÊNÂŸB‚™[˜Ý[Ûˆ™[™\“Z[\ÝÛ™JZ[\ÝÛ™JHÂˆÛÛœÝ\™XÝÜžHH	ÛÝ]]›ÛÝKÉÛZ[\ÝÛ™KšYXÂˆÛÛœÝ›ÝÜÈHZ[\ÝÛ™Kœ™XÛÜ™Ë›X\
™XÛÜ™O‚ˆ	Ü™XÛÜ™]_H	Ü™XÛÜ™šÚ[™Wˆ
Èù¢dùo 9.#ycëùcæ9«hù¥¡×J	Û[šÑœ›ÛJ\™XÝÜžK™XÛÜ™œ]
_JH	Ü™XÛÜ™˜›ØŸW
NÂ‚ˆ™]\›ˆÂˆÈ	ÛZ[\ÝÛ™K]_HXØÙ\[˜ÙH™XÛÜ™Øˆ	ÉËˆ	Ïˆ9«i9¥¡ù.í¹å,HØÜš\ËÙÙ[™\˜]KXXØÙ\[˜ÙKXØ][ÙË›ZœØ9å'ù¢$8à ¹.#z) y¢bùméyï%º/¤xà ‰Ëˆ	ÉËˆZ[\ÝÛ™KœÝ[[X\žKˆ	ÉËˆ	ß™XÛÜ™Ú[™[[]]X›HØÝ[Y[ØÚÙYÚ]›Øˆ	Ëˆ	ßKKHKKHKKHKKH	Ëˆ‹‹œ›ÝÜËˆ	ÉËˆ	ù§+:hmycêº-'ú-(ùb!¹ìnùd£9kï:"*»ï#9.#yi#yb-¸à y¥.ya¦y¢%ºaãy¥¬:)èúaâ¹c¡¹cì¹«hù¥¡øà ‰Âˆ
È	ùodùbcz ïyb¦ùâ­¹  z+íù§éyç"ÈÐÝ\œ™[Ø\Xš[]HÝ]\×J‹‹Ë‹‹ØÝ\œ™[ØØ\Xš[]K\Ý]\Ë›Y
xà ‰Ëˆ	ÉËˆKš›Ú[Š	×‰ÊNÂŸB‚™[˜Ý[ÛˆÜš]SÜÚXÚÊ™[]]™T]ÛÛ[
HÂˆÛÛœÝXœÛÛ]T]H]š›Ú[Š›ÛÝ™[]]™T]
NÂˆYˆ
ÚXÚÓÛ›JHÂˆ\ÜÙ\™\]X[
^\ÝÔÞ[˜ÊXœÛÛ]T]
KYKÙ[™\˜]Yš[H\ÈZ\ÜÚ[™Îˆ	Ü™[]]™T]X
NÂˆ\ÜÙ\™\]X[
™XYš[TÞ[˜ÊXœÛÛ]T]	Ý]Ž	ÊKÛÛ[Ù[™\˜]Yš[HšYˆ	Ü™[]]™T]X
NÂˆ™]\›ŽÂˆBˆZÙ\”Þ[˜Ê]™\›˜[YJXœÛÛ]T]
KÈ™XÝ\œÚ]™NˆYHJNÂˆÜš]Qš[TÞ[˜ÊXœÛÛ]T]ÛÛ[	Ý]Ž	ÊNÂŸB‚˜ÛÛœÝØ][ÙÈH\œÙJÛÝ\˜ÙT]
NÂ˜ÛÛœÝØÚÈH\œÙJØ][ÙË›ØÚÑš[JNÂ˜[Y]JØ][ÙËØÚÊNÂ˜ÛÛœÝ[œšXÚYH[œšXÚ
Ø][ÙËØÚÊNÂ‚Üš]SÜÚXÚÊ	ÛÝ]]›ÛÝKÔ‘PQQK›Y™[™\”›ÛÝ
[œšXÚY
JNÂÜš]SÜÚXÚÊ	ÛÝ]]›ÛÝKØØ][ÙËšœÛÛ˜	Ò”ÓÓ‹œÝš[™ÚYžJ[œšXÚY[Š_W˜
NÂ™›Üˆ
ÛÛœÝZ[\ÝÛ™HÙˆ[œšXÚY›Z[\ÝÛ™\ÊHÂˆÜš]SÜÚXÚÊ	ÛÝ]]›ÛÝKÉÛZ[\ÝÛ™KšYKÔ‘PQQK›Y™[™\“Z[\ÝÛ™JZ[\ÝÛ™JJNÂŸB‚šYˆ
ÚXÚÓÛ›JHÂˆÛÛœÛÛK›ÙÊ	ÑÙ[™\˜]YXØÙ\[˜ÙHØ][ÙÈØÝ[Y[È\™HÝ\œ™[‰ÊNÂŸH[ÙHÂˆÛÛœÛÛK›ÙÊ	ÑÙ[™\˜]YØÜËØXØÙ\[˜ÙKÔ‘PQQK›Y	ÊNÂˆÛÛœÛÛK›ÙÊ	ÑÙ[™\˜]YØÜËØXØÙ\[˜ÙKØØ][ÙËšœÛÛ‰ÊNÂˆ›Üˆ
ÛÛœÝZ[\ÝÛ™HÙˆ[œšXÚY›Z[\ÝÛ™\ÊHÂˆÛÛœÛÛK›ÙÊÙ[™\˜]YØÜËØXØÙ\[˜ÙKÉÛZ[\ÝÛ™KšYKÔ‘PQQK›Y
NÂˆBŸB