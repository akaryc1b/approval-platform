export function slaTransitionVersion(identityId: string, activeVersion: number | undefined, selectedId: string | undefined, selectedVersion: number): number;
export function slaVersionIsImmutable(loadedVersion: { immutable: boolean; calendarVersion?: number; policyVersion?: number } | undefined, selectedVersion: number): boolean;
export function slaDurationText(value?: number | string | null): string;
