import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import test from 'node:test';
import { liveBootProfile } from './boot-http-plugin-live-compatibility.test.mjs';
import './boot-utilities-compatibility-harness.test.mjs';

const membersBytes = fs.readFileSync(new URL('./fixtures/boot-utilities-members.tsv', import.meta.url));
const members = membersBytes.toString().trimEnd().split('\n');
const memberHash = createHash('sha256').update(membersBytes).digest('hex');

export function assertUtilityConsumers(result) {
  assert.equal(result.status, 'PASSED');
  assert.equal(result.naturalGoalReachabilityClaim, false);
  assert.equal(result.scannerEvidence, false);
  assert.equal(result.missingMemberNegativeControl, 'PASSED');
  assert.deepEqual(Object.keys(result.variants).sort(), ['baseline', 'candidate']);
  for (const [name, variant] of Object.entries(result.variants)) {
    assert.equal(variant.status, 'PASSED');
    assert.equal(variant.compiledAgainst, 'baseline');
    assert.deepEqual(variant.members, members);
    assert.equal(variant.memberSetSha256, memberHash);
    assert.ok(variant.assertions >= 200);
    assert.ok(variant.origins.length >= 20);
    assert.deepEqual(variant.scannerDefaults, { gitignoreIncluded: true, cvsignoreIncluded: name === 'candidate' });
    assert.equal(variant.independentTar.length, 2);
    assert.deepEqual(variant.independentTar.map(item => item.memberNames.length), [4, 0]);
    for (const tar of variant.independentTar) {
      assert.match(tar.sha256, /^[a-f0-9]{64}$/);
      assert.equal(tar.parser, 'Python standard-library tarfile; read-only, no extraction');
    }
    for (const expected of ['Boot AOT XML overrides compiler properties', 'Boot AOT nested Xpp3Dom lookup',
      'BuildContext scanner exact default inclusion set', 'Shade actual components XML transform',
      'Shade actual plugin XML transform attributes', 'Shade actual stream resource append',
      'Boot TAR exact normal Unicode long empty names', 'Compress implode exact literal fixture',
      'Compress Deflate64 fixed Huffman exact content', 'Compress LZ77 literal backreference and end reconstruction',
      'Compress Pack200 actual reflective field success', 'Compress Pack200 JDK module access rejected']) {
      assert.ok(variant.passedAssertions.includes(expected), expected);
    }
  }
  return result;
}

const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.BOOT_UTILITIES_COMPATIBILITY === 'true';
test('reviewed Boot utilities retain full HTTP/TLS/Unix/Jackson/goals and pass actual utility consumers', {
  skip: enabled ? false : 'Boot utilities live execution needs CI or BOOT_UTILITIES_COMPATIBILITY=true.', timeout: 1250000,
}, async () => {
  const receipt = await liveBootProfile('utilities');
  assertUtilityConsumers(receipt.stages.utilities);
});
