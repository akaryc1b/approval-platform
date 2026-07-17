import type { TabBar } from '@uni-helper/vite-plugin-uni-pages'
import type { UserRole } from '@/api/types/login'

/**
 * Native tab item. Page paths are validated by the generated pages manifest,
 * rather than the private global _LocationUrl type used by upstream Unibest.
 */
export type NativeTabBarItem = Omit<TabBar['list'][number], 'pagePath'> & {
  pagePath: string
}

export type CustomTabBarItemBadge = number | 'dot'

export interface CustomTabBarItem {
  text: string
  pagePath: string
  iconType: 'uiLib' | 'unocss' | 'iconfont' | 'image'
  icon: string
  iconActive?: string
  badge?: CustomTabBarItemBadge
  isBulge?: boolean
  roles?: UserRole[]
}
