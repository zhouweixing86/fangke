<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="姓名 / 手机号 / 车牌 / 单位" clearable
        style="width: 210px" @keyup.enter="search" />
      <el-select v-model="query.areaId" placeholder="全部区域" clearable style="width: 150px">
        <el-option v-for="d in options.AREA" :key="d.id" :value="d.id" :label="d.name" />
      </el-select>
      <el-select v-model="query.causeId" placeholder="全部事由" clearable style="width: 140px">
        <el-option v-for="d in options.CAUSE" :key="d.id" :value="d.id" :label="d.name" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px">
        <el-option label="在场" :value="1" />
        <el-option label="已离开" :value="2" />
      </el-select>
      <el-date-picker v-model="query.range" type="daterange" value-format="YYYY-MM-DD"
        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新增登记</el-button>
      <el-button @click="openDict">基础字典</el-button>
      <el-button @click="doExport">导出</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="areaName" label="区域" width="130"  sortable />
      <el-table-column prop="causeName" label="事由" width="110"  sortable />
      <el-table-column prop="phoneMask" label="手机号" width="130"  sortable />
      <el-table-column prop="visitorName" label="姓名" width="110"  sortable />
      <el-table-column prop="plateNo" label="车牌号" width="110"  sortable />
      <el-table-column prop="company" label="来访单位" min-width="150"  sortable />
      <el-table-column prop="personCount" label="人数" width="70"  sortable />
      <el-table-column prop="arrivalTime" label="到达时间" width="160"  sortable />
      <el-table-column label="是否离开" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 2 ? 'info' : 'success'" size="small">
            {{ row.status === 2 ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="leaveTime" label="离开时间" width="160"  sortable />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="primary" @click="doLeave(row)">离开</el-button>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <!-- 新增登记 -->
    <el-dialog v-model="createVisible" title="来访登记" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="姓名" required>
          <el-input v-model="form.visitorName" maxlength="64" placeholder="访客姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" maxlength="11" placeholder="手机号（可选，落库脱敏）" />
        </el-form-item>
        <el-form-item label="区域">
          <el-select v-model="form.areaId" clearable style="width: 100%" placeholder="选择区域">
            <el-option v-for="d in options.AREA" :key="d.id" :value="d.id" :label="d.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="事由">
          <el-select v-model="form.causeId" clearable style="width: 100%" placeholder="选择事由">
            <el-option v-for="d in options.CAUSE" :key="d.id" :value="d.id" :label="d.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="车牌号">
          <el-input v-model="form.plateNo" maxlength="16" placeholder="如 黑LND156" />
        </el-form-item>
        <el-form-item label="来访单位">
          <el-input v-model="form.company" maxlength="128" />
        </el-form-item>
        <el-form-item label="人数">
          <el-input-number v-model="form.personCount" :min="1" :max="999" />
        </el-form-item>
        <el-form-item label="到达时间">
          <el-date-picker v-model="form.arrivalTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%" placeholder="默认当前时间" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 基础字典 -->
    <el-dialog v-model="dictVisible" title="基础字典（事由 / 区域 / 岗位）" width="760px">
      <el-tabs v-model="dictTab" @tab-change="loadDictRows">
        <el-tab-pane label="事由信息" name="CAUSE" />
        <el-tab-pane label="区域信息" name="AREA" />
        <el-tab-pane label="岗位管理" name="POST" />
      </el-tabs>
      <div class="toolbar">
        <el-button type="primary" :icon="Plus" size="small" @click="openDictEdit(null)">新增</el-button>
        <span class="spacer" />
        <span class="text-muted">停用后不会再出现在登记下拉里，历史记录不受影响</span>
      </div>
      <el-table :data="dictRows" border size="small" max-height="320">
        <el-table-column prop="name" label="名称" min-width="160"  sortable />
        <el-table-column prop="sort" label="排序" width="80"  sortable />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140"  sortable />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDictEdit(row)">修改</el-button>
            <el-popconfirm title="删除这条字典？" confirm-button-text="删除" @confirm="removeDict(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="dictEditVisible" :title="dictForm.id ? '修改' : '新增'" width="420px" append-to-body>
        <el-form :model="dictForm" label-width="70px">
          <el-form-item label="名称" required>
            <el-input v-model="dictForm.name" maxlength="64" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="dictForm.sort" :min="0" :max="9999" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="dictForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="dictForm.remark" maxlength="255" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dictEditVisible = false">取消</el-button>
          <el-button type="primary" :loading="dictSaving" @click="saveDict">保存</el-button>
        </template>
      </el-dialog>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createVisitDict,
  createVisitRegister,
  deleteVisitDict,
  exportVisitRegisters,
  leaveVisitRegister,
  listVisitDict,
  listVisitRegisters,
  updateVisitDict,
  visitDictOptions
} from '@/api'
import type { VisitDict, VisitDictType, VisitRegisterRow } from '@/api/types'

const loading = ref(false)
const saving = ref(false)
const rows = ref<VisitRegisterRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const options = reactive<Record<VisitDictType, VisitDict[]>>({ CAUSE: [], AREA: [], POST: [] })

const query = reactive<{
  keyword: string
  areaId: number | undefined
  causeId: number | undefined
  status: number | undefined
  range: [string, string] | null
}>({ keyword: '', areaId: undefined, causeId: undefined, status: undefined, range: null })

const createVisible = ref(false)
const form = reactive({
  visitorName: '',
  phone: '',
  areaId: undefined as number | undefined,
  causeId: undefined as number | undefined,
  plateNo: '',
  company: '',
  personCount: 1,
  arrivalTime: '' as string,
  remark: ''
})

async function loadOptions() {
  const data = await visitDictOptions()
  options.CAUSE = data.CAUSE || []
  options.AREA = data.AREA || []
  options.POST = data.POST || []
}

async function load() {
  loading.value = true
  try {
    const result = await listVisitRegisters({
      keyword: query.keyword || undefined,
      areaId: query.areaId,
      causeId: query.causeId,
      status: query.status,
      from: query.range?.[0],
      to: query.range?.[1],
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
  Object.assign(form, {
    visitorName: '', phone: '', areaId: undefined, causeId: undefined,
    plateNo: '', company: '', personCount: 1, arrivalTime: '', remark: ''
  })
  createVisible.value = true
}

async function submitCreate() {
  if (!form.visitorName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  saving.value = true
  try {
    await createVisitRegister({
      visitorName: form.visitorName.trim(),
      phone: form.phone || undefined,
      areaId: form.areaId,
      causeId: form.causeId,
      plateNo: form.plateNo || undefined,
      company: form.company || undefined,
      personCount: form.personCount,
      arrivalTime: form.arrivalTime || undefined,
      remark: form.remark || undefined
    })
    ElMessage.success('已登记')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function doLeave(row: VisitRegisterRow) {
  await leaveVisitRegister(row.id)
  ElMessage.success(`${row.visitorName} 已标记离开`)
  await load()
}

async function doExport() {
  const blob = await exportVisitRegisters({
    keyword: query.keyword || undefined,
    areaId: query.areaId,
    causeId: query.causeId,
    status: query.status,
    from: query.range?.[0],
    to: query.range?.[1]
  })
  const url = URL.createObjectURL(blob as Blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `来访登记-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

/* ---------------- 基础字典 ---------------- */

const dictVisible = ref(false)
const dictTab = ref<VisitDictType>('CAUSE')
const dictRows = ref<VisitDict[]>([])
const dictEditVisible = ref(false)
const dictSaving = ref(false)
const dictForm = reactive({
  id: null as number | null,
  name: '',
  sort: 0,
  enabled: true,
  remark: ''
})

async function openDict() {
  dictVisible.value = true
  await loadDictRows()
}

async function loadDictRows() {
  dictRows.value = await listVisitDict(dictTab.value)
}

function openDictEdit(row: VisitDict | null) {
  Object.assign(dictForm, row
    ? { id: row.id, name: row.name, sort: row.sort, enabled: row.status === 1, remark: row.remark || '' }
    : { id: null, name: '', sort: 0, enabled: true, remark: '' })
  dictEditVisible.value = true
}

async function saveDict() {
  if (!dictForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  dictSaving.value = true
  try {
    if (dictForm.id) {
      await updateVisitDict(dictForm.id, {
        name: dictForm.name.trim(), sort: dictForm.sort,
        status: dictForm.enabled ? 1 : 0, remark: dictForm.remark
      })
    } else {
      await createVisitDict({
        dictType: dictTab.value, name: dictForm.name.trim(),
        sort: dictForm.sort, remark: dictForm.remark
      })
    }
    ElMessage.success('已保存')
    dictEditVisible.value = false
    await Promise.all([loadDictRows(), loadOptions()])
  } finally {
    dictSaving.value = false
  }
}

async function removeDict(row: VisitDict) {
  await deleteVisitDict(row.id)
  ElMessage.success('已删除')
  await Promise.all([loadDictRows(), loadOptions()])
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<style scoped>
</style>
