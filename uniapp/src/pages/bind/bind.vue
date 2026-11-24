<template>
  <view class="page">
    <view class="card">
      <view class="title">绑定身份</view>
      <view v-if="roleName" class="role">
        即将获得角色：<text class="role-name">{{ roleName }}</text>
      </view>
      <view v-else class="role muted">正在识别二维码…</view>

      <view v-if="error" class="error">{{ error }}</view>

      <template v-if="roleName && !done">
        <view class="field">
          <text class="label">姓名</text>
          <input v-model="form.name" class="input" placeholder="请填写真实姓名" />
        </view>
        <view class="field">
          <text class="label">手机号</text>
          <input v-model="form.phone" class="input" type="number" maxlength="11" placeholder="请填写手机号" />
        </view>
        <button class="btn" :loading="submitting" @click="submit">提交</button>
        <view class="hint">手机号会与员工档案自动匹配；同一微信可扫多个角色码，身份会累加。</view>
      </template>

      <template v-if="done">
        <view class="ok">绑定成功：{{ roleName }}</view>
        <view class="hint">当前身份：{{ roleText }}</view>
        <button class="btn" @click="goHome">进入首页</button>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { bindPreview, bindRole } from '@/api'
import { applyWxLogin, cacheWxCode, requestWxCode } from '@/store/auth'
import { showToast } from '@/utils/ui'

const token = ref('')
const roleName = ref('')
const error = ref('')
const submitting = ref(false)
const done = ref(false)
const bindRoles = ref<string[]>([])

const form = reactive({ name: '', phone: '' })

const roleText = computed(() => bindRoles.value.join('、') || roleName.value)

/** 支持三种进入方式：普通链接二维码（q=完整URL）、小程序码（scene）、直接带 t */
function pickToken(options: Record<string, string>): string {
  if (options.t) return options.t
  if (options.q) {
    const q = decodeURIComponent(options.q)
    const match = q.match(/[?&]t=([^&]+)/)
    if (match) return decodeURIComponent(match[1])
  }
  if (options.scene) {
    const scene = decodeURIComponent(options.scene)
    const match = scene.match(/t=([^&]+)/)
    return match ? decodeURIComponent(match[1]) : scene
  }
  return ''
}

async function submit() {
  if (!form.name.trim()) return showToast('请填写姓名')
  if (!/^1\d{10}$/.test(form.phone.trim())) return showToast('请填写正确的手机号')
  submitting.value = true
  try {
    const code = await requestWxCode()
    const result = await bindRole({
      token: token.value,
      wxCode: code,
      name: form.name.trim(),
      phone: form.phone.trim()
    })
    applyWxLogin(result.login)
    // mock 模式 openid 由 code 派生，缓存后保持身份稳定
    cacheWxCode(code)
    bindRoles.value = result.roles
    done.value = true
  } finally {
    submitting.value = false
  }
}

function goHome() {
  uni.reLaunch({ url: '/pages/visitor/home' })
}

onLoad(async (options: Record<string, string>) => {
  token.value = pickToken(options || {})
  if (!token.value) {
    error.value = '二维码缺少绑定参数，请重新扫描管理端「角色二维码」页面上的码'
    return
  }
  try {
    const preview = await bindPreview(token.value)
    roleName.value = preview.roleName
  } catch (e: any) {
    error.value = e?.message || '二维码已失效，请联系管理员重新生成'
  }
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 40rpx 32rpx;
}

.card {
  background: #ffffff;
  border-radius: 20rpx;
  padding: 40rpx 32rpx;
}

.title {
  font-size: 40rpx;
  font-weight: 600;
  color: #1f2329;
}

.role {
  margin-top: 20rpx;
  font-size: 30rpx;
  color: #1f2329;
}

.role-name {
  color: #1989fa;
  font-weight: 600;
}

.muted {
  color: #8a9099;
}

.error {
  margin-top: 20rpx;
  color: #e45656;
  font-size: 28rpx;
}

.field {
  margin-top: 32rpx;
}

.label {
  font-size: 28rpx;
  color: #606266;
}

.input {
  margin-top: 12rpx;
  height: 88rpx;
  border: 2rpx solid #e4e7ed;
  border-radius: 12rpx;
  padding: 0 24rpx;
  font-size: 30rpx;
}

.btn {
  margin-top: 40rpx;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 46rpx;
  background: #1989fa;
  color: #ffffff;
  font-size: 32rpx;
}

.hint {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: #909399;
  line-height: 1.6;
}

.ok {
  margin-top: 24rpx;
  font-size: 34rpx;
  color: #0aa06e;
  font-weight: 600;
}
</style>
