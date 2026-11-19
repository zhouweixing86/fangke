<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增门点</el-button>
      <span class="spacer" />
      <span class="text-muted">
        预约授权按门点下发：访客只能打开被授权门点下的设备
      </span>
    </div>

    <el-table v-loading="loading" :data="gates" border stripe>
      <el-table-column prop="gateCode" label="门点编码" width="150" />
      <el-table-column prop="gateName" label="名称" width="150">
        <template #default="{ row }">
          {{ row.gateName }}
          <el-tag v-if="row.unassigned" size="small" type="info" style="margin-left: 6px">系统</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gateTypeText" label="类型" width="100" />
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="设备数" width="100">
        <template #default="{ row }">
          <router-link v-if="row.deviceCount > 0" :to="`/devices?gateId=${row.id}`">
            {{ row.deviceCount }} 台
          </router-link>
          <span v-else class="text-muted">0</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.statusText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="220" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="!row.unassigned" link :type="row.status === 1 ? 'danger' : 'success'"
            @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑门点' : '新增门点'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="门点编码" required>
          <el-input v-model="form.gateCode" :disabled="editing"
            placeholder="字母数字下划线，如 EAST_GATE" />
        </el-form-item>
        <el-form-item label="门点名称" required>
          <el-input v-model="form.gateName" placeholder="如 东门（访客通道）" />
        </el-form-item>
        <el-form-item label="门点类型">
          <el-select v-model="form.gateType" style="width: 100%">
            <el-option :value="1" label="大门" />
            <el-option :value="2" label="侧门" />
            <el-option :value="3" label="车库门" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示顺序">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
          <span class="text-muted" style="margin-left: 8px">越小越靠前</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { createGate, listGates, updateGate, updateGateStatus } from '@/api'
import type { GateRow } from '@/api/types'

const loading = ref(false)
const saving = ref(false)
const gates = ref<GateRow[]>([])

const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  gateCode: '',
  gateName: '',
  gateType: 1,
  sortNo: 0,
  remark: ''
})

async function load() {
  loading.value = true
  try {
    gates.value = await listGates()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  editingId.value = null
  form.value = { gateCode: '', gateName: '', gateType: 1, sortNo: 0, remark: '' }
  dialogVisible.value = true
}

function openEdit(row: GateRow) {
  editing.value = true
  editingId.value = row.id
  form.value = {
    gateCode: row.gateCode,
    gateName: row.gateName,
    gateType: row.gateType,
    sortNo: row.sortNo,
    remark: row.remark
  }
  dialogVisible.value = true
}

async function submit() {
  if (!form.value.gateName.trim()) {
    ElMessage.warning('请填写门点名称')
    return
  }
  if (!editing.value && !form.value.gateCode.trim()) {
    ElMessage.warning('请填写门点编码')
    return
  }
  saving.value = true
  try {
    if (editing.value && editingId.value) {
      await updateGate(editingId.value, {
        gateName: form.value.gateName,
        gateType: form.value.gateType,
        sortNo: form.value.sortNo,
        remark: form.value.remark
      })
      ElMessage.success('已保存')
    } else {
      await createGate(form.value)
      ElMessage.success('门点已新增')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: GateRow) {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    await ElMessageBox.confirm(
      `停用「${row.gateName}」后，新的预约不能再选这个门点。确认停用？`,
      '停用门点',
      { type: 'warning' }
    )
  }
  await updateGateStatus(row.id, next)
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

onMounted(load)
</script>
