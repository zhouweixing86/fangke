<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker v-model="query.range" type="daterange" value-format="YYYY-MM-DD"
        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
      <el-input v-model="query.plateNo" placeholder="车牌号" clearable style="width: 150px"
        @keyup.enter="search" />
      <el-select v-model="query.gateId" placeholder="全部门点" clearable style="width: 150px">
        <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
      </el-select>
      <el-select v-model="query.result" placeholder="全部结果" clearable style="width: 130px">
        <el-option label="放行" :value="1" />
        <el-option label="拒绝" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="spacer" />
      <span class="text-muted">车牌识别一期：相机推事件 → 平台查员工/访客授权 → 回 openGate</span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="eventTime" label="时间" width="170" />
      <el-table-column prop="plateNo" label="车牌" width="120" />
      <el-table-column prop="gateName" label="门点" width="110" />
      <el-table-column prop="directionText" label="方向" width="80" />
      <el-table-column prop="matchedTypeText" label="匹配" width="90" />
      <el-table-column prop="matchedName" label="人员" width="110" />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">{{ row.resultText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="failReason" label="原因" min-width="200" />
      <el-table-column prop="deviceSn" label="设备号" width="150" />
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listGates, listVehicleEvents } from '@/api'
import type { GateRow, VehicleEventRow } from '@/api/types'

const loading = ref(false)
const rows = ref<VehicleEventRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const gates = ref<GateRow[]>([])

const query = reactive<{
  range: [string, string] | null
  plateNo: string
  gateId: number | undefined
  result: number | undefined
}>({ range: null, plateNo: '', gateId: undefined, result: undefined })

async function load() {
  loading.value = true
  try {
    const result = await listVehicleEvents({
      from: query.range?.[0],
      to: query.range?.[1],
      plateNo: query.plateNo || undefined,
      gateId: query.gateId,
      result: query.result,
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

onMounted(async () => {
  gates.value = await listGates()
  await load()
})
</script>
