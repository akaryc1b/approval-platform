export interface ApprovalMenuItem {
  authority?: string[];
  badge?: string;
  children?: ApprovalMenuItem[];
  icon?: string;
  path: string;
  title: string;
}

export interface ApprovalAccessSnapshot {
  permissions: ReadonlySet<string>;
  roles: ReadonlySet<string>;
}

/**
 * Boundary between the PC shell and a host identity/menu provider.
 * RuoYi, OIDC and standalone deployments implement this contract independently.
 */
export interface ApprovalAccessAdapter {
  can(permission: string): boolean;
  loadAccess(): Promise<ApprovalAccessSnapshot>;
  loadMenus(): Promise<ApprovalMenuItem[]>;
}
