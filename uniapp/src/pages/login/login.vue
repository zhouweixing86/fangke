<template>
  <view class="page page--plain login">
    <view class="hero">
      <text class="hero-title">访客通行</text>
      <text class="hero-sub">访客预约 · 员工审批 · 管理端</text>
    </view>

    <view class="card">
      <view class="seg">
        <view class="seg-item" :class="{ active: mode === 'app' }" @click="mode = 'app'">访客/员工</view>
        <view class="seg-item" :class="{ active: mode === 'admin' }" @click="mode = 'admin'">管理端</view>
      </view>

      <template v-if="mode === 'app'">
        <view class="field">
          <text class="field-label">手机号</text>
          <input v-model="phone" class="field-input" type="number" maxlength="11" placeholder="请输入手机号" />
        </view>
        <view class="field">
          <text class="field-label">验证码</text>
          <input v-model="code" class="field-input" type="number" maxlength="6" placeholder="6 位验证码" />
          <text class="code-btn" :class="{ disabled: counting }" @click="sendCode">
            {{ counting ? countdown + 's' : '获取验证码' }}
          </text>
        </view>
      </template>

      <template v-else>
        <view class="field">
          <text class="field-label">账号</text>
          <input v-model="username" class="field-input" placeholder="管理端账号" />
        </view>
        <view class="field">
          <text class="field-label">密码</text>
          <input v-model="password" class="field-input" password placeholder="登录密码" />
        </view>
      </template>

      <button class="btn btn--primary" :loading="loading" style="margin-top: 18px" @click="submit">登录</button>

      <view v-if="mode === 'app'" class="muted" style="margin-top: 14px; text-align: center">
        <text style="color: #1989fa" @click="goBooking">首次来访？先去提交预约 →</text>
      </view>

      <view class="muted" style="margin-top: 12px; text-align: center">
        访客/员工手机号需与档案或预约登记一致；首次来访请先在「访客预约」提交
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { adminLogin, appLogin, appSendCode } from '@/api'
import { applyAdminLogin, applyAppLogin, homePath } from '@/store/auth'
import { alert, showToast } from '@/utils/ui'

const mode = ref<'app' | 'admin'>('app')
const phone = ref('')
const code = ref('')
const username = ref('')
const password = ref('')
const loading = ref(false)
const counting = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

/** 首次来访：未登录也能提交预约（后端预约接口公开） */
function goBooking() {
  uni.navigateTo({ url: '/pages/visitor/new' })
}

async function sendCode() {
  if (counting.value) return
  if (!/^1[3-9]\d{9}$/.test(phone.value)) {
    showToast('请输入正确手机号')
    return
  }
  const result = await appSendCode(phone.value)
  counting.value = true
  countdown.value = result.resendAfterSeconds
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      counting.value = false
      if (timer) clearInterval(timer)
      timer = null
    }
  }, 1000)
  if (result.mockCode) {
    code.value = result.mockCode
    await alert('开发模式验证码：' + result.mockCode + '（已自动填入）\n生产环境由短信下发。', '验证码')
  } else {
    showToast('验证码已发送')
  }
}

async function submit() {
  if (mode.value === 'app') {
    if (!phone.value || !code.value) {
      showToast('请填写手机号和验证码')
      return
    }
    loading.value = true
    try {
      applyAppLogin(await appLogin(phone.value, code.value))
      uni.reLaunch({ url: homePath() })
    } finally {
      loading.value = false
    }
    return
  }
  if (!username.value || !password.value) {
    showToast('请填写账号和密码')
    return
  }
  loading.value = true
  try {
    applyAdminLogin(await adminLogin(username.value.trim(), password.value))
    uni.reLaunch({ url: homePath() })
  } finally {
    loading.value = false
  }
}

onUnload(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.login {
  padding: 0 0 20px;
}

.hero {
  background: linear-gradient(135deg, #1989fa, #0a6ed1);
  color: #fff;
  padding: 56px 24px 40px;
  display: flex;
  flex-direction: column;
}

.hero-title {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 2px;
}

.hero-sub {
  margin-top: 8px;
  font-size: 13px;
  opacity: 0.85;
}

.card {
  margin: 14px 14px 0;
}

.seg {
  display: flex;
  background: #f2f3f5;
  border-radius: 8px;
  padding: 3px;
  margin-bottom: 8px;
}

.seg-item {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  font-size: 13px;
  color: #646566;
  border-radius: 6px;
}

.seg-item.active {
  background: #fff;
  color: #1989fa;
  font-weight: 600;
}

.code-btn {
  color: #1989fa;
  font-size: 13px;
  padding-left: 10px;
  white-space: nowrap;
}

.code-btn.disabled {
  color: #c8c9cc;
}
</style>
