<template>
  <div class="page-card">
    <el-tabs v-model="tab">
      <el-tab-pane label="账号" name="user">
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
      </el-tab-pane>

      <el-tab-pane label="角色管理" name="role">
        <div class="toolbar">
          <el-button :icon="Refresh" @click="loadRoleTab">刷新</el-button>
          <span class="spacer" />
          <el-button type="primary" :icon="Plus" @click="openRoleCreate">新增角色</el-button>
        </div>
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
          title="角色决定账号能看到哪些页面：勾了哪个模块，登录后左侧就会出现对应菜单，接口也按同一权限校验。一个账号可分配多个角色，权限取并集。" />

        <el-table v-loading="roleLoading" :data="roleRows" border stripe>
          <el-table-column prop="roleName" label="角色名称" width="150" />
          <el-table-column prop="roleCode" label="编码" width="150" />
          <el-table-column prop="description" label="说明" min-width="180" />
          <el-table-column label="页面权限" min-width="300">
            <template #default="{ row }">
              <el-tag v-for="p in permText(row.perms)" :key="p" size="small" class="perm-tag">{{ p }}</el-tag>
              <span v-if="!row.perms || row.perms.length === 0" class="text-muted">未分配（看不到任何页面）</span>
            </template>
          </el-table-column>
          <el-table-column label="在用账号" width="90">
            <template #default="{ row }">{{ row.userCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRoleEdit(row)">编辑</el-button>
              <el-popconfirm v-if="!row.builtin" title="删除这个角色？" confirm-button-text="删除"
                @confirm="removeRole(row)">
                <template #reference>
                  <el-button link type="danger">删除</el-button>
                </template>
              </el-popconfirm>
              <span v-else class="text-muted" style="font-size: 12px">内置</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="roleVisible" :title="roleForm.id ? '编辑角色' : '新增角色'" width="640px">
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="角色名称" required>
          <el-input v-model="roleForm.roleName" maxlength="64" placeholder="如：行政审批员" />
        </el-form-item>
        <el-form-item label="角色编码" required>
          <el-input v-model="roleForm.roleCode" :disabled="!!roleForm.id" maxlength="32"
            placeholder="大写字母/数字/下划线，如 HR_APPROVER" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="页面权限">
          <div class="perm-groups">
            <el-collapse v-model="permOpenGroups">
              <el-collapse-item v-for="(items, group) in groupedPerms" :key="group" :name="group">
                <template #title>
                  <span class="perm-group-title">{{ group }}</span>
                  <el-tag size="small" type="info" class="perm-count">
                    已选 {{ selectedInGroup(items) }} / {{ items.length }}
                  </el-tag>
                </template>
                <div class="perm-actions">
                  <el-button link type="primary" size="small" @click.stop="selectGroup(items, true)">全选本组</el-button>
                  <el-button link size="small" @click.stop="selectGroup(items, false)">清空本组</el-button>
                </div>
                <el-checkbox-group v-model="roleForm.perms">
                  <el-checkbox v-for="item in items" :key="item.perm" :value="item.perm">
                    {{ item.name }}
                  </el-checkbox>
                </el-checkbox-group>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createAdminUser,
  createRole,
  deleteRole,
  listAdminUsers,
  listRoles,
  resetUserPassword,
  rolePermissions,
  updateAdminUser,
  updateRole,
  updateUserStatus
} from '@/api'
import type { AdminUserRow, PermissionItem, RoleRow } from '@/api/types'

const tab = ref<'user' | 'role'>('user')

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

/* ---------------- 角色管理（C-73） ---------------- */

const roleLoading = ref(false)
const roleSaving = ref(false)
const roleRows = ref<RoleRow[]>([])
const roleVisible = ref(false)
const permissionItems = ref<PermissionItem[]>([])

const groupedPerms = computed(() => {
  const map: Record<string, PermissionItem[]> = {}
  for (const item of permissionItems.value) {
    if (!map[item.group]) {
      map[item.group] = []
    }
    map[item.group].push(item)
  }
  return map
})

