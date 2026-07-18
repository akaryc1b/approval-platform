/// <reference types="vite/client" />
/// <reference types="vite-svg-loader" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string
  readonly VITE_SERVER_PORT: string
  readonly VITE_SERVER_BASEURL: string
  readonly VITE_SERVER_BASEURL__WEIXIN_DEVELOP?: string
  readonly VITE_SERVER_BASEURL__WEIXIN_TRIAL?: string
  readonly VITE_SERVER_BASEURL__WEIXIN_RELEASE?: string
  readonly VITE_APP_PROXY_ENABLE: 'true' | 'false'
  readonly VITE_APP_PROXY_PREFIX: string
  readonly VITE_SERVER_HAS_API_PREFIX: 'true' | 'false'
  readonly VITE_AUTH_MODE: 'single' | 'double'
  readonly VITE_DELETE_CONSOLE: string
  readonly VITE_APPROVAL_API_URL?: string
  readonly VITE_APPROVAL_CONNECTOR?: string
  readonly VITE_APPROVAL_TENANT_ID?: string
  readonly VITE_APPROVAL_OPERATOR_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare const __VITE_APP_PROXY__: 'true' | 'false'
