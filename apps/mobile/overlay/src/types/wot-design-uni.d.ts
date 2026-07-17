import type { DefineComponent } from 'vue'

type ApprovalMobileComponent = DefineComponent<Record<string, unknown>, {}, any>

declare module '@vue/runtime-core' {
  export interface GlobalComponents {
    WdButton: ApprovalMobileComponent
    WdSearch: ApprovalMobileComponent
    WdTag: ApprovalMobileComponent
    WdTextarea: ApprovalMobileComponent
  }
}

export {}
