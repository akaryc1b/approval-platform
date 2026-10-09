const pinnedTypeCheck = 'vue-tsc --noEmit';
const governedTypeCheck = `${pinnedTypeCheck} && node --test ../../scripts/tests/mobile-cold-start-components.test.mjs`;

export function applyUnibestCompatibility(packageJson) {
  const currentTypeCheck = packageJson.scripts?.['type-check'];
  if (currentTypeCheck !== pinnedTypeCheck && currentTypeCheck !== governedTypeCheck) {
    throw new Error('Pinned Unibest type-check command changed; expected vue-tsc --noEmit.');
  }
  packageJson.scripts['type-check'] = governedTypeCheck;
  // Wot 1.14.0 forwards this native open type. Only the pinned Uni helper's
  // declarations omit it; pnpm validates the exact version and patch content.
  packageJson.pnpm = {
    ...packageJson.pnpm,
    patchedDependencies: {
      ...packageJson.pnpm?.patchedDependencies,
      '@uni-helper/uni-app-types@1.0.0-alpha.6':
        'patches/uni-app-types-1.0.0-alpha.6.txt',
    },
  };
}
