<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <span class="text-muted">每 5 分钟自动对账一次；审批通过后即时下发</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe @row-click="showItems">
      <el-table-column prop="batchNo" label="批次号" width="230"  sortable />
      <el-table-column label="触发" width="120">
        <template #default="{ row }">{{ triggerText(row.triggerType) }}</template>
      </el-table-column>
      <el-table-column prop="gateName" label="通道" min-width="120"  sortable />
      <el-table-column prop="totalCount" label="总条数" width="90"  sortable />
      <el-table-column prop="successCount" label="成功" width="80"  sortable />
      <el-table-column prop="failCount" label="失败" width="80"  sortable />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="startedAt" label="开始时间" width="180"  sortable />
      <el-table-column prop="finishedAt" label="结束时间" width="180"  sortable />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="showItems(row)">明细</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="itemsVisible" :title="`批次明细 · ${currentBatch?.batchNo ?? ''}`" width="860px">
      <el-table v-loading="itemsLoading" :data="items" border stripe size="small" max-height="460">
        <el-table-column prop="deviceName" label="设备" min-width="220"  sortable />
        <el-table-column prop="personNo" label="人员编号" width="140"  sortable />
        <el-table-column label="动作" width="100">
          <template #default="{ row }">{{ row.action === 2 ? '删除' : '下发' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'info'" size="small">
              {{ row.status === 1 ? '成功' : row.status === 2 ? '失败' : '待执行' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="140"  sortable />
        <el-table-column prop="errorMsg" label="错误信息" min-width="180"  sortable />
        <el-table-column prop="ackedAt" label="确认时间" width="170"  sortable />
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="itemPage" v-model:page-size="itemSize" :total="itemTotal"
          layout="total, prev, pager, next" @current-change="loadItems" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listBatchItems, listBatches } from '@/api'
import type { SyncBatch, SyncBatchItem } from '@/api/types'

const rows = ref<SyncBatch[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const itemsVisible = ref(false)
const itemsLoading = ref(false)
const items = ref<SyncBatchItem[]>([])
const itemTotal = ref(0)
const itemPage = ref(1)
const itemSize = ref(50)
const currentBatch = ref<SyncBatch | null>(null)

function triggerText(type: number) {
  switch (type) {
    case 1: return '审批通过'
    case 2: return '定时对账'
    case 3: return '手动重下'
    case 4: return '首次全量'
    case 5: return '过期清理'
    default: return '未知'
  }
}

function statusText(status: number) {
  switch (status) {
    case 0: return '进行中'
    case 1: return '全部成功'
    case 2: return '部分失败'
    case 3: return '全部失败'
    default: return '未知'
  }
}

function statusType(status: number) {
  if (status === 1) return 'success'
  if (status === 0) return 'warning'
  if (status === 2) return 'warning'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const result = await listBatches({ page: page.value, size: size.value })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function showItems(row: SyncBatch) {
  currentBatch.value = row
  itemPage.value = 1
  itemsVisible.value = true
  await loadItems()
}

async function loadItems() {
  if (!currentBatch.value) return
  itemsLoading.value = true
  try {
    const result = await listBatchItems(currentBatch.value.id, { page: itemPage.value, size: itemSize.value })
    items.value = result.list
    itemTotal.value = result.total
  } finally {
    itemsLoading.value = false
  }
}

onMounted(load)
</script>
