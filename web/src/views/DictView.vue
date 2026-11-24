<template>
  <div class="page-card">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>基础字典</template>
      来访登记的「来访区域」「来访事由」，以及员工档案的「岗位」都在这里维护。停用后不会出现在新的下拉里，历史记录仍保留。
    </el-alert>

    <el-tabs v-model="activeTab" @tab-change="load">
      <el-tab-pane v-for="tab in tabs" :key="tab.type" :label="tab.label" :name="tab.type" />
    </el-tabs>

    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增{{ currentTab.label }}</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" :label="currentTab.label" min-width="160" />
      <el-table-column prop="sort" label="排序" width="90" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" active-text="启用" inactive-text="停用"
            @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑' : '新增'" width="460px">
      <el-form label-width="80px">
        <el-form-item :label="currentTab.label" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { createVisitDict, deleteVisitDict, listVisitDict, updateVisitDict } from '@/api'
import type { VisitDict, VisitDictType } from '@/api/types'

const tabs: { type: VisitDictType; label: string }[] = [
  { type: 'AREA', label: '来访区域' },
  { type: 'CAUSE', label: '来访事由' },
  { type: 'POST', label: '岗位' }
]

const activeTab = ref<VisitDictType>('AREA')
const currentTab = computed(() => tabs.find((t) => t.type === activeTab.value) ?? tabs[0])
const rows = ref<VisitDict[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{ id: number | null; name: string; sort: number; enabled: boolean; remark: string }>({
  id: null,
  name: '',
  sort: 0,
  enabled: true,
  remark: ''
})

async function load() {
  loading.value = true
  try {
    rows.value = await listVisitDict(activeTab.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.id = null
  form.name = ''
  form.sort = rows.value.length + 1
  form.enabled = true
  form.remark = ''
  dialogVisible.value = true
}

function openEdit(row: VisitDict) {
  form.id = row.id
  form.name = row.name
  form.sort = row.sort
  form.enabled = row.status === 1
  form.remark = row.remark ?? ''
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      dictType: activeTab.value,
      name: form.name.trim(),
      sort: form.sort,
      status: form.enabled ? 1 : 0,
      remark: form.remark || undefined
    }
    if (form.id) {
      await updateVisitDict(form.id, payload)
    } else {
      await createVisitDict(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: VisitDict) {
  const next = row.status === 1 ? 0 : 1
  await updateVisitDict(row.id, {
    name: row.name,
    sort: row.sort,
    status: next,
    remark: row.remark ?? undefined
  })
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

async function remove(row: VisitDict) {
  await ElMessageBox.confirm(`删除「${row.name}」？已有历史记录不受影响。`, '删除', { type: 'warning' })
  await deleteVisitDict(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
}
</style>
