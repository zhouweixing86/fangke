<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="账号 / 姓名" clearable style="width: 220px"
        @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新增账号</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
      title="角色写在登录 token 里：改完角色后，该账号需重新登录才生效（token 有效期 8 小时）。" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="username" label="账号" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="userTypeText" label="类型" width="110" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="role in row.roles" :key="role" size="small" class="role-tag">
            {{ roleLabel(role) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最后登录" width="170" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="changeStatus(row, 0)">停用</el-button>
          <el-button v-else link type="success" @click="changeStatus(row, 1)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑账号' : '新增账号'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="3~32 位字母、数字、_ . -" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.userType" style="width: 100%">
            <el-option label="管理员" :value="1" />
            <el-option label="访客管理员" :value="2" />
            <el-option label="保安" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色" prop="roles">
          <el-select v-model="form.roles" multiple style="width: 100%" placeholder="至少选择一个角色">
            <el-option v-for="r in roles" :key="r.roleCode" :label="r.roleName" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password
            placeholder="8~64 位，必须含字母和数字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="460px">
      <el-form label-width="90px">
        <el-form-item label="账号">
          <span>{{ resetTarget?.username }}（{{ resetTarget?.realName }}）</span>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="resetPassword" type="password" show-password
            placeholder="8~64 位，必须含字母和数字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doReset">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createAdminUser,
  listAdminUsers,
  listRoles,
  resetUserPassword,
  updateAdminUser,
  updateUserStatus
} from '@/api'
import type { AdminUserRow, RoleRow } from '@/api/types'

const rows = ref<AdminUserRow[]>([])
const roles = ref<RoleRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const query = reactive<{ keyword: string; status: number | undefined }>({ keyword: '', status: undefined })

const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<{
  id: number | null
  username: string
  realName: string
  userType: number
  roles: string[]
  password: string
}>({ id: null, username: '', realName: '', userType: 1, roles: [], password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roles: [{ required: true, message: '至少选择一个角色', trigger: 'change' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' }]
}

const resetVisible = ref(false)
const resetTarget = ref<AdminUserRow | null>(null)
const resetPassword = ref('')

function roleLabel(code: string) {
  return roles.value.find((r) => r.roleCode === code)?.roleName ?? code
}

async function load() {
  loading.value = true
  try {
    const result = await listAdminUsers({
      keyword: query.keyword || undefined,
      status: query.status,
      page: page.value,
      size: size.value
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function openCreate() {
  Object.assign(form, { id: null, username: '', realName: '', userType: 1, roles: [], password: '' })
  formVisible.value = true
}

function openEdit(row: AdminUserRow) {
  Object.assign(form, {
    id: row.id,
    username: row.username,
    realName: row.realName,
    userType: row.userType,
    roles: [...row.roles],
    password: ''
  })
  formVisible.value = true
}

async function save() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) {
      await updateAdminUser(form.id, {
        realName: form.realName,
        userType: form.userType,
        roles: form.roles
      })
      ElMessage.success('已保存（该账号需重新登录生效）')
    } else {
      await createAdminUser({
        username: form.username,
        realName: form.realName,
        userType: form.userType,
        roles: form.roles,
        password: form.password
      })
      ElMessage.success('账号已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openReset(row: AdminUserRow) {
  resetTarget.value = row
  resetPassword.value = ''
  resetVisible.value = true
}

async function doReset() {
  if (!resetTarget.value) return
  if (!resetPassword.value) {
    ElMessage.warning('请输入新密码')
    return
  }
  saving.value = true
  try {
    await resetUserPassword(resetTarget.value.id, resetPassword.value)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } finally {
    saving.value = false
  }
}

async function changeStatus(row: AdminUserRow, status: number) {
  await ElMessageBox.confirm(
    status === 0 ? `停用「${row.username}」后将无法登录后台，确认？` : `启用「${row.username}」？`,
    status === 0 ? '停用账号' : '启用账号',
    { type: 'warning' }
  )
  await updateUserStatus(row.id, status)
  ElMessage.success(status === 0 ? '已停用' : '已启用')
  await load()
}

onMounted(async () => {
  roles.value = await listRoles()
  await load()
})
</script>

<style scoped>
.role-tag {
  margin: 0 6px 0 0;
}
</style>
