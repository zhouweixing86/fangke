<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期"
        end-placeholder="结束日期" style="width: 250px" />
      <el-select v-model="query.module" placeholder="模块" clearable filterable style="width: 150px">
        <el-option v-for="m in options.modules" :key="m" :label="m" :value="m" />
      </el-select>
      <el-select v-model="query.action" placeholder="动作" clearable filterable style="width: 170px">
        <el-option v-for="a in options.actions" :key="a" :label="a" :value="a" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="账号 / 目标 / URI" clearable style="width: 200px"
        @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
      title="审计日志只增不删（BR-D-010）：任何账号（含管理员）都不能修改或删除这里的记录。" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column prop="userName" label="操作账号" width="120" />
      <el-table-column prop="module" label="模块" width="110" />
      <el-table-column prop="action" label="动作" width="170" />
      <el-table-column prop="target" label="目标" min-width="160" />
      <el-table-column prop="requestIp" label="来源 IP" width="130" />
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">{{ row.resultText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="detailVisible" title="审计详情" width="680px">
      <el-descriptions v-if="current" :column="1" border size="small">
        <el-descriptions-item label="时间">{{ current.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="操作账号">{{ current.userName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模块 / 动作">{{ current.module }} / {{ current.action }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{ current.target || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求">{{ current.requestUri || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源 IP">{{ current.requestIp || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="text-muted" style="margin: 12px 0 6px">明细（JSON）</div>
      <el-input :model-value="prettyDetail" type="textarea" :rows="10" readonly spellcheck="false" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { auditOptions, listAudit } from '@/api'
import type { AuditOptions, AuditRow } from '@/api/types'

const rows = ref<AuditRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const dateRange = ref<[string, string] | null>(defaultRange())
const query = reactive<{ module: string; action: string; keyword: string }>({
  module: '',
  action: '',
  keyword: ''
})
const options = ref<AuditOptions>({ modules: [], actions: [] })

const detailVisible = ref(false)
const current = ref<AuditRow | null>(null)
const prettyDetail = computed(() => {
  const raw = current.value?.detail
  if (!raw) return '（无明细）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

function defaultRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 6 * 24 * 3600 * 1000)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return [fmt(start), fmt(end)]
}

async function load() {
  loading.value = true
  try {
    const result = await listAudit({
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      module: query.module || undefined,
      action: query.action || undefined,
      keyword: query.keyword || undefined,
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

function showDetail(row: AuditRow) {
  current.value = row
  detailVisible.value = true
}

onMounted(async () => {
  options.value = await auditOptions()
  await load()
})
</script>
