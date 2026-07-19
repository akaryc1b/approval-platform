import type { UiSection } from '#/api/approval/form-types';

export interface FormSectionEntry {
  depth: number;
  index: number;
  parent?: UiSection;
  section: UiSection;
  siblings: UiSection[];
}

export function normalizeFormSections(sections: UiSection[]): UiSection[];
export function flattenFormSections(
  sections: UiSection[],
  depth?: number,
  parent?: UiSection,
): FormSectionEntry[];
export function findFormSection(
  sections: UiSection[],
  key: string,
): FormSectionEntry | undefined;
export function moveFormSection(
  sections: UiSection[],
  key: string,
  direction: -1 | 1,
): boolean;
export function removeFormSection(sections: UiSection[], key: string): boolean;
export function collectFormFieldOrder(sections: UiSection[]): string[];
export function countSectionFields(section: UiSection): number;
