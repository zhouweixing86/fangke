<template>
  <div class="page-card">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>微信扫码绑定角色</template>
      每个角色一个长期二维码。员工用微信扫码 → 在小程序里填「姓名 + 手机号」→ 该微信账号获得这个角色，
      小程序首页就会多出「我的审批」。审批模板里配了哪个角色，绑了该角色的微信就能看到对应待办。
    </el-alert>

    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="hint">
        当前二维码内容：<code>{{ bindUrl('&lt;token&gt;') }}</code>
        （正式上线需在微信后台配置「扫普通链接二维码打开小程序」，或提供 AppID 后改用小程序码）
      </span>
    </div>

    <div v-loading="loading" class="cards">
      <el-card v-for="item in rows" :key="item.roleId" shadow="hover" class="card">
        <div class="card-head">
          <div>
            <div class="role-name">{{ item.roleName }}</div>
            <div class="role-code">{{ item.roleCode }}</div>
          </div>
          <el-tag size="small" type="success">{{ item.bindCount }} 人已绑定</el-tag>
        </div>
        <div class="qr-box">
          <img v-if="images[item.token]" :src="images[item.token]" class="qr" alt="二维码" />
          <div v-else class="qr placeholder">生成中…</div>
        </div>
        <div class="token">{{ item.token }}</div>
        <div class="actions">
          <el-button size="small" @click="copy(item)">复制链接</el-button>
          <el-button size="small" type="warning" plain @click="regenerate(item)">重新生成</el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { listWxRoleQr, regenerateWxRoleQr } from '@/api'
import type { WxRoleQr } from '@/api/types'

const rows = ref<WxRoleQr[]>([])
const images = reactive<Record<string, string>>({})
const loading = ref(false)

/** 小程序扫码入口：正式环境换成备案域名；本地联调可用开发者工具的「扫码预览」 */
function bindUrl(token: string) {
  const base = import.meta.env.VITE_MP_BIND_URL || `${location.origin}/bind`
  return `${base}?t=${token}`
}

async function load() {
  loading.value = true
  try {
    rows.value = await listWxRoleQr()
    for (const item of rows.value) {
      if (!images[item.token]) {
        images[item.token] = await QRCode.toDataURL(bindUrl(item.token), { width: 220, margin: 1 })
      }
    }
  } finally {
    loading.value = false
  }
}

async function copy(item: WxRoleQr) {
  try {
    await navigator.clipboard.writeText(bindUrl(item.token))
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选择文本')
  }
}

async function regenerate(item: WxRoleQr) {
  await ElMessageBox.confirm(
    `重新生成后，「${item.roleName}」的旧二维码立即失效（已绑定的 ${item.bindCount} 人不受影响）。确认？`,
    '重新生成二维码',
    { type: 'warning' }
  )
  const updated = await regenerateWxRoleQr(item.roleId)
  delete images[item.token]
  images[updated.token] = await QRCode.toDataURL(bindUrl(updated.token), { width: 220, margin: 1 })
  ElMessage.success('已重新生成')
  await load()
}

onMounted(load)
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.role-name {
  font-size: 16px;
  font-weight: 600;
}

.role-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qr-box {
  display: flex;
  justify-content: center;
  padding: 12px 0;
}

.qr {
  width: 220px;
  height: 220px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.qr.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.token {
  text-align: center;
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.actions {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 10px;
}
</style>
