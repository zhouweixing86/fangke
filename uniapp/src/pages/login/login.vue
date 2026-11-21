<template>
  <view class="page page--plain login">
    <view class="hero">
      <text class="hero-title">访客通行</text>
      <text class="hero-sub">管理端 · 预约审核</text>
    </view>

    <view class="card">
      <view class="field">
        <text class="field-label">账号</text>
        <input v-model="username" class="field-input" placeholder="管理端账号" />
      </view>
      <view class="field">
        <text class="field-label">密码</text>
        <input v-model="password" class="field-input" password placeholder="登录密码" />
      </view>

      <button class="btn btn--primary" :loading="loading" style="margin-top: 18px" @click="submit">登录</button>

      <view class="muted" style="margin-top: 12px; text-align: center">
        管理端账号由系统管理员分配；访客端为微信小程序，无需在此登录
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { adminLogin } from '@/api'
import { applyAdminLogin, homePath } from '@/store/auth'
import { showToast } from '@/utils/ui'

const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
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
