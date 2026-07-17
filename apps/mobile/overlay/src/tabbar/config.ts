import type { TabBar } from '@uni-helper/vite-plugin-uni-pages'
import type { CustomTabBarItem, NativeTabBarItem } from './types'

export const TABBAR_STRATEGY_MAP = {
  NO_TABBAR: 0,
  NATIVE_TABBAR: 1,
  CUSTOM_TABBAR: 2,
}

export const selectedTabbarStrategy = TABBAR_STRATEGY_MAP.CUSTOM_TABBAR

export const nativeTabbarList: NativeTabBarItem[] = []

export const customTabbarList: CustomTabBarItem[] = [
  {
    text: '工作台',
    pagePath: 'pages/index/index',
    iconType: 'unocss',
    icon: 'i-carbon-task',
  },
  {
    text: '发起',
    pagePath: 'pages/initiate/index',
    iconType: 'unocss',
    icon: 'i-carbon-add-alt',
  },
  {
    text: '我的',
    pagePath: 'pages/profile/index',
    iconType: 'unocss',
    icon: 'i-carbon-user-avatar',
  },
]

export const tabbarCacheEnable = true
export const customTabbarEnable = true
export const needHideNativeTabbar = true
export const tabbarList = customTabbarList

const tabbar: TabBar = {
  custom: true,
  color: '#8a8f99',
  selectedColor: '#2563eb',
  backgroundColor: '#ffffff',
  borderStyle: 'white',
  height: '54px',
  fontSize: '11px',
  iconWidth: '24px',
  spacing: '3px',
  list: customTabbarList.map(item => ({
    text: item.text,
    pagePath: item.pagePath,
  })) as unknown as TabBar['list'],
}

export const tabBar = tabbar
