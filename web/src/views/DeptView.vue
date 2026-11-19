<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增部门</el-button>
      <span class="spacer" />
      <span class="text-muted">这里的部门会出现在「员工管理」的新增/编辑下拉里，导入模板也按部门编码匹配</span>
    </div>

    <el-table v-loading="loading" :data="depts" border stripe>
      <el-table-column prop="code" label="部门编码" width="140" />
      <el-table-column prop="name" label="部门名称" width="180" />
      <el-table-column label="上级部门" width="160">
        <template #default="{ row }">{{ parentName(row.parentId) }}</template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="员工数" width="100">
        <template #default="{ row }">
          <span v-if="row.employeeCount > 0">{{ row.employeeCount }} 人</span>
          <span v-else class="text-muted">0</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑部门' : '新增部门'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="部门编码" required>
          <el-input v-model="form.code" :disabled="editing" placeholder="如 SALES / PROD_LINE1" />
          <div class="text-muted">大写字母、数字、下划线；导入员工时按编码匹配，建好后不要改</div>
        </el-form-item>
        <el-form-item label="部门名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如 销售部" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="form.parentId" clearable placeholder="不选＝顶级部门" style="width: 100%">
            <el-option v-for="d in parentOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
          <span class="text-muted" style="margin-left: 8px">越小越靠前</span>
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
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { createDept, deleteDept, listDepts, updateDept } from '@/api'
import type { DeptView } from '@/api/types'

const loading = ref(false)
const saving = ref(false)
const depts = ref<DeptView[]>([])

const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ code: '', name: '', parentId: undefined as number | undefined, sortNo: 0 })

/** 编辑时不能把自己选成上级 */
const parentOptions = computed(() =>
  depts.value.filter((d) => !editing.value || d.id !== editingId.value)
)

function parentName(parentId: number) {
  if (!parentId) return '—'
  return depts.value.find((d) => d.id === parentId)?.name ?? `#${parentId}`
}

async function load() {
  loading.value = true
  try {
    depts.value = await listDepts()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  editingId.value = null
  form.value = { code: '', name: '', parentId: undefined, sortNo: 0 }
  dialogVisible.value = true
}

function openEdit(row: DeptView) {
  editing.value = true
  editingId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    parentId: row.parentId || undefined,
    sortNo: row.sortNo
  }
  dialogVisible.value = true
}

async function submit() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写部门名称')
    return
  }
  if (!editing.value && !form.value.code.trim()) {
    ElMessage.warning('请填写部门编码')
    return
  }
  saving.value = true
  try {
    const payload = {
      deptCode: form.value.code,
      deptName: form.value.name,
      parentId: form.value.parentId ?? 0,
      sortNo: form.value.sortNo
    }
    if (editing.value && editingId.value) {
      await updateDept(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createDept(payload)
      ElMessage.success('部门已新增，员工管理里可以直接选了')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: DeptView) {
  await ElMessageBox.confirm(`确认删除部门「${row.name}」？`, '删除部门', { type: 'warning' })
  await deleteDept(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>
