<template>
  <div class="page-card">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>审批台账</template>
      每一次审批动作都会留痕：谁、在哪个节点、什么时候、通过还是驳回、意见与批准门点。
      可按时间/结果/审批人筛选，导出的 CSV 可直接归档。
    </el-alert>

    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="审批起"
        end-placeholder="审批止" style="width: 260px" />
      <el-select v-model="query.action" placeholder="审批结果" clearable style="width: 130px">
        <el-option label="通过" :value="1" />
        <el-option label="驳回" :value="2" />
      </el-select>
      <el-input v-model="query.operator" placeholder="审批人" clearable style="width: 150px"
        @keyup.enter="search" />
      <el-input v-model="query.keyword" placeholder="单号 / 访客" clearable style="width: 180px"
        @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </div>

    <div class="summary">
      <span>共 <b>{{ total }}</b> 条审批记录</span>
      <span v-if="query.action === undefined">（当前筛选下通过 {{ approvedCount }} 条 / 驳回 {{ rejectedCount }} 条，仅统计本页）</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="createdAt" label="审批时间" width="170"  sortable />
      <el-table-column prop="appointmentNo" label="预约单号" width="170"  sortable />
      <el-table-column prop="visitorName" label="访客" width="110"  sortable />
      <el-table-column label="审批节点" min-width="130">
        <template #default="{ row }">{{ row.nodeName }}</template>
      </el-table-column>
      <el-table-column label="审批人" width="130">
        <template #default="{ row }">
          {{ row.operatorName }}
          <el-tag size="small" type="info">{{ row.operatorTypeText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.action === 1 ? 'success' : row.action === 2 ? 'danger' : 'info'">
            {{ row.actionText }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="意见" min-width="160" show-overflow-tooltip  sortable />
      <el-table-column label="批准门点" min-width="130">
        <template #default="{ row }">{{ row.gates || '-' }}</template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { exportApprovalRecords, listApprovalRecords } from '@/api'
import type { ApprovalRecordRow } from '@/api/types'

const rows = ref<ApprovalRecordRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const range = ref<[string, string] | null>(null)
const query = reactive<{ action: number | undefined; operator: string; keyword: string }>({
  action: undefined,
  operator: '',
  keyword: ''
})

const approvedCount = computed(() => rows.value.filter((r) => r.action === 1).length)
const rejectedCount = computed(() => rows.value.filter((r) => r.action === 2).length)

function params() {
  return {
    from: range.value?.[0],
    to: range.value?.[1],
    action: query.action,
    operator: query.operator || undefined,
    keyword: query.keyword || undefined
  }
}

async function load() {
  loading.value = true
  try {
    const result = await listApprovalRecords({ ...params(), page: page.value, size: size.value })
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

async function doExport() {
  const blob = await exportApprovalRecords(params())
  const url = URL.createObjectURL(blob as Blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `审批台账-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出')
}

onMounted(load)
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.summary {
  margin: 4px 0 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
