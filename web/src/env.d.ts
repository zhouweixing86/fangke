/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 小程序扫码绑定的落地地址（角色二维码内容前缀），正式环境换成备案域名 */
  readonly VITE_MP_BIND_URL?: string
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
