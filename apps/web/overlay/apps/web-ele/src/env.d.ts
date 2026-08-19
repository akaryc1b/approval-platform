/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APPROVAL_API_URL?: string;
  readonly VITE_APPROVAL_CONNECTOR_KEY?: string;
  readonly VITE_APPROVAL_LOCAL_IDENTITY_HEADERS?: string;
  readonly VITE_APPROVAL_OPERATOR_ID?: string;
  readonly VITE_APPROVAL_TENANT_ID?: string;
  readonly VITE_GLOB_API_URL?: string;
}
