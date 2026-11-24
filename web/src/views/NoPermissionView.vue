<template>
  <div class="page-card no-perm">
    <el-result icon="warning" title="暂无访问权限"
      sub-title="当前账号的角色还没有分配任何页面权限，请联系管理员在「账号管理 → 角色管理」里分配。">
      <template #extra>
        <el-button type="primary" @click="logout">重新登录</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>

<style scoped>
.no-perm {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}
</style>
