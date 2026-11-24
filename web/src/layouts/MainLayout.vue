<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">访客管理系统</div>
      <el-menu :default-active="activeMenu" router background-color="#1f2d3d" text-color="#c0c4cc"
        active-text-color="#409eff">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title }}</span>
        <span class="spacer" />
        <el-popover v-model:visible="msgVisible" placement="bottom-end" :width="380" trigger="click"
          @show="loadMessages">
          <template #reference>
            <span class="bell" title="我的消息（审批抄送等）">
              <el-badge :value="unread" :hidden="unread === 0" :max="99">
                <el-icon :size="18"><Bell /></el-icon>
              </el-badge>
            </span>
          </template>
          <div class="msg-head">
            <span>我的消息</span>
            <el-button link type="primary" size="small" :disabled="unread === 0" @click="markAllRead">
              全部已读
            </el-button>
          </div>
          <div v-if="messages.length === 0" class="msg-empty">暂无消息</div>
          <div v-for="m in messages" :key="m.id" class="msg-item" :class="{ unread: !m.read }"
            @click="openMessage(m)">
            <div class="msg-title">{{ m.title }}</div>
            <div class="msg-content">{{ m.content }}</div>
            <div class="msg-time">{{ m.createdAt }}</div>
          </div>
        </el-popover>
        <el-tag v-for="role in auth.roles" :key="role" size="small" type="info" class="role-tag">
          {{ roleLabel(role) }}
        </el-tag>
        <el-dropdown @command="onCommand">
          <span class="user">
            {{ auth.displayName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="passwordVisible" title="修改密码" width="460px">
      <el-form label-width="90px">
        <el-form-item label="原密码">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password
            placeholder="8~64 位，必须含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  changeOwnPassword,
  listMyNotifications,
  myUnreadCount,
  readAllMyNotifications,
  readMyNotification
} from '@/api'
import type { MyNotification } from '@/api/types'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  path: string
  title: string
  icon: string
  guardOnly?: boolean
  adminOnly?: boolean
}

const allMenus: MenuItem[] = [
  { path: '/', title: '保安工作台', icon: 'Monitor', guardOnly: true },
  { path: '/devices', title: '设备管理', icon: 'Cpu', adminOnly: true },
  { path: '/gates', title: '门点管理', icon: 'Place', adminOnly: true },
  { path: '/depts', title: '部门管理', icon: 'OfficeBuilding', adminOnly: true },
  { path: '/employees', title: '员工管理', icon: 'User', adminOnly: true },
  { path: '/blacklist', title: '黑名单', icon: 'CircleClose', adminOnly: true },
  { path: '/appointments', title: '预约查询', icon: 'Calendar', adminOnly: true },
  { path: '/events', title: '通行记录', icon: 'Document', adminOnly: true },
  { path: '/batches', title: '下发批次', icon: 'Upload', adminOnly: true },
  { path: '/report', title: '报表统计', icon: 'DataAnalysis', adminOnly: true },
  { path: '/users', title: '账号管理', icon: 'Avatar', adminOnly: true },
  { path: '/audit', title: '审计日志', icon: 'Tickets', adminOnly: true },
  { path: '/ops', title: '运维中心', icon: 'SetUp', adminOnly: true },
  { path: '/approval-config', title: '审批配置', icon: 'Setting', adminOnly: true }
]

const menus = computed(() =>
  allMenus.filter(
    (item) => (!item.guardOnly || auth.isGuard) && (!item.adminOnly || auth.isAdmin)
  )
)
const activeMenu = computed(() => (route.path === '' ? '/' : route.path))

function roleLabel(role: string) {
  if (role === 'ADMIN') return '系统管理员'
  if (role === 'GUARD') return '保安'
  if (role === 'VISITOR_ADMIN') return '访客管理员'
  return role
}

const passwordVisible = ref(false)
const passwordSaving = ref(false)
const passwordForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })

/* ---------------- 我的消息（审批抄送） ---------------- */
const msgVisible = ref(false)
const unread = ref(0)
const messages = ref<MyNotification[]>([])
let unreadTimer: number | undefined

async function loadUnread() {
  try {
    const result = await myUnreadCount()
    unread.value = result.unread
  } catch {
    // 消息铃铛失败不影响主流程
  }
}

async function loadMessages() {
  const result = await listMyNotifications({ page: 1, size: 20 })
  messages.value = result.list
  await loadUnread()
}

async function markAllRead() {
  await readAllMyNotifications()
  messages.value = messages.value.map((m) => ({ ...m, read: true }))
  unread.value = 0
}

async function openMessage(message: MyNotification) {
  if (!message.read) {
    try {
      await readMyNotification(message.id)
      message.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch {
      // 已读失败不阻断跳转
    }
  }
  msgVisible.value = false
  if (message.bizNo) {
    router.push({ path: '/appointments', query: { keyword: message.bizNo } })
  }
}

onMounted(() => {
  loadUnread()
  unreadTimer = window.setInterval(loadUnread, 60_000)
})

onUnmounted(() => {
  if (unreadTimer) {
    window.clearInterval(unreadTimer)
  }
})

async function onCommand(command: string) {
  if (command === 'password') {
    passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    passwordVisible.value = true
    return
  }
  if (command === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
    auth.logout()
    router.replace('/login')
  }
}

async function submitPassword() {
  const form = passwordForm.value
  if (!form.oldPassword || !form.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  passwordSaving.value = true
  try {
    await changeOwnPassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已修改，请用新密码重新登录')
    passwordVisible.value = false
    auth.logout()
    router.replace('/login')
  } finally {
    passwordSaving.value = false
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #1f2d3d;
  overflow: hidden;
}

.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.header {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  height: 56px;
}

.page-title {
  font-size: 15px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.role-tag {
  margin-right: 8px;
}

.bell {
  cursor: pointer;
  margin-right: 16px;
  display: inline-flex;
  align-items: center;
  color: #606266;
}

.msg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.msg-empty {
  padding: 18px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.msg-item {
  padding: 8px 4px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  cursor: pointer;
}

.msg-item:hover {
  background: var(--el-fill-color-light);
}

.msg-item.unread .msg-title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
  margin-right: 6px;
  vertical-align: middle;
}

.msg-title {
  font-size: 13px;
  font-weight: 600;
}

.msg-content {
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin: 2px 0;
  line-height: 1.5;
}

.msg-time {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.user {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #303133;
}

.main {
  background: #f5f7fa;
  padding: 16px;
}
</style>
