<template>
  <div class="page-card">
    <div class="toolbar">
      <el-radio-group v-model="preset" @change="applyPreset">
        <el-radio-button value="7">近 7 天</el-radio-button>
        <el-radio-button value="30">近 30 天</el-radio-button>
        <el-radio-button value="month">本月</el-radio-button>
      </el-radio-group>
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期"
        end-placeholder="结束日期" style="width: 250px" @change="preset = ''" />
      <el-button type="primary" :loading="loading" @click="load">统计</el-button>
      <span class="spacer" />
      <span class="text-muted">统计区间 {{ data?.from }} ~ {{ data?.to }}（按预约到访日/通行时间）</span>
    </div>

    <div class="cards">
      <div class="stat-card">
        <div class="value">{{ data?.appointments ?? 0 }}</div>
        <div class="label">预约总数</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.approved ?? 0 }}</div>
        <div class="label">审批通过（通过率 {{ percent(data?.approvalRate) }}）</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.rejected ?? 0 }}</div>
        <div class="label">已驳回</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.checkins ?? 0 }}</div>
        <div class="label">到访人次（含同行人与多次进出）</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.events ?? 0 }}</div>
        <div class="label">通行事件（成功率 {{ percent(data?.eventPassRate) }}）</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.onsiteNow ?? 0 }}</div>
        <div class="label">当前在访</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.avgApprovalMinutes ?? 0 }} <span class="unit">分钟</span></div>
        <div class="label">平均审批时长</div>
      </div>
      <div class="stat-card">
        <div class="value">{{ data?.pending ?? 0 }}</div>
        <div class="label">仍在待审批</div>
      </div>
    </div>

    <el-table v-loading="loading" :data="daily" border stripe style="margin-top: 16px">
      <el-table-column prop="date" label="日期" width="130"  sortable />
      <el-table-column prop="appointments" label="预约数" width="100"  sortable />
      <el-table-column prop="checkins" label="到访人次" width="110"  sortable />
      <el-table-column prop="eventPass" label="通行成功" width="110"  sortable />
      <el-table-column prop="eventFail" label="通行失败" width="110"  sortable />
      <el-table-column label="通行趋势" min-width="240">
        <template #default="{ row }">
          <div class="bar-track">
            <div class="bar-pass" :style="{ width: barWidth(row.eventPass) }" />
            <div class="bar-fail" :style="{ width: barWidth(row.eventFail) }" />
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="group-grid">
      <div class="group-card">
        <div class="group-title">按部门（访客）</div>
        <el-table :data="visitorReport?.byDept ?? []" border size="small">
          <el-table-column prop="key" label="部门" min-width="110"  sortable />
          <el-table-column prop="total" label="预约" width="70"  sortable />
          <el-table-column prop="a" label="通过" width="70"  sortable />
          <el-table-column prop="b" label="到访" width="70"  sortable />
        </el-table>
      </div>

      <div class="group-card">
        <div class="group-title">按访客类型</div>
        <el-table :data="visitorReport?.byType ?? []" border size="small">
          <el-table-column prop="key" label="类型" min-width="90"  sortable />
          <el-table-column prop="total" label="预约" width="70"  sortable />
          <el-table-column prop="a" label="通过" width="70"  sortable />
          <el-table-column prop="b" label="到访" width="70"  sortable />
        </el-table>
      </div>

      <div class="group-card">
        <div class="group-title">按设备（通行）</div>
        <el-table :data="accessReport?.byDevice ?? []" border size="small">
          <el-table-column prop="key" label="设备" min-width="130"  sortable />
          <el-table-column prop="total" label="事件" width="70"  sortable />
          <el-table-column prop="a" label="通过" width="70"  sortable />
          <el-table-column prop="b" label="失败" width="70"  sortable />
        </el-table>
      </div>

      <div class="group-card">
        <div class="group-title">按识别方式</div>
        <el-table :data="accessReport?.byIdentifyType ?? []" border size="small">
          <el-table-column prop="key" label="方式" min-width="90"  sortable />
          <el-table-column prop="total" label="事件" width="70"  sortable />
          <el-table-column prop="a" label="通过" width="70"  sortable />
          <el-table-column prop="b" label="失败" width="70"  sortable />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { reportAccess, reportDaily, reportOverview, reportVisitor } from '@/api'
import type { AccessReport, ReportDaily, ReportOverview, VisitorReport } from '@/api/types'

type Preset = '' | '7' | '30' | 'month'

const preset = ref<Preset>('7')
const dateRange = ref<[string, string] | null>(null)
const data = ref<ReportOverview | null>(null)
const daily = ref<ReportDaily[]>([])
const visitorReport = ref<VisitorReport | null>(null)
const accessReport = ref<AccessReport | null>(null)
const loading = ref(false)

const maxEvents = computed(() =>
  Math.max(1, ...daily.value.map((d) => d.eventPass + d.eventFail))
)

function fmt(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function applyPreset(value: Preset) {
  const today = new Date()
  if (value === '7') {
    dateRange.value = [fmt(new Date(today.getTime() - 6 * 86400000)), fmt(today)]
  } else if (value === '30') {
    dateRange.value = [fmt(new Date(today.getTime() - 29 * 86400000)), fmt(today)]
  } else if (value === 'month') {
    dateRange.value = [fmt(new Date(today.getFullYear(), today.getMonth(), 1)), fmt(today)]
  }
  load()
}

async function load() {
  loading.value = true
  try {
    const params = { from: dateRange.value?.[0], to: dateRange.value?.[1] }
    const [overview, dailyResult, visitorResult, accessResult] = await Promise.all([
      reportOverview(params),
      reportDaily(params),
      reportVisitor(params),
      reportAccess(params)
    ])
    data.value = overview
    daily.value = dailyResult.list
    visitorReport.value = visitorResult
    accessReport.value = accessResult
  } finally {
    loading.value = false
  }
}

function percent(rate: number | undefined) {
  if (!rate) return '0%'
  return `${(rate * 100).toFixed(1)}%`
}

function barWidth(count: number) {
  return `${Math.round((count / maxEvents.value) * 100)}%`
}

onMounted(() => applyPreset('7'))
</script>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px 16px;
}

.stat-card .value {
  font-size: 26px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}

.stat-card .unit {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.stat-card .label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bar-track {
  display: flex;
  height: 10px;
  background: #f0f2f5;
  border-radius: 5px;
  overflow: hidden;
}

.bar-pass {
  background: #67c23a;
}

.bar-fail {
  background: #f56c6c;
}

.group-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.group-card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
}

.group-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-text-color-regular);
}
</style>
