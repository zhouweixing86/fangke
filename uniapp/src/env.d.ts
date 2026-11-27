/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  /** 后端地址：H5 留空走 /api 代理；小程序/APP 必须填绝对地址，如 http://192.168.10.50:8080 */
  readonly VITE_API_BASE?: string
  /** 微信订阅消息：审批待办模板 ID（留空则不弹订阅授权） */
  readonly VITE_WX_SUB_TODO?: string
  /** 微信订阅消息：审批结果模板 ID（留空则不弹订阅授权） */
  readonly VITE_WX_SUB_RESULT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