const roleForm = reactive<{
  id: number | null
  roleCode: string
  roleName: string
  description: string
  perms: string[]
  enabled: boolean
}>({ id: null, roleCode: '', roleName: '', description: '', perms: [], enabled: true })

/** C-78：权限按分组折叠，默认只展开「已选过」的分组 */
const permOpenGroups = ref<string[]>([])

function selectedInGroup(items: PermissionItem[]) {
  return items.filter((item) => roleForm.perms.includes(item.perm)).length
}

function selectGroup(items: PermissionItem[], checked: boolean) {
  const codes = items.map((item) => item.perm)
  const rest = roleForm.perms.filter((perm) => !codes.includes(perm))
  roleForm.perms = checked ? [...rest, ...codes] : rest
}

function openGroupsFor(perms: string[]) {
  const groups = Object.entries(groupedPerms.value)
    .filter(([, items]) => items.some((item) => perms.includes(item.perm)))
    .map(([group]) => group)
  permOpenGroups.value = groups.length
    ? groups
    : [Object.keys(groupedPerms.value)[0]].filter(Boolean)
}

async function loadRoleTab() {
  roleLoading.value = true
  try {
    const [list, perms] = await Promise.all([listRoles(), rolePermissions()])
    roleRows.value = list
    permissionItems.value = perms
    roles.value = list
  } finally {
    roleLoading.value = false
  }
}

/** 权限码转中文：* → 全部；module:* → 模块全部；module:read → 模块名 */
function permText(perms?: string[]) {
  if (!perms || perms.length === 0) return []
  return perms.map((perm) => {
    if (perm === '*') return '全部权限'
    const [module, action] = perm.split(':')
    const name = permissionItems.value.find((p) => p.module === module)?.name ?? module
    return action === '*' ? `${name}（全部）` : name
  })
}

function openRoleCreate() {
  Object.assign(roleForm, {
    id: null,
    roleCode: '',
    roleName: '',
    description: '',
    perms: ['appointment:read'],
    enabled: true
  })
  openGroupsFor(roleForm.perms)
  roleVisible.value = true
}

function openRoleEdit(row: RoleRow) {
  Object.assign(roleForm, {
    id: row.id,
    roleCode: row.roleCode,
    roleName: row.roleName,
    description: row.description || '',
    perms: row.roleCode === 'ADMIN' ? ['*'] : [...(row.perms || [])],
    enabled: row.status !== 0
  })
  openGroupsFor(roleForm.perms)
  roleVisible.value = true
}

async function saveRole() {
  if (!roleForm.roleName.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  if (!roleForm.id && !roleForm.roleCode.trim()) {
    ElMessage.warning('请填写角色编码')
    return
  }
  roleSaving.value = true
  try {
    if (roleForm.id) {
      await updateRole(roleForm.id, {
        roleName: roleForm.roleName.trim(),
        description: roleForm.description,
        perms: roleForm.perms,
        status: roleForm.enabled ? 1 : 0
      })
    } else {
      await createRole({
        roleCode: roleForm.roleCode.trim().toUpperCase(),
        roleName: roleForm.roleName.trim(),
        description: roleForm.description,
        perms: roleForm.perms
      })
    }
    ElMessage.success('角色已保存，相关账号重新登录后生效')
    roleVisible.value = false
    await loadRoleTab()
  } finally {
    roleSaving.value = false
  }
}

async function removeRole(row: RoleRow) {
  try {
    await deleteRole(row.id)
    ElMessage.success('角色已删除')
    await loadRoleTab()
  } catch {
    // 错误已由 http 层提示
  }
}

watch(tab, (value) => {
  if (value === 'role' && roleRows.value.length === 0) {
    loadRoleTab()
  }
})

onMounted(async () => {
  roles.value = await listRoles()
  await load()
})
</script>

<style scoped>
.role-tag {
  margin: 0 6px 0 0;
}

.perm-tag {
  margin: 0 6px 4px 0;
}

.perm-groups {
  width: 100%;
}

.perm-group-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.perm-count {
  margin-left: 10px;
}

.perm-actions {
  margin-bottom: 6px;
}
</style>
