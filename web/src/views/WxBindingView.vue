<template>
  <div class="page-card">
    <el-alert type="warning" :closable="false" show-icon class="tip">
      <template #title>微信身份管理</template>
      访客/员工扫「角色二维码」后填姓名+手机号即获得角色。若有预料之外的人绑定了身份，在这里**撤销角色**或**解除绑定**：
      撤销角色只去掉这一个身份；解除绑定会清空该微信的全部身份，下次进入回到普通访客。
    </el-alert>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="姓名 / 手机号" clearable style="width: 220px"
        @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="hint">共 {{ rows.length }} 个微信已绑定身份</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="姓名" width="120"  sortable />
      <el-table-column prop="phoneMask" label="手机号" width="140"  sortable />
      <el-table-column label="已授予角色" min-width="260">
        <template #default="{ row }">
          <el-tag v-for="code in roleCodesOf(row)" :key="code" size="small" type="success" closable
            class="role-tag" @close="revokeRole(row, code)">
            {{ roleName(code) }}
          </el-tag>
          <span v-if="roleCodesOf(row).length === 0" class="text-muted">无角色</span>
        </template>
      </el-table-column>
      <el-table-column label="员工档案" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.employeeId" size="small" type="info">已关联</el-tag>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="openid" label="openid" min-width="180" show-overflow-tooltip  sortable />
      <el-table-column prop="createdAt" label="绑定时间" width="170"  sortable />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="revokeAll(row)">解除绑定</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listRoles, listWxBindings, revokeWxBinding, revokeWxRole } from '@/api'
import type { RoleRow, WxBindingRow } from '@/api/types'

const rows = ref<WxBindingRow[]>([])
const roles = ref<RoleRow[]>([])
const keyword = ref('')
const loading = ref(false)

function roleCodesOf(row: WxBindingRow): string[] {
  return (row.roleCodes || '').split(',').filter(Boolean)
}

function roleName(code: string) {
  return roles.value.find((r) => r.roleCode === code)?.roleName || code
}

async function load() {
  loading.value = true
  try {
    const [bindings, roleList] = await Promise.all([listWxBindings(keyword.value || undefined), listRoles()])
    rows.value = bindings
    roles.value = roleList
  } finally {
    loading.value = false
  }
}

async function revokeRole(row: WxBindingRow, code: string) {
  await ElMessageBox.confirm(
    `撤销「${row.name}」的「${roleName(code)}」身份？该微信将不能再处理此角色的审批。`,
    '撤销角色',
    { type: 'warning' }
  )
  await revokeWxRole(row.id, code)
  ElMessage.success('已撤销')
  await load()
}

async function revokeAll(row: WxBindingRow) {
  await ElMessageBox.confirm(
    `解除「${row.name}」的全部绑定？该微信会失去所有身份，回到普通访客。`,
    '解除绑定',
    { type: 'warning' }
  )
  await revokeWxBinding(row.id)
  ElMessage.success('已解除绑定')
  await load()
}

onMounted(load)
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.hint {
  margin-left: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.role-tag {
  margin: 2px 6px 2px 0;
}

.text-muted {
  color: var(--el-text-color-secondary);
}
</style>
