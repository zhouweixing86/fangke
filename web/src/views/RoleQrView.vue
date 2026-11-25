<template>
  <div class="page-card">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>微信扫码绑定角色</template>
      每个角色一个长期二维码。员工用微信扫码 → 在小程序里填「姓名 + 手机号」→ 该微信账号获得这个角色，
      小程序首页就会多出「我的审批」。审批模板里配了哪个角色，绑了该角色的微信就能看到对应待办。
    </el-alert>

    <el-alert v-if="hint" type="warning" :closable="false" show-icon class="tip">
      <template #title>当前显示的是「链接二维码」</template>
      {{ hint }}
    </el-alert>

    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="hint">
        小程序码：微信扫码直达小程序「绑定身份」页；链接二维码：普通扫一扫只会用浏览器打开，需在小程序内访问
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
          <img v-if="wxacodes[item.roleId]" :src="wxacodes[item.roleId]" class="qr" alt="小程序码" />
          <img v-else-if="images[item.token]" :src="images[item.token]" class="qr" alt="二维码" />
          <div v-else class="qr placeholder">生成中…</div>
        </div>
        <div class="qr-label">
          {{ wxacodes[item.roleId] ? '小程序码（扫码直达小程序）' : '链接二维码（需在小程序内打开）' }}
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
import { listWxRoleQr, regenerateWxRoleQr, fetchWxRoleWxacode } from '@/api'
import type { WxRoleQr } from '@/api/types'

const rows = ref<WxRoleQr[]>([])
const images = reactive<Record<string, string>>({})
/** 小程序码（base64），有值时优先展示 */
const wxacodes = reactive<Record<number, string>>({})
const hint = ref('')
const loading = ref(false)

/** 小程序扫码入口：正式环境换成备案域名；本地联调可用开发者工具的「扫码预览」 */
function bindUrl(token: string) {
  const base = import.meta.env.VITE_MP_BIND_URL || `${location.origin}/bind`
  return `${base}?t=${token}`
}

async function loadWxacode(item: WxRoleQr) {
  try {
    const result = await fetchWxRoleWxacode(item.roleId)
    if (result.configured && result.imageBase64) {
      wxacodes[item.roleId] = `data:image/png;base64,${result.imageBase64}`
      delete images[item.token]
      return true
    }
    if (result.hint) {
      hint.value = result.hint
    }
  } catch {
    // 小程序码拿不到不影响链接二维码
  }
  return false
}

async function load() {
  loading.value = true
  hint.value = ''
  try {
    rows.value = await listWxRoleQr()
    for (const item of rows.value) {
      const ok = await loadWxacode(item)
      if (!ok && !images[item.token]) {
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
    `重新生成后，「${item.roleName}」的旧二维码/小程序码立即失效（已绑定的 ${item.bindCount} 人不受影响）。确认？`,
    '重新生成二维码',
    { type: 'warning' }
  )
  const updated = await regenerateWxRoleQr(item.roleId)
  delete images[item.token]
  delete wxacodes[item.roleId]
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

.qr-label {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}

.actions {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 10px;
}
</style>
