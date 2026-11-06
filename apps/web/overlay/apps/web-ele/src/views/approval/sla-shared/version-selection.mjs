/** A row may use its own editor selection, never another row's version. */
export function slaTransitionVersion(identityId, activeVersion, selectedId, selectedVersion) {
  const version = identityId === selectedId ? selectedVersion : (activeVersion ?? 1);
  if (!Number.isSafeInteger(version) || version < 1) throw new Error('SLA version must be a positive integer');
  return version;
}

export function slaVersionIsImmutable(loadedVersion, selectedVersion) {
  return loadedVersion?.immutable === true
    && (loadedVersion.calendarVersion ?? loadedVersion.policyVersion) === selectedVersion;
}

/** Jackson Duration may arrive as numeric seconds; editors submit ISO-8601. */
export function slaDurationText(value) {
  if (value === undefined || value === null) return '';
  return typeof value === 'number' ? `PT${value}S` : value;
}
